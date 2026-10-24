package com.example.toonhwa

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/**
 * Tang net bang AI: Waifu2x CUNet noise3_scale2x (ONNX) — khu nhieu manh
 * nhat + phong 2x + ve lai net.
 *
 * Lich su lua chon (24/10/2026, sau bake-off tren anh that cua user):
 * - Bo loc unsharp thuong: vo hat vung phang -> user chet ("vo hon goc").
 * - RealESRGAN anime 6B: net nhat nhung ~150s/trang tren may yeu, ca chap
 *   thi hang gio -> user huy vi qua cham.
 * - Waifu2x noise3 x2: sach nhat (vung phang gan nhu nguyen, khong ve
 *   ban vao bau troi nhu ESRGAN), nhanh hon ESRGAN ~5 lan. Ban luu trong
 *   app la x2 — vua cho ca doc thuong lan phong to xem chi tiet.
 *
 * Ky thuat: chay theo DAI NGANG (256 dong, dem phan xa 48px) de RAM nho.
 * Cong thuc CUNet da kiem chung trong bake-off Python: dau ra =
 * 2*dau vao - 72 moi chieu; vung giu lai nam o offset 2*PAD - 36.
 * prepare() thu XNNPACK truoc (tang toc CPU lon tren Android), khong duoc
 * thi lui ve CPU thuong; bat moi loi ke ca loi native de bao loi that.
 */
object AiUpscaler {

    private const val ASSET_NAME = "waifu2x.onnx"
    private const val STRIP_H = 256
    private const val PAD = 48

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
                val modelFile = File(context.filesDir, "ai/waifu2x.onnx")
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
     * Anh vao -> anh x2 da AI khu nhieu + ve lai net (theo dai ngang).
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

    /** 1 dai: dem phan xa PAD, chay model x2, cat dem, ghep vao bitmap dich. */
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
                val oW = inW * 2 - 72
                val oH = inH * 2 - 72
                val oPlane = oW * oH
                val keepW = w * 2
                val keepH = stripH * 2
                val stripOut = IntArray(keepW * keepH)
                val off = PAD * 2 - 36
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
                out.setPixels(stripOut, 0, keepW, 0, y0 * 2, keepW, keepH)
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
