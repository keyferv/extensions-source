package eu.kanade.tachiyomi.extension.es.visorcapitulo

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class VisorCapitulo : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return popularMangaParse(client.get(url).asJsoup())
    }

    private fun popularMangaParse(document: Document): MangasPage {
        val mangas = document.select("div.list-item").map { element ->
            SManga.create().apply {
                title = element.selectFirst("a.list-title")!!.text()
                thumbnail_url = element.selectFirst("img.list-img")?.let {
                    baseUrl + it.attr("src")
                }
                url = canonicalMangaUrl(element.selectFirst("a.list-title")!!.attr("abs:href"))
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next], li.page-item:last-child a.page-link") != null &&
            document.select("li.page-item.active + li.page-item a.page-link").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()

        return if (query.isNotBlank()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            searchMangaJsonParse(client.get(url).parseAs<List<SearchResultDto>>())
        } else if (genreFilter != null) {
            val selectedGenre = genreFilter.state.filter { it.state }.map { it.key }
            if (selectedGenre.isNotEmpty()) {
                val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
                    .addQueryParameter("genre", selectedGenre.first())
                    .addQueryParameter("page", page.toString())
                    .build()
                popularMangaParse(client.get(url).asJsoup())
            } else {
                getPopularManga(page)
            }
        } else {
            getPopularManga(page)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "manga") return null
        val manga = SManga.create().apply { this.url = canonicalMangaUrl(url.toString()) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    private fun searchMangaJsonParse(results: List<SearchResultDto>): MangasPage {
        val mangas = results.map { result ->
            SManga.create().apply {
                title = result.title
                thumbnail_url = if (result.image.startsWith("http")) result.image else baseUrl + result.image
                url = canonicalMangaUrl(result.link)
            }
        }
        return MangasPage(mangas, false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter("Géneros", getGenreList()),
    )

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val canonicalUrl = document.selectFirst("tr.chapter-row a")
            ?.attr("abs:href")
            ?.toHttpUrlOrNull()
            ?.let { chapterUrl ->
                chapterUrl.pathSegments
                    .takeIf { it.size >= 3 && it.first() == "manga" }
                    ?.let { segments -> "/${segments[0]}/${segments[1]}/" }
            }
            ?: canonicalMangaUrl(manga.url)

        return SMangaUpdate(
            manga = parseMangaDetails(document, canonicalUrl),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl
        title = document.selectFirst("h1.fw-bold")!!.text()
        thumbnail_url = document.selectFirst("img.manga-main-img")?.let {
            baseUrl + it.attr("src")
        }
        author = document.selectFirst("p:has(span.meta-label:contains(Author))")
            ?.text()?.substringAfter(":")?.trim()
        status = document.selectFirst("p:has(span.meta-label:contains(Status))")
            ?.text()?.substringAfter(":")?.trim().parseStatus()
        genre = document.select("a.genre-link").joinToString { it.text() }
        description = document.selectFirst("p:has(span.meta-label:contains(Synopsis))")
            ?.ownText()
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select("tr.chapter-row").map { row ->
        SChapter.create().apply {
            name = row.selectFirst("td a")!!.text()
            date_upload = row.select("td").getOrNull(2)?.text()?.let { dateFormat.tryParseDate(it) } ?: 0L
            setUrlWithoutDomain(row.selectFirst("td a")!!.attr("abs:href"))
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()
        val encodedData = document.selectFirst("i#data")?.attr("data-data") ?: return emptyList()

        if (encodedData.length < 11) return emptyList()

        val pageData = decodeWithWebView(chapterUrl) ?: return emptyList()
        return pageData.imagesLink.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    private suspend fun decodeWithWebView(chapterUrl: String): ChapterDataDto? = try {
        runWebView<ChapterDataDto?>(timeout = 15.seconds) {
            jsBridge("Android") { json ->
                resolve(runCatching { json.parseAs<ChapterDataDto>() }.getOrNull())
            }
            onPageFinished {
                evaluateJs(
                    """
                    (function() {
                        var readerMode = document.getElementById('reader-mode');
                        if (readerMode && readerMode.value !== '1002') {
                            readerMode.value = '1002';
                            readerMode.dispatchEvent(new Event('change'));
                        }
                        setTimeout(function() {
                            var imgs = [];
                            var fullReader = document.getElementById('full-reader');
                            if (fullReader) {
                                var images = fullReader.querySelectorAll('img');
                                for (var i = 0; i < images.length; i++) {
                                    if (images[i].src && !images[i].src.startsWith('data:')) {
                                        imgs.push(images[i].src);
                                    }
                                }
                            }
                            if (imgs.length === 0) {
                                var singleImg = document.querySelector('#single-reader img');
                                if (singleImg && singleImg.src && !singleImg.src.startsWith('data:')) {
                                    imgs.push(singleImg.src);
                                }
                            }
                            Android.post(JSON.stringify({images_link: imgs}));
                        }, 2000);
                    })()
                    """.trimIndent(),
                )
            }
            loadUrl(chapterUrl)
        }
    } catch (_: Exception) {
        null
    }

    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "ongoing", "publishing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun canonicalMangaUrl(value: String): String {
        val parsed = value.toHttpUrlOrNull() ?: return value
        return parsed.encodedPath
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    @Serializable
    private class SearchResultDto(
        val title: String,
        val image: String,
        val link: String,
        val chapter: String = "",
    )

    @Serializable
    private class ChapterDataDto(
        @SerialName("images_link") val imagesLink: List<String> = emptyList(),
    )

    private fun getGenreList() = listOf(
        Genre("Action", "action"),
        Genre("Adventure", "adventure"),
        Genre("Comedy", "comedy"),
        Genre("Crime", "crime"),
        Genre("Drama", "drama"),
        Genre("Fantasy", "fantasy"),
        Genre("Historical", "historical"),
        Genre("Horror", "horror"),
        Genre("Isekai", "isekai"),
        Genre("Magical Girls", "magical-girls"),
        Genre("Mecha", "mecha"),
        Genre("Medical", "medical"),
        Genre("Mystery", "mystery"),
        Genre("Philosophical", "philosophical"),
        Genre("Psychological", "psychological"),
        Genre("Romance", "romance"),
        Genre("Sci-Fi", "sci-fi"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Sports", "sports"),
        Genre("Superhero", "superhero"),
        Genre("Thriller", "thriller"),
        Genre("Tragedy", "tragedy"),
        Genre("Wuxia", "wuxia"),
    )
}

class Genre(title: String, val key: String) : Filter.CheckBox(title)
class GenreFilter(title: String, genres: List<Genre>) : Filter.Group<Genre>(title, genres)
