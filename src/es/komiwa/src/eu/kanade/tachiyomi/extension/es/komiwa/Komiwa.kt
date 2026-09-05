package eu.kanade.tachiyomi.extension.es.komiwa

import android.util.Base64
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
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Instant

@Source
abstract class Komiwa : KeiSource() {

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }
    private val apiUrl = "https://b78sk.komiwa.lat"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        rateLimit(3) { it.host == baseUrlHost }
        addInterceptor { chain ->
            val request = chain.request()
            val referer = request.url.fragment?.takeIf { it.isNotBlank() }
                ?: return@addInterceptor chain.proceed(request)
            chain.proceed(request.newBuilder().header("Referer", referer).build())
        }
    }

    private val rscHeaders by lazy {
        headersBuilder()
            .add("RSC", "1")
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseSearchBody(client.get(catalogUrl(page, "views"), rscHeaders).use { it.body.string() })

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseSearchBody(client.get(catalogUrl(page, "updatedAt"), rscHeaders).use { it.body.string() })

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
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

        return parseSearchBody(client.get(url.build(), rscHeaders).use { it.body.string() })
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.getOrNull(0) != "manga") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val slug = url.pathSegments.getOrNull(2).orEmpty()
        val manga = SManga.create().apply {
            this.url = if (slug.isNotBlank()) "$id/$slug" else id
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    private fun parseSearchBody(body: String): MangasPage {
        val mangas = parseInitialItems(body)
        val total = extractInt(body, """"initialTotal":""", ",")
        val page = extractInt(body, """"initialPage":""", ",")
        val hasNextPage = (page * PAGE_LIMIT) < total
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
        val id = manga.url.substringBefore("/")
        val slug = manga.url.substringAfter("/", "")
        return if (slug.isNotEmpty()) "$baseUrl/manga/$id/$slug" else "$baseUrl/manga/$id"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.substringBefore("/")
        val body = client.get("$apiUrl/manga/$id").use { it.body.string() }
        return SMangaUpdate(
            manga = parseMangaDetails(body, id),
            chapters = parseChapterList(body),
        )
    }

    private fun parseMangaDetails(body: String, id: String): SManga {
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

    private fun parseChapterList(body: String): List<SChapter> {
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val body = client.get(chapterUrl, rscHeaders).use { it.body.string() }
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
            Page(i, url = chapterUrl, imageUrl = "${proxyImageUrl(url)}#$chapterUrl")
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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
        private const val IMAGE_PROXY_URL = "https://x4v1.komiwa.net/v/"

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

        private fun tryParseDate(dateString: String?): Long = Instant.tryParse(dateString)

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

        private fun proxyImageUrl(url: String): String = IMAGE_PROXY_URL + Base64.encodeToString(
            url.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

        private fun parseStatus(raw: String?): Int = when (raw?.lowercase()) {
            "ongoing", "en curso" -> SManga.ONGOING
            "completed", "finalizado" -> SManga.COMPLETED
            "hiatus", "pausado" -> SManga.ON_HIATUS
            "cancelled", "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}
