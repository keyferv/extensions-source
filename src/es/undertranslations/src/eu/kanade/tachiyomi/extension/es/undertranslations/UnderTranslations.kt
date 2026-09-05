package eu.kanade.tachiyomi.extension.es.undertranslations

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
import keiyoushi.utils.tryParseDate
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class UnderTranslations : KeiSource() {

    private val dateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale("es", "MX"))

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0")
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("order", "popular")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("order", "update")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return parseMangaList(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val manga = SManga.create().apply { setUrlWithoutDomain(url.toString()) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    private fun parseMangaList(document: Document): MangasPage {
        val cards = document.select("div.bsx")

        val mangas = cards.map { card ->
            val link = card.selectFirst("a[href]")!!

            SManga.create().apply {
                title = link.attr("title").ifBlank {
                    card.selectFirst(".tt")?.text() ?: "Unknown"
                }
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = card.selectFirst("img[src]")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst(".pagination a.next.page-numbers") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url

        title = document.selectFirst(".entry-title")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: "Unknown"

        thumbnail_url = document.selectFirst(".thumb img[src]")?.attr("abs:src")

        description = document.select(".info-desc .wd-full")
            .firstOrNull { it.select(".entry-content").isNotEmpty() }
            ?.select(".entry-content p")
            ?.joinToString("\n") { it.text() }

        status = parseStatus(document.selectFirst(".spe span")?.text())

        genre = document.select(".mgen a")
            .joinToString { it.text() }

        author = document.selectFirst(
            ".infotable tr:contains(Autor) td:last-child, " +
                ".infotable tr:contains(autor) td:last-child",
        )?.text()
            ?: document.selectFirst("td:contains(Autor) + td")?.text()
            ?: "Desconocido"
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

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = document.select("#chapterlist li")

        return chapters.mapNotNull { li ->
            val link = li.selectFirst(".eph-num a") ?: return@mapNotNull null
            val numSpan = li.selectFirst(".chapternum")
            val dateSpan = li.selectFirst(".chapterdate")
            val dataNum = li.attr("data-num")

            val chapterName = numSpan?.text()
                ?: link.text().ifBlank { return@mapNotNull null }
            val chapterNum = dataNum.ifBlank { chapterName }

            SChapter.create().apply {
                name = chapterName
                setUrlWithoutDomain(link.attr("abs:href"))
                date_upload = dateFormat.tryParseDate(dateSpan?.text())
                chapter_number = parseChapterNumber(chapterNum)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun parseChapterNumber(num: String): Float = try {
        num.replace("Capítulo ", "", ignoreCase = true)
            .trim()
            .toFloat()
    } catch (_: Exception) {
        0f
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val body = client.get(getChapterUrl(chapter)).use { it.body.string() }

        // Reader images are injected by ts_reader.run({...}) into an empty #readerarea.
        val imagesBlock = IMAGES_ARRAY_REGEX.find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val images = IMAGE_URL_REGEX.findAll(imagesBlock)
            .map { match -> match.groupValues[1].replace("\\/", "/") }
            .toList()

        return images.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    companion object {
        private val IMAGES_ARRAY_REGEX = Regex(
            "\"images\"\\s*:\\s*\\[(.*?)]",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val IMAGE_URL_REGEX = Regex("\"(https?:\\\\/\\\\/[^\"]+|https?://[^\"]+)\"")
    }
}
