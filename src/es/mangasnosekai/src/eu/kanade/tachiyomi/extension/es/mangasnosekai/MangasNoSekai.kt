package eu.kanade.tachiyomi.extension.es.mangasnosekai

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.synchrony.Deobfuscator
import kotlinx.serialization.decodeFromString
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class MangasNoSekai :
    Madara(
        "Mangas No Sekai",
        "https://mangasnosekai.com",
        "es",
        SimpleDateFormat("MMMM dd, yyyy", Locale("es")),
    ) {

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val client = super.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 2, 1)
        .build()

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
            // Skip keys already set explicitly — the script's defaults would
            // override our values on servers that read the first occurrence.
            if (key !in listOf("page", "mangaid")) {
                form.add(key, value)
            }
        }

        return POST(baseUrl + url, xhrHeaders, form.build())
    }

    private val altChapterListSelector = "div.contenedor-capitulo-miniatura"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        launchIO { countViews(document) }

        val mangaSlug = response.request.url.toString().substringAfter(baseUrl).removeSuffix("/")

        // Intentar obtener capítulos vía AJAX/JSON (ruta principal — la que usa el sitio real)
        val ajaxChapters = tryFetchAjaxChapters(document, mangaSlug)
        if (ajaxChapters != null) return ajaxChapters

        // Fallback: capítulos renderizados directamente en el HTML
        val directChapters = document.select(altChapterListSelector)
        if (directChapters.isNotEmpty()) {
            return directChapters.map(::altChapterFromElement)
        }

        throw Exception("No se pudieron obtener los capítulos")
    }

    /**
     * Intenta obtener capítulos vía el endpoint AJAX/JSON del sitio.
     * Este es el método que usa el sitio real para cargar TODOS los capítulos paginados.
     */
    private fun tryFetchAjaxChapters(document: Document, mangaSlug: String): List<SChapter>? {
        val coreScript = document.selectFirst("script#wp-manga-js")?.attr("abs:src")
            ?: return null

        val coreScriptBody = Deobfuscator.deobfuscateScript(client.newCall(GET(coreScript, headers)).execute().body.string())
            ?: return null

        val regexCapture = ACTION_REGEX.find(coreScriptBody)?.groupValues
            ?: return null
        val url = regexCapture.getOrNull(1) ?: return null
        val data = regexCapture.getOrNull(2)?.trim() ?: return null

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
            ?: return null

        // Página 1
        val firstRequest = altChapterRequest(url, mangaId, 1, objects)
        val firstResponse = client.newCall(firstRequest).execute()
        if (!firstResponse.isSuccessful) return null
        val firstBody = firstResponse.body.string()

        if (!firstBody.startsWith("{")) return null

        // Formato paginado nuevo (getcaps7)
        val paginatedResult = try {
            json.decodeFromString<ChapterListResponse>(firstBody)
        } catch (_: Exception) {
            null
        }

        if (paginatedResult != null && paginatedResult.chaptersToDisplay.isNotEmpty()) {
            val allChapters = paginatedResult.chaptersToDisplay.map { it.toSChapter() }.toMutableList()

            // Fetchear TODAS las páginas restantes
            for (p in 2..paginatedResult.totalPages) {
                val nextRequest = altChapterRequest(url, mangaId, p, objects)
                val nextResponse = client.newCall(nextRequest).execute()
                if (!nextResponse.isSuccessful) break
                val nextBody = nextResponse.body.string()
                val nextResult = try {
                    json.decodeFromString<ChapterListResponse>(nextBody)
                } catch (_: Exception) {
                    null
                }
                val nextChapters = nextResult?.chaptersToDisplay
                    ?.map { it.toSChapter() }
                    ?: break
                allChapters.addAll(nextChapters)
            }
            return allChapters
        }

        // Fallback: formato JSON antiguo (PayloadDto)
        return chaptersFromJson(firstBody, mangaSlug)
    }

    private fun altChapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
        name = element.selectFirst("div.text-sm")?.text()?.trim() ?: ""
        date_upload = element.selectFirst("time")?.text()?.let {
            parseChapterDate(it)
        } ?: 0
    }

    private fun chaptersFromJson(jsonString: String, mangaSlug: String): List<SChapter>? {
        return try {
            val result = json.decodeFromString<PayloadDto>(jsonString)
            if (result.manga.isEmpty()) return null
            result.manga.first().chapters.map { it.toSChapter(mangaSlug) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        val ACTION_REGEX = MangasNoSekaiPatterns.ACTION_REGEX
        val OBJECTS_REGEX = MangasNoSekaiPatterns.OBJECTS_REGEX
        val MANGA_ID_REGEX = MangasNoSekaiPatterns.MANGA_ID_REGEX
        val ALT_MANGA_ID_REGEX = MangasNoSekaiPatterns.ALT_MANGA_ID_REGEX
    }
}

/**
 * Pure Kotlin object holding regex patterns. Extracted for testability
 * without requiring Android dependencies.
 */
object MangasNoSekaiPatterns {
    val ACTION_REGEX = """function\s+.*?[\s\S]*?\.ajax;?[\s\S]*?(?:'?url'?:\s*'([^']*)')(?:[\s\S]*?'?data'?:\s*\{([^}]*)\})?""".toRegex()
    val OBJECTS_REGEX = """\s*'?(\w+)'?\s*:\s*(?:(?:'([^']*)'|([^,\r\n]+))\s*,?\s*)""".toRegex()
    val MANGA_ID_REGEX = """"manga_id"\s*:\s*"([^"]+)"""".toRegex()
    val ALT_MANGA_ID_REGEX = """"postId"\s*:\s*"([^"]+)"""".toRegex()
}
