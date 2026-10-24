package com.example.toonhwa

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Nguon du lieu: NetTruyen (ban co dien ASP.NET) — KHOA THE LOAI MANHWA.
 *
 * Hai "cua" vao CUNG MOT kho (kiem chung 24/10/2026: cung danh muc, cung
 * truyen, cung moc cap nhat): nettruyen.gg (chinh) va nettruyenar.com (phu).
 * RemoteConfig chon cua dang song; URL luu trong may tu rebase sang cua moi.
 *
 * KET QUA KHAO SAT TRUC TIEP:
 * - Ke manhwa: /tim-truyen/manhwa-11400 (36 truyen/trang, phan trang ?page=N)
 *   Sap xep cua chinh web: mac dinh = Ngay cap nhat, sort=15 Truyen moi,
 *   sort=10 Top all, sort=11/12/13 Top thang/tuan/ngay, sort=30 So chapter.
 * - Chi tiet: gg dung /truyen-tranh/{slug}; ar dung /truyen-tranh/{slug}-{id}.
 *   KHONG tu dung URL: luon lay href nguyen ban tu trang liet ke/trang chi tiet.
 * - Trang chi tiet chi nhung san 20 chuong moi nhat. FULL CHAP lay tu dich vu
 *   cua chinh web: GET /Comic/Services/ComicService.asmx/ChapterList?slug={slug}
 *   -> JSON {"data":[{chapter_num, chapter_name, chapter_id, updated_at,...}]}
 *   (dung nguyen cach web tu goi khi bam "Xem them"). URL chuong web tu dung:
 *   /truyen-tranh/{slug}/chuong-{so}.
 * - Trang chuong: anh trong img.page-chapter, thuoc tinh data-sv1 (du phong
 *   data-sv2 / data-src / src). CDN doi theo cua: gg -> image*.kcgsbok.com,
 *   ar -> cdn*.cloud-zzz.com; ca hai deu can Referer = cua dang dung (ImageLoader).
 * - Tim kiem: gg nhan ?keyword=, ar nhan ?query= -> thu ca hai.
 * - Khong Cloudflare, khong bat dang nhap doc truyen (chi binh luan moi can).
 */
object NetTruyenSource {

    const val DEFAULT_BASE = "https://nettruyen.gg"

    /** Ten mien dang dung that (RemoteConfig cap nhat khi web doi ten mien). */
    @Volatile
    var activeBase: String = DEFAULT_BASE

    /** Moi code doc BASE se tu nhan ten mien dang hoat dong. */
    val BASE: String
        get() = activeBase

    /** Duong dan ke manhwa (dung cho moi tab duyet truyen). */
    private const val MANHWA_PATH = "/tim-truyen/manhwa-11400"

    /**
     * URL da luu (yeu thich / lich su) co the tro ve cua cu. Cung mot kho nen
     * chi can doi host sang cua dang dung, giu nguyen duong dan.
     */
    fun rebase(url: String): String {
        if (!url.contains("/truyen-tranh/")) return url
        val host = Regex("""^https?://[^/]+""").find(url)?.value ?: return url
        return if (host == activeBase) url else activeBase + url.substring(host.length)
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    class BlockedException : Exception("Cloudflare blocked")

    private val detailPathRegex = Regex("""/truyen-tranh/[a-z0-9\-]+$""")
    private val chapInPathRegex = Regex("""/(?:chuong|chapter)-([\d]+(?:\.[\d]+)?)(?:/|\?|$)""")
    private val timeRegex = Regex("""(\d+\s*(Phút|Giờ|Ngày|Tuần|Tháng|Năm)\s*Trước)""", RegexOption.IGNORE_CASE)

    fun storyIdOf(detailUrl: String): String {
        val m = Regex("""-(\d+)$""").find(detailUrl.trimEnd('/'))
        return m?.groupValues?.get(1) ?: ""
    }

    /** Slug tran cua truyen (bo hau to -{id} dang ar neu co). */
    fun slugOf(detailUrl: String): String {
        val seg = detailUrl.trimEnd('/').substringAfter("/truyen-tranh/", "")
        if (seg.isEmpty() || seg.contains("/")) return ""
        return seg.replace(Regex("""-\d+$"""), "")
    }

    fun fetchDoc(rawUrl: String): Document {
        val url = rebase(rawUrl)
        val doc = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
            .header("Referer", "$BASE/")
            .timeout(30000)
            .followRedirects(true)
            .get()
        val text = doc.text()
        if (doc.title().contains("Just a moment", true) ||
            text.contains("xác minh bạn là con người", true) ||
            text.contains("Thực hiện xác minh bảo mật", true)
        ) {
            throw BlockedException()
        }
        return doc
    }

    fun parseHtml(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)

    // ---------------- URL cac ke truyen (cho ca duong WebView du phong) ----------------

    fun latestUrl(page: Int): String =
        if (page <= 1) "$BASE$MANHWA_PATH" else "$BASE$MANHWA_PATH?page=$page"

    fun newUrl(page: Int): String =
        if (page <= 1) "$BASE$MANHWA_PATH?sort=15" else "$BASE$MANHWA_PATH?sort=15&page=$page"

    fun hotUrl(): String = "$BASE$MANHWA_PATH?sort=10"

    fun searchUrl(keyword: String): String =
        "$BASE/tim-truyen?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}"

    // ---------------- Danh sach truyen ----------------

    /** Trang chu: manhwa moi cap nhat (sap xep mac dinh cua web). */
    fun fetchLatest(page: Int): List<Comic> = parseList(fetchDoc(latestUrl(page)))

    /** Tab Moi: truyen moi dang (sort=15). */
    fun fetchNewPage(page: Int): List<Comic> = parseList(fetchDoc(newUrl(page)))

    /** Tab Hot: xem nhieu nhat moi thoi dai (sort=10), 1 trang. */
    fun fetchHot(): List<Comic> = parseList(fetchDoc(hotUrl()))

    fun search(keyword: String): List<Comic> {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        for (param in listOf("keyword", "query")) {
            try {
                val list = parseList(fetchDoc("$BASE/tim-truyen?$param=$q"))
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // Thu tham so tiep theo
            }
        }
        return emptyList()
    }

    /** Lay URL anh that tu the img: uu tien cac thuoc tinh lazy-load, cuoi cung moi la src. */
    private fun imgUrl(img: org.jsoup.nodes.Element): String {
        for (attr in listOf("data-original", "data-src", "data-lazy-src", "data-url", "src")) {
            val v = img.absUrl(attr)
            if (v.isNotEmpty() && !v.contains("placeholder", true) &&
                !v.endsWith("loading.gif") && !v.contains("thumb-default")
            ) {
                return v
            }
        }
        return img.absUrl("src")
    }

    /**
     * Doc the truyen tu trang liet ke/ket qua tim kiem.
     * Cau truc the chuan cua ho web nay: div.item > figure > .image a + figcaption
     * (h3 a = ten; cac link chuong ben duoi = chuong moi nhat + thoi gian).
     */
    fun parseList(doc: Document, strict: Boolean = true): List<Comic> {
        val result = LinkedHashMap<String, Comic>()
        for (item in doc.select("div.item")) {
            val a = item.selectFirst(".image a[href], figure a[href], a[href]") ?: continue
            val href = a.absUrl("href").substringBefore("?").trimEnd('/')
            if (!detailPathRegex.containsMatchIn(href)) continue
            if (result.containsKey(href)) continue
            val img = item.selectFirst("img") ?: continue
            val title = item.selectFirst("figcaption h3 a")?.text()?.trim()
                .orEmpty()
                .ifEmpty { img.attr("alt").trim() }
                .ifEmpty { a.attr("title").trim() }
            if (title.isEmpty()) continue
            val cover = imgUrl(img)
            var latest = ""
            var updated = ""
            for (ca in item.select("a[href]")) {
                val chref = ca.absUrl("href")
                if (chapInPathRegex.containsMatchIn(chref)) {
                    latest = ca.text().trim()
                    break
                }
            }
            val m = timeRegex.find(item.text())
            if (m != null) updated = m.value
            result[href] = Comic(title, href, cover, latest, updated)
        }
        if (result.isNotEmpty()) return result.values.toList()
        return parseListLoose(doc)
    }

    /** Cach doc long hon: bat ky link chi tiet nao boc kem anh bia trong cung khoi. */
    fun parseListLoose(doc: Document): List<Comic> {
        val result = LinkedHashMap<String, Comic>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").substringBefore("?").trimEnd('/')
            if (!detailPathRegex.containsMatchIn(href)) continue
            if (chapInPathRegex.containsMatchIn(href)) continue
            if (result.containsKey(href)) continue
            var container: org.jsoup.nodes.Element? = a
            var img: org.jsoup.nodes.Element? = a.selectFirst("img")
            var depth = 0
            while (img == null && container != null && depth < 4) {
                container = container.parent()
                img = container?.selectFirst("img")
                depth++
            }
            val title = a.text().trim()
                .ifBlank { a.attr("title").trim() }
                .ifBlank { img?.attr("alt")?.trim() ?: "" }
            if (title.isEmpty()) continue
            result[href] = Comic(title, href, img?.let { imgUrl(it) } ?: "")
        }
        return result.values.toList()
    }

    // ---------------- Chi tiet truyen + FULL CHAP ----------------

    fun fetchDetail(detailUrl: String): ComicDetail {
        val rebased = rebase(detailUrl)
        try {
            return parseDetail(fetchDoc(rebased), rebased)
        } catch (e: Exception) {
            // Cua gg khong nhan dang -{id}, cua ar khong nhan slug tran:
            // thu dang con lai truoc khi bo cuoc.
            val alt = alternateDetailUrl(rebased)
            if (alt != null) {
                try {
                    return parseDetail(fetchDoc(alt), alt)
                } catch (e2: Exception) {
                    // Roi xuong duong tim kiem tu chua ben duoi
                }
            }
            // Chua lanh cuoi: tim lai truyen theo ten slug tren cua dang dung
            val slug = slugOf(rebased)
            if (slug.isNotEmpty()) {
                val found = search(slug.replace('-', ' ')).firstOrNull()
                if (found != null && found.url != rebased) {
                    return parseDetail(fetchDoc(found.url), found.url)
                }
            }
            throw e
        }
    }

    /** Dang URL chi tiet con lai (them/bo hau to -{id}) de thu khi dang chinh 404. */
    private fun alternateDetailUrl(url: String): String? {
        val clean = url.trimEnd('/')
        val seg = clean.substringAfter("/truyen-tranh/", "")
        if (seg.isEmpty() || seg.contains("/")) return null
        return if (Regex("""-\d+$""").containsMatchIn(seg)) {
            clean.replace(Regex("""-\d+$"""), "")
        } else {
            null // Khong the tu biet id cua cua ar
        }
    }

    fun parseDetail(doc: Document, detailUrl: String): ComicDetail {
        val title = doc.selectFirst("h1")?.text()?.trim()
            .orEmpty()
            .ifEmpty { doc.title().substringBefore("[").trim() }
        var cover = ""
        val coverImg = doc.selectFirst(".col-image img")
            ?: doc.select("img").firstOrNull { it.attr("alt").trim() == title && title.isNotEmpty() }
        if (coverImg != null) cover = imgUrl(coverImg)

        var author = ""
        var status = ""
        var genres = ""
        // Bang thong tin chuan: ul.list-info > li > p.name (nhan) + p.col-xs-8 (gia tri)
        for (li in doc.select("ul.list-info li, .list-info li")) {
            val label = li.selectFirst(".name")?.text()?.trim() ?: continue
            val valueEl = li.selectFirst(".col-xs-8") ?: continue
            when {
                label.contains("Tác giả", true) -> author = valueEl.text().trim()
                label.contains("Tình trạng", true) -> status = valueEl.text().trim()
                label.contains("Thể loại", true) -> {
                    val links = valueEl.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
                    genres = if (links.isNotEmpty()) links.joinToString(", ")
                    else valueEl.text().replace("-", ", ").trim()
                }
            }
        }
        if (genres.isEmpty()) {
            genres = doc.select("a[href*=/tim-truyen/]")
                .map { it.text().trim() }
                .filter { it.length in 2..30 }
                .distinct().joinToString(", ")
        }

        var description = doc.selectFirst(".detail-content .shortened")?.text()?.trim().orEmpty()
        if (description.isEmpty()) {
            description = doc.selectFirst("meta[name=description]")?.attr("content") ?: ""
        }

        return ComicDetail(
            comic = Comic(title.ifEmpty { detailUrl }, detailUrl, cover),
            author = author,
            status = status,
            genres = genres,
            description = description,
            chapters = fetchFullChapters(doc, detailUrl),
            totalChapters = 0 // 0 = khong cong bo; danh sach lay tu dich vu la day du
        )
    }

    /**
     * FULL CHAP: goi dich vu ChapterList cua chinh web (theo slug).
     * LUU Y: dich vu nay chi song o cua gg — cua ar tra 302 ve trang chu
     * (kiem chung 24/10/2026) nen phai thu LAN LUOT cac cua cho den khi
     * cua nao tra JSON that. URL chuong van dung tren cua dang dung.
     * Loi het thi rut xuong danh sach nhung san trong HTML (20 chuong moi nhat).
     */
    private fun fetchFullChapters(doc: Document, detailUrl: String): List<ComicChapter> {
        val slug = slugFromDoc(doc, detailUrl)
        if (slug.isNotEmpty()) {
            val doors = listOf(BASE, DEFAULT_BASE, "https://nettruyenar.com").distinct()
            for (door in doors) {
                try {
                    val body = Jsoup.connect("$door/Comic/Services/ComicService.asmx/ChapterList?slug=$slug")
                        .userAgent(UA)
                        .header("Referer", "$door/")
                        .ignoreContentType(true)
                        .timeout(20000)
                        .execute()
                        .body()
                    val arr = JSONObject(body).optJSONArray("data")
                    if (arr == null || arr.length() == 0) continue
                    val list = ArrayList<ComicChapter>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val num = o.optDouble("chapter_num", -1.0)
                        if (num < 0) continue
                        val numStr = if (num == num.toLong().toDouble()) num.toLong().toString()
                        else num.toString()
                        val name = o.optString("chapter_name").ifEmpty { "Chapter $numStr" }
                        val date = o.optString("updated_at").substringBefore(" ")
                        list.add(
                            ComicChapter(
                                name = name,
                                number = num,
                                url = "$BASE/truyen-tranh/$slug/chuong-$numStr",
                                date = date
                            )
                        )
                    }
                    if (list.isNotEmpty()) return list.sortedBy { it.number }
                } catch (e: Exception) {
                    // Cua nay khong co dich vu song: thu cua tiep theo
                }
            }
        }
        return chaptersFromHtml(doc, detailUrl)
    }

    /** Slug lay tu chinh trang chi tiet: hang chuong dau tien, hoac script gOpts, hoac URL. */
    private fun slugFromDoc(doc: Document, detailUrl: String): String {
        for (a in doc.select(".list-chapter a[href], #nt_listchapter a[href]")) {
            val m = Regex("""/truyen-tranh/([a-z0-9\-]+)/(?:chuong|chapter)-""")
                .find(a.absUrl("href"))
            if (m != null) return m.groupValues[1]
        }
        val scriptText = doc.select("script").joinToString(" ") { it.html() }
        Regex("""comicSlug["']?\s*[:=]\s*["']([a-z0-9\-]+)["']""").find(scriptText)
            ?.let { return it.groupValues[1] }
        return slugOf(detailUrl)
    }

    /** Danh sach chuong nhung san trong HTML (chi ~20 chuong moi nhat) — du phong. */
    private fun chaptersFromHtml(doc: Document, detailUrl: String): List<ComicChapter> {
        val byNum = LinkedHashMap<Double, ComicChapter>()
        val slug = slugOf(detailUrl)
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href").substringBefore("?").trimEnd('/')
            val m = chapInPathRegex.find(href) ?: continue
            if (slug.isNotEmpty() && !href.contains("/$slug/")) continue
            val num = m.groupValues[1].toDoubleOrNull() ?: continue
            val name = a.text().trim().ifEmpty { "Chapter ${m.groupValues[1]}" }
            val parentText = a.parents().firstOrNull { it.hasClass("row") }?.text() ?: ""
            val date = Regex("""\d{2}/\d{2}/\d{4}""").find(parentText)?.value
                ?: Regex("""\d+\s*(Phút|Giờ|Ngày|Tuần|Tháng|Năm)\s*Trước""", RegexOption.IGNORE_CASE)
                    .find(parentText)?.value ?: ""
            byNum[num] = ComicChapter(name, num, href, date)
        }
        return byNum.values.sortedBy { it.number }
    }

    // ---------------- Trang doc chuong (anh) ----------------

    fun fetchChapter(chapterUrl: String): ChapterPages = parseChapter(fetchDoc(chapterUrl))

    fun parseChapter(doc: Document): ChapterPages {
        val images = ArrayList<String>()
        for (img in doc.select("img.page-chapter")) {
            var src = ""
            for (attr in listOf("data-sv1", "data-sv2", "data-src", "data-original", "src")) {
                val v = img.absUrl(attr)
                if (v.isNotEmpty() && (v.contains("cloud-zzz.com") || v.contains("kcgsbok.com") ||
                        v.contains("/nettruyen/"))
                ) {
                    src = v
                    break
                }
            }
            if (src.isEmpty()) {
                // Chap dung CDN la: van lay data-sv1/src neu co
                src = img.absUrl("data-sv1").ifEmpty { img.absUrl("src") }
            }
            if (src.isNotEmpty() && !images.contains(src)) images.add(src)
        }
        if (images.isEmpty()) {
            // Du phong long hon: moi img co duong dan giong anh trang truyen
            val pagePattern = Regex("""/nettruyen/[^/]+/[\d.]+/\d+\.(png|jpg|jpeg|webp)""", RegexOption.IGNORE_CASE)
            for (img in doc.select("img")) {
                val src = img.absUrl("data-sv1").ifEmpty { img.absUrl("data-src") }
                    .ifEmpty { img.absUrl("src") }
                if (src.isNotEmpty() && pagePattern.containsMatchIn(src) && !images.contains(src)) {
                    images.add(src)
                }
            }
        }
        var prev: String? = null
        var next: String? = null
        for (a in doc.select("a[href]")) {
            val t = a.text()
            if (prev == null && (t.contains("Chap trước", true) || t.contains("Chương trước", true))) {
                prev = a.absUrl("href")
            }
            if (next == null && (t.contains("Chap sau", true) || t.contains("Chương sau", true))) {
                next = a.absUrl("href")
            }
        }
        return ChapterPages(images, prev, next)
    }
}
