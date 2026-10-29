package eu.kanade.tachiyomi.extension.es.zonatmo

import eu.kanade.tachiyomi.network.GET
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
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Zonatmo : KeiSource() {

    private val imageClient: OkHttpClient by lazy {
        network.client.newBuilder().build()
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        add("Accept-Language", "es-ES,es;q=0.9")
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val isChapterImage = request.url.host == "storage.zonatmo.org" &&
                request.url.encodedPath.contains("/chapters/")

            if (!isChapterImage) {
                return@addInterceptor chain.proceed(request)
            }

            val adjustedRequest = request.newBuilder()
                .removeHeader("Accept-Encoding")
                .header("Accept-Encoding", "identity")
                .removeHeader("Referer")
                .build()

            val response = imageClient.newCall(adjustedRequest).execute()
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
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "likes")
            .addQueryParameter("order", "DESC")
            .addQueryParameter("page", page.toString())
            .build()
        return browseParse(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/ultimas-subidas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        val document = client.get(url).asJsoup()
        val mangas = document.select("div.upload-file-row")
            .mapNotNull(::parseLatestCard)
            .distinctBy { "${it.title.lowercase()}|${it.thumbnail_url.orEmpty()}" }

        val hasNextPage = document.select("nav a[href*=\"page=\"]")
            .any { it.attr("rel") == "next" || it.text().contains("Siguiente") || it.text().contains("»") }

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("title", query.trim())
        }

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("type[]", it.value)
                }
                is StatusFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("status[]", it.value)
                }
                is GenreFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("genders[]", it.value)
                }
                is DemographyFilter -> filter.state.filter { it.state }.forEach {
                    url.addQueryParameter("demography[]", it.value)
                }
                is SortFilter -> {
                    val sortOption = SORT_OPTIONS[filter.state]
                    url.addQueryParameter("sort", sortOption.second)
                }
                is SortOrderFilter -> {
                    url.addQueryParameter("order", if (filter.state == 0) "DESC" else "ASC")
                }
                else -> {}
            }
        }

        return browseParse(client.get(url.build()).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val baseHost = baseUrl.toHttpUrl().host
        if (!(url.host == baseHost || url.host.endsWith(".$baseHost"))) return null
        if (!url.encodedPath.startsWith("/library/")) return null
        val manga = SManga.create().apply { setUrlWithoutDomain(url.toString()) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        SortOrderFilter(),
        TypeFilter(),
        DemographyFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private fun browseParse(document: Document): MangasPage {
        val mangas = document.select("a[href*=/library/]")
            .filter { link -> link.selectFirst("img") != null && link.selectFirst("h4") != null }
            .distinctBy { it.attr("abs:href") }
            .mapNotNull(::parseCard)

        val hasNextPage = document.select("nav a[href*=\"page=\"]")
            .any { it.text().contains("Siguiente") || it.text().contains("»") }

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val (seriesDocument, mangaUrl) = resolveSeriesDocument(document)
        return SMangaUpdate(
            manga = parseMangaDetails(seriesDocument, mangaUrl),
            chapters = parseChapterList(seriesDocument),
        )
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl

        title = document.selectFirst("h1.element-title")
            ?.text()
            ?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            ?: throw Exception("Título no encontrado")

        thumbnail_url = document.selectFirst("img.book-thumbnail")
            ?.attr("abs:src")
            ?.takeIf { it.isNotBlank() && it.startsWith("http") }

        description = document.selectFirst("p.element-description, #manga-synopsis")
            ?.text()
            ?.ifBlank { null }

        genre = document.select("h6 a.badge[href*=biblioteca?genders], h6 a.badge.badge-primary")
            .eachText()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
            .ifBlank { null }

        author = document.select("a[href*=filter_by=author]")
            .eachText()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()
            .ifBlank { null }

        status = parseStatus(
            document.selectFirst("span.book-status")
                ?.text(),
        )
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("li.upload-link")
        .mapNotNull { li ->
            val chapterNum = li.attr("data-chapter-number")
                .ifBlank { null }
                ?: li.selectFirst("span.chapter-number")
                    ?.attr("data-number")
                    ?.ifBlank { null }
                ?: return@mapNotNull null

            val readLink = li.selectFirst("a.btn.btn-primary[href*=/view_uploads/]")
                ?.attr("abs:href")
                ?.ifBlank { null }
                ?: return@mapNotNull null

            val titleSpan = li.selectFirst("span.chapter-number")
                ?.text()
                ?: "Capítulo $chapterNum"

            SChapter.create().apply {
                name = titleSpan
                setUrlWithoutDomain(readLink)
                chapter_number = chapterNum.toFloatOrNull() ?: -1f
            }
        }
        .distinctBy { it.url }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()

        val pages = document.select("img.reader-image, img[alt*=Página]")
            .mapNotNull { img ->
                val src = img.attr("abs:src").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                src?.takeIf { it.startsWith("http") }
            }
            .distinct()

        if (pages.isEmpty()) {
            throw Exception("No se encontraron imágenes en el capítulo")
        }

        return pages.mapIndexed { index, imageUrl ->
            Page(index, url = imageUrl, imageUrl = imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl
            ?: page.url.takeIf { it.isNotBlank() }
            ?: throw Exception("URL de imagen vacía")
        return GET(
            url,
            headersBuilder()
                .removeAll("Referer")
                .build(),
        )
    }

    private fun parseCard(link: Element): SManga? {
        val href = link.attr("abs:href").ifBlank { return null }
        if (!href.contains("/library/")) return null

        val title = link.selectFirst("h4")?.text() ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = link.selectFirst("img")
                ?.attr("abs:src")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }
        }
    }

    private fun parseLatestCard(row: Element): SManga? {
        val link = row.selectFirst("a[href*=/view_uploads/]") ?: return null
        val title = row.selectFirst(".thumbnail-title h4")?.text() ?: return null
        val href = link.attr("abs:href").ifBlank { return null }
        val coverUrl = row.selectFirst("style")
            ?.data()
            ?.let { BACKGROUND_URL_REGEX.find(it)?.groupValues?.get(1) }
            ?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

        return SManga.create().apply {
            setUrlWithoutDomain(href)
            this.title = title
            thumbnail_url = coverUrl?.takeIf { it.startsWith("http") }
        }
    }

    private suspend fun resolveSeriesDocument(document: Document): Pair<Document, String> {
        val requestPath = document.location().toHttpUrl().encodedPath
        if (!requestPath.contains("/view_uploads/")) {
            return document to requestPath
        }

        val seriesUrl = document.selectFirst("a.btn-rh[href*=/library/]")
            ?.attr("abs:href")
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("Serie no encontrada desde el capítulo")

        val seriesDocument = client.get(seriesUrl).asJsoup()
        return seriesDocument to seriesUrl.removePrefix(baseUrl)
    }

    private fun parseStatus(text: String?): Int = when {
        text.isNullOrBlank() -> SManga.UNKNOWN
        text.contains("emisión", ignoreCase = true) || text.contains("publicándose", ignoreCase = true) -> SManga.ONGOING
        text.contains("completado", ignoreCase = true) || text.contains("finalizado", ignoreCase = true) -> SManga.COMPLETED
        text.contains("pausa", ignoreCase = true) || text.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        text.contains("cancel", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    companion object {
        private val BACKGROUND_URL_REGEX = Regex("""url\(['\"]?([^'\")]+)""")

        private val SORT_OPTIONS = arrayOf(
            "Me gusta" to "likes",
            "Alfabético" to "title",
            "Puntuación" to "score",
            "Creación" to "created",
            "Fecha estreno" to "release",
            "Núm. Capítulos" to "chapters",
        )
    }
}
