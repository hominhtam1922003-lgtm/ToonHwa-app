package com.example.toonhwa

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Tang net bang AI THAT: RealESRGAN_x4plus_anime_6B (ONNX) — model chuyen
 * cho anh anime/manhwa, ve lai net chu khong chi khuech dai tuong phan
 * nhu bo loc thuong. User chon ban nay du cham (quyet dinh 24/10/2026
 * sau bake-off day du: loc thuong vo hat, waifu2x khac biet qua nho,
 * ESRGAN net nhat).
 *
 * Ky thuat chay tren may yeu:
 * - Xu ly theo DAI NGANG (128 dong, dem phan xa 16px): RAM nho, khong
 *   giu bitmap x4 khong lo — moi dai chay x4 xong THU NGAY ve x2 roi
 *   ghep vao bitmap dich (ban luu trong app la x2: vua du cho ca doc
 *   thuong lan phong to, ma nhe RAM).
 * - prepare() bat moi loi ke ca loi native (Error) va thu 2 cau hinh:
 *   ban 1.0 chet im lang tren gia lap vi chi bat Exception va vi
 *   abiFilters thieu kien truc 32-bit (ban nay dong goi du 4 ABI).
 */
object AiUpscaler {

    private const val ASSET_NAME = "esrgan.onnx"
    private const val STRIP_H = 128
    private const val PAD = 16

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String = "input"

    /** Loi gan nhat (prepare hoac enhance) — de giao dien hien cho user chup lai. */
    @Volatile
    var lastFailure: String? = null
        private set

    fun prepare(context: Context): Boolean {
        if (session != null) return true
        var failure: Throwable? = null
        for (attempt in 0..2) {
            try {
                val modelFile = File(context.filesDir, "ai/esrgan.onnx")
                if (!modelFile.exists() || modelFile.length() < 1_000_000) {
                    modelFile.parentFile?.mkdirs()
                    context.assets.open(ASSET_NAME).use { input ->
                        modelFile.outputStream().use { input.copyTo(it) }
                    }
                }
                val e = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    when (attempt) {
                        0 -> {
                            // XNNPACK: don tang toc CPU lon nhat tren Android
                            // neu co; may nao khong ho tro se tu rot xuong
                            // cau hinh thuong o attempt sau.
                            addXnnpack(emptyMap())
                            setIntraOpNumThreads(4)
                            setInterOpNumThreads(1)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        }
                        1 -> {
                            setIntraOpNumThreads(4)
                            setInterOpNumThreads(1)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        }
                    }
                }
                val s = e.createSession(modelFile.absolutePath, opts)
                env = e
                session = s
                inputName = s.inputNames.firstOrNull() ?: "input"
                lastFailure = null
                return true
            } catch (t: Throwable) {
                failure = t
            }
        }
        val t = failure
        lastFailure = "Không mở được model AI: ${t?.javaClass?.simpleName} ${t?.message ?: ""}".trim()
        return false
    }

    fun isReady(): Boolean = session != null

    /**
     * Anh vao -> anh x2 da AI ve lai net (chay x4 roi thu ve x2 theo dai).
     * Loi thi tra null va ghi lastFailure.
     */
    fun enhance(src: Bitmap): Bitmap? {
        val s = session ?: return null
        val e = env ?: return null
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return null
        return try {
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = Bitmap.createBitmap(w * 2, h * 2, Bitmap.Config.ARGB_8888)
            var y0 = 0
            while (y0 < h) {
                val stripH = minOf(STRIP_H, h - y0)
                runStrip(s, e, pixels, w, h, y0, stripH, out)
                y0 += stripH
            }
            lastFailure = null
            out
        } catch (t: Throwable) {
            lastFailure = "AI xử lý lỗi: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            null
        }
    }

    /** 1 dai: dem phan xa PAD, chay x4, cat dem, thu ve x2, ghep vao bitmap dich. */
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
        val tensor = OnnxTensor.createTensor(
            e, FloatBuffer.wrap(data),
            longArrayOf(1, 3, inH.toLong(), inW.toLong())
        )
        tensor.use {
            val result = s.run(mapOf(inputName to it))
            result.use {
                val outTensor = it[0] as OnnxTensor
                val buf = outTensor.floatBuffer
                val oW = inW * 4
                val oH = inH * 4
                val oPlane = oW * oH
                val keepW = w * 4
                val keepH = stripH * 4
                val stripOut = IntArray(keepW * keepH)
                val off = PAD * 4
                for (y in 0 until keepH) {
                    val oRow = (off + y) * oW
                    val dRow = y * keepW
                    for (x in 0 until keepW) {
                        val ox = off + x
                        val r = (buf.get(oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        val g = (buf.get(oPlane + oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        val b = (buf.get(2 * oPlane + oRow + ox) * 255f).toInt().coerceIn(0, 255)
                        stripOut[dRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                val stripBmp = Bitmap.createBitmap(stripOut, keepW, keepH, Bitmap.Config.ARGB_8888)
                val half = Bitmap.createScaledBitmap(stripBmp, w * 2, stripH * 2, true)
                if (half !== stripBmp) stripBmp.recycle()
                val rowsBack = IntArray(w * 2 * stripH * 2)
                half.getPixels(rowsBack, 0, w * 2, 0, 0, w * 2, stripH * 2)
                out.setPixels(rowsBack, 0, w * 2, 0, y0 * 2, w * 2, stripH * 2)
                half.recycle()
            }
        }
    }

    /** Chi so phan xa quanh mep: -1 -> 1, size -> size-2 ... */
    private fun reflect(p: Int, size: Int): Int {
        if (size <= 1) return 0
        var v = p
        while (v < 0 || v >= size) {
            v = if (v < 0) -v else 2 * (size - 1) - v
        }
        return v
    }
}
