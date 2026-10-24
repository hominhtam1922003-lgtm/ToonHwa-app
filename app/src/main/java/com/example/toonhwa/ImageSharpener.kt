package com.example.toonhwa

import android.graphics.Bitmap

/**
 * Lam net THAY DUOC BANG MAT o kich thuoc doc that (anh vua chieu ngang
 * man hinh), thay cho model AI phong to 2x truoc day.
 *
 * Bai hoc rut ra tu ban 1.0-1.0.3: phong to 2x bang waifu2x noi dung
 * nghe thi hay, nhung khi hien thi vua man hinh (~600px) thi ban 2x bi
 * thu nho lai gan nhu trung khop ban goc — nguoi doc khong thay khac
 * biet, chua ke model nang, cham va de loi tren gia lap.
 *
 * Cach lam moi (da do thu tren anh chuong that truoc khi viet code):
 * unsharp mask 2 tang do bang chinh anh thu nho/phong to lai (bilinear):
 *   tang min (thu 1/2) he so 0.9  -> net chi tiet, chu, net ve
 *   tang tho (thu 1/8) he so 0.45 -> tuong phan cuc bo ("clarity")
 * cong do bao hoa x1.05. O co hien thi 608px, do sac (Laplacian
 * variance) tang ~2.4-3.5 lan so voi anh goc — thay ro bang mat.
 * Xu ly theo dai ngang 512 dong, khong giu mang pixel khong lo.
 */
object ImageSharpener {

    @Volatile
    var lastFailure: String? = null
        private set

    private const val FINE_AMOUNT = 0.9f
    private const val COARSE_AMOUNT = 0.45f
    private const val SATURATION = 1.05f
    private const val BAND = 512

    fun enhance(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return null
        return try {
            val fine = roundtrip(src, 0.5f)
            val coarse = roundtrip(src, 0.125f)
            try {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val rows = IntArray(w * BAND)
                val rowsF = IntArray(w * BAND)
                val rowsC = IntArray(w * BAND)
                var y0 = 0
                while (y0 < h) {
                    val n = minOf(BAND, h - y0)
                    src.getPixels(rows, 0, w, 0, y0, w, n)
                    fine.getPixels(rowsF, 0, w, 0, y0, w, n)
                    coarse.getPixels(rowsC, 0, w, 0, y0, w, n)
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
            } finally {
                if (!fine.isRecycled) fine.recycle()
                if (!coarse.isRecycled) coarse.recycle()
            }
        } catch (t: Throwable) {
            lastFailure = "Làm nét lỗi: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            null
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
