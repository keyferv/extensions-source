package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

@Source
abstract class ManhwaShot : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            val baseHost = baseUrl.toHttpUrl().host
            val isPageImage = (request.url.host == baseHost || request.url.host.endsWith(".$baseHost")) &&
                request.url.encodedPath.startsWith(PAGE_IMAGE_PATH_PREFIX)

            if (!isPageImage) {
                return@addInterceptor chain.proceed(request)
            }

            val response = chain.proceed(
                request.newBuilder()
                    .removeHeader("Accept-Encoding")
                    .header("Accept-Encoding", "identity")
                    .build(),
            )
            val body = response.body
            val mediaType = body.contentType()
            val bytes = body.bytes()

            response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .body(bytes.toResponseBody(mediaType))
                .build()
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage = getExplorePage(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getExplorePage(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = exploreUrl(page).toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
        return parseMangaPage(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "manga") return null

        val manga = SManga.create().apply { setUrlWithoutDomain(url.encodedPath) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("img[src]")
            .mapNotNull { it.attr("abs:src").takeIf { url -> url.startsWith(IMAGE_PREFIX) } }
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    private suspend fun getExplorePage(page: Int): MangasPage = parseMangaPage(client.get(exploreUrl(page)).asJsoup())

    private fun parseMangaPage(document: Document): MangasPage {
        val mangas = document.select(".series-grid .s-card").mapNotNull(::parseMangaCard)
        val hasNextPage = document.selectFirst(".pager-btn[href]:matchesOwn(Siguiente)") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseMangaCard(card: Element): SManga? {
        val link = card.selectFirst(".s-card-imglink[href], .s-card-title[href]") ?: return null
        val title = card.selectFirst(".s-card-title")?.text()?.takeIf { it.isNotEmpty() } ?: return null

        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            this.title = title
            thumbnail_url = card.selectFirst(".s-card-img img[src]")?.absUrl("src")
        }
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url
        title = document.selectFirst(".series-title")!!.text()
        thumbnail_url = document.selectFirst(".series-cover img[src]")?.absUrl("src")
        description = document.select(".series-desc")
            .map { it.text() }
            .firstOrNull { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in BOILERPLATE_DESCRIPTIONS }
        genre = document.select(".series-tags a, .series-tags span")
            .map { it.text() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()
            .ifEmpty { null }
        status = parseStatus(document.selectFirst(".badge-pill")?.text())
    }

    private fun parseChapterList(document: Document): List<SChapter> = document
        .select(".chapters-grid a.ch-row[href]")
        .mapNotNull { link ->
            val url = link.absUrl("href")
            val number = chapterNumberRegex.find(url)?.groupValues?.get(1)
                ?: chapterNumberRegex.find(link.selectFirst(".ch-num")?.text().orEmpty())?.groupValues?.get(1)
                ?: genericNumberRegex.find(url)?.groupValues?.get(1)
                ?: genericNumberRegex.find(link.selectFirst(".ch-num")?.text().orEmpty())?.groupValues?.get(1)
                ?: return@mapNotNull null

            SChapter.create().apply {
                this.url = url.removePrefix(baseUrl)
                name = link.selectFirst(".ch-num")?.text()?.ifEmpty { null } ?: "Capítulo $number"
                chapter_number = number.replace(',', '.').toFloatOrNull() ?: 0f
            }
        }
        .sortedByDescending { it.chapter_number }

    private fun parseStatus(text: String?): Int {
        val normalizedStatus = text?.lowercase(Locale.ROOT) ?: return SManga.UNKNOWN
        return when {
            "en emisión" in normalizedStatus -> SManga.ONGOING
            "finalizado" in normalizedStatus || "completado" in normalizedStatus -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    private fun exploreUrl(page: Int): String = if (page > 1) {
        "$baseUrl/explorar/page/$page/"
    } else {
        "$baseUrl/explorar/"
    }

    companion object {
        private const val PAGE_IMAGE_PATH_PREFIX = "/img/WP-manga/data/"
        private const val IMAGE_PREFIX = "https://img.manhwashot.lat/img/WP-manga/data/"
        private val BOILERPLATE_DESCRIPTIONS = setOf("sinopsis", "descripción", "descripcion")
        private val chapterNumberRegex = Regex("(?:cap[ií]tulo|chapter)[^0-9]*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
        private val genericNumberRegex = Regex("([0-9]+(?:[.,][0-9]+)?)")
    }
}
