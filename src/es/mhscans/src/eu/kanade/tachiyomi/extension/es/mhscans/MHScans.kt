package eu.kanade.tachiyomi.extension.es.mhscans

import eu.kanade.tachiyomi.source.model.Filter
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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.Calendar
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MHScans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2, 1.seconds)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${normalizeMangaPath(manga.url)}"

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"

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
        val normalizedPath = normalizeMangaPath(mangaUrl)
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
        title = document.selectFirst("h1")?.text() ?: manga.title
        thumbnail_url = document.selectFirst(".comic-hero img.comic-cover__img, .comic-cover img")?.let { img ->
            img.attr("abs:src").ifEmpty { img.attr("abs:data-src") }
        } ?: manga.thumbnail_url

        description = document.selectFirst("#comic-description")?.text()

        val statusText = document.selectFirst(".comic-meta__status")?.text()?.lowercase(Locale.ROOT).orEmpty()
        status = when {
            statusText.contains("emisión") || statusText.contains("emision") || statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("finalizado") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("hiatus") || statusText.contains("pausa") -> SManga.ON_HIATUS
            statusText.contains("cancelado") || statusText.contains("cancelled") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val genres = document.select(".comic-chips li.comic-chip, .comic-meta > span:not(.comic-meta__sep):not(.comic-meta__status)")
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
            ?: normalizeMangaPath(manga.url).removePrefix("/comics/").trim('/')

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages) {
            val response = client.get("$baseUrl/comics/$comicSlug/chapters?page=$page", fetch = Fetch.XHR)
            if (!response.isSuccessful) break

            val pagesHeader = response.header("X-Pages")?.toIntOrNull()
            if (pagesHeader != null && pagesHeader > totalPages) {
                totalPages = pagesHeader
            }

            val doc = response.asJsoup()
            val chapterElements = doc.select("a[data-chapter-num]")

            for (element in chapterElements) {
                val href = element.attr("href")
                if (!href.startsWith("/leer/")) continue

                val chapter = SChapter.create().apply {
                    url = href
                    val label = element.attr("data-chapter-label")
                    name = label.ifEmpty { element.selectFirst(".text-sm")?.text().orEmpty() }

                    val numStr = element.attr("data-chapter-num")
                    chapter_number = numStr.toFloatOrNull() ?: parseChapterNumber(name)

                    val dateText = element.selectFirst(".text-\\[0\\.65rem\\], span:containsOwn(Hace)")?.text()
                    date_upload = parseRelativeDate(dateText)
                }
                chapters.add(chapter)
            }

            page++
        }

        if (chapters.isEmpty()) {
            val initialElements = document.select("a[data-chapter-num]")
            for (element in initialElements) {
                val href = element.attr("href")
                if (!href.startsWith("/leer/")) continue

                chapters.add(
                    SChapter.create().apply {
                        url = href
                        val label = element.attr("data-chapter-label")
                        name = label.ifEmpty { element.selectFirst(".text-sm")?.text().orEmpty() }
                        val numStr = element.attr("data-chapter-num")
                        chapter_number = numStr.toFloatOrNull() ?: parseChapterNumber(name)
                        val dateText = element.selectFirst(".text-\\[0\\.65rem\\], span:containsOwn(Hace)")?.text()
                        date_upload = parseRelativeDate(dateText)
                    },
                )
            }
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

    private fun normalizeMangaPath(rawUrl: String): String {
        val clean = rawUrl.substringAfter(baseUrl).trimStart('/')
        if (clean.startsWith("series/")) {
            val slug = clean.removePrefix("series/").trim('/')
            return if (slug.startsWith("mhscans-")) "/comics/$slug" else "/comics/mhscans-$slug"
        }
        return if (clean.startsWith("/")) clean else "/$clean"
    }

    private fun parseChapterNumber(name: String): Float = CHAPTER_NUM_REGEX.find(name)?.value?.toFloatOrNull() ?: -1f

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val trimmed = dateStr.trim().lowercase(Locale.ROOT)
        val number = NUMBER_REGEX.find(trimmed)?.value?.toIntOrNull() ?: return 0L
        val now = Calendar.getInstance()
        when {
            trimmed.contains("min") || trimmed.endsWith("m") -> now.add(Calendar.MINUTE, -number)
            trimmed.contains("hora") || trimmed.endsWith("h") -> now.add(Calendar.HOUR_OF_DAY, -number)
            trimmed.contains("día") || trimmed.contains("dia") || trimmed.endsWith("d") -> now.add(Calendar.DAY_OF_MONTH, -number)
            trimmed.contains("sem") || trimmed.endsWith("w") -> now.add(Calendar.WEEK_OF_YEAR, -number)
            trimmed.contains("mes") -> now.add(Calendar.MONTH, -number)
            trimmed.contains("año") || trimmed.contains("ano") || trimmed.endsWith("y") -> now.add(Calendar.YEAR, -number)
            else -> return 0L
        }
        return now.timeInMillis
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Ordenar por",
            arrayOf("Predeterminado", "Visitas", "Actualización", "Calificación", "Título"),
        ) {
        val selected get() = when (state) {
            1 -> "views"
            2 -> "updated"
            3 -> "rating"
            4 -> "title"
            else -> ""
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Estado",
            arrayOf("Todos", "En emisión", "Finalizado", "En pausa", "Cancelado"),
        ) {
        val selected get() = when (state) {
            1 -> "Ongoing"
            2 -> "Completed"
            3 -> "Hiatus"
            4 -> "Cancelled"
            else -> ""
        }
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Tipo",
            arrayOf("Todos", "Manga", "Manhwa", "Manhua", "Doujinshi", "Novela", "Otros"),
        ) {
        val selected get() = when (state) {
            1 -> "Manga"
            2 -> "Manhwa"
            3 -> "Manhua"
            4 -> "Doujinshi"
            5 -> "Novel"
            6 -> "Other"
            else -> ""
        }
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Género",
            arrayOf(
                "Todos",
                "Acción",
                "Aliado",
                "Artes Marciales",
                "Aventura",
                "Ciencia ficción",
                "Comedia",
                "Drama",
                "Fantasía",
                "Manhua",
                "Manhwa",
                "Meian",
                "Nuevo",
                "Recomendado",
                "Reencarnación",
                "Shounen",
                "Sistema",
            ),
        ) {
        val selected get() = when (state) {
            1 -> "Acción"
            2 -> "Aliado"
            3 -> "Artes Marciales"
            4 -> "Aventura"
            5 -> "Ciencia ficción"
            6 -> "Comedia"
            7 -> "Drama"
            8 -> "Fantasía"
            9 -> "Manhua"
            10 -> "Manhwa"
            11 -> "Meian"
            12 -> "Nuevo"
            13 -> "Recomendado"
            14 -> "Reencarnación"
            15 -> "Shounen"
            16 -> "Sistema"
            else -> ""
        }
    }

    private companion object {
        val NUMBER_REGEX = Regex("""\d+""")
        val CHAPTER_NUM_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)""")
        val OLD_CHAPTER_REGEX = Regex("""/series/([^/]+)/capitulo-([0-9.]+)""", RegexOption.IGNORE_CASE)
    }
}
