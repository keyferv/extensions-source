package eu.kanade.tachiyomi.extension.es.onfmangas

import android.webkit.CookieManager
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

private const val ONF_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
private const val ONF_SEC_CH_UA =
    "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\""

@Source
abstract class OnfMangas : KeiSource() {

    override val supportsLatest = true
    override val supportsFilterFetching = true

    // Clean client without CloudflareInterceptor that interferes with Turnstile challenges
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" }
        connectTimeout(30, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        addInterceptor(::onfTokenInterceptor)
    }

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
        return request.newBuilder()
            .header("User-Agent", ONF_USER_AGENT)
            .header("sec-ch-ua", ONF_SEC_CH_UA)
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .build()
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", ONF_USER_AGENT)
        set("sec-ch-ua", ONF_SEC_CH_UA)
        set("sec-ch-ua-mobile", "?0")
        set("sec-ch-ua-platform", "\"Android\"")
    }

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/mangas.php?pagina=$page&orden=popular", Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/mangas.php?pagina=$page&orden=actualizado", Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    // ============================== Search ================================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/mangas.php?buscar=${query.trim()}&pagina=$page"
        } else {
            val tabFilter = filters.firstInstanceOrNull<TabFilter>()?.selected ?: "all"
            val genreFilter = filters.firstInstanceOrNull<GenreFilter>()?.state?.filter { it.state }?.map { it.id }.orEmpty()

            val builder = "$baseUrl/mangas.php?pagina=$page".toHttpUrl().newBuilder()

            when (tabFilter) {
                "popular" -> builder.addQueryParameter("orden", "popular")
                "updated" -> builder.addQueryParameter("orden", "actualizado")
                "created" -> builder.addQueryParameter("orden", "creado")
                "rating" -> builder.addQueryParameter("orden", "alfabetico")
            }

            genreFilter.forEach {
                builder.addQueryParameter("generos[]", it)
            }

            builder.build().toString()
        }

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select(".manga-card").map { card ->
            SManga.create().apply {
                val link = card.selectFirst("a")?.attr("href") ?: ""
                url = if (link.startsWith("/")) link else "/$link"
                title = card.selectFirst(".manga-card-title")?.text() ?: ""
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.selectFirst(".pagination-btn:contains(Siguiente)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/mangas.php", Fetch.DOCUMENT).asJsoup()
        return document.select("label.cat-checkbox").map { label ->
            GenreDto(label.selectFirst("input")!!.attr("value"), label.selectFirst(".cat-label")!!.text())
        }.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(TabFilter())
            data?.parseAs<List<GenreDto>>()?.let { add(GenreFilter(it)) }
        },
    )

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url, Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document).apply {
                url = manga.url
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            parseChapterList(document)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
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

    // ============================== Chapters ==============================

    private fun parseChapterList(document: Document): List<SChapter> {
        val json = document.decodeHexVariable("_hex")
            ?: document.selectFirst("script[type=application/json]:containsData(\"numero\":), script[type=application/json]:containsData(\"name\":)")?.data()
            ?: return emptyList()

        val chaptersData = json.parseAs<List<ChapterDto>>()

        return chaptersData
            .sortedWith(
                compareByDescending<ChapterDto> { it.numberFloat }
                    .thenByDescending { it.date },
            )
            .flatMap { it.toSChapters(false) }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + chapter.url
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
        val json = document.decodeHexVariable("_hexP")
            ?: document.selectFirst("script[type=application/json]:containsData(\"src\":)")?.data()
            ?: return emptyList()

        val pagesData = json.parseAs<List<PageDto>>()
        return pagesData.mapIndexed { index, dto -> dto.toPage(index, chapterUrl) }
    }

    // The site's image proxy answers 403 without the chapter page as Referer
    override fun getImageRequest(page: Page): Request = super.getImageRequest(page).newBuilder()
        .header("Referer", page.url)
        .build()

    // ============================= Utilities ==============================

    private fun Document.decodeHexVariable(name: String): String? = selectFirst("script:containsData(const $name =)")
        ?.data()
        ?.substringAfter("const $name = \"")
        ?.substringBefore("\";")
        ?.takeIf { it.isNotEmpty() && it.length % 2 == 0 }
        ?.let { decodeHex(it) }

    private fun decodeHex(hexString: String): String {
        require(hexString.length % 2 == 0) { "Must have an even length" }
        val bytes = hexString.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    private fun solveOnfCheck(html: String): String? {
        val scriptStart = html.indexOf("<script>")
        val scriptEnd = html.indexOf("</script>", scriptStart)
        if (scriptStart == -1 || scriptEnd == -1) return null

        val scriptContent = html.substring(scriptStart + 8, scriptEnd)

        return QuickJs.create().use { qjs ->
            val polyfill = """
                var capturedCookie = null;
                var document = {
                    cookie: "",
                    set cookie(val) { capturedCookie = val; },
                    get cookie() { return capturedCookie; }
                };
                var location = { reload: function() {} };
            """.trimIndent()

            qjs.evaluate(polyfill)
            qjs.evaluate(scriptContent)
            qjs.evaluate("capturedCookie") as? String
        }
    }
}
