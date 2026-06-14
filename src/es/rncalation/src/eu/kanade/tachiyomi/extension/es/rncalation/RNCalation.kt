package eu.kanade.tachiyomi.extension.es.rncalation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class RNCalation : HttpSource() {

    override val name = "RN Calation"

    override val baseUrl = "https://rncalation.online"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/library?sort=views".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.comic-card").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.selectFirst("p.text-\\[\\.85rem\\]")?.text()
                    ?: element.selectFirst("p")?.text()
                    ?: ""
                thumbnail_url = element.selectFirst("img")?.let { img ->
                    val src = img.attr("src")
                    if (src.startsWith("http")) src else baseUrl + src
                }
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next], button:contains(Siguiente)") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/library".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            addQueryParameter("sort", "latest")
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList(): FilterList = FilterList()

    // =========================== Manga Details ============================
    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""

            thumbnail_url = document.selectFirst("div.sm\\:w-56 img")?.let { img ->
                val src = img.attr("src")
                if (src.startsWith("http")) src else baseUrl + src
            }

            description = document.selectFirst("p.text-sm")?.text()

            val badges = document.select("span.comic-badge").map { it.text().trim() }
            genre = document.select("a[href*=\"/library?genre=\"]").joinToString { it.text() }

            status = when {
                badges.any { it.contains("En emisión", true) || it.contains("Ongoing", true) } -> SManga.ONGOING
                badges.any { it.contains("Completed", true) || it.contains("Finalizado", true) } -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        document.select("div#chapter-list a").forEach { a ->
            val href = a.attr("href")
            if (href.isNotEmpty()) {
                chapters.add(
                    SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = a.selectFirst("span.flex-1")?.text()?.trim() ?: ""
                        date_upload = parseDate(a.selectFirst("span.text-\\[\\.65rem\\]")?.text())
                    },
                )
            }
        }

        return chapters
    }

    // =============================== Pages ================================
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div.page-wrap img.page-img").mapIndexed { i, img ->
            val src = img.attr("src")
            Page(i, imageUrl = if (src.startsWith("http")) src else baseUrl + src)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0
        return try {
            dateFormat.parse(dateStr.trim())?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private val dateFormat by lazy {
        SimpleDateFormat("M/d/yyyy", Locale.ENGLISH)
    }
}
