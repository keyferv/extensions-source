package eu.kanade.tachiyomi.extension.es.animebbg

import android.webkit.CookieManager
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.runWebViewBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Source
abstract class AnimeBBG : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Referer", "$baseUrl/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::passBotCheck).rateLimit(2)

    private val botCheckLock = Any()

    @Volatile
    private var botCheckPassedAt = Instant.DISTANT_PAST

    private fun passBotCheck(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestedAt = Clock.System.now()
        val response = chain.proceed(request)
        val peek = response.peekBody(1024).string()
        if (!peek.contains("One moment, please") && !peek.contains("wsidchk")) {
            return response
        }
        response.close()
        synchronized(botCheckLock) {
            if (botCheckPassedAt < requestedAt) {
                val appUserAgent = request.header("User-Agent")
                val staleCookie = botCheckCookie()
                runCatching {
                    runWebViewBlocking<Unit>(chain.call(), timeout = 25.seconds) {
                        if (appUserAgent != null) {
                            userAgent = appUserAgent
                        }
                        domStorageEnabled = true
                        javaScriptEnabled = true
                        onPageStarted {
                            if (botCheckCookie().let { it != null && it != staleCookie }) resolve(Unit)
                        }
                        onPageFinished {
                            poll(500.milliseconds) {
                                if (botCheckCookie().let { it != null && it != staleCookie }) resolve(Unit)
                            }
                        }
                        loadUrl(baseUrl)
                    }
                }.onFailure {
                    throw IOException(
                        "Verificación de seguridad en AnimeBBG. Abre la extensión en WebView para resolverla.",
                        it,
                    )
                }
                botCheckPassedAt = Clock.System.now()
            }
        }
        return chain.proceed(request)
    }

    private fun botCheckCookie() = CookieManager.getInstance().getCookie(baseUrl)
        ?.split("; ")
        ?.firstOrNull { it.startsWith("$BOT_CHECK_COOKIE=") }

    // Popular (homepage ranking)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        return MangasPage(mangas, false)
    }

    private fun popularMangaSelector(): String = "a.h2-rank-card, a.xcHomeV2-rankCard"

    private fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst(".h2-rank-title")?.text()
            ?: element.selectFirst("strong")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page > 1) "$baseUrl/whats-new/?page=$page" else "$baseUrl/whats-new/"
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }
        val hasNextPage = document.selectFirst(latestUpdatesNextPageSelector(page + 1)) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun latestUpdatesSelector(): String = ".nv2-row"

    private fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        val mangaLink = element.selectFirst(".nv2-head a[href*='/obras/']")
            ?: element.select("a[href*='/obras/']").firstOrNull { it.text().isNotEmpty() }
        setUrlWithoutDomain(mangaLink?.attr("abs:href").orEmpty())
        title = mangaLink?.text() ?: ""
        thumbnail_url = element.selectFirst("a[href*='/obras/'] img")?.attr("abs:src")
            ?: element.selectFirst("img")?.attr("abs:src")
    }

    private fun latestUpdatesNextPageSelector(nextPage: Int): String = "a[href*='?page=$nextPage'], a.pageNav-jump--next"

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/obras/buscar/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector(page + 1)) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun searchMangaSelector(): String = ".search-comic"

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.search-comic-title a.search-comic-link, h3.search-comic-title a, a.search-comic-link")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        title = link?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    private fun searchMangaNextPageSelector(nextPage: Int): String = "a[href*='?page=$nextPage'], a.pageNav-jump--next"

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
                parseMangaDetails(client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup(), manga)
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
        title = document.selectFirst("h1.abbgFicha-title")?.text()
            ?: document.selectFirst("h1.p-title-value")?.ownText()
            ?: document.select("h1.p-title-value").text().substringAfter(" Manhwa ")
        description = document.select("div.bbWrapper").text()
        author = document.select("a.username[data-user-id]").firstOrNull()?.text()
        genre = document.select("a.tagItem, dl[data-field='demografia'] dd").joinToString { it.text() }
        status = when (document.select("dl[data-field='status'] dd").text()) {
            "Publicándose" -> SManga.ONGOING
            "Terminado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = document.selectFirst("img.abbgFicha-bannerImg")?.attr("abs:src")
            ?: document.selectFirst("div.resourceSidebarGroup--banner img, div.ozzmodz-adult-inner img")?.attr("abs:src")
    }

    // Chapters

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        var document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()

        // Canonical path follows /comics/ -> /obras/ redirects; pagination reuses it.
        var mangaPath = document.location().substringAfter(baseUrl).substringBefore("?").removeSuffix("/")
        if (mangaPath.isEmpty()) {
            mangaPath = manga.url.substringBefore("?").removeSuffix("/")
        }

        val chapters = mutableListOf<SChapter>()
        var page = 1

        while (true) {
            chapters.addAll(document.select(chapterListSelector()).map { element -> chapterFromElement(element) })
            if (document.selectFirst(chapterNextPageSelector(page + 1)) == null) break

            page++
            document = client.get(chapterListUrl(mangaPath, page), Fetch.DOCUMENT).asJsoup()
        }

        return chapters
    }

    private fun chapterListUrl(mangaPath: String, page: Int): String = baseUrl + mangaPath.removeSuffix("/") + if (page > 1) "?page=$page" else ""

    private fun chapterListSelector(): String = "li.abbgChRow, div.md-chapter-row"

    private fun chapterNextPageSelector(nextPage: Int): String = "a[href*='?page=$nextPage'], a.pageNav-jump--next"

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a.abbgChRow-link") ?: element.selectFirst("a.md-chapter-link")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        val number = element.selectFirst(".abbgChRow-num")?.text().orEmpty()
        val chapterTitle = element.selectFirst(".abbgChRow-title")?.text().orEmpty()
        name = listOf(number, chapterTitle).filter { it.isNotEmpty() }.joinToString(" - ")
            .ifEmpty { link?.text().orEmpty() }
        val isLocked = link?.hasAttr("data-abbg-unlock") == true ||
            element.selectFirst("[data-abbg-unlock], .ca-tag.t-early, .md-bbgMetaItem--lock") != null
        if (isLocked) name += " 🔒"
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return pageListParse(document)
    }

    private fun pageListParse(document: Document): List<Page> {
        val current = document.select("div.abbgReader-page img.abbgReader-img")
        if (current.isNotEmpty()) {
            return current.mapIndexedNotNull { i, img ->
                val imageUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                if (imageUrl.isNotEmpty() && !imageUrl.contains("data:image")) Page(i, imageUrl = imageUrl) else null
            }
        }

        // avmReader format: images use data-src for lazy loading
        val pages = document.select("div.avmReader-page:not(.avmReader-page--end)")

        if (pages.isEmpty()) {
            // Fallback to old format
            val images = document.select("div.media-container img, img.js-mediaImage")
            val legacy = images.mapIndexedNotNull { i, img ->
                val imageUrl = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                if (imageUrl.isNotEmpty() && !imageUrl.contains("data:image")) Page(i, imageUrl = imageUrl) else null
            }
            if (legacy.isNotEmpty() || !isLockedReader(document)) return legacy
            throw Exception("Chapter locked: requires credits on the site.")
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

    private fun isLockedReader(document: Document): Boolean {
        if (document.title().contains("bloqueado", ignoreCase = true)) return true
        return document.body().text().contains("CAPÍTULO DE PAGO", ignoreCase = true)
    }

    companion object {
        private const val BOT_CHECK_COOKIE = "wssplashchk"
    }
}
