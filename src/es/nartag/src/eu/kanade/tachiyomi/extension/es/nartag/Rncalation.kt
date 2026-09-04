package eu.kanade.tachiyomi.extension.es.nartag

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Rncalation : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2, 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/library?sort=views&page=$page").asJsoup()
        val mangas = parseLibraryMangas(document)
        return MangasPage(mangas, hasNextPage(document, page))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/library?sort=updated&page=$page").asJsoup()
        val mangas = parseLibraryMangas(document)
        return MangasPage(mangas, hasNextPage(document, page))
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/library".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("q", query)
            }
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        addQueryParameter("sort", sortOptions[filter.state].value)
                    }
                    is TypeFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("type", filter.values[filter.state])
                        }
                    }
                    is StatusFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("status", filter.values[filter.state])
                        }
                    }
                    is GenreFilter -> {
                        if (filter.state > 0) {
                            addQueryParameter("genre", filter.values[filter.state])
                        }
                    }
                    else -> {}
                }
            }
        }.build()
        val document = client.get(url).asJsoup()
        val mangas = parseLibraryMangas(document)
        return MangasPage(mangas, hasNextPage(document, page))
    }

    private fun parseLibraryMangas(document: Document): List<SManga> {
        return document.select(".lib-grid a.comic-card").mapNotNull { element ->
            val type = element.selectFirst("span.absolute.top-2.left-2")?.text()
            if (type != null && type.contains("Novel", ignoreCase = true)) {
                return@mapNotNull null
            }
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst("p.leading-snug")?.text() ?: element.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        val text = document.selectFirst("p:contains(Pág.)")?.text() ?: document.text()
        // Live site shows "337 cómics encontrados — Pág. 1 / 15"
        val regex = Regex("""Pág\.\s*(\d+)\s*/\s*(\d+)""")
        val match = regex.find(text) ?: regex.find(document.text())
        if (match != null) {
            val current = match.groupValues[1].toIntOrNull() ?: page
            val total = match.groupValues[2].toIntOrNull()
            if (total != null) {
                return current < total
            }
        }
        // Safe fallback only when indicator is absent: look for a link to the next page
        return document.selectFirst("a[href*=\"page=${page + 1}\"]") != null ||
            document.selectFirst("a[aria-label='Next page']") != null
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val doc = client.get(baseUrl + manga.url).asJsoup()
            parseMangaDetails(doc).apply {
                url = manga.url
                initialized = true
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            fetchAllChapters(manga.url)
        } else {
            chapters
        }

        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        description = document.select("p").firstOrNull {
            val cls = it.attr("class")
            cls.contains("text-[1.1rem]") && cls.contains("max-w-[48rem]")
        }?.text()?.trim()
            ?: document.selectFirst("p[class*='text-[1.1rem]']")?.text()?.trim()
            ?: document.selectFirst("div.comic-page-wrap p")?.text()?.trim()
            ?: ""

        val metaContainer = document.select("div").firstOrNull { el ->
            val cls = el.attr("class")
            cls.contains("flex") && cls.contains("flex-wrap") && cls.contains("gap-1.5") && el.selectFirst("span") != null
        }

        val rawSpans = metaContainer?.select("span")?.map { it.text().trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val statusText = rawSpans.firstOrNull { isStatusText(it) }
            ?.lowercase()?.replace("í", "i")?.replace("á", "a")?.replace("é", "e")?.replace("ó", "o")

        status = when {
            statusText == null -> SManga.UNKNOWN
            statusText.contains("completado") || statusText.contains("completed") || statusText.contains("finalizado") -> SManga.COMPLETED
            statusText.contains("emision") || statusText.contains("curso") || statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("pausa") || statusText.contains("hiatus") -> SManga.ON_HIATUS
            statusText.contains("cancelado") || statusText.contains("cancelled") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        // Fix genre extraction: exclude status, type and demographic badges.
        // Verified live detail (/comics/las-flipantes-aventuras-del-rey-de-la-espada-en-otro-mundo)
        // shows only EN EMISIÓN, SHOUNEN, MANHWA – no genuine genre chips there, so genre must be empty.
        val genreCandidates = rawSpans.filterNot { isStatusText(it) || isTypeText(it) || isDemographicText(it) }

        genre = if (genreCandidates.isNotEmpty()) {
            // Deduplicate case/accent-insensitively only when safe, keep original casing of first occurrence
            val seen = mutableSetOf<String>()
            genreCandidates.map { it.trim() }.filter { it.isNotEmpty() }.filter { candidate ->
                val key = candidate.lowercase(Locale.ROOT).replace("í", "i").replace("á", "a").replace("é", "e").replace("ó", "o").replace("ú", "u")
                seen.add(key)
            }.joinToString(", ")
        } else {
            // Fallback to legacy badge selector, still excluding non-genre badges
            val fallback = document.select("span.inline-flex.items-center.rounded")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .filterNot { isStatusText(it) || isTypeText(it) || isDemographicText(it) }
            if (fallback.isNotEmpty()) {
                val seen = mutableSetOf<String>()
                fallback.filter { seen.add(it.lowercase(Locale.ROOT)) }.joinToString(", ")
            } else {
                // Also check for genuine genre links like /library?genre=
                val genreLinks = document.select("a[href*=\"genre=\"]").map { it.text().trim() }.filter { it.isNotEmpty() }.filterNot { isStatusText(it) || isTypeText(it) || isDemographicText(it) }
                if (genreLinks.isNotEmpty()) {
                    val seen = mutableSetOf<String>()
                    genreLinks.filter { seen.add(it.lowercase(Locale.ROOT)) }.joinToString(", ")
                } else {
                    ""
                }
            }
        }

        val groupName = document.selectFirst("a[href^='/groups/']")?.text()
        if (!groupName.isNullOrEmpty()) {
            author = groupName
            artist = groupName
        }

        document.select(".flex.items-baseline.justify-between.gap-2").forEach { row ->
            val label = row.selectFirst("span.text-\\[var\\(--color-text3\\)\\]")?.text()
            val value = row.selectFirst("span.text-\\[var\\(--color-text2\\)\\]")?.text()
            if (label == "Autor") author = value
            if (label == "Arte") artist = value
        }
    }

    private fun isStatusText(text: String): Boolean {
        val t = text.lowercase().replace("í", "i").replace("á", "a").replace("é", "e").replace("ó", "o").trim()
        return t == "completado" || t == "complet" || t == "finalizado" ||
            t == "en curso" || t == "en emision" || t == "emision" || t == "curso" ||
            t == "pausa" || t == "en pausa" || t == "hiatus" ||
            t == "cancelado" || t == "cancelled" || t == "ongoing" || t == "completed" ||
            t.contains("completado") || t.contains("finalizado") || t.contains("completed") ||
            t.contains("en curso") || t.contains("emision") || t.contains("ongoing") ||
            t.contains("pausa") || t.contains("hiatus") ||
            t.contains("cancelado") || t.contains("cancelled")
    }

    private fun isTypeText(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT).trim()
        return t == "manga" || t == "manhwa" || t == "manhua" || t == "novela" || t == "novel" || t == "doujinshi" || t == "otro" || t == "other"
    }

    private fun isDemographicText(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT).trim()
        return t == "shounen" || t == "shonen" || t == "shoujo" || t == "shojo" || t == "seinen" || t == "josei" || t == "kodomo"
    }

    private suspend fun fetchAllChapters(mangaUrl: String): List<SChapter> {
        val slug = mangaUrl.removeSuffix("/").substringAfterLast("/")
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val response = client.get("$baseUrl/comics/$slug/chapters?page=$page")
            val document = response.asJsoup()
            val chapters = parseChapterList(document)
            if (chapters.isEmpty()) break
            allChapters.addAll(chapters)

            val currentPage = response.headers["x-page"]?.toIntOrNull() ?: break
            val totalPages = response.headers["x-pages"]?.toIntOrNull() ?: break

            if (currentPage >= totalPages) break
            page++
        }
        return allChapters
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        var elements = document.select("a[data-chapter-num], a[data-chapter-id]")
        if (elements.isEmpty()) {
            elements = document.select("a[href*=\"/auth/login\"]")
        }

        return elements.mapIndexed { num, el ->
            SChapter.create().apply {
                val rawHref = el.attr("href")
                val absoluteHref = el.absUrl("href").ifEmpty { rawHref }
                val isPremium = rawHref.contains("/auth/login") ||
                    absoluteHref.contains("/auth/login") ||
                    el.selectFirst("[class*=coin], [class*=Coin]") != null ||
                    el.text().contains("coin", ignoreCase = true) ||
                    el.attr("data-chapter-label").contains("coin", ignoreCase = true)

                val effectiveUrl = if (isPremium) {
                    val premiumPath = extractRedirectPath(absoluteHref) ?: absoluteHref
                    "$premiumPath#premium-${el.attr("data-chapter-num").ifBlank { num.toString() }}"
                } else {
                    absoluteHref
                }
                setUrlWithoutDomain(effectiveUrl)

                chapter_number = el.attr("data-chapter-num").toFloatOrNull() ?: num.toFloat()

                val rawLabel = el.attr("data-chapter-label").trim()
                val fallbackText = el.text().trim()
                var baseName = when {
                    rawLabel.isNotEmpty() -> rawLabel
                    fallbackText.isNotEmpty() -> fallbackText
                    else -> "Capítulo ${chapter_number.toInt()}"
                }

                if (isPremium) {
                    if (!baseName.contains("\uD83D\uDD12")) {
                        val coinBadge = el.select("span").firstOrNull { it.text().trim().matches(Regex("\\d+")) }?.text()?.trim()
                            ?: Regex("(\\d+)\\s*coin", RegexOption.IGNORE_CASE).find(baseName)?.groupValues?.getOrNull(1)
                        baseName = if (coinBadge != null && !baseName.contains("coin", ignoreCase = true)) {
                            "\uD83D\uDD12 $baseName ($coinBadge coin)"
                        } else {
                            "\uD83D\uDD12 $baseName"
                        }
                    }
                }

                name = baseName
                date_upload = el.selectFirst(".text-\\[0\\.65rem\\]")?.let { parseDate(it.text()) } ?: 0L
            }
        }
    }

    private fun extractRedirectPath(href: String): String? {
        return try {
            val httpUrl = href.toHttpUrl()
            httpUrl.queryParameter("redirect")
        } catch (_: Exception) {
            val idx = href.indexOf("redirect=")
            if (idx == -1) return null
            val encoded = href.substring(idx + "redirect=".length).substringBefore("&")
            try {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                encoded
            }
        }?.takeIf { it.isNotEmpty() }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(baseUrl + chapter.url).asJsoup()
        return document.select("img.page-img, .page-wrap img").mapIndexed { index, element ->
            val imageUrl = element.attr("abs:data-src").ifEmpty { element.attr("abs:src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(genresList),
    )
}
