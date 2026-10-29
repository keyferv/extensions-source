package eu.kanade.tachiyomi.extension.es.mantrazscan

import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class ManhwaScan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val baseHost = baseUrl.toHttpUrl().host
            val isPageImage = (request.url.host == baseHost || request.url.host.endsWith(".$baseHost")) &&
                request.url.encodedPath.startsWith(PAGE_IMAGE_PATH_PREFIX)

            if (!isPageImage) {
                return@addInterceptor chain.proceed(request)
            }

            val response = chain.proceed(
                request.newBuilder()
                    .removeHeader("Accept-Encoding")
                    .header("Accept-Encoding", "identity")
                    .build(),
            )
            val body = response.body
            val mediaType = body.contentType()
            val bytes = body.bytes()

            response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(bytes.toResponseBody(mediaType))
                .build()
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else exploreUrl(page)
        val document = client.get(url).asJsoup()
        return if (page == 1) {
            // Homepage renders trending slider; preserve exact local behavior
            parseTrendingPage(document)
        } else {
            parseSeriesGrid(document)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page == 1) baseUrl else exploreUrl(page)
        val document = client.get(url).asJsoup()
        return if (page == 1) {
            parseSeriesGrid(document, forceHasNextPage = true)
        } else {
            parseSeriesGrid(document)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        val basePath = if (page > 1) "$baseUrl/explorar/page/$page/" else "$baseUrl/explorar/"
        val url = basePath.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            if (genre.isNotEmpty()) addQueryParameter("genero", genre)
        }.build()
        val document = client.get(url).asJsoup()
        return parseSeriesGrid(document)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val baseHost = baseUrl.toHttpUrl().host
        if (url.host != baseHost && !url.host.endsWith(".$baseHost")) return null
        val path = url.encodedPath
        if (path == "/" || path == "/explorar" || path.startsWith("/explorar/")) return null
        // Normalize chapter URLs to manga URL when pasted
        val mangaPath = if (path.contains("/capitulo-")) path.substringBefore("/capitulo-") else path
        if (mangaPath.isBlank()) return null
        val manga = SManga.create().apply {
            this.url = mangaPath.ensureStartsWithSlash()
        }
        return try {
            fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).manga
                .takeIf { it.title.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val targetUrl = getMangaUrl(manga)
        var body = ""
        var requestPath = ""
        var document: Document? = null
        client.get(targetUrl).use { response ->
            body = response.body.string()
            requestPath = response.request.url.encodedPath.trimEnd('/')
            document = Jsoup.parse(body, baseUrl)
        }
        val doc = document!!

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = doc.selectFirst(".series-title")?.text()?.trim().orEmpty()
                thumbnail_url = doc.selectFirst(".series-cover img[src], .series-hero img[src]")?.attr("abs:src")
                description = doc.selectFirst(".series-desc")?.text()?.trim()
                genre = doc.select(".series-tags a, .series-tags span")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { null }
                status = parseStatus(doc.selectFirst(".badge-pill, .badge-ongoing")?.text())
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val fromRsc = parseEmbeddedChapterNumbers(body).map { chapterNumber ->
                SChapter.create().apply {
                    name = "Capítulo $chapterNumber"
                    url = chapterUrl(requestPath, chapterNumber)
                    chapter_number = chapterNumber.toFloatOrNull() ?: 0f
                }
            }.sortedByDescending { it.chapter_number }

            if (fromRsc.isNotEmpty()) {
                fromRsc
            } else {
                doc.select(".chapters-grid .ch-row[href]").mapNotNull { link ->
                    val chapterNumber = link.attr("href").trimEnd('/').substringAfterLast("capitulo-")
                    if (chapterNumber.isBlank()) return@mapNotNull null
                    SChapter.create().apply {
                        name = link.text().trim().ifEmpty { "Capítulo $chapterNumber" }
                        url = link.attr("href")
                            .substringAfter(baseUrl)
                            .ensureStartsWithSlash()
                        chapter_number = chapterNumber.toFloatOrNull() ?: 0f
                    }
                }.sortedByDescending { it.chapter_number }
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val pages = document.select(
            "main img[src*='/WP-manga/data/'], " +
                "main img[alt*='Página'], " +
                "link[rel=preload][as=image][href*='/WP-manga/data/']",
        )
            .mapNotNull { element ->
                when (element.tagName()) {
                    "link" -> element.attr("href")
                    else -> element.attr("src")
                        .ifBlank { element.attr("data-src") }
                        .ifBlank { element.attr("data-lazy-src") }
                }.toAbsoluteImageUrl()
            }
            .distinct()

        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filtrar por género"),
        GenreFilter(),
    )

    private fun parseSeriesGrid(document: Document, forceHasNextPage: Boolean = false): MangasPage {
        val mangas = document.selectFirst("main .series-grid")
            ?.select(".s-card")
            ?.mapNotNull { card ->
                val link = card.selectFirst("a.s-card-title[href]") ?: return@mapNotNull null
                SManga.create().apply {
                    title = link.text().trim()
                    url = link.attr("href")
                        .substringAfter(baseUrl)
                        .ensureStartsWithSlash()
                    thumbnail_url = card.selectFirst(".s-card-img img[src]")?.attr("abs:src")
                }
            }
            .orEmpty()

        val hasNextPage = forceHasNextPage || document.selectFirst(".pager-btn[href]:matchesOwn(Siguiente)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseTrendingPage(document: Document): MangasPage {
        val mangas = document.select(".tsl-slide").mapNotNull { slide ->
            val title = slide.selectFirst(".tsl-title")?.text()?.trim().orEmpty()
            val link = slide.selectFirst("a.tsl-cover[href], a.tsl-cta[href]") ?: return@mapNotNull null

            SManga.create().apply {
                this.title = title.ifEmpty { link.attr("title") }
                url = link.attr("href")
                    .substringAfter(baseUrl)
                    .ensureStartsWithSlash()
                thumbnail_url = slide.selectFirst(".tsl-cover img[src]")?.attr("abs:src")
            }
        }

        return MangasPage(mangas, true)
    }

    private fun exploreUrl(page: Int): String = if (page > 1) {
        "$baseUrl/explorar/page/$page/"
    } else {
        "$baseUrl/explorar/"
    }

    private fun parseStatus(text: String?): Int = when {
        text.isNullOrBlank() -> SManga.UNKNOWN
        text.contains("emisión", true) || text.contains("en emisión", true) || text.contains("ongoing", true) -> SManga.ONGOING
        text.contains("finalizado", true) || text.contains("completo", true) -> SManga.COMPLETED
        text.contains("pausa", true) || text.contains("hiatus", true) -> SManga.ON_HIATUS
        text.contains("cancel", true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun parseEmbeddedChapterNumbers(body: String): List<String> {
        val raw = CHAPTERS_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val cleaned = raw.replace(RSC_SPLIT_NOISE, "")

        return CHAPTER_NUM_REGEX.findAll(cleaned)
            .map { it.value }
            .distinct()
            .toList()
    }

    private fun chapterUrl(mangaPath: String, chapterNumber: String): String {
        val num = chapterNumber.toFloatOrNull()
        return if (num != null && num == num.toLong().toFloat()) {
            "$mangaPath/capitulo-${num.toLong().toInt()}/"
        } else {
            "$mangaPath/capitulo-$chapterNumber/"
        }
    }

    private fun String.ensureStartsWithSlash(): String = if (startsWith('/')) this else "/$this"

    private fun String.toAbsoluteImageUrl(): String? {
        if (isBlank()) return null
        return baseUrl.toHttpUrl()
            .resolve(this)
            ?.toString()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    companion object {
        private const val PAGE_IMAGE_PATH_PREFIX = "/img/WP-manga/data/"

        private val CHAPTERS_REGEX = Regex("\\\\\\\"chapters\\\\\\\":\\[(.*?)\\\\\\\"slug\\\\\"", setOf(RegexOption.DOT_MATCHES_ALL))
        private val CHAPTER_NUM_REGEX = Regex("[0-9]+(?:\\.[0-9]+)?")
        private val RSC_SPLIT_NOISE = "\"])</script><script>self.__next_f.push([1,\""
    }
}
