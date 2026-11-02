package eu.kanade.tachiyomi.extension.all.openhentai

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class OpenHentai : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Cookie", "disclaimer_accepted=1")

    private val pathPrefix: String
        get() = if (lang == "all") "/es" else "/$lang"

    private val languageParam: String
        get() = when (lang) {
            "es" -> "spanish"
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            "fr" -> "french"
            "de" -> "german"
            "pt" -> "portuguese"
            "ru" -> "russian"
            "it" -> "italian"
            "ko" -> "korean"
            "vi" -> "vietnamese"
            "id" -> "indonesian"
            else -> "all"
        }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val urlBuilder = if (page > 1) {
            "$baseUrl$pathPrefix/advanced_search/page/$page".toHttpUrl().newBuilder()
        } else {
            "$baseUrl$pathPrefix/advanced_search".toHttpUrl().newBuilder()
        }

        urlBuilder.apply {
            addQueryParameter("language", languageParam)
            addQueryParameter("sort", "decrease_view")
        }

        val document = client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val urlBuilder = if (lang == "all") {
            if (page > 1) {
                "$baseUrl$pathPrefix/page/$page".toHttpUrl().newBuilder()
            } else {
                "$baseUrl$pathPrefix/".toHttpUrl().newBuilder()
            }
        } else {
            if (page > 1) {
                "$baseUrl$pathPrefix/advanced_search/page/$page".toHttpUrl().newBuilder()
            } else {
                "$baseUrl$pathPrefix/advanced_search".toHttpUrl().newBuilder()
            }.apply {
                addQueryParameter("language", languageParam)
                addQueryParameter("sort", "recent_to_old")
            }
        }

        val document = client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sort = "recent_to_old"
        var author = ""
        var tags = ""
        var languageOverride = ""

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.toValue()
                is AuthorFilter -> author = filter.state.trim()
                is TagsFilter -> tags = filter.state.trim()
                is LanguageFilter -> languageOverride = filter.toValue()
                else -> {}
            }
        }

        val selectedLanguage = when {
            languageOverride.isNotBlank() -> languageOverride
            else -> languageParam
        }

        val urlBuilder = if (page > 1) {
            "$baseUrl$pathPrefix/advanced_search/page/$page".toHttpUrl().newBuilder()
        } else {
            "$baseUrl$pathPrefix/advanced_search".toHttpUrl().newBuilder()
        }

        urlBuilder.apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            if (author.isNotBlank()) addQueryParameter("a", author)
            if (tags.isNotBlank()) addQueryParameter("t", tags)
            addQueryParameter("language", selectedLanguage)
            addQueryParameter("sort", sort)
        }

        val document = client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup()
        return parseMangasPage(document)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        AuthorFilter(),
        TagsFilter(),
        LanguageFilter(),
    )

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val mangasIndex = segments.indexOf("mangas")
        if (mangasIndex == -1 || mangasIndex + 1 >= segments.size) return null
        val mangaId = segments[mangasIndex + 1]
        val mangaPath = "$pathPrefix/mangas/$mangaId"
        val manga = SManga.create().apply {
            this.url = mangaPath
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
            val titleElement = document.selectFirst("h1.om-show-title")
            if (titleElement != null) {
                title = titleElement.text()
            }

            val coverImg = document.selectFirst(".om-show-cover img")
            val coverUrl = coverImg?.attr("src")
            if (!coverUrl.isNullOrBlank()) {
                thumbnail_url = coverUrl
            }

            val tags = document.select(".om-chips a.om-chip").map { it.ownText().trim() }
            if (tags.isNotEmpty()) {
                genre = tags.joinToString()
            }

            status = SManga.COMPLETED

            description = buildString {
                document.selectFirst(".om-show-meta")?.children()?.forEach { meta ->
                    val text = meta.text().trim()
                    if (text.isNotBlank()) {
                        append(text, "\n")
                    }
                }
            }.trim()
        }

        val chapterList = if (fetchChapters) {
            val chapter = SChapter.create().apply {
                name = "Capítulo"
                url = manga.url
                val dateText = document.selectFirst(".om-show-meta span:has(i.fa-calendar-alt)")?.text()
                date_upload = spanishDateFormat.tryParseDate(dateText)
            }
            listOf(chapter)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}".toHttpUrl(), Fetch.DOCUMENT).asJsoup()
        val pageLinks = document.select("div#lightgallery a.om-show-page")
        return pageLinks.mapIndexed { index, a ->
            val imgUrl = a.attr("data-src").ifEmpty { a.attr("href") }
            Page(index, "", imgUrl)
        }
    }

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select("article.om-card").mapNotNull { card ->
            val link = card.selectFirst("a.om-card-link") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.contains("/shop")) return@mapNotNull null

            val title = card.selectFirst(".om-card-title")?.text()
                ?: link.attr("aria-label").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val img = card.selectFirst("img.om-cover")
            val thumbnail = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")

            val tags = card.select(".om-card-tags .om-pill").joinToString { it.text() }

            SManga.create().apply {
                this.title = title
                this.url = href.removePrefix(baseUrl)
                this.thumbnail_url = thumbnail
                this.genre = tags
            }
        }

        val hasNextPage = document.selectFirst("link[rel=next], ul.pagination li:last-child:not(.disabled) a") != null
        return MangasPage(mangas, hasNextPage)
    }

    companion object {
        private val spanishDateFormat = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"))
    }
}
