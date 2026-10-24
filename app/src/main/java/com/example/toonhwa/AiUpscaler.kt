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
        return try {
            val modelFile = File(context.filesDir, "ai/model.onnx")
            if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                modelFile.parentFile?.mkdirs()
                context.assets.open(ASSET_NAME).use { input ->
                    modelFile.outputStream().use { input.copyTo(it) }
                }
            }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val s = e.createSession(modelFile.absolutePath, opts)
            env = e
            session = s
            inputName = s.inputNames.firstOrNull() ?: "x"
            lastFailure = null
            true
        } catch (e: Exception) {
            lastFailure = "Không mở được model AI: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            false
        }
    }

    fun isReady(): Boolean = session != null

    /** Anh vao -> anh 2x da tang net. Loi thi tra null (man doc giu anh goc). */
    fun enhance(src: Bitmap): Bitmap? {
        val s = session ?: return null
        val e = env ?: return null
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return null
        return try {
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val outW = w * 2
            val outH = h * 2
            val outPixels = IntArray(outW * outH)
            var y0 = 0
            while (y0 < h) {
                val stripH = minOf(TILE_H, h - y0)
                runStrip(s, e, pixels, w, h, y0, stripH, outPixels, outW)
                y0 += stripH
            }
            sharpen(outPixels, outW, outH, 0.5f)
            Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            lastFailure = "AI xử lý lỗi: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            null
        }
    }

    /**
     * Lam net vien (unsharp mask nhe) sau khi phong to: day la lop "net thay
     * duoc bang mat" o kich thuoc hien thi vua man hinh — anh goc 700px xem o
     * ~600px thi rieng do phan giai 2x chua du de thay khac biet.
     * Blur xap xi bang trung binh 4 diem lan can (tren/dudi/trai/phai).
     */
    private fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
        val src = px.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            val up = row - w
            val down = row + w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = src[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val br = (((src[up + x] shr 16) and 0xFF) + ((src[down + x] shr 16) and 0xFF) +
                    ((src[row + x - 1] shr 16) and 0xFF) + ((src[row + x + 1] shr 16) and 0xFF)) / 4
                val bg = (((src[up + x] shr 8) and 0xFF) + ((src[down + x] shr 8) and 0xFF) +
                    ((src[row + x - 1] shr 8) and 0xFF) + ((src[row + x + 1] shr 8) and 0xFF)) / 4
                val bb = ((src[up + x] and 0xFF) + (src[down + x] and 0xFF) +
                    (src[row + x - 1] and 0xFF) + (src[row + x + 1] and 0xFF)) / 4
                val nr = (r + amount * (r - br)).toInt().coerceIn(0, 255)
                val ng = (g + amount * (g - bg)).toInt().coerceIn(0, 255)
                val nb = (b + amount * (b - bb)).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /** Xu ly 1 dai ngang: dem phan xa PAD px, chay model, cat vung giua dung 2x. */
    private fun runStrip(
        s: OrtSession, e: OrtEnvironment,
        pixels: IntArray, w: Int, h: Int,
        y0: Int, stripH: Int,
        outPixels: IntArray, outW: Int
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
                val dstY0 = y0 * 2
                val keepH = stripH * 2
                val keepW = w * 2
                for (y in 0 until keepH) {
                    val oy = TRIM_OUT + y
                    val oRow = oy * oW
                    val dRow = (dstY0 + y) * outW
                    for (x in 0 until keepW) {
                        val ox = TRIM_OUT + x
                        val r = (buf.get(oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        val g = (buf.get(oPlane + oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        val b = (buf.get(2 * oPlane + oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        outPixels[dRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
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
