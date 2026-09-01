package eu.kanade.tachiyomi.extension.es.mangasnosekai

import android.content.Intent
import android.util.Log
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.synchrony.Deobfuscator
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MangasNoSekai :
    Madara(),
    ConfigurableSource {
    override val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("es"))
    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }
    private val preferences = getPreferences()
    private val diagnosticLock = Any()
    private var diagnosticRequestCount = 0L
    private var diagnosticFirstRequestAt = 0L
    private var diagnosticLastRequestAt = 0L
    private var diagnosticFirst429Request = 0L
    private var diagnosticActiveRequests = 0
    private var diagnosticMaxConcurrentRequests = 0

    private val isRateLimitDiagnosticEnabled: Boolean
        get() = preferences.getBoolean(RATE_LIMIT_DIAGNOSTIC, false)

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val client = super.client.newBuilder()
        .addInterceptor { chain ->
            if (!isRateLimitDiagnosticEnabled) {
                return@addInterceptor chain.proceed(chain.request())
            }

            val requestStartedAt = System.nanoTime()
            val requestNumber: Long
            val elapsedSincePreviousRequest: Long
            val activeRequests: Int

            synchronized(diagnosticLock) {
                requestNumber = ++diagnosticRequestCount
                elapsedSincePreviousRequest = if (diagnosticLastRequestAt == 0L) {
                    0L
                } else {
                    requestStartedAt - diagnosticLastRequestAt
                }
                diagnosticFirstRequestAt = if (diagnosticFirstRequestAt == 0L) {
                    requestStartedAt
                } else {
                    diagnosticFirstRequestAt
                }
                diagnosticLastRequestAt = requestStartedAt
                activeRequests = ++diagnosticActiveRequests
                diagnosticMaxConcurrentRequests = maxOf(
                    diagnosticMaxConcurrentRequests,
                    activeRequests,
                )
            }

            val response = chain.proceed(chain.request())
            val responseAt = System.nanoTime()

            synchronized(diagnosticLock) {
                diagnosticActiveRequests--
                val elapsedSinceFirstRequest = responseAt - diagnosticFirstRequestAt
                val requestDuration = responseAt - requestStartedAt
                val requestsPerMinute = if (elapsedSinceFirstRequest > 0L) {
                    diagnosticRequestCount * 60_000_000_000.0 / elapsedSinceFirstRequest
                } else {
                    0.0
                }
                val requestsPerSecond = requestsPerMinute / 60.0
                val endpoint = response.request.url
                Log.d(
                    RATE_LIMIT_TAG,
                    "Request #$requestNumber → ${response.code} | " +
                        "Δt=${elapsedSincePreviousRequest / 1_000_000}ms | " +
                        "duration=${requestDuration / 1_000_000}ms | " +
                        "concurrent=$activeRequests | maxConcurrent=$diagnosticMaxConcurrentRequests | " +
                        "rate=${"%.2f".format(Locale.ROOT, requestsPerSecond)} req/s " +
                        "(${"%.1f".format(Locale.ROOT, requestsPerMinute)} req/min) | " +
                        "endpoint=$endpoint",
                )
                appendDiagnosticLog(
                    "Request #$requestNumber → ${response.code} | " +
                        "Δt=${elapsedSincePreviousRequest / 1_000_000}ms | " +
                        "duration=${requestDuration / 1_000_000}ms | " +
                        "concurrent=$activeRequests | maxConcurrent=$diagnosticMaxConcurrentRequests | " +
                        "rate=${"%.2f".format(Locale.ROOT, requestsPerSecond)} req/s " +
                        "(${"%.1f".format(Locale.ROOT, requestsPerMinute)} req/min) | " +
                        "endpoint=$endpoint",
                )

                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    val retryAfter = response.header("Retry-After")
                    if (diagnosticFirst429Request == 0L) {
                        diagnosticFirst429Request = requestNumber
                        Log.d(
                            RATE_LIMIT_TAG,
                            "Primer 429: request #$requestNumber | " +
                                "tiempo transcurrido=${elapsedSinceFirstRequest / 1_000_000_000.0}s | " +
                                "rate=${"%.2f".format(Locale.ROOT, requestsPerSecond)} req/s " +
                                "(${"%.1f".format(Locale.ROOT, requestsPerMinute)} req/min) | " +
                                "endpoint=$endpoint | " +
                                "Retry-After=${retryAfter ?: "ausente"}",
                        )
                        appendDiagnosticLog(
                            "Primer 429: request #$requestNumber | " +
                                "tiempo transcurrido=${elapsedSinceFirstRequest / 1_000_000_000.0}s | " +
                                "rate=${"%.2f".format(Locale.ROOT, requestsPerSecond)} req/s " +
                                "(${"%.1f".format(Locale.ROOT, requestsPerMinute)} req/min) | " +
                                "endpoint=$endpoint | " +
                                "Retry-After=${retryAfter ?: "ausente"}",
                        )
                    } else {
                        Log.d(
                            RATE_LIMIT_TAG,
                            "429 adicional: request #$requestNumber | endpoint=$endpoint | " +
                                "Retry-After=${retryAfter ?: "ausente"}",
                        )
                        appendDiagnosticLog(
                            "429 adicional: request #$requestNumber | endpoint=$endpoint | " +
                                "Retry-After=${retryAfter ?: "ausente"}",
                        )
                    }
                }
            }

            response
        }
        .rateLimit(1, 3.seconds) { it.host == baseUrlHost }
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = RATE_LIMIT_DIAGNOSTIC
            title = "Diagnóstico de rate limit HTTP"
            summary = "Registra solicitudes, concurrencia, tiempos y respuestas 403/429"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                resetRateLimitDiagnostics()
                true
            }
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            title = "Exportar diagnóstico de rate limit"
            summary = "Comparte el historial guardado de solicitudes y respuestas"
            setOnPreferenceClickListener {
                isChecked = false
                exportRateLimitDiagnostics(screen)
                true
            }
        }.also(screen::addPreference)
    }

    private fun appendDiagnosticLog(line: String) {
        val currentLog = preferences.getString(RATE_LIMIT_LOG, "").orEmpty()
        val updatedLog = (currentLog + line + "\n").takeLast(MAX_RATE_LIMIT_LOG_LENGTH)
        preferences.edit().putString(RATE_LIMIT_LOG, updatedLog).apply()
    }

    private fun exportRateLimitDiagnostics(screen: PreferenceScreen) {
        val log = preferences.getString(RATE_LIMIT_LOG, "").orEmpty()
        val exportIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mangas No Sekai - diagnóstico de rate limit")
            putExtra(Intent.EXTRA_TEXT, log.ifEmpty { "No hay solicitudes registradas." })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        screen.context.startActivity(Intent.createChooser(exportIntent, "Exportar diagnóstico"))
    }

    private fun resetRateLimitDiagnostics() {
        synchronized(diagnosticLock) {
            diagnosticRequestCount = 0L
            diagnosticFirstRequestAt = 0L
            diagnosticLastRequestAt = 0L
            diagnosticFirst429Request = 0L
            diagnosticActiveRequests = 0
            diagnosticMaxConcurrentRequests = 0
            preferences.edit().remove(RATE_LIMIT_LOG).apply()
        }
    }

    override val useNewChapterEndpoint = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/biblioteca/${searchPage(page)}?m_orderby=views", headers)

    override fun popularMangaSelector() = "div.page-listing-item > div.row > div"

    override fun popularMangaNextPageSelector() = "a.next.page-numbers"

    override val popularMangaUrlSelector = "a[href]"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            selectFirst(popularMangaUrlSelector)!!.let {
                manga.setUrlWithoutDomain(it.attr("abs:href"))
            }

            selectFirst("figcaption")!!.let {
                manga.title = it.text()
            }

            selectFirst("img")?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
        }

        return manga
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/biblioteca/${searchPage(page)}?m_orderby=latest", headers)

    override fun searchMangaNextPageSelector() = "nav.navigation a.next"

    override val mangaDetailsSelectorTitle = "div.thumble-container p.titleMangaSingle"
    override val mangaDetailsSelectorThumbnail = "div.thumble-container img.img-responsive"
    override val mangaDetailsSelectorDescription = "section#section-sinopsis > p"
    override val mangaDetailsSelectorStatus = "section#section-sinopsis div.d-flex:has(div:contains(Estado)) p"
    override val mangaDetailsSelectorAuthor = "section#section-sinopsis div.d-flex:has(div:contains(Autor)) p a"
    override val mangaDetailsSelectorGenre = "section#section-sinopsis div.d-flex:has(div:contains(Generos)) p a"
    override val altNameSelector = "section#section-sinopsis div.d-flex:has(div:contains(Otros nombres)) p"
    override val altName = "Otros nombres: "

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        with(document) {
            selectFirst(mangaDetailsSelectorTitle)?.let {
                manga.title = it.ownText()
            }
            select(mangaDetailsSelectorAuthor).joinToString { it.text() }.let {
                manga.author = it
            }
            select(mangaDetailsSelectorDescription).let {
                manga.description = it.text()
            }
            select(mangaDetailsSelectorThumbnail).first()?.let {
                manga.thumbnail_url = imageFromElement(it)
            }
            selectFirst(mangaDetailsSelectorStatus)?.ownText()?.let {
                manga.status = when (it) {
                    in completedStatusList -> SManga.COMPLETED
                    in ongoingStatusList -> SManga.ONGOING
                    in hiatusStatusList -> SManga.ON_HIATUS
                    in canceledStatusList -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
            val genres = select(mangaDetailsSelectorGenre)
                .map { element -> element.text().lowercase(Locale.ROOT) }
                .toMutableSet()

            manga.genre = genres.toList().joinToString(", ") { genre ->
                genre.replaceFirstChar {
                    if (it.isLowerCase()) {
                        it.titlecase(
                            Locale.ROOT,
                        )
                    } else {
                        it.toString()
                    }
                }
            }

            document.select(altNameSelector).firstOrNull()?.ownText()?.let {
                if (it.isBlank().not() && it.notUpdating()) {
                    manga.description = when {
                        manga.description.isNullOrBlank() -> altName + it
                        else -> manga.description + "\n\n$altName" + it
                    }
                }
            }
        }

        return manga
    }

    override val orderByFilterOptions: Map<String, String> = mapOf(
        intl["order_by_filter_relevance"] to "",
        intl["order_by_filter_latest"] to "latest3",
        intl["order_by_filter_az"] to "alphabet",
        intl["order_by_filter_rating"] to "rating",
        intl["order_by_filter_trending"] to "trending",
        intl["order_by_filter_views"] to "views3",
        intl["order_by_filter_new"] to "new-manga",
    )

    private fun altChapterRequest(url: String, mangaId: String, page: Int, objects: List<Pair<String, String>>): Request {
        val form = FormBody.Builder()
            .add("mangaid", mangaId)
            .add("page", page.toString())

        objects.forEach { (key, value) ->
            form.add(key, value)
        }

        return POST(baseUrl + url, xhrHeaders, form.build())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        launchIO { countViews(document) }

        val coreScript = document.selectFirst("script#wp-manga-js")!!.attr("abs:src")
        val coreScriptBody = Deobfuscator.deobfuscateScript(client.newCall(GET(coreScript, headers)).execute().body.string())
            ?: throw Exception("No se pudo deobfuscar el script")

        val regexCapture = ACTION_REGEX.find(coreScriptBody)?.groupValues
        val url = regexCapture?.get(1) ?: throw Exception("No se pudo obtener la url del capítulo")
        val data = regexCapture.getOrNull(2)?.trim() ?: throw Exception("No se pudo obtener la data del capítulo")

        val objects = OBJECTS_REGEX.findAll(data)
            .mapNotNull { matchResult ->
                val key = matchResult.groupValues[1]
                val value = matchResult.groupValues.getOrNull(2)
                if (!value.isNullOrEmpty()) key to value else null
            }.toList()

        val mangaId = document.selectFirst("script#wp-manga-js-extra")?.data()
            ?.let { MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("script#manga_disqus_embed-js-extra")?.data()
                ?.let { ALT_MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: throw Exception("No se pudo obtener el id del manga")

        val chapterList = mutableListOf<SChapter>()
        var page = 1
        do {
            val request = altChapterRequest(url, mangaId, page, objects)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: Intente iniciar sesión en WebView")
            }
            val result = response.parseAs<ChapterWrapper>()
            chapterList.addAll(result.chapters.map { it.toSChapter() })
            page++
        } while (result.hasNextPage())

        return chapterList
    }

    private fun Chapter.toSChapter() = SChapter.create().apply {
        name = this@toSChapter.name
        val cleanDate = Jsoup.parseBodyFragment(this@toSChapter.date).wholeText()
        date_upload = parseChapterDate(cleanDate)
        setUrlWithoutDomain(this@toSChapter.url.removeSuffix("/"))
    }

    override fun pageListRequest(chapter: SChapter): Request = super.pageListRequest(chapter.apply { url = "$url/" })

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val RATE_LIMIT_DIAGNOSTIC = "rateLimitDiagnostic"
        private const val RATE_LIMIT_LOG = "rateLimitDiagnosticLog"
        private const val RATE_LIMIT_TAG = "MangasNoSekaiRL"
        private const val MAX_RATE_LIMIT_LOG_LENGTH = 256 * 1024

        val ACTION_REGEX = """function\s+.*?[\s\S]*?\.ajax;?[\s\S]*?(?:'?url'?:\s*'([^']*)')(?:[\s\S]*?'?data'?:\s*\{([^}]*)\})?""".toRegex()
        val OBJECTS_REGEX = """\s*'?(\w+)'?\s*:\s*(?:(?:'([^']*)'|([^,\r\n]+))\s*,?\s*)""".toRegex()
        val MANGA_ID_REGEX = """\"manga_id"\s*:\s*"(.*)\"""".toRegex()
        val ALT_MANGA_ID_REGEX = """\"postId"\s*:\s*"(.*)\"""".toRegex()
    }
}
