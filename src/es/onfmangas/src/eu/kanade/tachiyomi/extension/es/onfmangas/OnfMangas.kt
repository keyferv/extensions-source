package eu.kanade.tachiyomi.extension.es.onfmangas

import android.webkit.CookieManager
import app.cash.quickjs.QuickJs
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val ONF_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
private const val ONF_SEC_CH_UA =
    "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\""

class OnfMangas : HttpSource() {

    override val name = "ONF MANGAS"
    override val baseUrl = "https://onfmangas.com"
    override val lang = "es"
    override val supportsLatest = true

    // Clean client without CloudflareInterceptor that interferes with Turnstile challenges
    override val client = OkHttpClient.Builder()
        .cookieJar(super.client.cookieJar)
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(::onfTokenInterceptor)
        .build()

    private object TurnstileAttempted

    private fun onfTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = normalizeBrowserHeaders(chain.request())
        val response = chain.proceed(request)

        if (response.code == 403) {
            val body = response.peekBody(65536).string()

            // onfmangas "Verificando" custom challenge — solve with QuickJs
            if (body.contains("Verificando")) {
                response.close()
                val cookieString = solveOnfCheck(body)
                    ?: error("Failed to solve cookie challenge")
                val cookie = Cookie.parse(request.url, cookieString)
                client.cookieJar.saveFromResponse(request.url, listOfNotNull(cookie))
                return chain.proceed(request)
            }

            // Cloudflare Turnstile — only attempt once per request chain
            if (request.tag(TurnstileAttempted::class.java) != null) {
                response.close()
                throw Exception(
                    "Cloudflare bloqueó la solicitud. Abrí onfmangas.com en tu navegador, " +
                        "resolvé el captcha, y volvé a intentar.",
                )
            }

            response.close()
            val resolved = CloudflareResolver.resolve(
                loadUrl = request.url.toString(),
                userAgent = ONF_USER_AGENT,
            )
            if (resolved) {
                syncCookiesFromWebView(request.url.toString())
                val retryRequest = normalizeBrowserHeaders(
                    request.newBuilder()
                        .tag(TurnstileAttempted::class.java, TurnstileAttempted)
                        .build(),
                )
                return chain.proceed(retryRequest)
            }
            throw Exception(
                "Cloudflare bloqueó la solicitud. Abrí onfmangas.com en tu navegador, " +
                    "resolvé el captcha, y volvé a intentar.",
            )
        }

        val body = response.peekBody(8192).string()
        if (!body.contains("Verificando")) return response

        response.close()
        val cookieString = solveOnfCheck(body)
            ?: error("Failed to solve cookie challenge")
        val cookie = Cookie.parse(request.url, cookieString)
        client.cookieJar.saveFromResponse(request.url, listOfNotNull(cookie))

        return chain.proceed(request)
    }

    private fun syncCookiesFromWebView(url: String) {
        val rawCookies = CookieManager.getInstance().getCookie(url) ?: return
        val cookies = rawCookies.split(';').mapNotNull {
            Cookie.parse(url.toHttpUrl(), it.trim())
        }
        client.cookieJar.saveFromResponse(url.toHttpUrl(), cookies)
    }

    private fun normalizeBrowserHeaders(request: Request): Request {
        if (request.url.host != baseUrl.toHttpUrl().host) return request

        val isTopLevelHome = request.url.encodedPath == "/"
        val secFetchSite = if (isTopLevelHome) "none" else "same-origin"
        val referer = if (isTopLevelHome) null else request.url.toString()

        return request.newBuilder()
            .header("User-Agent", ONF_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Sec-CH-UA", ONF_SEC_CH_UA)
            .header("Sec-CH-UA-Mobile", "?1")
            .header("Sec-CH-UA-Platform", "\"Android\"")
            .header("Sec-Fetch-Site", secFetchSite)
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Dest", "document")
            .apply {
                if (referer != null) {
                    header("Referer", referer)
                }
            }
            .build()
    }

    private fun solveOnfCheck(body: String): String? {
        val document = org.jsoup.Jsoup.parse(body)
        val script = document.selectFirst("script")?.data() ?: error("Failed to find cookie challenge script")

        return QuickJs.create().use { js ->
            js.evaluate(
                """
            var window = { location: {} };
            var document = { cookie: null };
            var location = window.location;
            var setTimeout = function(fn, _) { fn(); };

            $script

            document.cookie;
                """.trimIndent(),
            )?.toString()
        }
    }

    // Mimic a standard desktop browser to bypass Cloudflare WAF 403s
    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", ONF_USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Sec-CH-UA", ONF_SEC_CH_UA)
        .set("Sec-CH-UA-Mobile", "?1")
        .set("Sec-CH-UA-Platform", "\"Android\"")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Dest", "document")

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/populares.php", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
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
        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/mangas.php?tab=general&genero=0&q=&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
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
                ?: throw Exception("Could not parse manga title")
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
        }
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val hexString = document.selectFirst("script:containsData(const _hex =)")
            ?.data()
            ?.substringAfter("const _hex = \"")
            ?.substringBefore("\";")
            ?: return emptyList()

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
        return chapters
    }

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val hexString = document.selectFirst("script:containsData(const _hexP =)")
            ?.data()
            ?.substringAfter("const _hexP = \"")
            ?.substringBefore("\";")
            ?: return emptyList()

        val jsonString = decodeHex(hexString)
        val pagesData = jsonString.parseAs<List<PageDto>>()

        return pagesData.mapIndexed { index, dto -> dto.toPage(index) }
    }

    // Decent chance for primary src to fail
    private val probeClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val src = page.url
        val fallback = page.url.toHttpUrl().fragment?.removePrefix("fallback=")

        if (fallback.isNullOrBlank()) return Observable.just(src)

        return Observable.fromCallable {
            try {
                val response = probeClient.newCall(Request.Builder().head().url(src).build()).execute()
                val success = response.isSuccessful
                response.close()
                if (success) src else fallback
            } catch (_: Exception) {
                fallback
            }
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
