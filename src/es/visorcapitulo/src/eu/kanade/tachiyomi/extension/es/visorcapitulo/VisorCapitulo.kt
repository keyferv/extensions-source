package eu.kanade.tachiyomi.extension.es.visorcapitulo

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Source
abstract class VisorCapitulo : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    // ================= Popular =================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.list-item").map { element ->
            SManga.create().apply {
                title = element.selectFirst("a.list-title")!!.text()
                thumbnail_url = element.selectFirst("img.list-img")?.let {
                    baseUrl + it.attr("src")
                }
                setUrlWithoutDomain(element.selectFirst("a.list-title")!!.attr("href"))
            }
        }
        val hasNextPage = document.selectFirst("a[rel=next], li.page-item:last-child a.page-link") != null &&
            document.select("li.page-item.active + li.page-item a.page-link").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // ================= Latest =================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ================= Search =================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()

        return if (query.isNotBlank()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            GET(url, headers)
        } else if (genreFilter != null) {
            val selectedGenre = genreFilter.state.filter { it.state }.map { it.key }
            if (selectedGenre.isNotEmpty()) {
                val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
                    .addQueryParameter("genre", selectedGenre.first())
                    .addQueryParameter("page", page.toString())
                    .build()
                GET(url, headers)
            } else {
                popularMangaRequest(page)
            }
        } else {
            popularMangaRequest(page)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        return if (url.contains("/search/")) {
            searchMangaJsonParse(response)
        } else {
            popularMangaParse(response)
        }
    }

    private fun searchMangaJsonParse(response: Response): MangasPage {
        val results = response.parseAs<List<SearchResultDto>>()
        val mangas = results.map { result ->
            SManga.create().apply {
                title = result.title
                thumbnail_url = if (result.image.startsWith("http")) result.image else baseUrl + result.image
                url = result.link
            }
        }
        return MangasPage(mangas, false)
    }

    // ================= Filters =================

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter("Géneros", getGenreList()),
    )

    // ================= Details =================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
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
                ?.ownText()?.trim()
        }
    }

    // ================= Chapters =================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("tr.chapter-row").map { row ->
            SChapter.create().apply {
                name = row.selectFirst("td a")!!.text()
                date_upload = row.select("td").getOrNull(2)?.text()?.let { dateFormat.tryParse(it) } ?: 0L
                setUrlWithoutDomain(row.selectFirst("td a")!!.attr("href"))
            }
        }.reversed()
    }

    // ================= Pages =================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val dataEl = document.selectFirst("i#data") ?: return emptyList()
        val encodedData = dataEl.attr("data-data")
        val chapterUrl = response.request.url.toString()

        if (encodedData.length < 11) return emptyList()

        val pageData = decodeWithWebView(chapterUrl, encodedData)
        if (pageData != null) {
            return pageData.imagesLink.mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
        }

        return emptyList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    /**
     * Uses WebView to decode chapter image URLs.
     * The site's JavaScript uses a substitution cipher that changes per chapter,
     * so we need to execute the site's own decode logic to get the image URLs.
     */
    private fun decodeWithWebView(chapterUrl: String, encodedData: String): ChapterDataDto? {
        val latch = CountDownLatch(1)
        var result: ChapterDataDto? = null

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(applicationContext)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true

            webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onImagesReady(json: String) {
                        result = runCatching { json.parseAs<ChapterDataDto>() }.getOrNull()
                        latch.countDown()
                    }
                },
                "Android",
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Switch to vertical mode to load all images, then extract
                    view?.evaluateJavascript(
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
                                Android.onImagesReady(JSON.stringify({images_link: imgs}));
                            }, 2000);
                        })()
                        """.trimIndent(),
                        null,
                    )
                }
            }

            webView.loadUrl(chapterUrl)

            Handler(Looper.getMainLooper()).postDelayed({
                if (latch.count > 0) {
                    latch.countDown()
                }
                webView.destroy()
            }, 15000)
        }

        latch.await(20, TimeUnit.SECONDS)
        return result
    }

    // ================= Helpers =================

    private fun String?.parseStatus(): Int = when (this?.lowercase()) {
        "ongoing", "publishing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Serializable
    private class SearchResultDto(
        val title: String,
        val image: String,
        val link: String,
        val chapter: String = "",
    )

    @Serializable
    private class ChapterDataDto(
        @kotlinx.serialization.SerialName("images_link") val imagesLink: List<String> = emptyList(),
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
