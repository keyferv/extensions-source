package eu.kanade.tachiyomi.extension.es.leercapitulo

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class LeerCapitulo : HttpSource() {
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(1, 3.seconds) { it.host == baseUrlHost }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a.lc-side-item[href^=/manga/]").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".lc-side-name")!!.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()

            return GET(url, headers)
        }

        val urlBuilder = "$baseUrl/manga/".toHttpUrl().newBuilder()

        filters.firstInstanceOrNull<QueryFilter>()?.takeIf { it.state.isNotBlank() }?.let {
            urlBuilder.addQueryParameter("q", it.state)
        }
        filters.firstInstanceOrNull<GenreFilter>()?.takeIf { it.state != 0 }?.let {
            urlBuilder.addQueryParameter("genre", it.toUriPart())
        }
        filters.firstInstanceOrNull<ThemeFilter>()?.takeIf { it.state != 0 }?.let {
            urlBuilder.addQueryParameter("theme", it.toUriPart())
        }
        filters.firstInstanceOrNull<TypeFilter>()?.takeIf { it.state != 0 }?.let {
            urlBuilder.addQueryParameter("type", it.toUriPart())
        }
        filters.firstInstanceOrNull<StatusFilter>()?.takeIf { it.state != 0 }?.let {
            urlBuilder.addQueryParameter("status", it.toUriPart())
        }
        filters.firstInstanceOrNull<SortFilter>()?.let {
            urlBuilder.addQueryParameter("sort", it.toUriPart())
        }
        urlBuilder.addQueryParameter("page", page.toString())

        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.contains("search")) {
            val mangas = response.parseAs<List<Dto>>().map { it.toSManga() }
            return MangasPage(mangas, hasNextPage = false)
        }

        val document = response.asJsoup()
        val mangas = document.select("article.lc-card").map { element ->
            SManga.create().apply {
                val nameLink = element.selectFirst("a.lc-card-name")!!
                setUrlWithoutDomain(nameLink.attr("abs:href"))
                title = nameLink.text()
                thumbnail_url = element.selectFirst("a.lc-card-cover img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst("a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Text search ignores the filters below."),
        QueryFilter(),
        GenreFilter(),
        ThemeFilter(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("article:has(a[href^='/manga/']):has(a[href^='/leer/'])").map { element ->
            val mangaLink = element.select("a[href^=/manga/]").firstOrNull { it.text().isNotBlank() }!!
            SManga.create().apply {
                setUrlWithoutDomain(mangaLink.attr("abs:href"))
                title = mangaLink.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.h3.mb-1")!!.text()

            val altNames = document.selectFirst("p.small.lc-muted")?.text()
            val desc = document.selectFirst("section#sinopsis p")?.text()
            description = buildString {
                if (!desc.isNullOrEmpty()) append(desc)
                if (!altNames.isNullOrEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alt name(s): ")
                    append(altNames)
                }
            }

            genre = document.select("a.badge[href*=genre], a.badge[href*=theme]").joinToString { it.text() }
            status = document.select("ul.lc-facts li")
                .firstOrNull { it.selectFirst(".k")?.text() == "Estado" }
                ?.selectFirst("a")?.text()?.toStatus() ?: SManga.UNKNOWN
            thumbnail_url = document.selectFirst(".lc-cover-lg img")?.attr("abs:src")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("#chapterList > a.lc-chapter-row").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                name = element.selectFirst("span.n")!!.text()
                date_upload = dateFormat.tryParseDate(element.selectFirst("span.d")?.text())
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> = response.asJsoup().select("main img[data-src]").mapIndexed { index, img ->
        Page(index, imageUrl = img.attr("abs:data-src"))
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun Dto.toSManga() = SManga.create().apply {
        setUrlWithoutDomain(uri)
        title = name
        thumbnail_url = baseUrl + coverUri
    }

    private fun String.toStatus() = when (this) {
        "Ongoing" -> SManga.ONGOING
        "Paused" -> SManga.ON_HIATUS
        "Completed" -> SManga.COMPLETED
        "Cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
}
