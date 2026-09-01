package eu.kanade.tachiyomi.extension.es.spnmanga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Spnmanga : KeiSource() {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/browse/?sort=popular&page=$page"
        val document = client.get(url).asJsoup()
        return MangasPage(
            mangas = parseMangaList(document),
            hasNextPage = hasNextPage(document, page),
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/browse/?sort=latest&page=$page"
        val document = client.get(url).asJsoup()
        return MangasPage(
            mangas = parseMangaList(document),
            hasNextPage = hasNextPage(document, page),
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = buildBrowseUrl(page, query, filters)
        val document = client.get(url).asJsoup()
        return MangasPage(
            mangas = parseMangaList(document),
            hasNextPage = hasNextPage(document, page),
        )
    }

    private fun buildBrowseUrl(page: Int, query: String, filters: FilterList): String {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedValue().orEmpty()
        val theme = filters.filterIsInstance<ThemeFilter>().firstOrNull()?.selectedValue().orEmpty()
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selectedValue().orEmpty()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue().orEmpty()
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedValue().orEmpty()

        val builder = "$baseUrl/browse/".toHttpUrl().newBuilder()
        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())
        if (genre.isNotBlank()) builder.addQueryParameter("genre", genre)
        if (theme.isNotBlank()) builder.addQueryParameter("theme", theme)
        if (type.isNotBlank()) builder.addQueryParameter("type", type)
        if (status.isNotBlank()) builder.addQueryParameter("status", status)
        if (sort.isNotBlank()) builder.addQueryParameter("sort", sort)
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return builder.build().toString()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) throw Exception("URL no soportada")
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty() || segments[0] != "leer") throw Exception("URL no soportada")
        if (segments.size < 3) throw Exception("URL no soportada")
        val mangaPath = "/leer/${segments[1]}/${segments[2]}/"
        val document = client.get(baseUrl + mangaPath).asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(baseUrl + mangaPath)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val updatedManga = parseMangaDetails(document).apply {
            // Ensure title fallback to existing when parsing fails
            if (title.isBlank()) title = manga.title
        }
        val chapterList = parseChapterList(document)
        return SMangaUpdate(manga = updatedManga, chapters = chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        val images = document.select("div.reader__pages img[src], main.reader img[src], #pages img[src]")
        return images.mapIndexedNotNull { index, element ->
            val imageUrl = element.absUrl("src").ifBlank { element.absUrl("data-src") }
            if (imageUrl.isBlank()) return@mapIndexedNotNull null
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("La busqueda por texto usa el campo superior; los filtros combinan con ?q y pagina."),
        GenreFilter(),
        ThemeFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    private fun parseMangaList(document: Document): List<SManga> {
        return document.select("article.grid-item").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/leer/']") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank()) return@mapNotNull null
            val title = element.selectFirst("h3.grid-item__name")?.text()?.trim()
                ?: element.selectFirst(".grid-item__name")?.text()?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = element.selectFirst("img[src]")?.absUrl("src")
            }
        }.distinctBy { it.url }
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.detail__title")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        }
        thumbnail_url = document.selectFirst("div.detail__cover img")?.absUrl("src")
            ?: document.selectFirst("img[alt*='cover']")?.absUrl("src")

        val altNames = document.selectFirst("p.detail__alt")?.text()?.trim().orEmpty()
        val synopsis = document.selectFirst("p.detail__syn")?.text()?.trim().orEmpty()
        description = buildString {
            if (altNames.isNotBlank()) append(altNames)
            if (synopsis.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(synopsis)
            }
        }.ifBlank { null }

        genre = document.select("div.chips a.chip").joinToString { it.text().trim() }.takeIf { it.isNotBlank() }

        val statusRaw = document.selectFirst(".detail__facts .v.status-dot")?.attr("data-status")
            ?.ifBlank { document.selectFirst(".detail__facts .v.status-dot")?.text() }
            ?: document.selectFirst(".detail__facts .v.status-dot a")?.text()
        status = parseStatus(statusRaw)

        // Author / Artist from meta-block
        document.select(".meta-block > div").forEach { row ->
            val key = row.selectFirst("span.k")?.text()?.trim()?.lowercase(Locale.ROOT)
            val value = row.selectFirst("span:not(.k)")?.text()?.trim().orEmpty()
            if (value.isBlank()) return@forEach
            when (key) {
                "story" -> author = value
                "art" -> artist = value
            }
        }
        // Fallback: if artist still blank but author present, duplicate
        if (artist.isNullOrBlank() && !author.isNullOrBlank()) artist = author
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        return document.select("a.chapter-row[href]").mapNotNull { element ->
            val href = element.absUrl("href")
            if (href.isBlank()) return@mapNotNull null
            val nameRaw = element.selectFirst("span.chapter-row__name")?.text()?.trim().orEmpty()
            val numberLabel = element.selectFirst("span.chapter-row__no")?.text()?.trim().orEmpty()
            val name = when {
                numberLabel.isNotBlank() && nameRaw.isNotBlank() -> "$numberLabel $nameRaw".trim()
                nameRaw.isNotBlank() -> nameRaw
                numberLabel.isNotBlank() -> numberLabel
                else -> return@mapNotNull null
            }
            val dataNumber = element.attr("data-number")
            val chapterNumber = dataNumber.toFloatOrNull()
                ?: CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: -1f
            val dateStr = element.selectFirst("time[datetime]")?.attr("datetime")
                ?: element.selectFirst("time")?.text()?.trim()
            val date = dateStr?.let { dateFormatter.tryParseDate(it, ZoneId.of("UTC")) } ?: 0L

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                this.name = name
                this.date_upload = date
                this.chapter_number = chapterNumber
            }
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val nextPage = page + 1
        if (document.selectFirst("a[href*='page=$nextPage']") != null) return true
        if (document.selectFirst("nav a[rel=next]") != null) return true
        val paginationText = document.selectFirst("nav[aria-label='Pagination']")?.text().orEmpty()
        if (paginationText.contains(nextPage.toString())) {
            return document.select("nav[aria-label='Pagination'] a").any { it.attr("href").contains("page=$nextPage") }
        }
        // Fallback: if exactly 30 items, assume more pages (browse shows 30 per page)
        val count = document.select("article.grid-item").size
        return count >= 30 && document.selectFirst("a[href*='page=']") != null
    }

    private fun parseStatus(raw: String?): Int = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "ongoing", "en curso" -> SManga.ONGOING
        "completed", "completo", "finalizado" -> SManga.COMPLETED
        "paused", "hiatus", "en pausa" -> SManga.ON_HIATUS
        "cancelled", "cancelado" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = GENRES[state].second
    }

    private class ThemeFilter :
        Filter.Select<String>(
            "Theme",
            THEMES.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = THEMES[state].second
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Type",
            TYPES.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = TYPES[state].second
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            STATUSES.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = STATUSES[state].second
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort",
            SORTS.map { it.first }.toTypedArray(),
        ) {
        fun selectedValue(): String = SORTS[state].second
    }

    companion object {
        private val CHAPTER_NUMBER_REGEX = """(\d+(?:\.\d+)?)""".toRegex()

        private val GENRES = arrayOf(
            "All genres" to "",
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Crime" to "crime",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Magical Girls" to "magical-girls",
            "Mecha" to "mecha",
            "Medical" to "medical",
            "Mystery" to "mystery",
            "Philosophical" to "philosophical",
            "Psychological" to "psychological",
            "Romance" to "romance",
            "Sci-Fi" to "sci-fi",
            "Slice of Life" to "slice-of-life",
            "Sports" to "sports",
            "Superhero" to "superhero",
            "Thriller" to "thriller",
            "Tragedy" to "tragedy",
            "Wuxia" to "wuxia",
            "Seinen" to "seinen",
            "Shounen" to "shounen",
            "Ecchi" to "ecchi",
            "Shoujo" to "shoujo",
            "Mature" to "mature",
            "Adult" to "adult",
            "Shounen Ai" to "shounen-ai",
            "Gender Bender" to "gender-bender",
            "Shotacon" to "shotacon",
            "Josei" to "josei",
            "Yaoi" to "yaoi",
            "Smut" to "smut",
            "Yuri" to "yuri",
            "Shoujo Ai" to "shoujo-ai",
            "Doujinshi" to "doujinshi",
            "Lolicon" to "lolicon",
            "Hentai" to "hentai",
        )

        private val THEMES = arrayOf(
            "All themes" to "",
            "Aliens" to "aliens",
            "Animals" to "animals",
            "Cooking" to "cooking",
            "Cross-dressing" to "cross-dressing",
            "Delinquents" to "delinquents",
            "Demons" to "demons",
            "Genderswap" to "genderswap",
            "Ghosts" to "ghosts",
            "Gyaru" to "gyaru",
            "Harem" to "harem",
            "Incest" to "incest",
            "Loli" to "loli",
            "Mafia" to "mafia",
            "Magic" to "magic",
            "Martial Arts" to "martial-arts",
            "Military" to "military",
            "Monster Girls" to "monster-girls",
            "Monsters" to "monsters",
            "Music" to "music",
            "Ninja" to "ninja",
            "Office Workers" to "office-workers",
            "Police" to "police",
            "Post-Apocalyptic" to "post-apocalyptic",
            "Reincarnation" to "reincarnation",
            "Reverse Harem" to "reverse-harem",
            "Samurai" to "samurai",
            "School Life" to "school-life",
            "Shota" to "shota",
            "Supernatural" to "supernatural",
            "Survival" to "survival",
            "Time Travel" to "time-travel",
            "Traditional Games" to "traditional-games",
            "Vampires" to "vampires",
            "Video Games" to "video-games",
            "Villainess" to "villainess",
            "Virtual Reality" to "virtual-reality",
            "Zombies" to "zombies",
        )

        private val TYPES = arrayOf(
            "All types" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Comic" to "comic",
        )

        private val STATUSES = arrayOf(
            "Any status" to "",
            "Completed" to "completed",
            "Ongoing" to "ongoing",
            "Paused" to "paused",
            "Cancelled" to "cancelled",
        )

        private val SORTS = arrayOf(
            "A -> Z" to "az",
            "Z -> A" to "za",
            "Latest update" to "latest",
            "Most popular" to "popular",
        )
    }
}
