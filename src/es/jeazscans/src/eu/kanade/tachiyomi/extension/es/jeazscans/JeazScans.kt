package eu.kanade.tachiyomi.extension.es.jeazscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.util.Calendar

class JeazScans : HttpSource() {

    override val name = "Jeaz Scans"

    override val id = 6060467445851256728L

    override val baseUrl = "https://lectorhub.j5z.xyz"

    override val lang = "es"

    override val supportsLatest = true

    private val readerBaseUrl = "https://jeazscans.xyz"

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(1)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ── Popular ──────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/directorio.php?orden=vistas&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseDirectoryPage(response)

    // ── Latest Updates ───────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/directorio.php?orden=actualizado&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseDirectoryPage(response)

    // ── Search (JSON API) ────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/ajax_search.php?q=$query", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val json = Json { ignoreUnknownKeys = true }
        val items = json.decodeFromString<List<SearchResponseItem>>(response.body.string())
        val mangas = items.mapNotNull { it.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    // ── Manga Details ────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.blood-title")?.text().orEmpty()

            thumbnail_url = doc.selectFirst(".cultivation-panel img[src*=\"/uploads/mangas/\"]")
                ?.attr("abs:src")

            // Site doesn't display author/artist
            author = ""

            genre = doc.select("a[href*=\"?genero=\"]").joinToString { it.text() }

            description = doc.selectFirst("h3:containsOwn(SINOPSIS)")?.parent()?.ownText()

            val statusText = doc.selectFirst("span.status-badge")?.text()?.uppercase().orEmpty()
            status = when {
                "CULTIVO" in statusText || "EMISIÓN" in statusText -> SManga.ONGOING
                "COMPLETADO" in statusText || "FINALIZADO" in statusText -> SManga.COMPLETED
                "PAUSA" in statusText || "MEDITACIÓN" in statusText -> SManga.ON_HIATUS
                "HIATUS" in statusText -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ── Chapter List ─────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return doc.select("a.chapter-item").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                val chapterNum = element.attr("data-chapter-number")
                name = element.selectFirst(".chapter-title")?.text()
                    ?: "Capítulo $chapterNum"
                date_upload = parseRelativeDate(
                    element.select("span.text-\\[10px\\]").lastOrNull()?.text(),
                )
            }
        }.reversed() // Site lists newest first; Tachiyomi expects oldest first
    }

    // ── Page List (Reader API on different domain) ───────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url = "/leer/{slug}/capitulo-{numero}"
        val path = chapter.url.removePrefix("/leer/")
        val parts = path.split("/capitulo-")
        val slug = parts.getOrElse(0) { "" }
        val cap = parts.getOrElse(1) { "" }
        return GET("$readerBaseUrl/api_lector.php?slug=$slug&cap=$cap", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = Json { ignoreUnknownKeys = true }
        val apiResponse = json.decodeFromString<ApiLectorResponse>(response.body.string())
        return apiResponse.paginas.mapIndexed { index, pageData ->
            Page(index, imageUrl = pageData.decodeImageUrl())
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ── Helpers ──────────────────────────────────────────────────────

    private fun parseDirectoryPage(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("a.cultivation-card").map { card ->
            SManga.create().apply {
                setUrlWithoutDomain(card.attr("abs:href"))
                title = card.selectFirst("h3")?.text().orEmpty()
                thumbnail_url = card.selectFirst("img.manual-img")?.attr("abs:src")
            }
        }
        val hasNextPage = doc.selectFirst("a.sys-page-link:has(i.ph-caret-right)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val number = text.replace(Regex("""\D"""), "").toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        when {
            "segundo" in text -> cal.add(Calendar.SECOND, -number)
            "minuto" in text -> cal.add(Calendar.MINUTE, -number)
            "hr" in text || "hora" in text -> cal.add(Calendar.HOUR, -number)
            "día" in text || "dia" in text -> cal.add(Calendar.DAY_OF_YEAR, -number)
            "semana" in text -> cal.add(Calendar.WEEK_OF_YEAR, -number)
            "mes" in text -> cal.add(Calendar.MONTH, -number)
            "año" in text -> cal.add(Calendar.YEAR, -number)
        }
        return cal.timeInMillis
    }
}
