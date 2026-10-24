package com.example.toonhwa

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.target.Target

/**
 * Tai anh tu CDN cua nguon (i.hinhhinh.com / i*.truyenvua.com).
 *
 * CDN nay chan tai anh "tran" (khong co Referer / User-Agent cua trinh duyet)
 * nen Glide mac dinh bi loi 403 -> anh bia va anh trang truyen deu hong.
 * Moi lan tai anh deu gan header nhu trinh duyet that dang xem tren web nguon.
 */
object ImageLoader {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun glideUrl(raw: String): GlideUrl {
        return GlideUrl(
            raw,
            LazyHeaders.Builder()
                .addHeader("Referer", NetTruyenSource.BASE + "/")
                .addHeader("User-Agent", BROWSER_UA)
                .addHeader("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .build()
        )
    }

    fun cover(iv: ImageView, url: String) {
        if (url.isEmpty()) {
            iv.setImageResource(R.mipmap.ic_launcher)
            return
        }
        Glide.with(iv).load(glideUrl(url))
            .placeholder(R.mipmap.ic_launcher)
            .error(R.mipmap.ic_launcher)
            .centerCrop()
            .into(iv)
    }

    /** Tai anh bia; neu nguon mat file (404) thi goi onFail de man hinh tu xu ly. */
    fun coverOrElse(iv: ImageView, url: String, onFail: () -> Unit) {
        if (url.isEmpty()) {
            onFail()
            return
        }
        Glide.with(iv).load(glideUrl(url))
            .placeholder(R.mipmap.ic_launcher)
            .error(R.mipmap.ic_launcher)
            .centerCrop()
            .listener(object :
                com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    onFail()
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(iv)
    }

    fun page(iv: ImageView, url: String) {
        Glide.with(iv).load(glideUrl(url))
            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_report_image)
            .into(iv)
    }

    /** Anh trang truyen da tang net bang AI (file local trong cache). */
    fun pageFile(iv: ImageView, file: java.io.File) {
        Glide.with(iv).load(file)
            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_report_image)
            .into(iv)
    }
}
