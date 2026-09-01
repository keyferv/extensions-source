package eu.kanade.tachiyomi.extension.es.komiwa

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Source
abstract class Komiwa : HttpSource() {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }
    private val apiUrl = "https://b78sk.komiwa.lat"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(3) { it.host == baseUrlHost }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val rscHeaders by lazy {
        headersBuilder()
            .add("RSC", "1")
            .build()
    }

    override fun popularMangaRequest(page: Int): Request = GET(catalogUrl(page, "views"), rscHeaders)

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(catalogUrl(page, "updatedAt"), rscHeaders)

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = catalogPath(page).toHttpUrl().newBuilder()

        var sortBy = "views"
        var sortOrder = "desc"

        filters.forEach { filter ->
            when (filter) {
                is SortByFilter -> {
                    val sort = filter.toUriPart()
                    sortBy = sort
                    sortOrder = if (sort == "alphabetical") "asc" else "desc"
                }
                else -> {}
            }
        }

        url.addQueryParameter("sortBy", sortBy)
        url.addQueryParameter("sortOrder", sortOrder)

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        return GET(url.build(), rscHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val mangas = parseInitialItems(body)
        val total = extractInt(body, """"initialTotal":""", ",")
        val page = extractInt(body, """"initialPage":""", ",")
        val limit = 24
        val hasNextPage = (page * limit) < total
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseInitialItems(body: String): List<SManga> {
        val items = extractJsonArray(body, """"initialItems":""")
        if (items.isBlank()) return emptyList()
        val result = mutableListOf<SManga>()
        val regex = Regex("""\{"id":"([^"]+)","slug":"([^"]+)","title":"([^"]+)","cover":"([^"]+)"""")
        regex.findAll(items).forEach { match ->
            val (id, slug, title, cover) = match.destructured
            result.add(
                SManga.create().apply {
                    this.title = title
                    thumbnail_url = cover
                    url = "$id/$slug"
                },
            )
        }
        return result
    }

    override fun getMangaUrl(manga: SManga): String {
        val (id, slug) = manga.url.split("/", limit = 2)
        return "$baseUrl/manga/$id/$slug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringBefore("/")
        return GET("$apiUrl/manga/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        val id = response.request.url.pathSegments.last()
        val slug = extractString(body, "\"slug\":\"", "\"") ?: id

        return SManga.create().apply {
            url = "$id/$slug"
            title = extractString(body, "\"title\":\"", "\"") ?: ""
            thumbnail_url = extractString(body, "\"cover\":\"", "\"") ?: ""
            description = extractString(body, "\"description\":\"", "\"")
                ?: extractString(body, "\"synopsis\":\"", "\"")
                ?: ""
            status = parseStatus(
                extractNestedString(body, "status", "slug")
                    ?: extractNestedString(body, "status", "name")
                    ?: extractString(body, "\"status\":\"", "\""),
            )
            author = extractNames(body, "authors").joinToString()
            genre = extractNames(body, "genres").joinToString()
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val chaptersObject = extractJsonArray(body, """"chapters":""")
        val chaptersArray = extractJsonArray(chaptersObject, """"chapters":""")
        if (chaptersArray.isBlank()) return emptyList()

        val result = mutableListOf<SChapter>()
        val regex = Regex("""\{"id":"([^"]+)","number":"([^"]+)","title":"([^"]*)",""")
        regex.findAll(chaptersArray).forEach { match ->
            val (id, number, title) = match.destructured
            result.add(
                SChapter.create().apply {
                    this.url = id
                    name = "Cap. $number"
                    if (title.isNotBlank()) {
                        name += " - $title"
                    }
                    date_upload = tryParseDate(
                        extractStringNear(body, id, """"publishedAt":"([^"]+)"""")
                            ?: extractStringNear(body, id, """"createdAt":"([^"]+)""""),
                    )
                },
            )
        }
        return result
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/chapter/${chapter.url}", rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val pagesArray = extractJsonArray(body, """"pages":""")
        if (pagesArray.isBlank()) return emptyList()

        val images = mutableListOf<String>()
        val regex = Regex(""""([^"]+)"""")
        regex.findAll(pagesArray).forEach { match ->
            val url = normalizeImageUrl(match.groupValues[1])
            if (url.startsWith("http")) {
                images.add(url)
            }
        }
        return images.distinct().mapIndexed { i, url ->
            Page(i, url = response.request.url.toString(), imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = super.headersBuilder()
            .add("Referer", page.url.ifBlank { "$baseUrl/" })
            .build()

        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        SortByFilter("Ordenar por", getSortList()),
    )

    private fun getSortList() = arrayOf(
        Pair("Popularidad", "views"),
        Pair("Recientes", "updatedAt"),
        Pair("A-Z", "alphabetical"),
    )

    private fun catalogUrl(page: Int, sortBy: String): String = catalogPath(page).toHttpUrl().newBuilder()
        .addQueryParameter("sortBy", sortBy)
        .addQueryParameter("sortOrder", "desc")
        .build()
        .toString()

    private fun catalogPath(page: Int): String = if (page == 1) {
        "$baseUrl/catalog"
    } else {
        "$baseUrl/catalog/$page"
    }

    companion object {
        private const val PAGE_LIMIT = 24

        private fun extractString(body: String, prefix: String, suffix: String): String? {
            val start = body.indexOf(prefix)
            if (start == -1) return null
            val valueStart = start + prefix.length
            val end = body.indexOf(suffix, valueStart)
            if (end == -1) return null
            return body.substring(valueStart, end)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
        }

        private fun extractInt(body: String, prefix: String, suffix: String): Int = extractString(body, prefix, suffix)?.toIntOrNull() ?: 0

        private fun extractJsonArray(body: String, prefix: String): String {
            val start = body.indexOf(prefix)
            if (start == -1) return ""
            var pos = start + prefix.length
            var depth = 0
            var inString = false
            var escaped = false
            while (pos < body.length) {
                val c = body[pos]
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = !inString
                } else if (!inString) {
                    when (c) {
                        '[' -> depth++
                        '{' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) return body.substring(start + prefix.length, pos + 1)
                        }
                        '}' -> {
                            depth--
                            if (depth == 0) return body.substring(start + prefix.length, pos + 1)
                        }
                    }
                }
                pos++
            }
            return ""
        }

        private fun tryParseDate(dateString: String?): Long {
            if (dateString == null) return 0
            return try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(dateString)
                    ?.time ?: 0
            } catch (_: Exception) {
                0
            }
        }

        private fun extractStringNear(body: String, nearId: String, pattern: String): String? {
            val nearIndex = body.indexOf(nearId)
            if (nearIndex == -1) return null
            val searchRegion = body.substring(nearIndex)
            val regex = Regex(pattern)
            return regex.find(searchRegion)?.groupValues?.getOrNull(1)
        }

        private fun extractNestedString(body: String, objectName: String, fieldName: String): String? {
            val obj = extractJsonArray(body, "\"$objectName\":")
            if (obj.isBlank()) return null
            return extractString(obj, "\"$fieldName\":\"", "\"")
        }

        private fun extractNames(body: String, arrayName: String): List<String> {
            val array = extractJsonArray(body, "\"$arrayName\":")
            if (array.isBlank()) return emptyList()
            return Regex(""""name":"([^"]+)"""")
                .findAll(array)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        }

        private fun normalizeImageUrl(url: String): String = url
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")

        private fun parseStatus(raw: String?): Int = when (raw?.lowercase()) {
            "ongoing", "en curso" -> SManga.ONGOING
            "completed", "finalizado" -> SManga.COMPLETED
            "hiatus", "pausado" -> SManga.ON_HIATUS
            "cancelled", "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}
