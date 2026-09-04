package eu.kanade.tachiyomi.extension.es.lectorhentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class LectorHentai : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Referer", "$baseUrl/")

    override fun okhttp3.OkHttpClient.Builder.configureClient(): okhttp3.OkHttpClient.Builder = rateLimit(2)

    private fun popularMangaSelector() = "div.bs.styletere"

    private fun popularMangaNextPageSelector() = "a.r, a:contains(Siguiente)"

    private fun popularMangaFromElement(element: Element) = SManga.create().apply {
        element.selectFirst("a[title]")?.let { link ->
            setUrlWithoutDomain(link.attr("href"))
            title = element.selectFirst("div.tt")?.text()?.trim() ?: link.attr("title")
        }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-original").ifEmpty {
                img.attr("abs:src")
            }
        }?.let { url ->
            if (url.startsWith("//")) "https:$url" else url
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page > 1) "$baseUrl/?page=$page" else baseUrl
        val document = client.get(url).asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host) return null
        val path = url.encodedPath
        if (path.startsWith("/manga/")) {
            return createMangaFromPath(path)
        }
        return null
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.startsWith(PREFIX_ID_SEARCH)) {
            val path = query.removePrefix(PREFIX_ID_SEARCH).let {
                if (it.startsWith("/")) it else "/manga/$it"
            }
            return MangasPage(listOf(createMangaFromPath(path)), false)
        }

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

        val document = client.get(url.build()).asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst(popularMangaNextPageSelector()) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun createMangaFromPath(path: String) = SManga.create().apply {
        setUrlWithoutDomain(path)
        title = path.trim('/').substringAfterLast('/')
            .replace('-', ' ')
            .replaceFirstChar { it.titlecase() }
    }

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

    private fun chapterListSelector() = "div.releases a.leer, div.eplister li"

    private fun chapterFromElement(element: Element) = SChapter.create().apply {
        if (element.tagName() == "a" && element.hasClass("leer")) {
            setUrlWithoutDomain(element.attr("href"))
            name = "Capítulo Único"
        } else {
            element.selectFirst("a")?.let { link ->
                setUrlWithoutDomain(link.attr("href"))
                name = link.selectFirst("div.chapternum")?.text() ?: link.attr("title")
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()
        val updatedManga = if (fetchDetails) {
            mangaDetailsParse(document).apply {
                url = manga.url
            }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            document.select(chapterListSelector()).map { chapterFromElement(it) }
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}").asJsoup()
        return pageListParse(document)
    }

    private fun pageListParse(document: Document): List<Page> {
        val html = document.html()

        val imagesRegex = Regex(""""images"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val imagesMatch = imagesRegex.find(html)

        if (imagesMatch != null) {
            val imagesJson = imagesMatch.groupValues[1]

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

        val chapterIdRegex = Regex("/read/(\\d+)/")
        val chapterId = chapterIdRegex.find(document.location())?.groupValues?.get(1)

        if (chapterId != null) {
            val selectPaged = document.selectFirst("select#select-paged, select.ts-select-paged")
            val totalPages = selectPaged?.select("option")?.size ?: 0

            if (totalPages > 0) {
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

    private class GenreFilter(name: String) : Filter.CheckBox(name)

    private class GenreList(genres: List<GenreFilter>) : Filter.Group<GenreFilter>("Géneros", genres)

    private class OrderByFilter(name: String, orderValues: Array<String>) : Filter.Select<String>(name, orderValues)

    override fun getFilterList(data: JsonElement?) = FilterList(
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
