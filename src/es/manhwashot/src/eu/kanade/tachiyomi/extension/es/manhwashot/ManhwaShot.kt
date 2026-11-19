package eu.kanade.tachiyomi.extension.es.manhwashot

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
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document

@Source
abstract class ManhwaShot : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(::contentLengthInterceptor)
    }

    private fun contentLengthInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        val contentType = body.contentType()?.toString() ?: ""

        // Si es una imagen o si el contentLength es desconocido (-1), bufferizamos el cuerpo
        // para proporcionar un Content-Length explícito y evitar el bug de byteCount < 0: -1 en KumoYomi.
        if (body.contentLength() < 0 || contentType.startsWith("image/")) {
            val bytes = body.bytes()
            val newBody = bytes.toResponseBody(body.contentType())
            return response.newBuilder()
                .body(newBody)
                .build()
        }
        return response
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/ranking/", Fetch.DOCUMENT).asJsoup()
        // The page holds the week, month and all time rankings, in this order
        val mangas = document.select("div[role=tabpanel]").last()!!.select("a.trx-row").map { row ->
            SManga.create().apply {
                setUrlWithoutDomain(row.absUrl("href"))
                title = row.selectFirst(".trx-row-name")!!.text()
                thumbnail_url = row.selectFirst("img")?.absUrl("src")?.toFullSizeCover()
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int) = getMangaList("$baseUrl/ultimas-actualizaciones/".toHttpUrl(), page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selected
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.checked
        val url = "$baseUrl/explorar/".toHttpUrl().newBuilder().apply {
            type?.let(::addPathSegment)
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            genres?.let { addQueryParameter("genero", it.joinToString(",")) }
            status?.let { addQueryParameter("estado", it) }
            // Without a type, text or filter, the catalogue only shows part of the series
            if (type == null && query.isBlank() && genres == null && status == null) addQueryParameter("ver", "todos")
        }.build()
        return getMangaList(url, page)
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (!url.isSite()) return MangasPage(emptyList(), false)
        return when (url.pathSegments[0]) {
            "genero" -> getMangaList(url, page)
            // The novel tab lists text novels
            "explorar" -> if ("novela" in url.pathSegments) MangasPage(emptyList(), false) else getMangaList(url, page)
            else -> super.getMangasByUrl(url, page)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.getOrNull(1)
        if (!url.isSite() || url.pathSegments[0] != "manga" || slug.isNullOrEmpty()) return null
        val manga = SManga.create().apply { this.url = "/manga/$slug/" }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(document.toSManga(manga.url), document.toChapters())
    }

    private fun Document.toSManga(path: String) = SManga.create().apply {
        url = path
        title = selectFirst("h1.series-title")!!.text()
        thumbnail_url = selectFirst(".series-cover img")?.absUrl("src")?.toFullSizeCover()
        genre = select(".series-genres a.genre-tag").joinToString { it.text() }.ifEmpty { null }
        status = when (selectFirst(".series-badges a[href*=estado=]")?.absUrl("href")?.toHttpUrl()?.queryParameter("estado")) {
            "on-going" -> SManga.ONGOING
            "end" -> SManga.COMPLETED
            "on-hold" -> SManga.ON_HIATUS
            "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        val votes = selectFirst(".rate-meta")?.ownText()?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        description = MarkdownDescription.Builder()
            .rating(selectFirst(".rate-meta b")?.text()?.toDoubleOrNull()?.takeIf { votes > 0 }, max = 5, votes = votes)
            // The other paragraphs are the same text for every series
            .description(selectFirst(".series-desc:not([style])"))
            .altTitles(selectFirst(".series-alt-titles")?.ownText()?.split(" · ").orEmpty(), "Títulos alternativos")
            .build()
    }

    private fun Document.toChapters(): List<SChapter> {
        val rows = select(".chapters-grid a.ch-row")
        if (rows.isEmpty()) return emptyList()
        // The page shows relative dates only: the chapter list's data has the exact ones, and the lock levels
        val data = extractNextJs<ChapterListDto>()
        return rows.map { row ->
            val href = row.absUrl("href")
            val number = href.trimEnd('/').substringAfterLast("capitulo-")
            val title = row.selectFirst(".ch-num")?.ownText() ?: "Capítulo $number"
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = when (data?.levelOf(number)) {
                    null -> title
                    "vip" -> "🔒 $title (VIP)"
                    "pago" -> "🔒 $title (VIP de pago)"
                    else -> "🔒 $title"
                }
                chapter_number = number.toFloatOrNull() ?: -1f
                date_upload = data?.dateOf(number) ?: 0L
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()

        val images = document.select("img[src*=/img/WP-manga/data/], img[src*=img.manhwashot.lat/img/]")
            .mapNotNull { img ->
                val src = img.attr("abs:src")
                if (src.contains("/img/WP-manga/data/") || src.contains("img.manhwashot.lat")) {
                    src.substringBefore("?").ifEmpty { src }
                } else {
                    null
                }
            }
            .distinct()

        if (images.isNotEmpty()) {
            return images.mapIndexed { index, url -> Page(index, url = url, imageUrl = url) }
        }

        val reader = runCatching {
            client.get(getChapterUrl(chapter), rscHeaders, Fetch.XHR).extractNextJs<ReaderDto>()
        }.getOrNull()

        if (reader != null) {
            if (reader.locked) return emptyList()
            return reader.toPages()
        }

        return emptyList()
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/explorar/", Fetch.DOCUMENT).asJsoup()
        .select("a.exp-genre[href*=genero=]")
        .map { GenreDto(it.text(), it.absUrl("href").toHttpUrl().queryParameter("genero")!!) }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?) = FilterList(
        listOfNotNull(
            TypeFilter(),
            StatusFilter(),
            data?.let { GenreFilter(it.parseAs()) },
        ),
    )

    private suspend fun getMangaList(url: HttpUrl, page: Int): MangasPage {
        val pageUrl = baseUrl.toHttpUrl().newBuilder().apply {
            url.pathSegments.takeWhile { it != "page" }.filter { it.isNotEmpty() }.forEach(::addPathSegment)
            if (page > 1) addPathSegments("page/$page")
            addPathSegment("")
            encodedQuery(url.encodedQuery)
        }.build()
        val document = client.get(pageUrl, Fetch.DOCUMENT).asJsoup()
        val mangas = document.select(".series-grid .s-card").map { card ->
            SManga.create().apply {
                // A card is either the link itself or holds it in its title
                setUrlWithoutDomain((card.selectFirst("a.s-card-title") ?: card).absUrl("href"))
                title = card.selectFirst(".s-card-title")!!.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")?.toFullSizeCover()
            }
        }
        return MangasPage(mangas, document.selectFirst(".pager a.pager-btn:contains(Siguiente)") != null)
    }

    private val rscHeaders get() = headersBuilder().set("RSC", "1").build()

    private fun HttpUrl.isSite() = host.removePrefix("www.") == baseUrl.toHttpUrl().host

    // Lists ask for resized covers: the full size in webp, as on the series page (some originals are huge animated GIFs)
    private fun String.toFullSizeCover() = toHttpUrl().newBuilder()
        .removeAllQueryParameters("w")
        .setQueryParameter("f", "webp")
        .build()
        .toString()
}
