package eu.kanade.tachiyomi.extension.es.nartag

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Rncalation : HttpSource() {

    override val name = "Rncalation"

    override val baseUrl = "https://rncalation.online"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("M/d/yyyy", Locale.ROOT)

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/library?sort=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".lib-grid a.comic-card").map { element ->
            SManga.create().apply {
                title = element.selectFirst("p.leading-snug")?.text() ?: ""
                setUrlWithoutDomain(element.attr("href"))
                thumbnail_url = element.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("a.lib-page-btn--nav:last-child") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/library?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("library")

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val index = filter.state
                    if (index > 0) {
                        url.addQueryParameter("sort", sortOptions[index].value)
                    }
                }
                is TypeFilter -> {
                    val index = filter.state
                    if (index > 0) {
                        url.addQueryParameter("type", types[index].lowercase(Locale.ROOT))
                    }
                }
                is StatusFilter -> {
                    val index = filter.state
                    if (index > 0) {
                        url.addQueryParameter("status", statuses[index].lowercase(Locale.ROOT))
                    }
                }
                is GenreFilter -> {
                    val index = filter.state
                    if (index > 0) {
                        url.addQueryParameter("genre", genres[index].lowercase(Locale.ROOT))
                    }
                }
                else -> {}
            }
        }

        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList() = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val hero = document.selectFirst(".comic-hero-wrap")
            ?: throw Exception("Manga details not found")

        return SManga.create().apply {
            title = hero.selectFirst("h1")?.text() ?: "Unknown"

            thumbnail_url = hero.selectFirst("img")?.absUrl("src")
                ?: hero.selectFirst("img")?.attr("abs:data-src")

            description = hero.selectFirst("p.leading-relaxed")?.text()

            author = hero.selectFirst("span:contains(Autor)")?.ownText()
                ?: hero.selectFirst("span:contains(Autor)")?.text()
                    ?.removePrefix("Autor:")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            artist = hero.selectFirst("span:contains(Arte)")?.ownText()
                ?: hero.selectFirst("span:contains(Arte)")?.text()
                    ?.removePrefix("Arte:")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

            val statusText = hero.selectFirst("span.comic-badge")?.text()?.lowercase(Locale.ROOT)
            status = when {
                statusText?.contains("ongoing") == true ||
                    statusText?.contains("publicando") == true -> SManga.ONGOING
                statusText?.contains("completed") == true ||
                    statusText?.contains("completado") == true -> SManga.COMPLETED
                statusText?.contains("hiatus") == true ||
                    statusText?.contains("pausa") == true -> SManga.ON_HIATUS
                statusText?.contains("cancelled") == true ||
                    statusText?.contains("cancelado") == true -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            genre = hero.select("a[href*=genre]").joinToString { it.text() }
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val slug = response.request.url.pathSegments.getOrNull(1) ?: ""

        val chapters = mutableListOf<SChapter>()

        // Parse regular chapter list
        document.select("#chapter-list a").mapTo(chapters) { chapterFromElement(it, slug) }

        // Parse chapters-extra template (premium/extra content)
        document.select("template#chapters-extra").forEach { template ->
            val html = template.html()
            if (html.isNotBlank()) {
                val fragment = org.jsoup.Jsoup.parseBodyFragment(html, document.baseUri())
                fragment.body().select("a").mapTo(chapters) { chapterFromElement(it, slug) }
            }
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun chapterFromElement(element: Element, slug: String): SChapter = SChapter.create().apply {
        val href = element.attr("abs:href").ifEmpty { element.attr("href") }

        // Check if it's a premium chapter (redirect to login)
        val isPremium = href.contains("/auth/login")

        if (isPremium) {
            // Construct URL from slug and chapter number
            val redirectParam = element.attr("href").substringAfter("redirect=")
            val chapterNum = redirectParam.substringAfterLast("/")
            name = "\uD83D\uDD12 ${element.selectFirst("span.flex-1")?.text() ?: ""}"
            setUrlWithoutDomain("/series/$slug/chapter/$chapterNum")
        } else {
            setUrlWithoutDomain(href)
            name = element.selectFirst("span.flex-1")?.text() ?: ""
        }

        val dateText = element.selectFirst("span:matches(\\d{1,2}/\\d{1,2}/\\d{4})")?.text()
        date_upload = dateText?.let { dateFormat.tryParse(it) } ?: 0L

        // Extract chapter number from name for sorting
        chapter_number = Regex("""(\d+(\.\d+)?)""").find(name)?.value?.toFloatOrNull() ?: 0f

        scanlator = element.select("span").find { span ->
            val text = span.text()
            text.isNotEmpty() &&
                text != (element.selectFirst("span.flex-1")?.text() ?: "") &&
                !text.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{4}"))
        }?.text()
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img.page-img, .page-wrap img").mapIndexed { index, img ->
            val url = img.attr("abs:data-src").ifEmpty {
                img.attr("abs:src").ifEmpty {
                    img.attr("data-src").ifEmpty {
                        img.attr("src")
                    }
                }
            }
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
