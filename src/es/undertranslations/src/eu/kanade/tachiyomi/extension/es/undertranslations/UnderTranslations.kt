package eu.kanade.tachiyomi.extension.es.undertranslations

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
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
class UnderTranslations(
    override val lang: String,
    override val id: Long,
) : HttpSource() {

    override val name = "UnderTranslations"

    override val baseUrl = "https://undertranslations.com"

    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.of("es", "MX"))

    // ──── Headers ────

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0")

    // ──── Popular ────

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("order", "popular")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ──── Latest Updates ────

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("order", "update")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // ──── Search ────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    // ──── Shared list parsing ────

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val cards = document.select("div.bsx")

        val mangas = cards.map { card ->
            val link = card.selectFirst("a[href]")!!

            SManga.create().apply {
                title = link.attr("title").ifBlank {
                    card.selectFirst(".tt")?.text() ?: "Unknown"
                }
                url = link.attr("href").substringAfter(baseUrl).ifEmpty { "/" }
                thumbnail_url = card.selectFirst("img[src]")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst(".pagination a.next.page-numbers") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ──── Manga Details ────

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst(".entry-title")?.text()
                ?: document.selectFirst("h1.entry-title")?.text()
                ?: "Unknown"

            thumbnail_url = document.selectFirst(".thumb img[src]")?.attr("abs:src")

            description = document.select(".info-desc .wd-full")
                .firstOrNull { it.select(".entry-content").isNotEmpty() }
                ?.select(".entry-content p")
                ?.joinToString("\n") { it.text() }

            // Parse status (no status element found on this site)
            status = parseStatus(document.selectFirst(".spe span")?.text())

            // Genres
            genre = document.select(".mgen a")
                .joinToString(", ") { it.text() }

            author = document.selectFirst(
                ".infotable tr:contains(Autor) td:last-child, " +
                    ".infotable tr:contains(autor) td:last-child",
            )?.text()
                ?: document.selectFirst("td:contains(Autor) + td")?.text()
                ?: "Desconocido"
        }
    }

    private fun parseStatus(text: String?): Int = when {
        text.isNullOrBlank() -> SManga.UNKNOWN
        text.contains("finalizado", true) -> SManga.COMPLETED
        text.contains("en emision", true) || text.contains("en curso", true) ||
            text.contains("publicando", true) || text.contains("emisión", true) -> SManga.ONGOING
        text.contains("cancelado", true) || text.contains("abandonado", true) -> SManga.CANCELLED
        text.contains("pausado", true) || text.contains("en pausa", true) -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ──── Chapter List ────

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select("#chapterlist li")

        return chapters.mapNotNull { li ->
            val link = li.selectFirst(".eph-num a") ?: return@mapNotNull null
            val numSpan = li.selectFirst(".chapternum")
            val dateSpan = li.selectFirst(".chapterdate")
            val dataNum = li.attr("data-num")

            val chapterName = numSpan?.text()?.trim()
                ?: link.text().trim()
            val chapterNum = dataNum.ifBlank { chapterName }

            SChapter.create().apply {
                name = chapterName
                url = link.attr("href").substringAfter(baseUrl).ifEmpty { "/" }
                date_upload = parseChapterDate(dateSpan?.text())
                chapter_number = parseChapterNumber(chapterNum)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    private fun parseChapterDate(dateText: String?): Long {
        if (dateText.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(dateText.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseChapterNumber(num: String): Float = try {
        num.replace("Capítulo ", "", ignoreCase = true)
            .trim()
            .toFloat()
    } catch (_: Exception) {
        0f
    }

    // ──── Page List (Images) ────

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val images = document.select("#readerarea img.ts-main-image")

        return images.mapIndexed { index, img ->
            val url = img.attr("abs:src").ifBlank {
                img.attr("abs:data-src")
            }
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
