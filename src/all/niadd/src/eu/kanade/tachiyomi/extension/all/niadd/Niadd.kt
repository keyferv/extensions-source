package eu.kanade.tachiyomi.extension.all.niadd

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.booleanOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Niadd : KeiSource() {

    // Causes pageList 302 redirect loop to dead host
    override fun Headers.Builder.configureHeaders(): Headers.Builder = removeAll("Referer")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addNetworkInterceptor(::blockedUserInterceptor)

    // The site flags networks with a cookie and then serves a fake "404 Not Found" page with status 200 on every route.
    // A flagged network gets that page with or without the cookie, so the cookie only keeps blocking after a network change.
    private fun blockedUserInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = request.header("Cookie")
            ?.split("; ")
            ?.filterNot { it.startsWith("$BLOCKED_COOKIE=") }
            ?.joinToString("; ")
        val response = chain.proceed(
            request.newBuilder().apply {
                if (cookies.isNullOrEmpty()) removeHeader("Cookie") else header("Cookie", cookies)
            }.build(),
        )
        if (response.headers("Set-Cookie").any { it.startsWith("$BLOCKED_COOKIE=") }) {
            response.close()
            throw IOException(BLOCKED_NETWORK)
        }
        return response
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/list/Hot-Manga.html", Fetch.DOCUMENT).asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("div.manga-item").mapNotNull(::mangaFromElement)
        return MangasPage(mangas, document.selectFirst("a.next") != null)
    }

    // Lists carry blank cards linking /manga/.html
    private fun mangaFromElement(element: Element): SManga? {
        val title = element.selectFirst("div.manga-name")!!.textOrNull() ?: return null
        return SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
            thumbnail_url = element.selectFirst("div.manga-img img")?.absUrl("src")
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(client.get("$baseUrl/list/New-Update.html", Fetch.DOCUMENT).asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder().apply {
            addQueryParameter("name", query)
            filters.forEach { filter ->
                when (filter) {
                    is AuthorFilter -> addQueryParameter("author", filter.state)
                    is ArtistFilter -> addQueryParameter("artist", filter.state)
                    is SelectFilter -> addQueryParameter(filter.parameter, filter.value)
                    is GenreFilter -> {
                        addQueryParameter("category_id", filter.included.joinToString(","))
                        addQueryParameter("out_category_id", filter.excluded.joinToString(","))
                    }
                    else -> {}
                }
            }
            addQueryParameter("page", "$page.html")
        }.build()
        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.") ||
            segments.size != 2 ||
            segments[0] !in ENTRY_PATHS ||
            !segments[1].endsWith(".html")
        ) {
            return null
        }

        val manga = SManga.create().apply {
            this.url = "/${segments[0]}/${segments[1]}"
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val isOriginal = manga.url.startsWith("/original/")
        return coroutineScope {
            val details = async { if (fetchDetails) mangaDetailsParse(manga.url, isOriginal) else manga }
            val chapterList = async { if (fetchChapters) parseChapterList(manga.url, isOriginal) else chapters }
            SMangaUpdate(details.await(), chapterList.await())
        }
    }

    private suspend fun mangaDetailsParse(url: String, isOriginal: Boolean): SManga {
        val document = client.get(baseUrl + url, Fetch.DOCUMENT).asJsoup()
        return SManga.create().apply {
            val info = document.selectFirst("div.manga-detail")!!
            title = info.selectFirst("div.manga-detail-name")!!.text()
            thumbnail_url = info.selectFirst("div.manga-detail-img img")?.absUrl("src")
            author = info.selectFirst("a[href*=/search/?author=]")?.textOrNull()
            artist = info.selectFirst("a[href*=/search/?artist=]")?.textOrNull()
            genre = info.select("div.manga-detail-category a.category-name").joinToString { it.text() }
            status = when (info.selectFirst("div.manga-detail-status a.status-name")?.textOrNull()?.lowercase()) {
                in ONGOING_STATUSES -> SManga.ONGOING
                in COMPLETED_STATUSES -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            val ratingText = info.selectFirst("div.manga-detail-rating")?.textOrNull()?.let { rating ->
                val score = rating.substringBefore("/").toDoubleOrNull()
                val votes = rating.substringAfter("ratings:").trim().toIntOrNull()
                score?.let { "★ $it/5.0" }?.let { if (votes != null) "$it ($votes)" else it }
            }
            val desc = document.selectFirst("div.manga-detail-desc")?.textOrNull()
            description = listOfNotNull(ratingText, desc).joinToString("\n\n").ifEmpty { null }
        }
    }

    private suspend fun parseChapterList(url: String, isOriginal: Boolean): List<SChapter> {
        val chapters = if (isOriginal) {
            val document = client.get(baseUrl + url, Fetch.DOCUMENT).asJsoup()
            document.select("div.manga-chp-content a.manga-chp-item")
        } else {
            val mangaId = url.substringAfterLast("/").substringBefore(".").substringAfterLast("-")
            val chapterUrl = "$baseUrl/manga/$mangaId/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("v", System.currentTimeMillis().toString())
                .build()
            client.get(chapterUrl, Fetch.DOCUMENT).asJsoup().select("a.chp-item")
        }

        return chapters.map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                name = element.selectFirst("span.chp-title")!!.text()
                date_upload = dateFormat.tryParseDate(element.selectFirst("span.chp-time")?.text())
                if (isOriginal) memo = buildJsonObject { put("original", true) }
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val chapterId = chapterUrl.toHttpUrl().pathSegments[2].removeSuffix(".html")
        val url = readerJumpUrl().newBuilder().apply {
            setQueryParameter("cid", chapterId)
            // Users' original works have their own reader type in every language
            if (chapter.memo["original"]?.booleanOrNull == true) setQueryParameter("type", "ogniadd")
        }.build()
        val imageUrls = try {
            val html = client.get(url, Fetch.DOCUMENT.from(chapterUrl)).body.string()
            val imageList = ALL_IMGS_URL_REGEX.find(html)?.groupValues?.get(1) ?: throw Exception(PAGES_NOT_FOUND)
            IMAGE_URL_REGEX.findAll(imageList).map { it.groupValues[1] }.toList()
        } catch (e: Exception) {
            cachedJumpUrl = null
            throw e
        }

        return imageUrls.mapIndexed { i, imageUrl -> Page(i, imageUrl = imageUrl) }
    }

    private val readerJumpMutex = Mutex()
    private var cachedJumpUrl: HttpUrl? = null
    private var hasNoReader = false

    // The site's /chapter/ links redirect to a dead host. Its chapter lists link each chapter through /go/rds/
    // to a reader jump url that only differs in cid
    private suspend fun readerJumpUrl(): HttpUrl = readerJumpMutex.withLock {
        cachedJumpUrl?.let { return@withLock it }
        if (hasNoReader) throw Exception(NO_READER)

        val mangaUrl = getMangaUrl(getLatestUpdates(1).mangas.first())
        val redirectUrl = client.get(mangaUrl, Fetch.DOCUMENT).asJsoup()
            .selectFirst("a[href*=/go/rds/]")
            ?.absUrl("href")
        if (redirectUrl == null) {
            hasNoReader = true
            throw Exception(NO_READER)
        }
        val script = client.get(redirectUrl, Fetch.DOCUMENT.from(mangaUrl)).body.string()
        val jumpUrl = JUMP_URL_REGEX.find(script)?.groupValues?.get(1) ?: throw Exception(PAGES_NOT_FOUND)
        jumpUrl.toHttpUrl().also { cachedJumpUrl = it }
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/search/?type=high", Fetch.DOCUMENT).asJsoup()
        fun options(group: String) = document.select("[option_group_name=$group]").map { it.text() to it.attr("option_val") }

        return FilterData(
            genres = document.select("li.genre-item a[onclick*=clickGenre]").map {
                it.text() to GENRE_ID_REGEX.find(it.attr("onclick"))!!.groupValues[1]
            },
            years = options("publish_years"),
            ratings = options("rating"),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterData>()
        return FilterList(
            listOfNotNull(
                AuthorFilter(),
                ArtistFilter(),
                SelectFilter("Completed", "completed_series", listOf("Either" to "", "Yes" to "YES", "No" to "NO")),
                filterData?.ratings?.takeIf { it.isNotEmpty() }?.let { SelectFilter("Rating", "rate_star", it) },
                filterData?.years?.takeIf { it.isNotEmpty() }?.let { SelectFilter("Released", "released", it) },
                filterData?.genres?.takeIf { it.isNotEmpty() }?.let(::GenreFilter),
            ),
        )
    }

    companion object {
        private const val PAGES_NOT_FOUND = "Chapter pages not found on the site"
        private const val BLOCKED_COOKIE = "niadd_bad_user_2"
        private const val BLOCKED_NETWORK = "Niadd has blocked this network, try another network"
        private const val NO_READER = "The site has no reader for this language"
        private val ENTRY_PATHS = setOf("manga", "original")
        private val GENRE_ID_REGEX = Regex("""'(\d+)'""")
        private val JUMP_URL_REGEX = Regex("""document\.location\s*=\s*'([^']+)'""")
        private val ALL_IMGS_URL_REGEX = Regex("""all_imgs_url\s*:\s*\[([\s\S]*?)\]""")
        private val IMAGE_URL_REGEX = Regex(""""(https?://[^"]+)"""")
        private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        private val ONGOING_STATUSES = setOf("ongoing", "en marcha", "em progresso", "in corso", "laufend", "en cours", "постоянный")
        private val COMPLETED_STATUSES = setOf("completed", "terminado", "concluído", "completato", "abgeschlossen", "complété", "завершенный")
    }
}
