package eu.kanade.tachiyomi.extension.es.onfmangas

import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class OnfMangas : HttpSource() {

    override val name = "ONF MANGAS"
    override val baseUrl = "https://onfmangas.com"
    override val lang = "es"
    override val supportsLatest = true

    companion object {
        private const val TAG = "OnfMangas"
        private val BLOCKED_MARKER = Regex("Acceso Restringido|Acceso Denegado", RegexOption.IGNORE_CASE)
        private val CHALLENGE_MARKER = Regex("Verificando\\.\\.\\.", RegexOption.IGNORE_CASE)

        // Challenge page embeds two hex vars that get concatenated and reversed to form __onf_chk.
        // Example: var _0x1 = "5a89..."; var _0x2 = "3bf4..."; → (__0x2 + _0x1).reversed()
        private val CHALLENGE_VARS_REGEX = Regex(
            """var _0x1\s*=\s*"([^"]+)";\s*var _0x2\s*=\s*"([^"]+)"""",
        )
    }

    override val client = super.client.newBuilder()
        .addInterceptor(::onfTokenInterceptor)
        .build()

    /**
     * Interceptor that handles the site's custom JS challenge.
     *
     * The site serves a "Verificando..." page with inline JS that computes
     * an __onf_chk cookie: (_0x2 + _0x1).reversed(). We extract the two
     * hex vars, compute the token, set the cookie, and retry — no WebView needed.
     */
    private fun onfTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Sync any existing cookies from WebView (PHPSESSID, __onf_chk)
        syncCookiesFromWebView(url)

        var response = chain.proceed(request)

        val contentType = response.header("Content-Type")
        if (contentType == null || "text/html" !in contentType) {
            return response
        }

        val body = response.peekBody(16_384).string()

        // Check for the site's JS challenge page ("Verificando...")
        if (CHALLENGE_MARKER.containsMatchIn(body)) {
            response.close()

            val match = CHALLENGE_VARS_REGEX.find(body)
            if (match != null) {
                val ox1 = match.groupValues[1]
                val ox2 = match.groupValues[2]
                val token = (ox2 + ox1).reversed()
                Log.d(TAG, "Challenge solved: __onf_chk=${token.take(8)}...")

                val cookie = Cookie.parse(request.url, "__onf_chk=$token; path=/")
                client.cookieJar.saveFromResponse(request.url, listOfNotNull(cookie))

                response = chain.proceed(request)

                // Verify we got past the challenge
                val retryBody = response.peekBody(16_384).string()
                if (CHALLENGE_MARKER.containsMatchIn(retryBody)) {
                    Log.w(TAG, "Challenge: still blocked after token — site may have changed format")
                } else {
                    Log.d(TAG, "Challenge: solved successfully")
                }
            } else {
                Log.w(TAG, "Challenge: could not extract _0x1/_0x2 vars — format changed?")
                // Fallback: try syncing from WebView in case user solved it there
                syncCookiesFromWebView(url)
                response = chain.proceed(request)
            }

            return response
        }

        // Check for block page
        if (BLOCKED_MARKER.containsMatchIn(body)) {
            Log.w(TAG, "BLOCKED by anti-bot: $url")
        }

        return response
    }

    /**
     * Sync cookies from Android's CookieManager (WebView) to OkHttp's cookie jar.
     * Copies PHPSESSID and __onf_chk so OkHttp shares the WebView session.
     */
    private fun syncCookiesFromWebView(url: String) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url) ?: return
        val httpUrl = url.toHttpUrl()

        for (cookiePart in cookies.split(";")) {
            val cookie = Cookie.parse(httpUrl, cookiePart.trim()) ?: continue
            client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
        }
    }

    /**
     * Chrome desktop headers with Client Hints + Fetch Metadata.
     *
     * The site checks sec-ch-ua / sec-fetch-* headers server-side.
     * Without them, content pages return "Acceso Restringido".
     * OkHttp doesn't send these by default — we set them explicitly.
     */
    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Accept-Language", "es-ES,es;q=0.9")
        .set("sec-ch-ua", "\"Google Chrome\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"")
        .set("sec-ch-ua-mobile", "?0")
        .set("sec-ch-ua-platform", "\"Windows\"")
        .set("sec-fetch-site", "none")
        .set("sec-fetch-mode", "navigate")
        .set("sec-fetch-user", "?1")
        .set("sec-fetch-dest", "document")

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/populares.php", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        Log.d(TAG, "Popular HTML (first 500): ${document.html().take(500)}")
        val mangas = document.select("a.pop-podium-card, a.pop-card").mapNotNull { element ->
            SManga.create().apply {
                title = element.selectFirst(".pop-podium-name, .pop-name")?.text()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

                setUrlWithoutDomain(
                    element.attr("abs:href")
                        .takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                )

                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        Log.d(TAG, "Popular: parsed ${mangas.size} mangas")
        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/mangas.php?tab=general&genero=0&q=&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        Log.d(TAG, "Latest HTML (first 500): ${document.html().take(500)}")
        val mangas = document.select(".manga-grid .manga-card").mapNotNull { element ->
            SManga.create().apply {
                title = element.selectFirst(".manga-title")?.text()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                setUrlWithoutDomain(
                    element.selectFirst("a")?.attr("abs:href")
                        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                )
                thumbnail_url = element.selectFirst(".card-cover img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst(".pagination a.page-btn:contains(Siguiente)") != null
        Log.d(TAG, "Latest: parsed ${mangas.size} mangas, hasNext=$hasNextPage")
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/mangas.php".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())

        val tab = filters.firstInstanceOrNull<TabFilter>()?.selected ?: "general"
        val genero = filters.firstInstanceOrNull<GenreFilter>()?.selected ?: "0"

        url.addQueryParameter("tab", tab)

        // The website expects "generos[0]" instead of "genero"
        // Also skip adding it entirely if the default "Todas las categorías" (0) is selected
        if (genero != "0") {
            url.addQueryParameter("generos[0]", genero)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun getFilterList() = FilterList(
        TabFilter(),
        GenreFilter(),
    )

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".manga-title")?.text()
                ?.takeIf { it.isNotEmpty() }
                ?: throw Exception("Could not parse manga title — page may be blocked")
            author = document.selectFirst(".author-link")?.text()
            description = document.selectFirst(".manga-description")?.text()
            genre = document.select(".genre-tag").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".manga-poster")?.attr("abs:src")

            val statusText = document.select(".manga-meta span").last()?.text()
            status = when {
                statusText?.contains("EMISIÓN", true) == true -> SManga.ONGOING
                statusText?.contains("FINALIZADO", true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            Log.d(TAG, "Details: title=$title, status=$statusText")
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val hexString = document.selectFirst("script:containsData(const _hex =)")
            ?.data()
            ?.substringAfter("const _hex = \"")
            ?.substringBefore("\";")

        if (hexString == null) {
            Log.w(TAG, "Chapters: no _hex data found — page may be blocked or site changed")
            return emptyList()
        }

        val jsonString = decodeHex(hexString)
        val chaptersData = jsonString.parseAs<List<ChapterDto>>()

        val chapters = mutableListOf<SChapter>()

        // Simulating the source's client-side descending sorting
        val sortedChapters = chaptersData.sortedWith(
            compareByDescending<ChapterDto> { it.numberFloat }
                .thenByDescending { it.date },
        )

        for (dto in sortedChapters) {
            val parentChapter = dto.toSChapter().apply {
                date_upload = dateFormat.tryParse(dto.date)
            }
            chapters.add(parentChapter)

            dto.getOtherVersions()?.forEach { otherVersion ->
                chapters.add(
                    otherVersion.toSChapter(dto).apply {
                        date_upload = parentChapter.date_upload
                    },
                )
            }
        }
        Log.d(TAG, "Chapters: parsed ${chapters.size} chapters from ${chaptersData.size} entries")
        return chapters
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val hexString = document.selectFirst("script:containsData(const _hexP =)")
            ?.data()
            ?.substringAfter("const _hexP = \"")
            ?.substringBefore("\";")

        if (hexString == null) {
            Log.w(TAG, "Pages: no _hexP data found — page may be blocked or site changed")
            return emptyList()
        }

        val jsonString = decodeHex(hexString)
        val pagesData = jsonString.parseAs<List<PageDto>>()

        Log.d(TAG, "Pages: parsed ${pagesData.size} pages")
        return pagesData.mapIndexed { index, dto -> dto.toPage(index) }
    }

    // Decent chance for primary src to fail
    override fun fetchImageUrl(page: Page): Observable<String> {
        val src = page.url
        val fallback = page.url.toHttpUrl().fragment?.removePrefix("fallback=")

        if (fallback.isNullOrBlank()) return Observable.just(src)

        return Observable.fromCallable {
            val response = client.newCall(Request.Builder().head().url(src).build()).execute()
            val success = response.isSuccessful
            response.close()
            if (success) src else fallback
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun decodeHex(hexString: String): String {
        require(hexString.length % 2 == 0) { "Must have an even length" }
        val bytes = hexString.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        return String(bytes, Charsets.UTF_8)
    }
}
