package com.example.toonhwa

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STORY_URL = "story_url"
        const val EXTRA_STORY_TITLE = "story_title"
        const val EXTRA_COVER = "story_cover"
        const val EXTRA_CHAPTER_URL = "chapter_url"
        const val EXTRA_CHAPTER_NAME = "chapter_name"
    }

    private lateinit var db: DbRepo
    private lateinit var storyUrl: String
    private lateinit var storyTitle: String
    private lateinit var cover: String
    private var chapterUrl: String = ""
    private var chapterName: String = ""
    private var pageAdapter: PageAdapter? = null
    private val aiStatusHandler = Handler(Looper.getMainLooper())
    private val aiStatusTick = object : Runnable {
        override fun run() {
            updateAiButton()
            aiStatusHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        db = DbRepo(this)

        storyUrl = NetTruyenSource.rebase(intent.getStringExtra(EXTRA_STORY_URL) ?: run { finish(); return })
        storyTitle = intent.getStringExtra(EXTRA_STORY_TITLE) ?: ""
        cover = intent.getStringExtra(EXTRA_COVER) ?: ""
        chapterUrl = NetTruyenSource.rebase(intent.getStringExtra(EXTRA_CHAPTER_URL) ?: run { finish(); return })
        chapterName = intent.getStringExtra(EXTRA_CHAPTER_NAME) ?: ""

        findViewById<Button>(R.id.btnPrev).setOnClickListener { moveChapter(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { moveChapter(1) }

        val btnAi = findViewById<Button>(R.id.btnAi)
        btnAi.setOnClickListener {
            val on = !AiEnhancer.isEnabled(this)
            AiEnhancer.setEnabled(this, on)
            if (on) AiEnhancer.clearError()
            updateAiButton()
            Toast.makeText(
                this,
                if (on) "Đã bật tăng nét AI — nút sẽ hiện 'Đang nét hoá…' tới khi xong"
                else "Đã tắt tăng nét AI",
                Toast.LENGTH_SHORT
            ).show()
            // Nap lai trang dang doc theo che do moi
            pageAdapter?.let { wireAi(it); it.notifyDataSetChanged() }
        }
        updateAiButton()
        aiStatusHandler.postDelayed(aiStatusTick, 1500)

        loadChapter()
    }

    override fun onDestroy() {
        aiStatusHandler.removeCallbacks(aiStatusTick)
        super.onDestroy()
    }

    /** Nut AI phan anh dung trang thai that: Tat / Bat / Dang xu ly / Loi. */
    private fun updateAiButton() {
        val btn = findViewById<Button>(R.id.btnAi) ?: return
        if (!AiEnhancer.isEnabled(this)) {
            btn.text = "AI nét: Tắt"
            return
        }
        val active = AiEnhancer.activeCount()
        btn.text = when {
            AiEnhancer.lastError != null -> "AI: Lỗi"
            active > 0 -> "AI: Đang nét hoá (còn $active)"
            else -> "AI nét: Bật"
        }
    }

    private fun currentIndex(): Int =
        ChapterListHolder.chapters.indexOfFirst { it.url == chapterUrl }

    private fun moveChapter(delta: Int) {
        val list = ChapterListHolder.chapters
        val idx = currentIndex()
        if (ChapterListHolder.storyUrl != storyUrl || idx < 0) {
            Toast.makeText(this, "Hãy chọn chương từ trang chi tiết truyện", Toast.LENGTH_SHORT).show()
            return
        }
        val target = idx + delta
        if (target !in list.indices) {
            Toast.makeText(
                this,
                if (delta < 0) "Đây là chương đầu tiên" else "Đây là chương mới nhất",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val ch = list[target]
        chapterUrl = ch.url
        chapterName = ch.name
        loadChapter()
    }

    private fun loadChapter() {
        title = "$storyTitle — $chapterName"
        findViewById<TextView>(R.id.tvReaderTitle).text = chapterName
        val progress = findViewById<ProgressBar>(R.id.readerProgress)
        progress.visibility = View.VISIBLE

        // Luu lich su doc
        db.addHistory(
            HistoryItem(storyUrl, storyTitle, cover, chapterName, chapterUrl, System.currentTimeMillis())
        )

        val url = chapterUrl
        thread {
            var pages: ChapterPages? = null
            try {
                pages = NetTruyenSource.fetchChapter(url)
            } catch (e: Exception) {
                pages = null
            }
            runOnUiThread {
                if (pages != null && pages.images.isNotEmpty()) {
                    showPages(pages)
                } else {
                    // Du phong WebView cho moi truong hop tai loi / khong co anh
                    WebFetcher.fetchHtml(this, url) { html ->
                        val parsed = if (html.isNullOrEmpty()) null else
                            NetTruyenSource.parseChapter(
                                NetTruyenSource.parseHtml(html, NetTruyenSource.BASE)
                            )
                        if (parsed != null && parsed.images.isNotEmpty()) showPages(parsed)
                        else loadFailed()
                    }
                }
            }
        }
    }

    private fun showPages(pages: ChapterPages) {
        findViewById<ProgressBar>(R.id.readerProgress).visibility = View.GONE
        val rv = findViewById<RecyclerView>(R.id.rvPages)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = PageAdapter(pages.images)
        pageAdapter = adapter
        wireAi(adapter)
        rv.adapter = adapter
        rv.scrollToPosition(0)
        // Xu ly truoc vai anh dau chuong de cuon xuong la thay ban net lien
        if (AiEnhancer.isEnabled(this)) {
            pages.images.take(3).forEachIndexed { pos, url ->
                AiEnhancer.submit(this, url) { refreshPage(adapter, pos, url) }
            }
        }
    }

    /**
     * Gan co che tang net AI vao danh sach trang:
     * trang nao da co ban net trong cache thi hien ban net, con lai hien ban
     * goc va dua vao hang doi nen. Viec doc khong bao gio phai cho AI.
     */
    private fun wireAi(adapter: PageAdapter) {
        if (AiEnhancer.isEnabled(this)) {
            adapter.enhancedLookup = { url -> AiEnhancer.cachedFile(url) }
            adapter.onPageBound = { pos, url ->
                // Anh dang truoc mat nguoi doc: xu ly truoc moi anh khac
                AiEnhancer.submitPriority(this, url) { refreshPage(adapter, pos, url) }
            }
        } else {
            adapter.enhancedLookup = null
            adapter.onPageBound = null
        }
    }

    private fun refreshPage(adapter: PageAdapter, pos: Int, url: String) {
        if (pageAdapter !== adapter) return
        if (adapter.images.getOrNull(pos) != url) return
        if (AiEnhancer.cachedFile(url) != null) adapter.notifyItemChanged(pos)
    }

    private fun loadFailed() {
        findViewById<ProgressBar>(R.id.readerProgress).visibility = View.GONE
        Toast.makeText(
            this,
            "Không tải được ảnh chương. Kiểm tra mạng rồi mở lại chương.",
            Toast.LENGTH_LONG
        ).show()
    }
}
