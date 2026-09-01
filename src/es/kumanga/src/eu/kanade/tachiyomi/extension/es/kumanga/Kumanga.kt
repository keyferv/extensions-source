package eu.kanade.tachiyomi.extension.es.kumanga

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Kumanga : KeiSource() {

    private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")
    private val mangaDetailRegex = Regex("""^/manga/\d+/[^/]+/?$""")
    private val mangaIdRegex = Regex("""^/manga/\d+/?$""")
    private val chapterUrlRegex = Regex("""(?:https?://[^/]+)?(/manga/\d+/capitulo/[^\"'\\]+)""")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        return parseCatalog(document, page, ".main-hot-updates a.mhu-name")
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        return parseCatalog(document, page, ".update_item .update_left a[href*='manga/']")
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()
        val genrePart = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        val typePart = filters.filterIsInstance<TypeFilter>().firstOrNull()?.toUriPart().orEmpty()
        val yearPart = filters.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart().orEmpty()
        val statusPart = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart().orEmpty()

        val builder = "$baseUrl/mangalist".toHttpUrl().newBuilder()
        if (trimmed.isNotEmpty()) {
            builder.addQueryParameter("keywords", trimmed)
        }
        if (genrePart.isNotEmpty()) builder.addQueryParameter("categories", genrePart)
        if (typePart.isNotEmpty()) builder.addQueryParameter("types", typePart)
        if (yearPart.isNotEmpty()) builder.addQueryParameter("years", yearPart)
        if (statusPart.isNotEmpty()) builder.addQueryParameter("status", statusPart)
        builder.addQueryParameter("page", page.toString())

        return parseSearchCatalog(client.get(builder.build()).asJsoup(), page)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val host = baseUrl.toHttpUrl().host
        val hostOk = url.host == host || url.host.endsWith(host.removePrefix("www."))
        if (!hostOk) return null
        val path = url.encodedPath
        if (path.contains("/capitulo/") || path.contains("/leer/") || path == "/manga/c" || path.contains("/manga/c/")) return null
        if (!mangaDetailRegex.matches(path) && !mangaIdRegex.matches(path)) return null
        val document = client.get(url.toString()).asJsoup()
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(url.encodedPath)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document).apply {
                setUrlWithoutDomain(manga.url)
                if (title.isBlank()) title = manga.title
            }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rawPath = chapter.url.substringBefore("?").substringBefore("#")
        val absChapterUrl = when {
            rawPath.startsWith("http") -> rawPath
            rawPath.startsWith("/") -> baseUrl + rawPath
            else -> "$baseUrl/$rawPath"
        }

        val readerUrlString = when {
            rawPath.contains("/capitulo/") -> {
                val doc = client.get(absChapterUrl).asJsoup()
                val link = doc.selectFirst("a[href*='manga/c/']")?.absUrl("href")
                    ?: doc.selectFirst("a[href*='manga/leer/']")?.absUrl("href")
                val guessed = link?.takeIf { it.isNotBlank() }?.replace("/manga/c/", "/manga/leer/")
                    ?: absChapterUrl.replace("/capitulo/", "/leer/")
                if (guessed.contains("/manga/c/")) guessed.replace("/manga/c/", "/manga/leer/") else guessed
            }
            rawPath.contains("/manga/c/") -> absChapterUrl.replace("/manga/c/", "/manga/leer/")
            rawPath.contains("/manga/leer/") -> absChapterUrl
            else -> absChapterUrl
        }

        val readerUrl = runCatching { readerUrlString.toHttpUrl() }.getOrNull()
            ?: absChapterUrl.toHttpUrl()
        val imageUrls = runCatching {
            runWebView<List<String>>(timeout = 60.seconds) {
                poll {
                    evaluateJs(
                        """
                        (function() {
                            return Array.from(document.querySelectorAll('#rkx img'))
                                .map(function(image) {
                                    var value = image.getAttribute('data-src') || image.getAttribute('src');
                                    return value ? new URL(value, document.baseURI).href : '';
                                })
                                .filter(function(url) {
                                    return url && !url.includes('/assets/img/image_loader.svg');
                                });
                        })()
                        """.trimIndent(),
                    ) { result ->
                        val urls = result.parseAs<List<String>>().distinct()
                        if (urls.isNotEmpty()) resolve(urls)
                    }
                }
                loadUrl(readerUrl.toString())
            }
        }.getOrDefault(emptyList())

        return imageUrls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        FilterHeader("La búsqueda por texto usa el campo superior; los filtros combinan con keywords, categories, types, years y status."),
        GenreFilter(),
        TypeFilter(),
        YearFilter(),
        StatusFilter(),
    )

    private fun parseCatalog(
        document: Document,
        page: Int,
        selector: String = "a[href*='/manga/']",
    ): MangasPage {
        val mangas = document.select(selector).mapNotNull { el ->
            val href = el.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull()
                ?: href.substringBefore("?").substringBefore("#")
            if (!mangaDetailRegex.matches(path)) return@mapNotNull null
            if (path.contains("/capitulo/") || path.contains("/c/") || path.contains("/leer/")) return@mapNotNull null
            val title = el.text().trim().takeIf { it.isNotEmpty() }
                ?: el.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: el.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val thumb = extractThumbnail(el)
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(path)
                thumbnail_url = thumb?.takeIf { it.isNotBlank() }
            }
        }.distinctBy { it.url }

        val fallback = if (mangas.isEmpty()) {
            document.select("li, div").mapNotNull { container ->
                val link = container.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
                val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: return@mapNotNull null
                if (!mangaDetailRegex.matches(path)) return@mapNotNull null
                val title = container.selectFirst("p")?.text()?.trim()
                    ?: link.text().trim().takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val thumb = extractThumbnail(link)
                SManga.create().apply {
                    this.title = title
                    setUrlWithoutDomain(path)
                    thumbnail_url = thumb
                }
            }.distinctBy { it.url }
        } else {
            emptyList()
        }

        val resultList = if (mangas.isNotEmpty()) mangas else fallback
        val paginated = document.select("a[href*='page=']").any { it.attr("href").contains("page=${page + 1}") } ||
            document.selectFirst("a[href*='page=${page + 1}']") != null
        return MangasPage(resultList, paginated)
    }

    private fun extractThumbnail(element: Element): String? {
        val image = element.selectFirst("img")
            ?: element.parent()?.selectFirst("img")
        val imageUrl = image?.let {
            it.absUrl("data-src").takeIf { url -> url.isNotBlank() }
                ?: it.absUrl("src").takeIf { url -> url.isNotBlank() }
        }
        if (imageUrl != null) return imageUrl

        val backgroundElement = element.selectFirst("[style*='background-image']")
            ?: element.parent()?.selectFirst("a.mhu-card[style*='background-image']")
            ?: element.parent()?.selectFirst("[style*='background-image']")
        val backgroundUrl = backgroundElement?.attr("style")
            ?.let { Regex("background-image:\\s*url\\(['\"]?([^'\")]+)['\"]?\\)").find(it)?.groupValues?.get(1) }
            ?: return null
        return element.baseUri().toHttpUrl().resolve(backgroundUrl)?.toString()
    }

    private fun parseSearchCatalog(document: Document, page: Int): MangasPage {
        val mangas = document.select("li.km-li-crd[onclick]").mapNotNull { card ->
            val href = Regex("""window\.open\(['\"]([^'\"]+)['\"]\)""")
                .find(card.attr("onclick"))
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull()
                ?: return@mapNotNull null
            if (!mangaDetailRegex.matches(path)) return@mapNotNull null
            val title = card.selectFirst(".km-title-p-card")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(path)
                thumbnail_url = card.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }
                    ?: extractThumbnail(card)
            }
        }.distinctBy { it.url }

        val paginated = document.select("a[href*='page=']").any {
            it.attr("href").contains("page=${page + 1}")
        }
        return MangasPage(mangas, paginated)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
        }
        if (title.isBlank()) throw Exception("Title not found")

        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img[alt]")?.let { img ->
                val alt = img.attr("alt").trim()
                if (alt.equals(title.trim(), ignoreCase = true) || alt.contains(title.trim(), ignoreCase = true)) {
                    img.absUrl("src").takeIf { it.isNotBlank() } ?: img.absUrl("data-src")
                } else {
                    null
                }
            }
            ?: document.selectFirst("img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }

        val candidates = document.select("p")
        description = candidates.firstOrNull { p ->
            val txt = p.text().trim()
            txt.length > 40 && !txt.contains("Capítulo", ignoreCase = true) && !txt.contains("Estado del título", ignoreCase = true)
        }?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        genre = document.select("a[href*='categories=']").map { it.text().trim() }.filter { it.isNotEmpty() }
            .joinToString(", ").takeIf { it.isNotBlank() }

        val statusText = document.select("*").firstOrNull { it.tagName() != "script" && it.ownText().contains("Estado del título", ignoreCase = true) }
            ?.let { label ->
                label.nextElementSibling()?.text()?.trim()
                    ?: label.parent()?.ownText()?.substringAfter("Estado del título", "")?.trim()
                    ?: label.parent()?.text()?.substringAfter("Estado del título", "")?.trim()?.substringBefore("\n")?.trim()
            }
            ?: Regex("Estado del título\\s*([^\\n<]+)", RegexOption.IGNORE_CASE).find(document.text())?.groupValues?.getOrNull(1)?.trim()

        status = parseStatus(statusText)
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        var elements = document.select("a[href*='/capitulo/']")
        if (elements.isEmpty()) {
            elements = document.select("a[href*='/manga/c/']")
        }
        val chapters = elements.mapNotNull { el ->
            val href = el.absUrl("href").takeIf { it.isNotBlank() } ?: el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull()
                ?: href.substringBefore("?").substringBefore("#")
            if (!path.contains("/capitulo/") && !path.contains("/manga/c/")) return@mapNotNull null
            val name = el.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: el.text().trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val number = chapterNumberRegex.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: path.substringAfter("/capitulo/").substringBefore("/").substringBefore("?").toFloatOrNull()
                ?: path.substringAfterLast("/").substringBefore("?").toFloatOrNull()
                ?: -1f

            SChapter.create().apply {
                this.name = name
                setUrlWithoutDomain(href)
                this.chapter_number = number
            }
        }

        val jsonLdChapters = document.select("script[type='application/ld+json']").flatMap { script ->
            chapterUrlRegex.findAll(script.data()).mapNotNull { match ->
                val path = match.groupValues[1]
                val number = path.substringAfterLast("/capitulo/").toFloatOrNull() ?: return@mapNotNull null
                SChapter.create().apply {
                    name = "Capítulo $number".replace(".0", "")
                    setUrlWithoutDomain(path)
                    chapter_number = number
                }
            }.toList()
        }

        return (chapters + jsonLdChapters).distinctBy { it.url }
    }

    private fun parseStatus(raw: String?): Int {
        val t = raw?.trim()?.lowercase().orEmpty()
        return when {
            t.isBlank() -> SManga.UNKNOWN
            t.contains("en emisión") || t.contains("en emision") || t.contains("en curso") || t.contains("activo") -> SManga.ONGOING
            t.contains("finalizado") || t.contains("completado") || t.contains("terminado") -> SManga.COMPLETED
            t.contains("pausa") || t.contains("hiatus") || t.contains("inconcluso") -> SManga.ON_HIATUS
            t.contains("cancelado") || t.contains("abandonado") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private class FilterHeader(name: String) : eu.kanade.tachiyomi.source.model.Filter.Header(name)
}
