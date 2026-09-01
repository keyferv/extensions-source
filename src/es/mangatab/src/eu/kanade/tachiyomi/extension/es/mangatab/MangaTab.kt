package eu.kanade.tachiyomi.extension.es.mangatab

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class MangaTab : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "es-ES,es;q=0.9")

    override fun popularMangaRequest(page: Int): Request = browseRequest(page, sort = "popular")

    override fun popularMangaParse(response: Response): MangasPage = browseParse(response)

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page, sort = "recent")

    override fun latestUpdatesParse(response: Response): MangasPage = browseParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/proxy/manga".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("sort", "recent")
            .addQueryParameter("limit", MANGA_LIST_LIMIT.toString())
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = browseParse(response)

    override fun getFilterList() = FilterList()

    private fun browseRequest(page: Int, sort: String): Request {
        val url = "$baseUrl/api/proxy/manga".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("limit", MANGA_LIST_LIMIT.toString())
            .addQueryParameter("page", page.toString())
            .build()

        return GET(url, headers)
    }

    private fun browseParse(response: Response): MangasPage {
        val payload = JSONObject(response.body.string())
        val items = payload.getJSONArray("items")

        val mangas = List(items.length()) { index ->
            val item = items.getJSONObject(index)
            val id = item.getString("id")
            val slug = item.getString("slug")

            SManga.create().apply {
                title = item.getString("title")
                url = "/manga/$id/$slug"
                thumbnail_url = item.optString("coverImage").takeIf(String::isNotBlank)
            }
        }

        val hasNextPage = payload.optInt("page") < payload.optInt("pages")

        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()
        val document = Jsoup.parse(html, response.request.url.toString())

        return SManga.create().apply {
            title = metaContent(document, "meta[property=og:title]")
                ?.removePrefix("Leer manga ")
                ?.substringBefore(" | Mangatab")
                ?: labeledValue(html, "Título Completo")
                ?: document.title().substringBefore(" | Mangatab").removePrefix("Leer manga ")

            thumbnail_url = document.selectFirst("link[rel=preload][as=image][href*=mangatab-images/covers]")?.attr("abs:href")
                ?: document.selectFirst("img[src*=mangatab-images/covers]")?.attr("abs:src")

            author = labeledValue(html, "Autor")
            description = labeledParagraph(html, "Sinopsis")
                ?: metaContent(document, "meta[name=description]")

            status = parseStatus(labeledValue(html, "Estado"))

            genre = document.select("a[href^=/browse?genres=], a[href^=/browse?genre=]")
                .eachText()
                .distinct()
                .joinToString()
                .ifBlank { null }
        }
    }

    private fun parseStatus(text: String?): Int = when {
        text.isNullOrBlank() -> SManga.UNKNOWN
        text.contains("curso", ignoreCase = true) -> SManga.ONGOING
        text.contains("finalizado", ignoreCase = true) || text.contains("completado", ignoreCase = true) -> SManga.COMPLETED
        text.contains("pausa", ignoreCase = true) || text.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        text.contains("cancel", ignoreCase = true) || text.contains("abandon", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select("nav[data-seo-chapters] a[href^=/chapter/]")
            .distinctBy { it.attr("href") }
            .map { link ->
                val name = link.text().replace(Regex("\\s+"), " ").trim()

                SChapter.create().apply {
                    this.name = name
                    url = link.attr("href")
                    chapter_number = Regex("Capítulo\\s+([0-9]+(?:\\.[0-9]+)?)")
                        .find(name)
                        ?.groupValues
                        ?.get(1)
                        ?.toFloatOrNull()
                        ?: -1f
                }
            }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = document.select("link[rel=preload][as=image][href*=mangatab-images/mangas], img[src*=mangatab-images/mangas]")
            .mapNotNull { element ->
                element.attr("abs:href").ifBlank { element.attr("abs:src") }
                    .takeIf { it.contains("/chapters/") }
            }
            .distinct()

        return pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headersBuilder().set("Referer", baseUrl).build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun metaContent(document: Document, selector: String): String? = document.selectFirst(selector)
        ?.attr("content")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun labeledValue(html: String, label: String): String? {
        val pattern = Regex(
            Regex.escape(label) + "</span>\\s*<(?:span|a)[^>]*>(.*?)</(?:span|a)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        return pattern.find(html)?.groupValues?.get(1)?.htmlToText()
    }

    private fun labeledParagraph(html: String, label: String): String? {
        val pattern = Regex(
            Regex.escape(label) + ".*?<p[^>]*>(.*?)</p>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        return pattern.find(html)?.groupValues?.get(1)?.htmlToText()
    }

    private fun String.htmlToText(): String = Jsoup.parse(this).text().trim().takeIf(String::isNotBlank).orEmpty()

    private companion object {
        const val MANGA_LIST_LIMIT = 28
    }
}
