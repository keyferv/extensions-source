package eu.kanade.tachiyomi.extension.es.mhscans

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MHScans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2, 1.seconds)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${normalizeMangaPath(manga.url, baseUrl)}"

    override fun getChapterUrl(chapter: SChapter): String = when {
        chapter.url.startsWith("http") -> chapter.url
        chapter.url.startsWith("/leer/") -> "$baseUrl${chapter.url}"
        chapter.url.startsWith("/") -> "$baseUrl${chapter.url}"
        else -> "$baseUrl/leer/${chapter.url}"
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/library?sort=views&page=$page", fetch = Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/library?sort=updated&page=$page", fetch = Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            val urlBuilder = "$baseUrl/library".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())

            for (filter in filters) {
                when (filter) {
                    is SortFilter -> {
                        if (filter.selected.isNotEmpty()) {
                            urlBuilder.addQueryParameter("sort", filter.selected)
                        }
                    }
                    is StatusFilter -> {
                        if (filter.selected.isNotEmpty()) {
                            urlBuilder.addQueryParameter("status", filter.selected)
                        }
                    }
                    is TypeFilter -> {
                        if (filter.selected.isNotEmpty()) {
                            urlBuilder.addQueryParameter("type", filter.selected)
                        }
                    }
                    is GenreFilter -> {
                        if (filter.selected.isNotEmpty()) {
                            urlBuilder.addQueryParameter("genre", filter.selected)
                        }
                    }
                    else -> {}
                }
            }
            urlBuilder.build()
        }

        val document = client.get(url, fetch = Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    private fun parseMangaList(document: Document): MangasPage {
        val elements = document.select("a.comic-card")
        val mangas = elements.mapNotNull { element ->
            val href = element.attr("href").ifEmpty { element.attr("abs:href") }
            if (href.isBlank()) return@mapNotNull null

            val path = if (href.startsWith("http")) {
                runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: href
            } else {
                href
            }

            SManga.create().apply {
                url = path
                title = element.selectFirst("p.line-clamp-2")?.text()
                    ?: element.selectFirst("img.comic-cover__img")?.attr("alt")
                    ?: ""
                thumbnail_url = element.selectFirst("img.comic-cover__img")?.let { img ->
                    img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
                }
            }
        }

        return MangasPage(mangas, hasNextPage = elements.size >= 24)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchMangaDocument(manga.url)
        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(document, manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchMangaDocument(mangaUrl: String): Document {
        val normalizedPath = normalizeMangaPath(mangaUrl, baseUrl)
        val response = client.get("$baseUrl$normalizedPath", fetch = Fetch.DOCUMENT)
        if (response.code == 404 && normalizedPath.startsWith("/comics/mhscans-")) {
            response.close()
            val fallbackPath = "/comics/" + normalizedPath.removePrefix("/comics/mhscans-")
            return client.get("$baseUrl$fallbackPath", fetch = Fetch.DOCUMENT).asJsoup()
        }
        return response.asJsoup()
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst(".comic-hero h1, h1")?.text() ?: manga.title
        thumbnail_url = document.selectFirst(".comic-hero img.comic-cover__img, .comic-hero__cover img, .comic-cover img")?.let { img ->
            img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
        } ?: manga.thumbnail_url

        description = document.selectFirst("#comic-description")?.text()

        val statusText = document.selectFirst(".comic-meta__status")?.text()?.lowercase(Locale.ROOT).orEmpty()
        status = when {
            statusText.contains("emisión") || statusText.contains("emision") || statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("finalizado") || statusText.contains("completado") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("hiatus") || statusText.contains("hiato") || statusText.contains("pausa") -> SManga.ON_HIATUS
            statusText.contains("cancelado") || statusText.contains("cancelled") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val genres = document.select(".comic-chips li, .comic-meta > span:not(.comic-meta__sep):not(.comic-meta__status)")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (genres.isNotEmpty()) {
            genre = genres.joinToString(", ")
        }

        author = document.selectFirst(".flex:has(img) a[href^='/groups/']")?.text()
    }

    private suspend fun fetchChapterList(document: Document, manga: SManga): List<SChapter> {
        val comicSlug = document.selectFirst("#chapter-list")?.attr("data-comic-slug")
            ?: normalizeMangaPath(manga.url, baseUrl).removePrefix("/comics/").trim('/')

        val chapters = walkChapterPages { page ->
            val response = client.get("$baseUrl/comics/$comicSlug/chapters?page=$page", fetch = Fetch.XHR)
            if (!response.isSuccessful) {
                return@walkChapterPages Pair(emptyList(), null)
            }
            val pagesHeader = response.header("X-Pages")?.toIntOrNull()
            val doc = response.asJsoup()
            Pair(parseChapterElements(doc.select("a[data-chapter-num]")), pagesHeader)
        }

        if (chapters.isEmpty()) {
            return parseChapterElements(document.select("a[data-chapter-num]"))
        }

        return chapters
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = resolveChapterUrl(chapter)
        val document = client.get("$baseUrl$chapterUrl", fetch = Fetch.DOCUMENT).asJsoup()
        val images = document.select("img.page-img")
        return images.mapIndexedNotNull { index, img ->
            val url = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            if (url.isBlank()) null else Page(index, imageUrl = url)
        }
    }

    private suspend fun resolveChapterUrl(chapter: SChapter): String {
        if (chapter.url.startsWith("/leer/")) return chapter.url
        if (!chapter.url.startsWith("/") && !chapter.url.startsWith("http")) return "/leer/${chapter.url}"

        val match = OLD_CHAPTER_REGEX.find(chapter.url)
        if (match != null) {
            val slug = match.groupValues[1]
            val num = match.groupValues[2]
            val comicSlug = if (slug.startsWith("mhscans-")) slug else "mhscans-$slug"
            val resp = client.get("$baseUrl/comics/$comicSlug/chapters?q=$num", fetch = Fetch.XHR).asJsoup()
            val newHref = resp.selectFirst("a[href^='/leer/']")?.attr("href")
            if (!newHref.isNullOrBlank()) {
                return newHref
            }
        }
        return chapter.url
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (path.contains("/comics/") || path.contains("/series/")) {
            val doc = fetchMangaDocument(path)
            val manga = SManga.create().apply { this.url = path }
            return parseMangaDetails(doc, manga)
        }
        return null
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/library", fetch = Fetch.DOCUMENT).asJsoup()
        .select("a.dd-opt[href*=genre=]")
        .mapNotNull { it.absUrl("href").toHttpUrl().queryParameter("genre") }
        .distinct()
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(SortFilter())
            add(TypeFilter())
            add(StatusFilter())
            val genres = data?.parseAs<List<String>>()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GENRES
            add(GenreFilter(genres))
        },
    )
}
