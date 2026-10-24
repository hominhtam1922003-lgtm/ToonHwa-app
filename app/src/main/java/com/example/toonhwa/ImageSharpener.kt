package com.example.toonhwa

import android.graphics.Bitmap

/**
 * Tang net cho anh truyen o kich thuoc doc that, thay cho model AI
 * phong to 2x truoc day.
 *
 * Bai hoc 1 (ban 1.0-1.0.3): phong to 2x bang waifu2x vo hinh o co
 * doc vua man hinh (~600px) vi anh goc da 800-940px, ban 2x bi thu
 * nho lai trung ban goc; model lai nang, cham, de loi tren gia lap.
 *
 * Bai hoc 2 (ban 1.1, bi user che "vo hon"): anh nguon nen JPEG nang,
 * unsharp manh (0.9/0.45) KHUECH DAI ca nhieu nen — vung phang (troi,
 * gradient) vo hat gap 3.8 lan goc. Do sac Laplacian dem ca nhieu
 * thanh "net" nen so lieu dep ma mat thay xau.
 *
 * Cong thuc hien tai (do lai tren anh that, ca vung phang lan vung
 * chi tiet): KHU NHIEU TRUOC — tron 28% anh mo vao anh goc — roi
 * moi unsharp vua tay (min 0.45 / tho 0.22) + bao hoa x1.03.
 * Ket qua do duoc: vung mat/toc sac +40%, vung phang chi them
 * ~1.5x hat (thay vi 3.8x), gradient giu min nhu goc.
 * Xu ly theo dai ngang 512 dong, khong giu mang pixel khong lo.
 */
object ImageSharpener {

    @Volatile
    var lastFailure: String? = null
        private set

    private const val DENOISE_MIX = 0.28f
    private const val FINE_AMOUNT = 0.45f
    private const val COARSE_AMOUNT = 0.22f
    private const val SATURATION = 1.03f
    private const val BAND = 512

    fun enhance(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return null
        var fine1: Bitmap? = null
        var base: Bitmap? = null
        var fine2: Bitmap? = null
        var coarse2: Bitmap? = null
        return try {
            // Buoc 1: khu nhieu nen — tron anh mo (thu/phong 1/2) vao goc
            fine1 = roundtrip(src, 0.5f)
            base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            blendInto(src, fine1, base, DENOISE_MIX)
            // Buoc 2: unsharp vua tay tren anh da khu nhieu
            fine2 = roundtrip(base, 0.5f)
            coarse2 = roundtrip(base, 0.125f)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rows = IntArray(w * BAND)
            val rowsF = IntArray(w * BAND)
            val rowsC = IntArray(w * BAND)
            var y0 = 0
            while (y0 < h) {
                val n = minOf(BAND, h - y0)
                base.getPixels(rows, 0, w, 0, y0, w, n)
                fine2.getPixels(rowsF, 0, w, 0, y0, w, n)
                coarse2.getPixels(rowsC, 0, w, 0, y0, w, n)
                val count = w * n
                for (i in 0 until count) {
                    val c = rows[i]
                    val f = rowsF[i]
                    val k = rowsC[i]
                    var r = (c shr 16) and 0xFF
                    var g = (c shr 8) and 0xFF
                    var b = c and 0xFF
                    r += (FINE_AMOUNT * (r - ((f shr 16) and 0xFF)) +
                        COARSE_AMOUNT * (r - ((k shr 16) and 0xFF))).toInt()
                    g += (FINE_AMOUNT * (g - ((f shr 8) and 0xFF)) +
                        COARSE_AMOUNT * (g - ((k shr 8) and 0xFF))).toInt()
                    b += (FINE_AMOUNT * (b - (f and 0xFF)) +
                        COARSE_AMOUNT * (b - (k and 0xFF))).toInt()
                    r = r.coerceIn(0, 255)
                    g = g.coerceIn(0, 255)
                    b = b.coerceIn(0, 255)
                    // Tang nhe do bao hoa quanh muc xam
                    val gray = (r * 299 + g * 587 + b * 114) / 1000
                    r = (gray + ((r - gray) * SATURATION).toInt()).coerceIn(0, 255)
                    g = (gray + ((g - gray) * SATURATION).toInt()).coerceIn(0, 255)
                    b = (gray + ((b - gray) * SATURATION).toInt()).coerceIn(0, 255)
                    rows[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                out.setPixels(rows, 0, w, 0, y0, w, n)
                y0 += n
            }
            lastFailure = null
            out
        } catch (t: Throwable) {
            lastFailure = "Làm nét lỗi: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            null
        } finally {
            if (fine1?.isRecycled == false) fine1.recycle()
            if (base?.isRecycled == false) base.recycle()
            if (fine2?.isRecycled == false) fine2.recycle()
            if (coarse2?.isRecycled == false) coarse2.recycle()
        }
    }

    /** out = (1-mix)*src + mix*blurred, theo dai ngang. */
    private fun blendInto(src: Bitmap, blurred: Bitmap, out: Bitmap, mix: Float) {
        val w = src.width
        val h = src.height
        val keep = 1f - mix
        val rows = IntArray(w * BAND)
        val rowsB = IntArray(w * BAND)
        var y0 = 0
        while (y0 < h) {
            val n = minOf(BAND, h - y0)
            src.getPixels(rows, 0, w, 0, y0, w, n)
            blurred.getPixels(rowsB, 0, w, 0, y0, w, n)
            val count = w * n
            for (i in 0 until count) {
                val c = rows[i]
                val f = rowsB[i]
                val r = (keep * ((c shr 16) and 0xFF) + mix * ((f shr 16) and 0xFF)).toInt()
                val g = (keep * ((c shr 8) and 0xFF) + mix * ((f shr 8) and 0xFF)).toInt()
                val b = (keep * (c and 0xFF) + mix * (f and 0xFF)).toInt()
                rows[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(rows, 0, w, 0, y0, w, n)
            y0 += n
        }
    }

    /** Thu nho roi phong to lai cung kich thuoc = anh mo (blur) re va nhanh (native). */
    private fun roundtrip(src: Bitmap, scale: Float): Bitmap {
        val w = src.width
        val h = src.height
        val sw = maxOf(1, (w * scale).toInt())
        val sh = maxOf(1, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val back = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== back && !small.isRecycled) small.recycle()
        return back
    }
}
