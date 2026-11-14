package eu.kanade.tachiyomi.extension.es.omniscan

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
import keiyoushi.utils.intOrNull
import keiyoushi.utils.longOrNull
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Omniscan : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(::hcdnChallengeInterceptor)

    // Hostinger CDN serves a 403 page that fetches /hcdn-cgi/jschallenge (`const cjs = '...'`)
    // and POSTs `challenge=<sha256(cjs)>` to /hcdn-cgi/jschallenge-validate to obtain the `hcdn` cookie.
    private fun hcdnChallengeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403 || !response.header("server").equals("hcdn", ignoreCase = true)) {
            return response
        }

        val urlStr = request.url.toString()
        if (urlStr.contains("/hcdn-cgi/")) {
            return response
        }

        response.close()

        val jsReq = Request.Builder()
            .url("$baseUrl/hcdn-cgi/jschallenge")
            .header("Referer", urlStr)
            .build()
        val jsBody = chain.proceed(jsReq).use { it.body.string() }
        val cjs = CJS_REGEX.find(jsBody)?.groupValues?.get(1) ?: return chain.proceed(request)

        val hash = sha256Hex(cjs)
        val validateReq = Request.Builder()
            .url("$baseUrl/hcdn-cgi/jschallenge-validate")
            .header("Referer", urlStr)
            .post(FormBody.Builder().add("challenge", hash).build())
            .build()

        val setCookie = chain.proceed(validateReq).use { valRes ->
            valRes.headers("Set-Cookie").mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
                .joinToString("; ")
        }

        val retryBuilder = request.newBuilder()
        if (setCookie.isNotEmpty()) {
            val existing = request.header("Cookie")
            retryBuilder.header("Cookie", if (existing.isNullOrBlank()) setCookie else "$existing; $setCookie")
        }
        return chain.proceed(retryBuilder.build())
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return catalogParse(client.get("$baseUrl/biblioteca", Fetch.DOCUMENT).asJsoup())
            .sortedByDescending { it.second }
            .map { it.first }
            .let { MangasPage(it, false) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return catalogParse(client.get("$baseUrl/biblioteca", Fetch.DOCUMENT).asJsoup())
            .sortedByDescending { it.first.memo["ts"]?.longOrNull ?: 0L }
            .map { it.first }
            .let { MangasPage(it, false) }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue.orEmpty()
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue.orEmpty()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "recent"
        val mangas = catalogParse(client.get("$baseUrl/biblioteca", Fetch.DOCUMENT).asJsoup())
            .filter { (manga, _) ->
                val mangaStatus = manga.memo["status"]?.stringOrNull.orEmpty()
                val mangaType = manga.memo["type"]?.stringOrNull.orEmpty()
                manga.title.contains(query, ignoreCase = true) &&
                    (status.isEmpty() || mangaStatus.contains(status, ignoreCase = true)) &&
                    (type.isEmpty() || mangaType.equals(type, ignoreCase = true))
            }
            .let { entries ->
                when (sort) {
                    "visits" -> entries.sortedByDescending { it.second }
                    "caps" -> entries.sortedByDescending { it.first.memo["caps"]?.intOrNull ?: 0 }
                    "az" -> entries.sortedBy { it.first.title.lowercase(Locale.ROOT) }
                    "za" -> entries.sortedByDescending { it.first.title.lowercase(Locale.ROOT) }
                    else -> entries.sortedByDescending { it.first.memo["ts"]?.longOrNull ?: 0L }
                }
            }
            .map { it.first }

        return MangasPage(mangas, false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga.url),
            chapters = parseChapterList(document),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
        return document.select("#readerImages img[data-file], img[data-file]").mapIndexed { index, image ->
            val imgUrl = image.absUrl("src").ifBlank {
                "$baseUrl/${image.attr("data-file").trimStart('/')}"
            }
            Page(index, url = chapterUrl, imageUrl = imgUrl)
        }
    }

    override fun getImageRequest(page: Page): Request = super.getImageRequest(page).newBuilder()
        .header("Referer", page.url.ifEmpty { "$baseUrl/" })
        .build()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        StatusFilter(),
        TypeFilter(),
        SortFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.getOrNull(0) != "obra") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return fetchMangaUpdate(SManga.create().apply { this.url = slug }, emptyList(), true, false).manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra/${manga.url}"

    private fun catalogParse(document: Document): List<Pair<SManga, Int>> = document.select("article.lbx-card, article.library-series-card").mapNotNull { card ->
        val link = card.selectFirst("a[href^=/obra/]") ?: return@mapNotNull null
        val title = card.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val image = card.selectFirst("img")?.absUrl("src")
        val status = card.attr("data-estado").ifBlank { card.attr("data-status") }
        val type = card.attr("data-tipo")
        val ts = card.attr("data-ts").toLongOrNull() ?: 0L
        val caps = card.attr("data-caps").toIntOrNull() ?: 0
        val visits = card.attr("data-visits").filter { it.isDigit() }.toIntOrNull() ?: 0
        Pair(
            SManga.create().apply {
                url = link.attr("href").substringAfter("/obra/").trim('/')
                this.title = title
                thumbnail_url = image
                memo = buildJsonObject {
                    put("status", status)
                    put("type", type)
                    put("ts", ts)
                    put("caps", caps)
                }
            },
            visits,
        )
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl
        title = document.selectFirst("h1.fx-title, h1.title, h1")?.text()?.trim()
            ?: throw Exception("Title not found")
        thumbnail_url = document.selectFirst("img.cover, .fx-cover img")?.absUrl("src")

        val syn = document.selectFirst(".fx-syn-text, .fx-lead, .synopsis")?.text()?.trim()
        val alt = document.selectFirst(".fx-alt")?.text()?.trim()?.takeIf { it.isNotBlank() }
        description = listOfNotNull(
            syn?.takeIf { it.isNotBlank() },
            alt?.let { "Título alternativo: $it" },
        ).joinToString("\n\n").ifBlank { null }

        val genres = buildList {
            document.selectFirst(".ix-chip")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            document.select(".fx-genres span, .genre-chip").forEach { el ->
                el.text().trim().takeIf { it.isNotBlank() }?.let(::add)
            }
            document.select("dl.fx-dl > div").firstOrNull {
                it.selectFirst("dt")?.text()?.contains("Género", ignoreCase = true) == true
            }?.selectFirst("dd")?.text()?.split(",")?.forEach { g ->
                g.trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
        genre = genres.joinToString().ifBlank { null }

        author = document.select("dl.fx-dl > div").firstOrNull {
            val dt = it.selectFirst("dt")?.text().orEmpty()
            dt.contains("Autor", ignoreCase = true) || dt.contains("Scan", ignoreCase = true)
        }?.selectFirst("dd")?.text()?.trim()
            ?: document.select(".meta-chip").firstOrNull { it.text().startsWith("Autor") }
                ?.text()?.substringAfter("Autor")?.trim()

        val statusText = (
            document.select("dl.fx-dl > div").firstOrNull {
                it.selectFirst("dt")?.text()?.contains("Estado", ignoreCase = true) == true
            }?.selectFirst("dd")?.text()
                ?: document.selectFirst(".meta-chip.status")?.text()
            )?.lowercase(Locale.ROOT).orEmpty()
        this.status = when {
            "emisión" in statusText || "progreso" in statusText -> SManga.ONGOING
            "finaliz" in statusText -> SManga.COMPLETED
            "paus" in statusText -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> = document.select(".chapter[data-number][data-chapter-id][data-series-slug], #chapterList a.chapter").mapNotNull { chapter ->
        val number = chapter.attr("data-number").ifBlank {
            chapter.selectFirst(".chapter-number b, .chapter-number")?.text().orEmpty()
        }.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val displayNumber = chapter.selectFirst(".chapter-number b")?.text()?.takeIf(String::isNotBlank)
            ?: number.removeSuffix(".00")
        SChapter.create().apply {
            url = chapter.attr("href")
            val rawTitle = chapter.selectFirst(".chapter-copy strong")?.text()?.trim()
                ?: chapter.attr("data-title").trim()
            name = when {
                rawTitle.startsWith("Capítulo", ignoreCase = true) -> rawTitle
                rawTitle.isNotBlank() && !rawTitle.equals("continuar historia", ignoreCase = true) ->
                    "Capítulo $displayNumber - $rawTitle"
                else -> "Capítulo $displayNumber"
            }
            chapter_number = number.toFloatOrNull() ?: -1f
            val dateEl = chapter.selectFirst(".chapter-date")
            val dateStr = dateEl?.attr("title")?.takeIf(String::isNotBlank) ?: dateEl?.text()
            date_upload = dateFormat.tryParseDate(dateStr)
        }
    }

    private fun sha256Hex(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val dateFormat = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT)

    companion object {
        private val CJS_REGEX = Regex("""cjs\s*=\s*['"]([^'"]+)['"]""")
    }
}
