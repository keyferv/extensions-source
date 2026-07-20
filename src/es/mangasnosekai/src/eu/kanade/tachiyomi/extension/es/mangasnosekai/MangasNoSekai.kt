package eu.kanade.tachiyomi.extension.es.mangasnosekai

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
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
            selectFirst("figcaption")?.let {
                manga.title = it.text()
            }

            selectFirst(popularMangaUrlSelector)?.let {
                manga.setUrlWithoutDomain(it.attr("abs:href"))
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

    private val altChapterListSelector = "div.contenedor-capitulo-miniatura"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        launchIO { countViews(document) }

        val mangaSlug = response.request.url.toString().substringAfter(baseUrl).removeSuffix("/")

        // Capítulos renderizados directamente en el HTML (página 1)
        val directChapters = document.select(altChapterListSelector).map(::altChapterFromElement)

        // Intentar obtener el resto de capítulos vía AJAX/JSON paginado
        val ajaxChapters = tryFetchAjaxChapters(document, mangaSlug, directChapters)
        if (ajaxChapters != null) return ajaxChapters

        if (directChapters.isNotEmpty()) {
            return directChapters
        }

        throw Exception("No se pudieron obtener los capítulos")
    }

    /**
     * Intenta obtener capítulos vía el endpoint AJAX/JSON del sitio.
     * Este es el método que usa el sitio real para cargar TODOS los capítulos paginados.
     */
    private fun tryFetchAjaxChapters(
        document: Document,
        mangaSlug: String,
        directChapters: List<SChapter>,
    ): List<SChapter>? {
        // 1. Obtener manga ID (ya funciona bien)
        val mangaId = document.selectFirst("script#wp-manga-js-extra")?.data()
            ?.let { MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("script#manga_disqus_embed-js-extra")?.data()
                ?.let { ALT_MANGA_ID_REGEX.find(it)?.groupValues?.get(1) }
            ?: return null

        // 2. Buscar el secret en script.js de madara-core
        val secret = extractSecret(document) ?: return null

        // 3. Llamar directo al endpoint conocido
        val endpoint = "$baseUrl/wp-json/muslitos/v1/getcaps7"

        fun buildForm(page: Int) = FormBody.Builder()
            .add("action", "muslitos_anti_hack")
            .add("mangaid", mangaId)
            .add("page", page.toString())
            .add("secret", secret)
            .build()

        // Página 1
        val firstBody = try {
            client.newCall(POST(endpoint, xhrHeaders, buildForm(1))).execute().body.string()
        } catch (_: Exception) {
            return null
        }

        val firstResult = try {
            json.decodeFromString<ChapterListResponse>(firstBody)
        } catch (_: Exception) {
            return null
        }

        if (firstResult.chaptersToDisplay.isEmpty()) return null

        val allChapters = firstResult.chaptersToDisplay.map { it.toSChapter() }.toMutableList()

        // Páginas restantes
        for (page in 2..firstResult.totalPages) {
            val body = try {
                client.newCall(POST(endpoint, xhrHeaders, buildForm(page))).execute().body.string()
            } catch (_: Exception) {
                break
            }

            val result = try {
                json.decodeFromString<ChapterListResponse>(body)
            } catch (_: Exception) {
                break
            }

            allChapters.addAll(result.chaptersToDisplay.map { it.toSChapter() })
        }

        return allChapters.takeIf { it.isNotEmpty() }
    }

    // Busca el secret en el script.js externo de madara-core
    private fun extractSecret(document: Document): String? {
        val scriptUrl = document
            .select("script[src*='madara-core'][src*='script.js']")
            .firstOrNull()
            ?.attr("abs:src") ?: return null

        val body = try {
            client.newCall(GET(scriptUrl, headers)).execute().body.string()
        } catch (_: Exception) {
            return null
        }

        return SECRET_REGEX.find(body)?.groupValues?.get(1)
    }

    private fun altChapterFromElement(element: Element) = SChapter.create().apply {
        name = element.selectFirst("div.text-sm")?.text()?.trim() ?: ""
        element.selectFirst("a")?.let {
            setUrlWithoutDomain(it.attr("abs:href"))
        }
        date_upload = element.selectFirst("time")?.text()?.let {
            parseChapterDate(it)
        } ?: 0
    }

    companion object {
        val MANGA_ID_REGEX = MangasNoSekaiPatterns.MANGA_ID_REGEX
        val ALT_MANGA_ID_REGEX = MangasNoSekaiPatterns.ALT_MANGA_ID_REGEX
        val SECRET_REGEX = MangasNoSekaiPatterns.SECRET_REGEX
    }
}

/**
 * Pure Kotlin object holding regex patterns. Extracted for testability
 * without requiring Android dependencies.
 */
object MangasNoSekaiPatterns {
    // Matches AJAX call: finds url (single/double/unquoted) and data object
    val ACTION_REGEX = """\.ajax\s*\([\s\S]*?['"]?url['"]?\s*:\s*(?:"([^"]*)"|'([^']*)'|`([^`]*)`|([^,;\s}]+))[\s\S]*?['"]?data['"]?\s*:\s*\{([^}]*)\}""".toRegex()

    // Matches key-value pairs in JS object: key: value (handles quotes and unquoted)
    val OBJECTS_REGEX = """\s*['"]?(\w+)['"]?\s*:\s*(?:"([^"]*)"|'([^']*)'|([^,}\r\n]+))""".toRegex()
    val MANGA_ID_REGEX = """"manga_id"\s*:\s*"([^"]+)"""".toRegex()
    val ALT_MANGA_ID_REGEX = """"postId"\s*:\s*"([^"]+)"""".toRegex()
    val SECRET_REGEX = """secret\s*:\s*['"]([^'"]+)['"]""".toRegex()
}
