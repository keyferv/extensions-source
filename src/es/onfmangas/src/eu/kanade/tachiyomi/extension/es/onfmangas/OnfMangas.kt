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
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val ONF_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
private const val ONF_SEC_CH_UA =
    "\"Android WebView\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\""

@Source
abstract class OnfMangas : KeiSource() {

    override val supportsLatest = true

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

            // If it's an API error response (e.g. {"error": ...}), don't send to WebView
            if (body.startsWith("{") && body.contains("error")) {
                return response
            }

            val loadUrl = when {
                request.url.encodedPath.endsWith(".php") -> request.header("Referer") ?: "$baseUrl/"
                else -> request.url.toString()
            }

            response.close()
            val resolved = CloudflareResolver.resolve(
                loadUrl = loadUrl,
                userAgent = ONF_USER_AGENT,
            )
            if (resolved) {
                syncCookiesFromWebView(loadUrl)
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

        val path = request.url.encodedPath
        val isApi = path.endsWith(".php") && request.method == "POST"
        val isImage = path.contains("image") || request.url.queryParameter("asset") != null
        val isTopLevelHome = path == "/"

        val secFetchSite = if (isTopLevelHome) "none" else "same-origin"
        val secFetchMode = when {
            isApi -> "cors"
            isImage -> "no-cors"
            else -> "navigate"
        }
        val secFetchDest = when {
            isApi -> "empty"
            isImage -> "image"
            else -> "document"
        }

        return request.newBuilder().apply {
            header("User-Agent", ONF_USER_AGENT)
            if (request.header("Accept") == null) {
                if (isImage) {
                    header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                } else {
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                }
            }
            header("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
            header("Sec-CH-UA", ONF_SEC_CH_UA)
            header("Sec-CH-UA-Mobile", "?1")
            header("Sec-CH-UA-Platform", "\"Android\"")
            header("Sec-Fetch-Site", secFetchSite)
            header("Sec-Fetch-Mode", secFetchMode)
            header("Sec-Fetch-Dest", secFetchDest)
            if (isApi) {
                header("Origin", baseUrl)
            }
            if (request.header("Referer") == null && !isTopLevelHome) {
                header("Referer", request.url.toString())
            }
        }.build()
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

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", ONF_USER_AGENT)
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        set("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Sec-CH-UA", ONF_SEC_CH_UA)
        set("Sec-CH-UA-Mobile", "?1")
        set("Sec-CH-UA-Platform", "\"Android\"")
        set("Sec-Fetch-Site", "none")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Dest", "document")
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private val utcZone = ZoneId.of("UTC")

    // ============================== Popular ===============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/populares.php", Fetch.DOCUMENT).asJsoup()
        return parsePopularMangasPage(document)
    }

    private fun parsePopularMangasPage(document: Document): MangasPage {
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

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/mangas.php?tab=general&genero=0&q=&page=$page", Fetch.DOCUMENT).asJsoup()
        return parseLatestMangasPage(document)
    }

    private fun parseLatestMangasPage(document: Document): MangasPage {
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

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
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

        val document = client.get(url.build(), Fetch.DOCUMENT).asJsoup()
        return parseLatestMangasPage(document)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    override fun getFilterList(data: JsonElement?) = FilterList(
        TabFilter(),
        GenreFilter(),
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
                date_upload = dateFormat.tryParseDateTime(dto.date, utcZone)
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + chapter.url
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()

        // 1. New reader engine: viewerContainer with data-reader-access token
        val access = document.selectFirst("#viewerContainer")?.attr("data-reader-access")
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("[data-reader-access]")?.attr("data-reader-access")
                ?.takeIf { it.isNotEmpty() }

        if (!access.isNullOrBlank()) {
            val jsonBody = buildJsonObject {
                put("access", access)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/reader_pages.php")
                .header("Referer", chapterUrl)
                .header("Origin", baseUrl)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(jsonBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyString = response.body.string()
            if (response.isSuccessful && !bodyString.contains("error")) {
                val readerPages = bodyString.parseAs<ReaderPagesDto>()
                return readerPages.pages.mapIndexed { index, dto -> dto.toPage(index, chapterUrl) }
            }
        }

        // 2. Legacy fallback: const _hexP = "..."
        val hexString = document.selectFirst("script:containsData(const _hexP =)")
            ?.data()
            ?.substringAfter("const _hexP = \"")
            ?.substringBefore("\";")

        if (!hexString.isNullOrBlank()) {
            val jsonString = decodeHex(hexString)
            val pagesData = jsonString.parseAs<List<PageDto>>()
            return pagesData.mapIndexed { index, dto -> dto.toPage(index, chapterUrl) }
        }

        return emptyList()
    }

    override fun getImageRequest(page: Page): Request {
        val request = super.getImageRequest(page)
        val url = page.imageUrl.orEmpty()
        return if (url.contains("mangadex")) {
            // uploads.mangadex.org serves a placeholder or 403 if Referer or Origin is present
            request.newBuilder()
                .removeHeader("Referer")
                .removeHeader("Origin")
                .build()
        } else {
            // onfmangas images require the chapter url as Referer
            request.newBuilder()
                .header("Referer", page.url.ifEmpty { "$baseUrl/" })
                .build()
        }
    }

    // ============================= Utilities ==============================

    private fun decodeHex(hexString: String): String {
        require(hexString.length % 2 == 0) { "Must have an even length" }
        val bytes = hexString.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        return String(bytes, Charsets.UTF_8)
    }
}
