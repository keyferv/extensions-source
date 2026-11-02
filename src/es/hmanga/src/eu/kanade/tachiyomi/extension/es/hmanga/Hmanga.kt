package eu.kanade.tachiyomi.extension.es.hmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Hmanga : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2, 1.seconds)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page > 1) {
            "$baseUrl/page/$page/?orderby=comment_count".toHttpUrl()
        } else {
            "$baseUrl/?orderby=comment_count".toHttpUrl()
        }
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = if (page > 1) {
            "$baseUrl/page/$page/".toHttpUrl()
        } else {
            "$baseUrl/".toHttpUrl()
        }
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sort = "date"
        var selectedTag: String? = null
        var author = ""

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.toValue()
                is TagFilter -> selectedTag = filter.toSlug()
                is AuthorFilter -> author = filter.state.trim().lowercase(Locale.ROOT)
                else -> {}
            }
        }

        val urlBuilder = when {
            author.isNotBlank() -> {
                if (page > 1) {
                    "$baseUrl/autor/$author/page/$page/".toHttpUrl().newBuilder()
                } else {
                    "$baseUrl/autor/$author/".toHttpUrl().newBuilder()
                }
            }
            selectedTag != null -> {
                if (page > 1) {
                    "$baseUrl/tags/$selectedTag/page/$page/".toHttpUrl().newBuilder()
                } else {
                    "$baseUrl/tags/$selectedTag/".toHttpUrl().newBuilder()
                }
            }
            else -> {
                if (page > 1) {
                    "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                } else {
                    "$baseUrl/".toHttpUrl().newBuilder()
                }
            }
        }

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("s", query.trim())
        }
        if (sort != "date") {
            urlBuilder.addQueryParameter("orderby", sort)
        }

        val document = client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TagFilter(),
        AuthorFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply {
            this.url = url.encodedPath
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}".toHttpUrl(), Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) {
            manga.apply {
                val titleElement = document.selectFirst("h1.entry-title, .section-main-title")
                if (titleElement != null) {
                    title = titleElement.text().trim()
                }

                val coverMeta = document.selectFirst("meta[property='og:image']")?.attr("content")
                val coverImg = document.selectFirst("#hm-post-body img, .card-img-top")
                val coverUrl = coverMeta?.ifEmpty { null }
                    ?: coverImg?.let {
                        val dataSrc = it.attr("abs:data-src")
                        if (dataSrc.isNotEmpty() && !dataSrc.startsWith("data:")) dataSrc else it.attr("abs:src")
                    }
                if (!coverUrl.isNullOrBlank()) {
                    thumbnail_url = coverUrl
                }

                val authorElement = document.selectFirst("a[href*='/autor/'] .tag-list-title, a[href*='/autor/']")
                if (authorElement != null) {
                    author = authorElement.ownText().ifEmpty { authorElement.text() }.trim()
                    artist = author
                }

                val tags = document.select("a[href*='/tags/'] .tag-list-title, a[href*='/tags/'] .tag-list-title-danger")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                if (tags.isNotEmpty()) {
                    genre = tags.distinct().joinToString(", ")
                }

                status = SManga.COMPLETED

                val subtitle = document.selectFirst("h2.entry-title")?.text()?.trim()
                description = subtitle ?: ""
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val seriesLink = document.selectFirst("a.btn[href*='/series/']")
            if (seriesLink != null) {
                val seriesUrl = seriesLink.attr("abs:href").toHttpUrl()
                val seriesDoc = client.get(seriesUrl, Fetch.DOCUMENT).asJsoup()
                parseSeriesChapters(seriesDoc)
            } else if (manga.url.contains("/series/")) {
                parseSeriesChapters(document)
            } else {
                val singleChapter = SChapter.create().apply {
                    name = "Capítulo Completo"
                    url = manga.url
                    date_upload = 0L
                    chapter_number = 1f
                }
                listOf(singleChapter)
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}".toHttpUrl(), Fetch.DOCUMENT).asJsoup()
        val html = document.html()

        val pagesMatch = PAGES_REGEX.find(html)
            ?: throw Exception("No se encontraron páginas para este capítulo")

        val pagesJson = pagesMatch.groupValues[1]
        val urls = pagesJson.parseAs<List<String>>()

        return urls.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = imgUrl)
        }
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val articles = document.select("article.card, article[id^='post-']")
        val mangas = articles.mapNotNull { article ->
            val link = article.selectFirst("a.lanzador[href]") ?: article.selectFirst("a[href]")
                ?: return@mapNotNull null
            val mangaUrl = link.attr("abs:href").toHttpUrlOrNull()?.encodedPath
                ?: return@mapNotNull null

            val title = article.selectFirst("h2.entry-title")?.text()?.trim()
                ?: link.attr("title").ifEmpty { null }
                ?: return@mapNotNull null

            val img = article.selectFirst("img")
            val coverUrl = img?.let {
                val dataSrc = it.attr("abs:data-src")
                if (dataSrc.isNotEmpty() && !dataSrc.startsWith("data:")) {
                    dataSrc
                } else {
                    it.attr("abs:src")
                }
            }

            SManga.create().apply {
                this.url = mangaUrl
                this.title = title
                this.thumbnail_url = coverUrl
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst("ul.pagination a.next, a.next.page-link") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseSeriesChapters(seriesDoc: Document): List<SChapter> {
        val articles = seriesDoc.select("article")
        val chapters = articles.mapNotNull { article ->
            val link = article.selectFirst("a.lanzador") ?: article.selectFirst("a")
                ?: return@mapNotNull null
            val chapterUrl = link.attr("abs:href").toHttpUrlOrNull()?.encodedPath
                ?: return@mapNotNull null
            val title = article.selectFirst("h2.entry-title")?.text()?.trim()
                ?: link.attr("title").ifEmpty { null }
                ?: "Capítulo"

            SChapter.create().apply {
                this.name = title
                this.url = chapterUrl
                this.date_upload = 0L
                this.chapter_number = title.split(" ").lastOrNull()?.toFloatOrNull() ?: -1f
            }
        }
        return chapters.ifEmpty {
            val single = SChapter.create().apply {
                name = "Capítulo Completo"
                url = seriesDoc.location().toHttpUrl().encodedPath
                date_upload = 0L
                chapter_number = 1f
            }
            listOf(single)
        }
    }

    companion object {
        private val PAGES_REGEX = Regex("""pages\s*=\s*(\[[\s\S]*?\])""")
    }
}
