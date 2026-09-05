package eu.kanade.tachiyomi.extension.es.mangalect

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class MangaLect : KeiSource() {

    private val assetMirror = "https://images.mangalect.org/file/leermangaesp"

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Referer", "$baseUrl/")
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0")
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/").asJsoup()

        val scriptData = document.select("script")
            .map { it.data() }
            .firstOrNull { it.trimStart().startsWith("[{\"id\"") }
            ?: return MangasPage(emptyList(), false)

        val items = scriptData.parseAs<List<TrendEntry>>()

        val mangas = items.map { entry ->
            SManga.create().apply {
                title = entry.titulo
                setUrlWithoutDomain(entry.slug)
                thumbnail_url = "$assetMirror/${entry.portada}"
            }
        }

        return MangasPage(mangas, false)
    }

    // The API returns ALL items in one flat array with no server-side pagination.
    // A _page tracking param is sent so each call slices the correct local page.
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val requestUrl = "$baseUrl/api/latest_chapters_with_dates/".toHttpUrl().newBuilder()
            .addQueryParameter("_page", page.toString())
            .build()
        val allItems = client.get(requestUrl).parseAs<List<LatestEntry>>()

        val pageSize = 20
        val startIndex = (page - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, allItems.size)
        val hasNextPage = endIndex < allItems.size

        if (startIndex >= allItems.size) {
            return MangasPage(emptyList(), false)
        }

        val mangas = allItems.subList(startIndex, endIndex).map { entry ->
            SManga.create().apply {
                title = entry.titulo
                setUrlWithoutDomain(entry.slug)
                thumbnail_url = "$assetMirror/${entry.portada}"
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val requestUrl = "$baseUrl/api/buscar_mangas/".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "20")
            .build()

        val result = client.get(requestUrl).parseAs<SearchResponse>()

        val mangas = result.resultados.map { entry ->
            SManga.create().apply {
                title = entry.titulo
                setUrlWithoutDomain(entry.slug)
                thumbnail_url = "$assetMirror/${entry.portada}"
            }
        }

        return MangasPage(mangas, page < result.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = when (url.pathSegments.firstOrNull()) {
            "info" -> url.pathSegments.getOrNull(1)
            "lectura" -> url.pathSegments.getOrNull(1)
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null

        val document = client.get("$baseUrl/info/$slug/").asJsoup()
        return parseMangaDetails(document, slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and chapters come from the same /info/{slug}/ page, so one
        // fetch serves both flags and there is no separate request to skip.
        val infoUrl = "$baseUrl/info/${manga.url}/".toHttpUrl()
        val document = client.get(infoUrl).asJsoup()
        val slug = document.body().attr("data-manga-slug")
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga.url),
            chapters = fetchChapterList(document, infoUrl, slug),
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/info/${manga.url}/"

    private fun parseMangaDetails(document: Document, slug: String): SManga = SManga.create().apply {
        setUrlWithoutDomain(slug)
        title = document.selectFirst("h1.manga-title")?.text()
            ?: throw Exception("Título no encontrado")

        // Author not available on this site
        author = "Desconocido"

        // Cover: prefer img.manga-cover, fallback to body data attribute
        thumbnail_url = document.selectFirst("img.manga-cover[src]")
            ?.attr("abs:src")
            ?: document.body().attr("data-portada-rel")
                .takeIf { it.isNotBlank() }
                ?.let { "$assetMirror/$it" }

        status = parseStatus(document.selectFirst("span.status-text")?.text())

        genre = document.select("#info-generos a.genero-item")
            .joinToString { it.text() }

        description = document.selectFirst("section.synopsis p#synopsis-text")
            ?.text()
    }

    private fun parseStatus(text: String?): Int = when (text?.trim()?.lowercase()) {
        "en curso" -> SManga.ONGOING
        "finalizado" -> SManga.COMPLETED
        "abandonado" -> SManga.CANCELLED
        "pausado" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private suspend fun fetchChapterList(firstDocument: Document, firstUrl: HttpUrl, slug: String): List<SChapter> {
        if (slug.isBlank()) return emptyList()

        val seen = linkedSetOf<String>()
        val chapters = mutableListOf<SChapter>()

        var currentUrl = firstUrl
        var currentDocument = firstDocument

        val visitedPages = mutableSetOf(currentUrl.toString())
        var pageCount = 1

        while (pageCount <= MAX_PAGINATION_PAGES) {
            currentDocument.select("div#chapter-list div.chapter-card a.chapter-link[data-chapter]")
                .forEach { link ->
                    val chapterNum = link.attr("data-chapter")
                    if (chapterNum.isEmpty()) return@forEach

                    val chapterTitle = link.selectFirst("div.chapter-title")?.text()
                        ?: return@forEach
                    val chapterDate = link.selectFirst("div.chapter-date")?.text()
                    val chapterUrl = "$slug/$chapterNum"

                    if (seen.add(chapterUrl)) {
                        chapters += SChapter.create().apply {
                            name = chapterTitle
                            setUrlWithoutDomain(chapterUrl)
                            date_upload = dateFormat.tryParseDate(chapterDate, siteZone)
                        }
                    }
                }

            val nextUrl = currentDocument.selectFirst("a#more-link[href]")
                ?.attr("href")
                ?.takeIf(String::isNotBlank)
                ?.let { currentUrl.resolve(it) }
                ?: break

            if (!visitedPages.add(nextUrl.toString())) break

            currentUrl = nextUrl
            currentDocument = client.get(nextUrl).asJsoup()
            pageCount++
        }

        return chapters
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/lectura/${chapter.url}/"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split("/", limit = 2)
        val slug = parts[0]
        val chapterNum = parts.getOrElse(1) { chapter.url }
        val document = client.get("$baseUrl/lectura/$slug/$chapterNum/").asJsoup()

        return document.select("img.manga-image[src]")
            .map { it.attr("abs:src") }
            .filter { it.contains("images.mangalect.org") }
            .distinct()
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    /** Inline script JSON item on the homepage (Tendencias / popular). */
    @Serializable
    class TrendEntry(
        val slug: String,
        val titulo: String,
        val portada: String = "",
    )

    /** Item from /api/latest_chapters_with_dates/ (Últimas Publicaciones / latest). */
    @Serializable
    class LatestEntry(
        val slug: String,
        val titulo: String,
        val portada: String = "",
    )

    /** Response from /api/buscar_mangas/ (search). */
    @Serializable
    class SearchResponse(
        val resultados: List<SearchResult> = emptyList(),
        @SerialName("total_pages") val totalPages: Int = 1,
    )

    @Serializable
    class SearchResult(
        val slug: String,
        val titulo: String,
        val portada: String = "",
    )

    companion object {
        private const val MAX_PAGINATION_PAGES = 30

        private val dateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH)
        private val siteZone = ZoneId.of("Europe/Madrid")
    }
}
