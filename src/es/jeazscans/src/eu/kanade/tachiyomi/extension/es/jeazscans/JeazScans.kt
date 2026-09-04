package eu.kanade.tachiyomi.extension.es.jeazscans

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

private const val JEAZ_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"

@Source
abstract class JeazScans : KeiSource() {

    // Chapter images are proxied through a WebView (browser TLS fingerprint) by
    // WebViewInterceptor, since OkHttp gets 403 from Cloudflare on /api/imagen-capitulo.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        followRedirects(true)
        rateLimit(2)
        addInterceptor(WebViewInterceptor(baseUrl, JEAZ_USER_AGENT))
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", JEAZ_USER_AGENT)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    // The site migrated to custom home sections and PHP routes for search.
    // The homepage popular carousel is a finite collection (24 items); it has no
    // pagination, so hasNextPage is always false.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/").asJsoup()
        val mangas = document.select(".popular-carousel-shell a.popular-card[href*='manga.php?id=']")
            .mapNotNull { card ->
                val title = card.selectFirst("strong")?.text().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(card.attr("abs:href"))
                    this.title = title
                    thumbnail_url = card.selectFirst("img")?.let { img ->
                        img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
                            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    }
                }
            }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/directorio.php?page=$page").asJsoup()
        val mangas = document.select(".directory-grid a.directory-card[href*='manga.php?id=']")
            .map { element ->
                SManga.create().apply {
                    setUrlWithoutDomain(element.attr("abs:href"))
                    title = element.selectFirst("h3")!!.let { it.attr("title").ifBlank { it.text() } }
                    thumbnail_url = element.selectFirst(".directory-cover img")?.attr("abs:src")
                }
            }

        val hasNextPage = document.selectFirst(".directory-pagination a[aria-label='Página siguiente']") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getLatestUpdates(page)

        val url = "$baseUrl/ajax_search.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
        val items = client.get(url).parseAs<List<SearchResponseItem>>()
        return MangasPage(items.mapNotNull { it.toSManga(baseUrl) }, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.encodedPath != "/manga.php") return null
        val id = url.queryParameter("id") ?: return null
        val manga = SManga.create().apply { this.url = "/manga.php?id=$id" }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = getMangaUrl(manga)
        val document = client.get(mangaUrl).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) {
            val mangaId = extractMangaIdFromUrl(mangaUrl)
                ?: extractMangaIdFromScript(document)
                ?: throw Exception("Could not extract Jeaz Scans manga id from: $mangaUrl")
            val slug = extractMangaSlug(document)
                ?: throw Exception("Could not extract Jeaz Scans manga slug from: $mangaUrl")
            fetchAllChapters(mangaId, slug)
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst("h1.blood-title")!!.text()

        description = buildString {
            val descriptionBlock = document.selectFirst("div.text-gray-200:has(h3:matchesOwn((?i)SINOPSIS))")
                ?: document.selectFirst("div.text-gray-200")
            descriptionBlock?.let {
                append(it.ownText().ifEmpty { it.text().replace(SINOPSIS_REGEX, "") })
            }
        }

        thumbnail_url = document.selectFirst("div.lg\\:col-span-3 div.cultivation-panel img")?.attr("abs:src")

        genre = document.select("a[href*='directorio.php?genero=']").joinToString { it.text() }

        val statusText = document.selectFirst("span.status-badge")?.text().orEmpty().lowercase()
        if (statusText.isNotEmpty()) {
            status = when {
                statusText.contains("complet") -> SManga.COMPLETED
                arrayOf("pausa", "hiato").any { statusText.contains(it) } -> SManga.ON_HIATUS
                arrayOf("cancel", "aband").any { statusText.contains(it) } -> SManga.CANCELLED
                arrayOf("cultivo", "curso", "ongoing", "emision").any { statusText.contains(it) } -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private suspend fun fetchAllChapters(mangaId: Int, slug: String): List<SChapter> {
        val pages = walkChapterPages { offset ->
            val dto = client.get(chapterListUrl(mangaId, offset)).parseAs<ChaptersPageDto>()
            if (!dto.success) throw Exception("Jeaz Scans chapters API returned error")
            dto.toChapterPage()
        }
        return pages.flatMap { page ->
            page.chapters.mapNotNull { chapter -> chapter.toSChapter(slug, baseUrl) }
        }
    }

    private fun chapterListUrl(mangaId: Int, offset: Int): HttpUrl = "$baseUrl/api_capitulos_manga.php".toHttpUrl().newBuilder()
        .addQueryParameter("manga_id", mangaId.toString())
        .addQueryParameter("offset", offset.toString())
        .addQueryParameter("limit", CHAPTER_API_LIMIT.toString())
        .addQueryParameter("orden", "desc")
        .build()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Locked (paid) chapters carry the LOCKED_READER_URL sentinel; fail with a
        // clear message instead of constructing a request for a non-existent route.
        if (chapter.url.startsWith("$LOCKED_READER_URL/")) {
            throw Exception("This chapter is locked and requires payment on the Jeaz Scans website")
        }
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val imageElements = document.select(
            "#pagesContainer img.reader-page-image, .page-container img.protected-img, .reader-body img, .reading-content img",
        )

        val htmlPages = imageElements.mapNotNull { element ->
            val imageUrl = when {
                element.hasAttr("data-verify") -> decodeVerifyToUrl(element.attr("data-verify"))
                element.hasAttr("data-sec-src") -> element.attr("abs:data-sec-src")
                element.hasAttr("data-src") -> element.attr("abs:data-src")
                else -> element.attr("abs:src")
            }

            imageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }

        if (htmlPages.isNotEmpty()) return htmlPages

        return fetchPagesFromApi(document)
    }

    private suspend fun fetchPagesFromApi(document: Document): List<Page> {
        val (slug, cap) = extractSlugAndCap(document) ?: throw Exception("Could not extract slug/cap for API")
        val apiUrl = buildApiUrl(document.location(), slug, cap) ?: throw Exception("Could not build API URL")

        val apiHeaders = headers.newBuilder()
            .set("Referer", document.location())
            .build()

        val apiResponse = client.get(apiUrl, apiHeaders).parseAs<ApiLectorResponse>()
        if (!apiResponse.success) throw Exception("API returned error")

        val pages = apiResponse.paginas

        return pages.filter { it.dataVerify.isNotBlank() }
            .sortedBy { it.orden }
            .mapNotNull { decodeVerifyToUrl(it.dataVerify) }
            .distinct()
            .mapIndexed { idx, imageUrl -> Page(idx, imageUrl = imageUrl) }
    }

    companion object {
        private const val CHAPTER_API_LIMIT = 20
        private val SINOPSIS_REGEX = Regex("^SINOPSIS:?\\s*", RegexOption.IGNORE_CASE)
    }
}
