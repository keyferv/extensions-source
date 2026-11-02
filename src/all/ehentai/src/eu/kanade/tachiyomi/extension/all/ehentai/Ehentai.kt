package eu.kanade.tachiyomi.extension.all.ehentai

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
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Ehentai : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Cookie", "nw=1; sl=dm_l")

    private val languageTag: String?
        get() = when (lang) {
            "es" -> "language:spanish"
            "en" -> "language:english"
            "ja" -> "language:japanese"
            "zh" -> "language:chinese"
            "ko" -> "language:korean"
            "ru" -> "language:russian"
            "fr" -> "language:french"
            "de" -> "language:german"
            "pt" -> "language:portuguese"
            "it" -> "language:italian"
            else -> null
        }

    private var popularNextCursor: String? = null
    private var latestNextCursor: String? = null
    private var searchNextCursor: String? = null

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page == 1) popularNextCursor = null

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("advsearch", "1")
            addQueryParameter("f_srdd", "4")
            val query = languageTag
            if (!query.isNullOrBlank()) {
                addQueryParameter("f_search", query)
            }
            if (page > 1 && !popularNextCursor.isNullOrBlank()) {
                addQueryParameter("next", popularNextCursor)
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = parseMangaList(document)
        popularNextCursor = parseNextCursor(document)

        return MangasPage(mangas, hasNextPage = popularNextCursor != null)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page == 1) latestNextCursor = null

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val query = languageTag
            if (!query.isNullOrBlank()) {
                addQueryParameter("f_search", query)
            }
            if (page > 1 && !latestNextCursor.isNullOrBlank()) {
                addQueryParameter("next", latestNextCursor)
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = parseMangaList(document)
        latestNextCursor = parseNextCursor(document)

        return MangasPage(mangas, hasNextPage = latestNextCursor != null)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page == 1) searchNextCursor = null

        val url = baseUrl.toHttpUrl().newBuilder().apply {
            val fullQuery = buildString {
                if (query.isNotBlank()) {
                    append(query.trim())
                }
                if (languageTag != null && !query.contains("language:")) {
                    if (isNotEmpty()) append(" ")
                    append(languageTag)
                }
            }
            if (fullQuery.isNotBlank()) {
                addQueryParameter("f_search", fullQuery)
            }

            var fCats = 0
            var hasAdvSearch = false

            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> {
                        filter.state.forEach { cat ->
                            if (!cat.state) {
                                fCats = fCats or cat.bit
                            }
                        }
                    }
                    is RatingFilter -> {
                        filter.toValue()?.let { rating ->
                            hasAdvSearch = true
                            addQueryParameter("f_srdd", rating)
                        }
                    }
                    is AdvancedOptionsFilter -> {
                        filter.state.forEach { opt ->
                            if (opt.state) {
                                hasAdvSearch = true
                                addQueryParameter(opt.param, "on")
                            }
                        }
                    }
                    is PageMinFilter -> {
                        if (filter.state.isNotBlank()) {
                            hasAdvSearch = true
                            addQueryParameter("f_spf", filter.state.trim())
                        }
                    }
                    is PageMaxFilter -> {
                        if (filter.state.isNotBlank()) {
                            hasAdvSearch = true
                            addQueryParameter("f_spt", filter.state.trim())
                        }
                    }
                    else -> {}
                }
            }

            if (fCats > 0) {
                addQueryParameter("f_cats", fCats.toString())
            }
            if (hasAdvSearch) {
                addQueryParameter("advsearch", "1")
            }

            if (page > 1 && !searchNextCursor.isNullOrBlank()) {
                addQueryParameter("next", searchNextCursor)
            }
        }.build()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = parseMangaList(document)
        searchNextCursor = parseNextCursor(document)

        return MangasPage(mangas, hasNextPage = searchNextCursor != null)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        CategoryFilter(getCategoriesList()),
        RatingFilter(),
        AdvancedOptionsFilter(getAdvancedOptionsList()),
        PageMinFilter(),
        PageMaxFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size < 3 || segments[0] != "g") return null
        val galleryPath = "/g/${segments[1]}/${segments[2]}/"
        val manga = SManga.create().apply {
            this.url = galleryPath
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

        val updatedManga = manga.apply {
            val mainTitle = document.selectFirst("#gn")?.text()?.takeIf { it.isNotBlank() }
            if (mainTitle != null) {
                title = mainTitle
            }

            val thumbStyle = document.selectFirst("#gd1 div")?.attr("style")
            val thumbUrl = thumbStyle?.let {
                Regex("""url\((https?://[^)]+)\)""").find(it)?.groupValues?.get(1)
            } ?: document.selectFirst("#gd1 img")?.attr("abs:src")
            if (!thumbUrl.isNullOrBlank()) {
                thumbnail_url = thumbUrl
            }

            val artists = document.select("#taglist tr:has(td:contains(artist:)) a").map { it.text() }
            val artistText = artists.joinToString().takeIf { it.isNotBlank() }
                ?: document.selectFirst("#gdn a")?.text()
            artist = artistText
            author = artistText

            val mainCat = document.selectFirst("#gdc .cs")?.text()
            val allTags = document.select("#taglist a").map { it.text() }
            genre = listOfNotNull(mainCat).plus(allTags).distinct().joinToString()

            status = SManga.COMPLETED

            description = buildString {
                document.selectFirst("#gj")?.text()?.takeIf { it.isNotBlank() }?.let {
                    append("Japanese: ", it, "\n\n")
                }
                document.selectFirst("#gdd td.gdt1:contains(Posted:) + td")?.text()?.let {
                    append("Posted: ", it, "\n")
                }
                document.selectFirst("#gdd td.gdt1:contains(Language:) + td")?.text()?.let {
                    append("Language: ", it, "\n")
                }
                document.selectFirst("#gdd td.gdt1:contains(Length:) + td")?.text()?.let {
                    append("Length: ", it, "\n")
                }
                document.selectFirst("#cdiv .c6")?.text()?.takeIf { it.isNotBlank() }?.let {
                    append("\nUploader Comment:\n", it)
                }
            }
        }

        val chapterList = if (fetchChapters) {
            val chapter = SChapter.create().apply {
                name = "Gallery"
                url = manga.url
                val postedText = document.selectFirst("#gdd td.gdt1:contains(Posted:) + td")?.text()
                date_upload = dateFormat.tryParseDateTime(postedText, ZoneOffset.UTC)
                scanlator = document.selectFirst("#gdn a")?.text()
            }
            listOf(chapter)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = mutableListOf<Page>()
        var currentUrl: String? = "$baseUrl${chapter.url}"
        var index = 0

        while (currentUrl != null) {
            val document = client.get(currentUrl.toHttpUrl(), Fetch.DOCUMENT).asJsoup()
            val links = document.select("#gdt a[href*=/s/]")
            for (link in links) {
                pages.add(Page(index++, link.attr("abs:href")))
            }

            val nextLink = document.selectFirst("table.ptb td:last-child a")
            val nextHref = nextLink?.attr("abs:href")
            currentUrl = if (!nextHref.isNullOrBlank() && nextHref != currentUrl) {
                nextHref
            } else {
                null
            }
        }

        return pages
    }

    override suspend fun getImageUrl(page: Page): String {
        val document = client.get(page.url.toHttpUrl(), Fetch.DOCUMENT).asJsoup()
        return document.selectFirst("#img")?.attr("abs:src")
            ?: throw Exception("No image found on ${page.url}")
    }

    private fun parseMangaList(document: Document): List<SManga> {
        return document.select("table.itg tr:has(td.gl3c)").mapNotNull { row ->
            val link = row.selectFirst("td.gl3c a") ?: return@mapNotNull null
            val titleElement = link.selectFirst(".glink") ?: link
            val href = link.attr("href")

            val img = row.selectFirst("td.gl2c .glthumb img")
            val thumb = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:") }

            val tags = row.select("td.gl3c .gt").joinToString { it.text() }

            SManga.create().apply {
                title = titleElement.text()
                url = href.removePrefix(baseUrl)
                thumbnail_url = thumb
                genre = tags
            }
        }
    }

    private fun parseNextCursor(document: Document): String? {
        val nextHref = document.selectFirst("a#unext, a#dnext")?.attr("href") ?: return null
        return nextHref.toHttpUrlOrNull()?.queryParameter("next")
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
    }
}
