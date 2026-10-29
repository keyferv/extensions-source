package eu.kanade.tachiyomi.extension.es.omniscan

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
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Omniscan : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return catalogParse(client.get("$baseUrl/biblioteca").asJsoup())
            .sortedByDescending { it.second }
            .map { it.first }
            .let { MangasPage(it, false) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(catalogParse(client.get("$baseUrl/biblioteca").asJsoup()).map { it.first }, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue.orEmpty()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "recent"
        val mangas = catalogParse(client.get("$baseUrl/biblioteca").asJsoup())
            .filter { (manga, _) ->
                manga.title.contains(query, ignoreCase = true) &&
                    (status.isEmpty() || manga.memo["status"]?.stringOrNull == status)
            }
            .let { entries ->
                when (sort) {
                    "visits" -> entries.sortedByDescending { it.second }
                    "az" -> entries.sortedBy { it.first.title.lowercase(Locale.ROOT) }
                    else -> entries
                }
            }
            .map { it.first }

        return MangasPage(mangas, false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        val document = client.get(url).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga.url),
            chapters = parseChapterList(document),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter)).asJsoup().select("img[data-file]").mapIndexed { index, image ->
        Page(index, imageUrl = image.absUrl("src"))
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(StatusFilter(), SortFilter())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.getOrNull(0) != "obra") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return fetchMangaUpdate(SManga.create().apply { this.url = slug }, emptyList(), true, false).manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra/${manga.url}"

    private fun catalogParse(document: Document): List<Pair<SManga, Int>> = document.select("article.library-series-card").mapNotNull { card ->
        val link = card.selectFirst("a[href^=/obra/]") ?: return@mapNotNull null
        val title = card.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val image = card.selectFirst("img")?.absUrl("src")
        val status = card.attr("data-status")
        Pair(
            SManga.create().apply {
                url = link.attr("href").substringAfter("/obra/")
                this.title = title
                thumbnail_url = image
                memo = buildJsonObject {
                    put("status", status)
                }
            },
            card.attr("data-visits").filter { it.isDigit() }.toIntOrNull() ?: 0,
        )
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl
        title = document.selectFirst("h1.title")?.text()?.trim()
            ?: throw Exception("Title not found")
        thumbnail_url = document.selectFirst("img.cover")?.absUrl("src")
        description = document.selectFirst(".synopsis")?.text()
        genre = document.select(".genre-chip").joinToString { it.text() }.ifBlank { null }
        author = document.select(".meta-chip").firstOrNull { it.text().startsWith("Autor") }
            ?.text()?.substringAfter("Autor")?.trim()
        val status = document.selectFirst(".meta-chip.status")?.text()?.lowercase(Locale.ROOT).orEmpty()
        this.status = when {
            "emisión" in status -> SManga.ONGOING
            "finaliz" in status -> SManga.COMPLETED
            "paus" in status -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".chapter[data-number][data-chapter-id][data-series-slug]").mapNotNull { chapter ->
        val number = chapter.attr("data-number").ifBlank {
            chapter.selectFirst(".chapter-number")?.text().orEmpty()
        }.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SChapter.create().apply {
            url = chapter.attr("href")
            val title = chapter.selectFirst(".chapter-copy strong")?.text()
                ?: chapter.attr("data-title")
            name = buildString {
                append("Capítulo ")
                append(number)
                if (title.isNotBlank()) append(" - ").append(title)
            }
            chapter_number = number.toFloatOrNull() ?: -1f
            date_upload = dateFormat.tryParseDate(chapter.selectFirst(".chapter-date")?.text())
        }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT)
}
