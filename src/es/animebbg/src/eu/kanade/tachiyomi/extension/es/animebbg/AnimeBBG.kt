package eu.kanade.tachiyomi.extension.es.animebbg

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class AnimeBBG : KeiSource() {

    private val seenLatestManga = mutableSetOf<String>()
    private var latestUpdatesId: String? = null

    // Popular (Top 10 Ranking - Day)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    private fun popularMangaSelector(): String = "a.xcHomeV2-rankCard"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("strong")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page == 1) {
            seenLatestManga.clear()
            latestUpdatesId = null
        }
        var currentPage = page
        while (true) {
            val idTemplate = latestUpdatesId?.let { "$it/" } ?: ""
            val url = if (currentPage == 1) {
                "$baseUrl/whats-new/resource-albums/"
            } else {
                "$baseUrl/whats-new/resource-albums/${idTemplate}page-$currentPage"
            }
            val document = client.get(url).asJsoup()

            // Extract/Update ID for next pages
            document.selectFirst(latestUpdatesNextPageSelector())?.attr("href")?.let { nextUrl ->
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
            if (filteredMangas.isNotEmpty() || !hasNextPage || currentPage >= 100) { // Safety limit
                return MangasPage(filteredMangas, hasNextPage)
            }
            currentPage++
        }
    }

    private fun latestUpdatesSelector(): String = "div.structItem--albumLink"

    private fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        val mangaLink = element.selectFirst(".structItem-title a:last-of-type")
        setUrlWithoutDomain(mangaLink?.attr("abs:href").orEmpty())
        title = mangaLink?.text() ?: ""
        thumbnail_url = element.selectFirst(".structItem-iconContainer img")?.attr("abs:src")
    }

    private fun latestUpdatesNextPageSelector(): String = "a.pageNav-jump--next"

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/search".toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("c[title_only]", "1")
            .addQueryParameter("o", "date")
            .addQueryParameter("page", page.toString())
            .build()

        val document = client.get(url).asJsoup()
        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun searchMangaSelector(): String = "div.contentRow:has(h3.contentRow-title a[href*='/comics/']):not(:has(span.label:contains(Discusión)))"

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.contentRow-title a")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        title = link?.ownText() ?: ""
        thumbnail_url = "" // Real thumbnail is fetched in mangaDetailsParse
    }

    private fun searchMangaNextPageSelector(): String = "a.pageNav-jump--next"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val manga = SManga.create().apply { setUrlWithoutDomain(url.toString()) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    // Details

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val updatedMangaAsync = async {
            if (fetchDetails) {
                parseMangaDetails(client.get(getMangaUrl(manga)).asJsoup(), manga)
            } else {
                manga
            }
        }
        val chaptersAsync = async {
            if (fetchChapters) fetchChapterList(manga) else chapters
        }
        SMangaUpdate(updatedMangaAsync.await(), chaptersAsync.await())
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.p-title-value")?.ownText()
            ?: document.select("h1.p-title-value").text().substringAfter(" Manhwa ")
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

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        var document = client.get(chapterListUrl(manga.url, 1)).asJsoup()

        // If not on chapters page, try to find the link from the tab
        if (document.selectFirst(chapterListSelector()) == null) {
            val tabUrl = document.selectFirst("a.tabs-tab[href$='/capitulos']")?.attr("abs:href")
            if (tabUrl != null) {
                document = client.get(tabUrl).asJsoup()
            }
        }

        val mangaPath = document.location().substringBefore("/capitulos").substringAfter(baseUrl)
        val chapters = mutableListOf<SChapter>()
        var page = 1

        while (true) {
            chapters.addAll(document.select(chapterListSelector()).map { element -> chapterFromElement(element) })
            if (document.selectFirst("a.pageNav-jump--next") == null) break

            page++
            document = client.get(chapterListUrl(mangaPath, page)).asJsoup()
        }

        return chapters
    }

    private fun chapterListUrl(mangaPath: String, page: Int): String = baseUrl + mangaPath.removeSuffix("/") + "/capitulos" + if (page > 1) "?page=$page" else ""

    private fun chapterListSelector(): String = "div.md-chapter-row"

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a.md-chapter-link")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        val title = link?.text() ?: ""
        val isLocked = element.selectFirst(".md-bbgMetaItem--lock") != null
        name = if (isLocked) "$title 🔒" else title
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return pageListParse(document)
    }

    private fun pageListParse(document: Document): List<Page> {
        // New avmReader format: images use data-src for lazy loading
        val pages = document.select("div.avmReader-page:not(.avmReader-page--end)")

        if (pages.isEmpty()) {
            // Fallback to old format
            val images = document.select("div.media-container img, img.js-mediaImage")
            return images.mapIndexed { i, img ->
                val imageUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                Page(i, imageUrl = imageUrl)
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
                Page(i, imageUrl = imageUrl)
            } else {
                // Deferred page: construct URL from media ID
                val mediaId = page.attr("data-media-id")
                if (mediaId.isNotEmpty() && chapterPrefix.isNotEmpty()) {
                    val pageNumber = (i + 1).toString().padStart(3, '0')
                    val constructedUrl = "$baseUrl/libreria/$chapterPrefix-$pageNumber.$mediaId/full"
                    Page(i, imageUrl = constructedUrl)
                } else {
                    null
                }
            }
        }.filterNotNull()
    }
}
