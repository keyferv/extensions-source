package eu.kanade.tachiyomi.extension.all.niadd

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Niadd : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        add("Referer", "$baseUrl/")
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    // Popular
    private fun popularMangaSelector() = "div.manga-item"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("div.manga-name")!!.text()
        val rawUrl = element.selectFirst("a")!!.absUrl("href")
        setUrlWithoutDomain(rawUrl)
        element.selectFirst("div.manga-img img")?.attr("abs:src")?.also { thumbnail_url = it }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/list/Hot-Manga.html").asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("name", query)
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/list/New-Update.html").asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    // Details + Chapters unified
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var updatedManga = manga
        var updatedChapters = chapters

        if (fetchDetails) {
            val document = client.get(getMangaUrl(manga)).asJsoup()
            updatedManga = parseMangaDetails(document)
            updatedManga.url = manga.url
        }

        if (fetchChapters) {
            val chaptersUrl = baseUrl + manga.url.removeSuffix(".html") + "/chapters.html"
            val document = client.get(chaptersUrl).asJsoup()
            document.selectFirst("ul.chapter-list")!!
            updatedChapters = document.select(chapterListSelector).map { chapterFromElement(it) }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val infoElement = document.select("div.bookside-general, div.detail-general")

        title = document.selectFirst("h1, .book-headline-name")!!.text()
        author = infoElement.select(".detail-general-cell:contains(Autor) span, [itemprop=author] span").text()
            .replace("Autor (es):", "", ignoreCase = true).ifBlank { null }
        artist = infoElement.select(".detail-general-cell:contains(Artista) span").text()
            .replace("Artista:", "", ignoreCase = true).ifBlank { null }
        genre = document.select("[itemprop=genre]").eachText().joinToString().ifBlank { null }

        val yearKeywords = listOf(
            "Released:",
            "Lanzado:",
            "Rilasciato:",
            "Выпущенный:",
            "Liberado:",
            "Freigegeben:",
        )

        val yearRaw = infoElement.select(".detail-general-cell").firstOrNull { cell ->
            yearKeywords.any { cell.text().contains(it, ignoreCase = true) }
        }?.selectFirst("span")?.text().orEmpty()

        val yearClean = yearRaw
            .let { text ->
                yearKeywords.fold(text) { acc, keyword -> acc.replace(keyword, "", ignoreCase = true) }
            }.trim()

        val synopsisKeywords = listOf(
            "Synopsis",
            "Sinopsis",
            "Sinossi",
            "конспект",
            "Sinopse",
            "Zusammenfassung",
        )

        val synopsisText = run {
            val titles = document.select(".detail-cate-title")
            for (title in titles) {
                val titleText = title.text()
                if (synopsisKeywords.any { keyword -> titleText.contains(keyword, ignoreCase = true) }) {
                    val nextSection = title.nextElementSibling()
                    if (nextSection != null && nextSection.hasClass("detail-section")) {
                        if (!nextSection.select("a[itemprop=genre]").any()) {
                            return@run nextSection.text()
                        }
                    }
                }
            }
            ""
        }

        description = buildString {
            if (yearClean.isNotEmpty()) append("Ano: $yearClean\n\n")
            if (synopsisText.isNotEmpty()) append(synopsisText)
        }.ifBlank { null }

        document.selectFirst("div.detail-img img, div.bookside-img img")?.attr("abs:src").also { thumbnail_url = it }
        status = SManga.ONGOING
    }

    // Chapters
    private val chapterListSelector = "ul.chapter-list a.hover-underline"

    private val dateFormats = listOf(
        SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH),
        SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
        SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
    )

    private fun parseDate(dateString: String): Long {
        val trimmed = dateString.trim()
        if (trimmed.contains("atrás", ignoreCase = true) ||
            trimmed.contains("ago", ignoreCase = true) ||
            trimmed.contains("hace", ignoreCase = true)
        ) {
            return 0L
        }

        for (format in dateFormats) {
            format.tryParse(trimmed).takeIf { it > 0L }?.let { return it }
        }
        return 0L
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val rawUrl = element.attr("abs:href")
        setUrlWithoutDomain(rawUrl)

        val rawName = element.selectFirst("span.chp-title, span.chapter-name")?.text()
            ?.takeIf(String::isNotEmpty)
            ?: element.text()

        val dateText = element.selectFirst("span.chp-time, span.chapter-time")?.text()
        if (dateText != null && dateText.isNotEmpty()) {
            date_upload = parseDate(dateText)
        }

        name = if (dateText != null) {
            rawName.removeSuffix(dateText).trimEnd()
        } else {
            rawName.replace(DATE_IN_NAME_REGEX, "")
        }

        chapter_number = CHAPTER_NUMBER_REGEX.find(name)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()
        return parsePageList(document)
    }

    private suspend fun parsePageList(document: Document): List<Page> {
        val seenUrls = LinkedHashSet<String>()
        val pages = mutableListOf<Page>()
        val currentUrl = document.location()
        val html = document.html()

        fun addPage(url: String, referrer: String = currentUrl) {
            if (seenUrls.add(url)) {
                pages.add(Page(pages.size, referrer, imageUrl = url))
            }
        }

        if (html.contains("all_imgs_url")) {
            val match = ALL_IMGS_URL_REGEX.find(html)
            if (match != null) {
                val content = match.groupValues[1]
                content.split(",")
                    .map { it.replace(CLEAN_IMG_URL_REGEX, "") }
                    .filter { it.startsWith("http") }
                    .forEach { addPage(it) }
                if (pages.isNotEmpty()) return pages
            }
        }

        val sourceButton = document.selectFirst("a.cool-blue.vision-button")
        if (sourceButton != null) {
            val sourceUrl = sourceButton.attr("abs:href")
            val requestHeaders = headersBuilder()
                .add("Referer", currentUrl)
                .build()
            val nextDoc = client.get(sourceUrl, requestHeaders).asJsoup()
            // Preserve recursion: delegate to same parsing with new document
            return parsePageListWithBase(nextDoc, currentUrl, seenUrls, pages)
        }

        document.select("div.pic_box img, div.reading-content img").forEach { img ->
            val url = img.attr("abs:src")
            if (url.isNotEmpty() && !url.contains("cover") && !url.contains("logo")) {
                addPage(url)
            }
        }

        val otherSubPages = document.select("select.sl-page option")
            .map { it.attr("value") }
            .filter { it.isNotEmpty() && !currentUrl.contains(it) }

        if (otherSubPages.isNotEmpty()) {
            otherSubPages.forEach { subPath ->
                val subUrl = if (subPath.startsWith("http")) subPath else baseUrl + subPath
                try {
                    val subDoc = client.get(subUrl).asJsoup()
                    subDoc.select("div.pic_box img, div.reading-content img").forEach { img ->
                        val imgUrl = img.attr("abs:src")
                        if (imgUrl.isNotEmpty() && !imgUrl.contains("cover")) {
                            addPage(imgUrl, subUrl)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return pages
    }

    private suspend fun parsePageListWithBase(
        document: Document,
        originalUrl: String,
        seenUrls: LinkedHashSet<String>,
        pages: MutableList<Page>,
    ): List<Page> {
        val currentUrl = document.location()
        val html = document.html()

        fun addPage(url: String, referrer: String = currentUrl) {
            if (seenUrls.add(url)) {
                pages.add(Page(pages.size, referrer, imageUrl = url))
            }
        }

        if (html.contains("all_imgs_url")) {
            val match = ALL_IMGS_URL_REGEX.find(html)
            if (match != null) {
                val content = match.groupValues[1]
                content.split(",")
                    .map { it.replace(CLEAN_IMG_URL_REGEX, "") }
                    .filter { it.startsWith("http") }
                    .forEach { addPage(it) }
                if (pages.isNotEmpty()) return pages
            }
        }

        // If redirected document still has sourceButton, follow once more
        val sourceButton = document.selectFirst("a.cool-blue.vision-button")
        if (sourceButton != null) {
            val sourceUrl = sourceButton.attr("abs:href")
            val requestHeaders = headersBuilder()
                .add("Referer", currentUrl)
                .build()
            val nextDoc = client.get(sourceUrl, requestHeaders).asJsoup()
            return parsePageListWithBase(nextDoc, originalUrl, seenUrls, pages)
        }

        document.select("div.pic_box img, div.reading-content img").forEach { img ->
            val url = img.attr("abs:src")
            if (url.isNotEmpty() && !url.contains("cover") && !url.contains("logo")) {
                addPage(url)
            }
        }

        val otherSubPages = document.select("select.sl-page option")
            .map { it.attr("value") }
            .filter { it.isNotEmpty() && !currentUrl.contains(it) }

        if (otherSubPages.isNotEmpty()) {
            otherSubPages.forEach { subPath ->
                val subUrl = if (subPath.startsWith("http")) subPath else baseUrl + subPath
                try {
                    val subDoc = client.get(subUrl).asJsoup()
                    subDoc.select("div.pic_box img, div.reading-content img").forEach { img ->
                        val imgUrl = img.attr("abs:src")
                        if (imgUrl.isNotEmpty() && !imgUrl.contains("cover")) {
                            addPage(imgUrl, subUrl)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return pages
    }

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headersBuilder()
            .add("Referer", page.url)
            .build()
        return Request.Builder().url(page.imageUrl!!).headers(imgHeaders).build()
    }

    companion object {
        private val ALL_IMGS_URL_REGEX = Regex("""all_imgs_url\s*:\s*\[([\s\S]*?)\]""")
        private val CLEAN_IMG_URL_REGEX = Regex("""["'\s]""")
        private val CHAPTER_NUMBER_REGEX = Regex("""(?:Chapter|Chapters|Ch\.?|Cap[ií]tulo)\s*[.:]?\s*(\d+(?:\.\d+)?)\b""")
        private val DATE_IN_NAME_REGEX = Regex("""\s+\w{3,9}\s+\d{1,2},?\s+\d{4}\s*$""")
    }
}
