package eu.kanade.tachiyomi.extension.es.animebbg

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class AnimeBBG : HttpSource() {

    override val supportsLatest = true
    private val seenLatestManga = mutableSetOf<String>()
    private var latestUpdatesId: String? = null

    // Popular (Top 10 Ranking - Day)

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        return MangasPage(mangas, false)
    }

    private fun popularMangaSelector(): String = "a.xcHomeV2-rankCard"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("strong")?.text()?.trim() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) {
            seenLatestManga.clear()
            latestUpdatesId = null
            return GET("$baseUrl/whats-new/resource-albums/", headers)
        }
        val idTemplate = if (latestUpdatesId != null) "$latestUpdatesId/" else ""
        return GET("$baseUrl/whats-new/resource-albums/${idTemplate}page-$page", headers)
    }

    private fun latestUpdatesSelector(): String = "div.structItem--albumLink"

    private fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        val mangaLink = element.selectFirst(".structItem-title a:last-of-type")
        setUrlWithoutDomain(mangaLink?.attr("href") ?: "")
        title = mangaLink?.text() ?: ""
        thumbnail_url = element.selectFirst(".structItem-iconContainer img")?.attr("abs:src")
    }

    private fun latestUpdatesNextPageSelector(): String = "a.pageNav-jump--next"

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // Extract/Update ID for next pages
        val nextUrl = document.selectFirst(latestUpdatesNextPageSelector())?.attr("href")
        if (nextUrl != null) {
            val newId = nextUrl.substringAfter("/resource-albums/", "").substringBefore("/", "")
            if (newId.isNotEmpty() && newId.all { it.isDigit() }) {
                latestUpdatesId = newId
            }
        }

        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }

        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector()) != null

        val filteredMangas = mangas.filter { seenLatestManga.add(it.url) }

        // If current page is empty after filtering but there's more, fetch next
        if (filteredMangas.isEmpty() && hasNextPage) {
            val currentUrl = response.request.url.toString()
            val page = currentUrl.substringAfter("page-", "1").toIntOrNull() ?: 1
            if (page < 100) { // Safety limit
                return latestUpdatesParse(client.newCall(latestUpdatesRequest(page + 1)).execute())
            }
        }

        return MangasPage(filteredMangas, hasNextPage)
    }

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("c[title_only]", "1")
            .addQueryParameter("o", "date")
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun searchMangaSelector(): String = "div.contentRow:has(h3.contentRow-title a[href*='/comics/']):not(:has(span.label:contains(Discusión)))"

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.contentRow-title a")
        setUrlWithoutDomain(link?.attr("href") ?: "")
        title = link?.ownText()?.trim() ?: link?.text()?.trim() ?: ""
        thumbnail_url = "" // Real thumbnail is fetched in mangaDetailsParse
    }

    private fun searchMangaNextPageSelector(): String = "a.pageNav-jump--next"

    // Details

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.p-title-value")?.ownText()?.trim()
            ?: document.select("h1.p-title-value").text().substringAfter(" Manhwa ").trim()
        description = document.select("div.bbWrapper").text()
        author = document.select("a.username[data-user-id]").firstOrNull()?.text()
        genre = document.select("a.tagItem, dl[data-field='demografia'] dd").joinToString { it.text() }
        status = when (document.select("dl[data-field='status'] dd").text()) {
            "Publicándose" -> SManga.ONGOING
            "Terminado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = document.selectFirst("div.resourceSidebarGroup--banner img, div.ozzmodz-adult-inner img")?.attr("abs:src")
    }

    // Chapters

    override fun chapterListRequest(manga: SManga): Request = chapterListRequest(manga, 1)

    private fun chapterListRequest(manga: SManga, page: Int): Request = GET(baseUrl + manga.url.removeSuffix("/") + "/capitulos" + if (page > 1) "?page=$page" else "", headers)

    private fun chapterListSelector(): String = "div.md-chapter-row"

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a.md-chapter-link")
        setUrlWithoutDomain(link?.attr("href") ?: "")
        val title = link?.text()?.trim() ?: ""
        val isLocked = element.selectFirst(".md-bbgMetaItem--lock") != null
        name = if (isLocked) "$title 🔒" else title
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        var document = response.asJsoup()

        // If not on chapters page, try to find the link from the tab
        if (document.selectFirst(chapterListSelector()) == null) {
            val tabUrl = document.selectFirst("a.tabs-tab[href$='/capitulos']")?.attr("abs:href")
            if (tabUrl != null) {
                document = client.newCall(GET(tabUrl, headers)).execute().asJsoup()
            }
        }

        val chapters = mutableListOf<SChapter>()
        var page = 1

        val mangaUrl = document.location().substringBefore("/capitulos").substringAfter(baseUrl)
        val manga = SManga.create().apply { url = mangaUrl }

        while (true) {
            chapters.addAll(document.select(chapterListSelector()).map { element -> chapterFromElement(element) })
            val nextPage = document.selectFirst("a.pageNav-jump--next")
            if (nextPage == null) break

            page++
            val nextResponse = client.newCall(chapterListRequest(manga, page)).execute()
            document = nextResponse.asJsoup()
        }

        return chapters
    }

    // Pages

    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    private fun pageListParse(document: Document): List<Page> {
        // New avmReader format: images use data-src for lazy loading
        val pages = document.select("div.avmReader-page:not(.avmReader-page--end)")

        if (pages.isEmpty()) {
            // Fallback to old format
            val images = document.select("div.media-container img, img.js-mediaImage")
            return images.mapIndexed { i, img ->
                val imageUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                Page(i, "", imageUrl)
            }.filter { it.imageUrl!!.isNotEmpty() && !it.imageUrl!!.contains("data:image") }
        }

        // Extract chapter number from first page that has a URL
        val firstPageWithUrl = pages.firstOrNull { it.selectFirst("img[data-src]")?.attr("data-src")?.isNotEmpty() == true }
        val chapterPrefix = firstPageWithUrl?.selectFirst("img[data-src]")?.attr("data-src")
            ?.substringAfter("/libreria/")?.substringBeforeLast(".")?.substringBeforeLast("-") ?: ""

        return pages.mapIndexed { i, page ->
            val img = page.selectFirst("img[data-src], img[src]")
            val imageUrl = img?.attr("abs:data-src")?.ifEmpty { img.attr("abs:src") }.orEmpty()

            if (imageUrl.isNotEmpty() && !imageUrl.contains("data:image")) {
                // Page has a direct URL
                Page(i, "", imageUrl)
            } else {
                // Deferred page: construct URL from media ID
                val mediaId = page.attr("data-media-id")
                if (mediaId.isNotEmpty() && chapterPrefix.isNotEmpty()) {
                    val pageNumber = (i + 1).toString().padStart(3, '0')
                    val constructedUrl = "$baseUrl/libreria/$chapterPrefix-$pageNumber.$mediaId/full"
                    Page(i, "", constructedUrl)
                } else {
                    null
                }
            }
        }.filterNotNull()
    }

    override fun imageUrlParse(response: Response): String = ""
}
