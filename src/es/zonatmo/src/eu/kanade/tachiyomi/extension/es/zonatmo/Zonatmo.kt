package eu.kanade.tachiyomi.extension.es.zonatmo

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Zonatmo : HttpSource() {

    private val imageClient: OkHttpClient by lazy {
        network.client.newBuilder().build()
    }

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "es-ES,es;q=0.9")

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
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
        .build()

    // ========================= Popular =========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/biblioteca".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "likes")
            .addQueryParameter("order", "DESC")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = browseParse(response)

    // ========================= Latest =========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/ultimas-subidas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.upload-file-row")
            .mapNotNull(::parseLatestCard)
            .distinctBy { "${it.title.lowercase()}|${it.thumbnail_url.orEmpty()}" }

        val hasNextPage = document.select("nav a[href*=\"page=\"]")
            .any { it.attr("rel") == "next" || it.text().contains("Siguiente") || it.text().contains("»") }

        return MangasPage(mangas, hasNextPage)
    }

    // ========================= Search =========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = browseParse(response)

    // ========================= Filters =========================

    override fun getFilterList() = FilterList(
        SortFilter(),
        SortOrderFilter(),
        TypeFilter(),
        DemographyFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    // ========================= Browse parse =========================

    private fun browseParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href*=/library/]")
            .filter { link -> link.selectFirst("img") != null && link.selectFirst("h4") != null }
            .distinctBy { it.attr("href") }
            .mapNotNull(::parseCard)

        val hasNextPage = document.select("nav a[href*=\"page=\"]")
            .any { it.text().contains("Siguiente") || it.text().contains("»") }

        return MangasPage(mangas, hasNextPage)
    }

    // ========================= Details =========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val (document, mangaUrl) = resolveSeriesDocument(response)

        return SManga.create().apply {
            url = mangaUrl

            title = document.selectFirst("h1.element-title")
                ?.text()
                ?.trim()
                ?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
                ?: throw Exception("Título no encontrado")

            thumbnail_url = document.selectFirst("img.book-thumbnail")
                ?.attr("abs:src")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }

            description = document.selectFirst("p.element-description, #manga-synopsis")
                ?.text()
                ?.trim()
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
                    ?.text()
                    ?.trim(),
            )
        }
    }

    // ========================= Chapters =========================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val (document, _) = resolveSeriesDocument(response)

        return document.select("li.upload-link")
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
                    ?.trim()
                    ?: "Capítulo $chapterNum"

                SChapter.create().apply {
                    name = titleSpan
                    url = readLink.removePrefix(baseUrl)
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f
                }
            }
            .distinctBy { it.url }
    }

    // ========================= Pages =========================

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val pages = document.select("img.reader-image, img[alt*=Página]")
            .mapNotNull { img ->
                val src = img.attr("abs:src").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                src?.takeIf { it.startsWith("http") && it.contains("/chapters/") }
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

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================= Utilities =========================

    private fun parseCard(link: Element): SManga? {
        val href = link.attr("href").ifBlank { return null }
        if (!href.contains("/library/")) return null

        val title = link.selectFirst("h4")?.text()?.trim() ?: return null

        return SManga.create().apply {
            url = href.removePrefix(baseUrl).ifBlank { return null }
            this.title = title
            thumbnail_url = link.selectFirst("img")
                ?.attr("abs:src")
                ?.takeIf { it.isNotBlank() && it.startsWith("http") }
        }
    }

    private fun parseLatestCard(row: Element): SManga? {
        val link = row.selectFirst("a[href*=/view_uploads/]") ?: return null
        val title = row.selectFirst(".thumbnail-title h4")?.text()?.trim() ?: return null
        val href = link.attr("abs:href").ifBlank { return null }
        val coverUrl = row.selectFirst("style")
            ?.data()
            ?.let { BACKGROUND_URL_REGEX.find(it)?.groupValues?.get(1) }
            ?.let { if (it.startsWith("http")) it else "$baseUrl$it" }

        return SManga.create().apply {
            url = href.removePrefix(baseUrl)
            this.title = title
            thumbnail_url = coverUrl?.takeIf { it.startsWith("http") }
        }
    }

    private fun resolveSeriesDocument(response: Response): Pair<Document, String> {
        val document = response.asJsoup()
        val requestPath = response.request.url.encodedPath
        if (!requestPath.contains("/view_uploads/")) {
            return document to requestPath
        }

        val seriesUrl = document.selectFirst("a.btn-rh[href*=/library/]")
            ?.attr("abs:href")
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("Serie no encontrada desde el capítulo")

        val seriesDocument = client.newCall(GET(seriesUrl, headers)).execute().use { it.asJsoup() }
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
