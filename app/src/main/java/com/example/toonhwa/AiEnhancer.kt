package com.example.toonhwa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Hang doi tang net AI o NEN cho man doc:
 * - Man doc luon hien anh GOC ngay (khong bao gio cho AI).
 * - Anh nao duoc yeu cau thi tai ban goc -> chay AiUpscaler -> luu cache dia.
 *   Xong thi bao ve giao dien de the cho anh do (cung ti le, khong nhay hinh).
 * - Mo lai chuong la co ban net san tu cache.
 * Tat/bat bang cong tac trong man doc (luu trong SharedPreferences).
 */
object AiEnhancer {

    private const val PREFS = "toonhwa_ai"
    private const val KEY_ENABLED = "ai_enabled"
    private const val MAX_CACHE_BYTES = 250L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-enhancer").apply { priority = Thread.MIN_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Thread { trimCacheIfNeeded() }.start()
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    private fun cacheDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "ai_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** File anh da tang net cua URL nay (null neu chua co). */
    fun cachedFile(url: String): File? {
        val dir = cacheDir() ?: return null
        val f = File(dir, key(url) + ".jpg")
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * Dua URL vao hang doi tang net. Xong (hoac da co san) thi goi onReady
     * tren luong chinh. Goi trung URL dang chay chi dang ky them callback.
     */
    fun submit(context: Context, url: String, onReady: () -> Unit) {
        init(context)
        if (cachedFile(url) != null) {
            mainHandler.post(onReady)
            return
        }
        val callbacks = pending.computeIfAbsent(url) {
            executor.execute { process(url) }
            mutableListOf()
        }
        synchronized(callbacks) { callbacks.add(onReady) }
    }

    private fun process(url: String) {
        val callbacks = pending[url]
        try {
            val ctx = appContext ?: return
            if (!AiUpscaler.prepare(ctx)) return
            val original = download(url) ?: return
            val enhanced = try {
                AiUpscaler.enhance(original)
            } finally {
                if (!original.isRecycled) original.recycle()
            } ?: return
            val dir = cacheDir() ?: return
            val out = File(dir, key(url) + ".jpg")
            val tmp = File(dir, key(url) + ".tmp")
            tmp.outputStream().use { enhanced.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!enhanced.isRecycled) enhanced.recycle()
            if (out.exists()) out.delete()
            tmp.renameTo(out)
        } catch (e: Exception) {
            // Loi 1 anh thi bo qua: man doc van dang hien anh goc
        } finally {
            pending.remove(url)
            if (callbacks != null) {
                val snapshot = synchronized(callbacks) { callbacks.toList() }
                if (snapshot.isNotEmpty()) {
                    mainHandler.post { snapshot.forEach { it() } }
                }
            }
        }
    }

    /** Tai anh goc voi header nhu trinh duyet (CDN nguon chan tai "tran"). */
    private fun download(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("Referer", NetTruyenSource.BASE + "/")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun trimCacheIfNeeded() {
        try {
            val dir = cacheDir() ?: return
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE_BYTES) break
                total -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            // Don cache loi thi thoi, khong anh huong doc truyen
        }
    }
}
