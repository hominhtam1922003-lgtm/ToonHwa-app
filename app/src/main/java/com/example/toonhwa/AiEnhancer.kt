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

/**
 * Hang doi tang net AI o NEN cho man doc:
 * - Man doc luon hien anh GOC ngay (khong bao gio cho AI).
 * - Anh dang NHIN THAY duoc uu tien xu ly truoc; anh sap cuon toi xep hang sau.
 * - Xong anh nao thi luu cache dia + bao ve giao dien de thay vao (cung ti le,
 *   khong nhay hinh). Mo lai chuong la co ban net san tu cache.
 * - Loi duoc ghi vao lastError de nut AI trong man doc hien trang thai that
 *   (Dang net hoa... / Loi) thay vi im lang.
 * Tat/bat bang cong tac trong man doc (luu trong SharedPreferences).
 */
object AiEnhancer {

    private const val PREFS = "toonhwa_ai"
    private const val KEY_ENABLED = "ai_enabled"
    private const val MAX_CACHE_BYTES = 250L * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    private val lock = Object()
    private val priorityQueue = ArrayDeque<String>()
    private val normalQueue = ArrayDeque<String>()
    @Volatile private var workerStarted = false

    @Volatile var lastError: String? = null
        private set

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

    fun clearError() {
        lastError = null
    }

    /** So anh dang cho/dang xu ly (de giao dien hien trang thai). */
    fun activeCount(): Int = pending.size

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

    /** Dua vao hang doi thuong (xu ly truoc cac anh sap doc toi). */
    fun submit(context: Context, url: String, onReady: () -> Unit) =
        enqueue(context, url, priority = false, onReady = onReady)

    /** Dua len dau hang doi (anh dang nam ngay truoc mat nguoi doc). */
    fun submitPriority(context: Context, url: String, onReady: () -> Unit) =
        enqueue(context, url, priority = true, onReady = onReady)

    private fun enqueue(context: Context, url: String, priority: Boolean, onReady: () -> Unit) {
        init(context)
        if (cachedFile(url) != null) {
            mainHandler.post(onReady)
            return
        }
        ensureWorker()
        var fresh = false
        val callbacks = synchronized(lock) {
            val existing = pending[url]
            if (existing != null) {
                existing
            } else {
                val list = mutableListOf<() -> Unit>()
                pending[url] = list
                fresh = true
                list
            }
        }
        synchronized(callbacks) { callbacks.add(onReady) }
        synchronized(lock) {
            if (fresh) {
                (if (priority) priorityQueue else normalQueue).addLast(url)
            } else if (priority && normalQueue.remove(url)) {
                priorityQueue.addLast(url)
            }
            lock.notifyAll()
        }
    }

    private fun ensureWorker() {
        if (workerStarted) return
        synchronized(lock) {
            if (workerStarted) return
            workerStarted = true
            Thread({ workerLoop() }, "ai-enhancer").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }.start()
        }
    }

    private fun workerLoop() {
        while (true) {
            val url = synchronized(lock) {
                when {
                    priorityQueue.isNotEmpty() -> priorityQueue.removeFirst()
                    normalQueue.isNotEmpty() -> normalQueue.removeFirst()
                    else -> {
                        lock.wait()
                        null
                    }
                }
            } ?: continue
            try {
                process(url)
            } catch (t: Throwable) {
                // Luong xu ly KHONG BAO GIO duoc chet (ke ca het bo nho):
                // chet la hang doi dung im lang mai mai.
                lastError = "AI lỗi nặng: ${t.javaClass.simpleName}"
                pending.remove(url)
            }
        }
    }

    private fun process(url: String) {
        val callbacks = pending[url]
        try {
            val ctx = appContext ?: return
            if (!AiUpscaler.prepare(ctx)) {
                lastError = AiUpscaler.lastFailure ?: "Không mở được model AI"
                return
            }
            val original = download(url) ?: return
            val enhanced = try {
                AiUpscaler.enhance(original)
            } finally {
                if (!original.isRecycled) original.recycle()
            }
            if (enhanced == null) {
                lastError = AiUpscaler.lastFailure ?: "AI xử lý lỗi không rõ"
                return
            }
            val dir = cacheDir() ?: return
            val out = File(dir, key(url) + ".jpg")
            val tmp = File(dir, key(url) + ".tmp")
            tmp.outputStream().use { enhanced.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!enhanced.isRecycled) enhanced.recycle()
            if (out.exists()) out.delete()
            tmp.renameTo(out)
        } catch (t: Throwable) {
            lastError = if (t is OutOfMemoryError) "AI hết bộ nhớ (OOM)"
            else "AI lỗi: ${t.javaClass.simpleName}"
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
