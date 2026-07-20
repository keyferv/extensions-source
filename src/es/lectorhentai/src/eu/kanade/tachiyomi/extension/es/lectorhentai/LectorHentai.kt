package eu.kanade.tachiyomi.extension.es.lectorhentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

@Source
abstract class LectorHentai : HttpSource() {
    override val name = "LectorHentai"
    override val supportsLatest = true
    override val baseUrl = "https://lectorhentai.com"

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // POPULARES
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/${if (page > 1) "?page=$page" else ""}", headers)

    private fun popularMangaSelector() = "div.bs.styletere"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun popularMangaFromElement(element: Element) = SManga.create().apply {
        element.selectFirst("a[title]")?.let { link ->
            setUrlWithoutDomain(link.attr("href"))
            title = element.selectFirst("div.tt")?.text()?.trim() ?: link.attr("title")
        }
        // Manejar lazy loading: data-original o src
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-original").ifEmpty {
                img.attr("abs:src")
            }
        }?.let { url ->
            // Asegurar que tenga protocolo
            if (url.startsWith("//")) "https:$url" else url
        }
    }

    private fun popularMangaNextPageSelector() = "a.r, a:contains(Siguiente)"

    // RECIENTES
    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // BÚSQUEDA
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val url = query.trim().toHttpUrlOrNull()
        if (url != null && url.host.removePrefix("www.") == baseUrl.toHttpUrl().host) {
            val path = url.encodedPath
            if (path.startsWith("/manga/")) {
                return Observable.just(MangasPage(listOf(createMangaFromPath(path)), false))
            }
        }

        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val path = query.removePrefix(PREFIX_ID_SEARCH).let {
                if (it.startsWith("/")) it else "/manga/$it"
            }
            return Observable.just(MangasPage(listOf(createMangaFromPath(path)), false))
        }

        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/tipo/all".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> {
                    filter.state
                        .filter { it.state }
                        .forEach { url.addQueryParameter("genre[]", it.name) }
                }
                is OrderByFilter -> {
                    val orderValues = arrayOf("latest", "title", "titlereverse", "popular")
                    url.addQueryParameter("order", orderValues[filter.state])
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun createMangaFromPath(path: String) = SManga.create().apply {
        setUrlWithoutDomain(path)
        title = path.trim('/').substringAfterLast('/')
            .replace('-', ' ')
            .replaceFirstChar { it.titlecase() }
    }

    // DETALLES
    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document) = SManga.create().apply {
        document.selectFirst("div.infomanga, div.bigcontent")?.let { info ->
            title = info.selectFirst("h1.entry-title")?.text()
                ?.replace("en Español | Leer Online Gratis", "")
                ?.trim() ?: ""
            thumbnail_url = info.selectFirst("div.thumbook img")?.attr("abs:src")
            artist = info.selectFirst("span.mgen a:contains(Artista), b:contains(Artista) + span.mgen a")?.text()
            genre = info.select("span.mgen a[rel=tag]").joinToString { it.text() }
            description = info.selectFirst("div.synp, div.entry-content")?.text()
        }
    }

    // CAPÍTULOS
    private fun chapterListSelector() = "div.releases a.leer, div.eplister li"

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup()
        .select(chapterListSelector())
        .map { chapterFromElement(it) }

    private fun chapterFromElement(element: Element) = SChapter.create().apply {
        // Si es un botón "Leer Manga" (en la página de descripción)
        if (element.tagName() == "a" && element.hasClass("leer")) {
            setUrlWithoutDomain(element.attr("href"))
            name = "Capítulo Único"
        } else {
            // Si es una lista de capítulos
            element.selectFirst("a")?.let { link ->
                setUrlWithoutDomain(link.attr("href"))
                name = link.selectFirst("div.chapternum")?.text() ?: link.attr("title")
            }
        }
    }

    // PÁGINAS
    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    private fun pageListParse(document: Document): List<Page> {
        val html = document.html()

        // Las imágenes están en un JSON dentro de ts_reader.run()
        // Buscar el array de imágenes en el script
        val imagesRegex = Regex(""""images"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val imagesMatch = imagesRegex.find(html)

        if (imagesMatch != null) {
            val imagesJson = imagesMatch.groupValues[1]

            // Extraer todas las URLs de imágenes del JSON
            val imageUrlRegex = Regex(""""(//[^"]+\.(?:webp|jpg|png|jpeg))"""")
            val imageUrls = imageUrlRegex.findAll(imagesJson)
                .map { it.groupValues[1] }
                .toList()

            if (imageUrls.isNotEmpty()) {
                return imageUrls.mapIndexed { index, url ->
                    val fullUrl = if (url.startsWith("//")) "https:$url" else url
                    Page(index, imageUrl = fullUrl)
                }
            }
        }

        // Fallback: intentar construir URLs basándose en el patrón
        val chapterIdRegex = Regex("/read/(\\d+)/")
        val chapterId = chapterIdRegex.find(document.location())?.groupValues?.get(1)

        if (chapterId != null) {
            val selectPaged = document.selectFirst("select#select-paged, select.ts-select-paged")
            val totalPages = selectPaged?.select("option")?.size ?: 0

            if (totalPages > 0) {
                // Buscar una imagen de ejemplo en el JSON para determinar el formato
                val sampleImgRegex = Regex("""img(\d*)\.giolandscaping\.com/library/$chapterId/(\d+)\.(webp|jpg|png|jpeg)""")
                val sampleMatch = sampleImgRegex.find(html)

                if (sampleMatch != null) {
                    val serverNum = sampleMatch.groupValues[1].ifEmpty { "1" }
                    val paddedExample = sampleMatch.groupValues[2]
                    val extension = sampleMatch.groupValues[3]
                    val format = "%0${paddedExample.length}d"

                    return List(totalPages) { i ->
                        val paddedIndex = String.format(format, i)
                        Page(i, imageUrl = "https://img$serverNum.giolandscaping.com/library/$chapterId/$paddedIndex.$extension")
                    }
                }
            }
        }

        return emptyList()
    }

    // FILTROS
    private class GenreFilter(name: String) : Filter.CheckBox(name)

    private class GenreList(genres: List<GenreFilter>) : Filter.Group<GenreFilter>("Géneros", genres)

    private class OrderByFilter(name: String, orderValues: Array<String>) : Filter.Select<String>(name, orderValues)

    override fun getFilterList() = FilterList(
        GenreList(getGenreList()),
        OrderByFilter("Ordenar por", arrayOf("Últimos Agregados", "A-Z", "Z-A", "Populares")),
    )

    private fun getGenreList() = listOf(
        GenreFilter("Ahegao"),
        GenreFilter("Big Breasts"),
        GenreFilter("BlowJob"),
        GenreFilter("Femdom"),
        GenreFilter("Mature"),
        GenreFilter("Nympho"),
        GenreFilter("Student"),
        GenreFilter("Bukkake"),
        GenreFilter("Forced"),
        GenreFilter("Orgy"),
        GenreFilter("Pregnant"),
        GenreFilter("Public Sex"),
        GenreFilter("Rape"),
        GenreFilter("Anal"),
        GenreFilter("Bondage"),
        GenreFilter("Fetish"),
        GenreFilter("Incest"),
        GenreFilter("Virgin"),
        GenreFilter("Romance"),
        GenreFilter("Vanilla"),
        GenreFilter("Uncensored"),
        GenreFilter("Comedy"),
        GenreFilter("Milf"),
        GenreFilter("Monsters"),
        GenreFilter("Colour"),
        GenreFilter("Furry"),
        GenreFilter("Lolicon"),
        GenreFilter("Small Breast"),
        GenreFilter("Domination"),
        GenreFilter("Parody"),
        GenreFilter("Fantasy"),
        GenreFilter("FootJob"),
        GenreFilter("Harem"),
        GenreFilter("Adultery"),
        GenreFilter("Adventure"),
        GenreFilter("Cheating"),
        GenreFilter("Netorare"),
        GenreFilter("Tsundere"),
        GenreFilter("Toys"),
        GenreFilter("Futanari"),
        GenreFilter("Sport"),
        GenreFilter("Bestiality"),
        GenreFilter("Horror"),
        GenreFilter("Yandere"),
        GenreFilter("Tentacles"),
        GenreFilter("3D"),
        GenreFilter("Shotacon"),
    )

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
    }
}
