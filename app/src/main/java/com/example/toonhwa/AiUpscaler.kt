package com.example.toonhwa

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Tang net anh bang AI ngay tren may: model waifu2x CUNet (noise0 + scale 2x)
 * chuyen cho tranh/line-art, chay bang ONNX Runtime.
 *
 * Hanh vi da do truc tiep tren model (24/10/2026): input NCHW float [0,1],
 * output moi chieu = 2 x input - 72 (model tu cat 36px moi phia) ->
 * phai dem phan xa (reflect) 24px moi phia roi cat lay vung giua dung 2x.
 * Do sac net (laplacian variance) tren anh chuong that: AI ~1086 so voi
 * phong to bilinear ~231.
 *
 * CHI chay tren luong nen (xem AiEnhancer) — khong bao gio cham vao luong giao dien.
 */
object AiUpscaler {

    private const val ASSET_NAME = "ai_model.onnx"
    private const val TILE_H = 512   // Chieu cao 1 mieng xu ly (khong gian input)
    private const val PAD = 24       // Dem phan xa moi phia (input)
    private const val TRIM_OUT = 12  // (PAD - 18) * 2: vung cat bo o output

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var inputName: String = "x"

    /** Loi gan nhat khi chuan bi / xu ly (de giao dien bao dung trang thai). */
    @Volatile var lastFailure: String? = null
        private set

    /** Copy model tu assets ra bo nho trong (1 lan) + mo session. True = san sang. */
    @Synchronized
    fun prepare(context: Context): Boolean {
        if (session != null) return true
        var failure: Throwable? = null
        // Thu 2 lan: lan 1 cau hinh day du, lan 2 cau hinh mac dinh tho —
        // vai may gia lap nga o tuy chon toi uu chu khong nga o model.
        for (attempt in 0..1) {
            try {
                val modelFile = File(context.filesDir, "ai/model.onnx")
                if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                    modelFile.parentFile?.mkdirs()
                    context.assets.open(ASSET_NAME).use { input ->
                        modelFile.outputStream().use { input.copyTo(it) }
                    }
                }
                val e = OrtEnvironment.getEnvironment()
                val opts = if (attempt == 0) OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                } else OrtSession.SessionOptions()
                val s = e.createSession(modelFile.absolutePath, opts)
                env = e
                session = s
                inputName = s.inputNames.firstOrNull() ?: "x"
                lastFailure = null
                return true
            } catch (t: Throwable) {
                failure = t // Ke ca loi native (Error): ghi lai chu khong vuot ra ngoai
            }
        }
        val t = failure
        lastFailure = "Không mở được model AI: ${t?.javaClass?.simpleName} ${t?.message ?: ""}".trim()
        return false
    }

    fun isReady(): Boolean = session != null

    /**
     * Anh vao -> anh 2x da tang net. Loi thi tra null (man doc giu anh goc).
     * Xu ly theo DAI NGANG va ghi thang vao bitmap dich (setPixels tung dai):
     * khong giu mang pixel khong lo trong RAM — ban cu giu cung luc 2 mang
     * ~40-60MB cho anh dai, tren gia lap de tran bo nho lam chet luong xu ly.
     */
    fun enhance(src: Bitmap): Bitmap? {
        val s = session ?: return null
        val e = env ?: return null
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return null
        // Anh dich qua lon (> 90 trieu diem) thi bo qua: nguyen trang dai bat
        // thuong, cu giu anh goc cho chac an bo nho.
        if (w.toLong() * h.toLong() * 4L > 90_000_000L) {
            lastFailure = "Ảnh quá dài, AI bỏ qua trang này"
            return null
        }
        return try {
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888)
            var y0 = 0
            while (y0 < h) {
                val stripH = minOf(TILE_H, h - y0)
                runStrip(s, e, pixels, w, h, y0, stripH, out)
                y0 += stripH
            }
            sharpenBitmap(out, 0.5f)
            out
        } catch (t: Throwable) {
            lastFailure = "AI xử lý lỗi: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            null
        }
    }

    /**
     * Lam net vien (unsharp mask nhe) sau khi phong to, lam theo dai 256 dong
     * (chong them 1 dong tren/duoi) de khong ton RAM. Day la lop "net thay
     * duoc bang mat" o kich thuoc hien thi vua man hinh.
     * Blur xap xi bang trung binh 4 diem lan can (tren/duoi/trai/phai).
     */
    private fun sharpenBitmap(bmp: Bitmap, amount: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        val bandH = 256
        var yStart = 0
        while (yStart < h) {
            val rows = minOf(bandH, h - yStart)
            val topPad = if (yStart > 0) 1 else 0
            val botPad = if (yStart + rows < h) 1 else 0
            val total = topPad + rows + botPad
            val buf = IntArray(w * total)
            bmp.getPixels(buf, 0, w, 0, yStart - topPad, w, total)
            val orig = buf.copyOf()
            for (ry in 0 until rows) {
                val gy = yStart + ry
                if (gy == 0 || gy == h - 1) continue // Dong bien toan cuc: khong du lan can
                val yy = topPad + ry
                val row = yy * w
                val up = row - w
                val down = row + w
                for (x in 1 until w - 1) {
                    val i = row + x
                    val c = orig[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val br = (((orig[up + x] shr 16) and 0xFF) + ((orig[down + x] shr 16) and 0xFF) +
                        ((orig[row + x - 1] shr 16) and 0xFF) + ((orig[row + x + 1] shr 16) and 0xFF)) / 4
                    val bg = (((orig[up + x] shr 8) and 0xFF) + ((orig[down + x] shr 8) and 0xFF) +
                        ((orig[row + x - 1] shr 8) and 0xFF) + ((orig[row + x + 1] shr 8) and 0xFF)) / 4
                    val bb = ((orig[up + x] and 0xFF) + (orig[down + x] and 0xFF) +
                        (orig[row + x - 1] and 0xFF) + (orig[row + x + 1] and 0xFF)) / 4
                    val nr = (r + amount * (r - br)).toInt().coerceIn(0, 255)
                    val ng = (g + amount * (g - bg)).toInt().coerceIn(0, 255)
                    val nb = (b + amount * (b - bb)).toInt().coerceIn(0, 255)
                    buf[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
            // Ghi lai dung phan dong cua dai (bo 2 dong dem)
            val writeRows = IntArray(w * rows)
            System.arraycopy(buf, topPad * w, writeRows, 0, w * rows)
            bmp.setPixels(writeRows, 0, w, 0, yStart, w, rows)
            yStart += rows
        }
    }

    /** Xu ly 1 dai ngang: dem phan xa PAD px, chay model, ghi thang vao bitmap dich. */
    private fun runStrip(
        s: OrtSession, e: OrtEnvironment,
        pixels: IntArray, w: Int, h: Int,
        y0: Int, stripH: Int,
        out: Bitmap
    ) {
        val inW = w + PAD * 2
        val inH = stripH + PAD * 2
        val data = FloatArray(3 * inW * inH)
        val plane = inW * inH
        for (y in 0 until inH) {
            val sy = reflect(y0 - PAD + y, h)
            val rowBase = sy * w
            val outRow = y * inW
            for (x in 0 until inW) {
                val sx = reflect(x - PAD, w)
                val argb = pixels[rowBase + sx]
                val i = outRow + x
                data[i] = ((argb shr 16) and 0xFF) / 255f
                data[plane + i] = ((argb shr 8) and 0xFF) / 255f
                data[2 * plane + i] = (argb and 0xFF) / 255f
            }
        }
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(data), longArrayOf(1, 3, inH.toLong(), inW.toLong()))
        tensor.use {
            val result = s.run(mapOf(inputName to it))
            result.use {
                val outTensor = it[0] as OnnxTensor
                val buf = outTensor.floatBuffer
                val oW = inW * 2 - 72
                val oPlane = oW * (inH * 2 - 72)
                val keepH = stripH * 2
                val keepW = w * 2
                val stripOut = IntArray(keepW * keepH)
                for (y in 0 until keepH) {
                    val oy = TRIM_OUT + y
                    val oRow = oy * oW
                    val dRow = y * keepW
                    for (x in 0 until keepW) {
                        val ox = TRIM_OUT + x
                        val r = (buf.get(oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        val g = (buf.get(oPlane + oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        val b = (buf.get(2 * oPlane + oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        stripOut[dRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out.setPixels(stripOut, 0, keepW, 0, y0 * 2, keepW, keepH)
            }
        }
    }

    /** Chi so phan xa quanh mep: -1 -> 1, -2 -> 2, size -> size-2 ... */
    private fun reflect(p: Int, size: Int): Int {
        if (size <= 1) return 0
        var v = p
        while (v < 0 || v >= size) {
            v = if (v < 0) -v else 2 * (size - 1) - v
        }
        return v
    }
}
