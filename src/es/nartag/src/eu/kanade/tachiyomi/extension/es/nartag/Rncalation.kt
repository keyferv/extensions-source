package eu.kanade.tachiyomi.extension.es.nartag

import eu.kanade.tachiyomi.network.GET
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
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Rncalation : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/library?sort=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".lib-grid a.comic-card").mapNotNull { element ->
            val type = element.selectFirst("span.absolute.top-2.left-2")?.text()
            if (type != null && type.contains("Novel", ignoreCase = true)) {
                return@mapNotNull null
            }
            SManga.create().apply {
                setUrlWithoutDomain(element.absUrl("href"))
                title = element.selectFirst("p.leading-snug")!!.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("a.lib-page-btn--nav:last-child") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/library?sort=updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            // Description is a stable paragraph: <p class="text-[1.1rem] text-[var(--color-text1)] leading-[1.8] max-w-[48rem] m-0">
            description = document.select("p").firstOrNull {
                val cls = it.attr("class")
                cls.contains("text-[1.1rem]") && cls.contains("max-w-[48rem]")
            }?.text()?.trim()
                ?: document.selectFirst("p[class*='text-[1.1rem]']")?.text()?.trim()
                ?: document.selectFirst("div.comic-page-wrap p")?.text()?.trim()
                ?: ""

            // Status and genres are sibling spans inside <div class="flex flex-wrap items-center justify-center md:justify-start gap-1.5">
            // inside the main detail content wrapper.
            val metaContainer = document.select("div").firstOrNull { el ->
                val cls = el.attr("class")
                cls.contains("flex") && cls.contains("flex-wrap") && cls.contains("gap-1.5") && el.selectFirst("span") != null
            }

            val rawSpans = metaContainer?.select("span")?.map { it.text().trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            fun isStatusText(text: String): Boolean {
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

            val genreCandidates = rawSpans.filterNot { isStatusText(it) }
            genre = if (genreCandidates.isNotEmpty()) {
                genreCandidates.joinToString(", ") { it }
            } else {
                // Fallback to legacy badge selector, still excluding status
                document.select("span.inline-flex.items-center.rounded")
                    .filterNot { isStatusText(it.text()) }
                    .joinToString(", ") { it.text().trim() }
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
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val response = client.newCall(chapterListRequest(slug, page)).execute()
            val chapters = chapterListParse(response)
            if (chapters.isEmpty()) break
            allChapters.addAll(chapters)

            val currentPage = response.header("x-page")?.toIntOrNull() ?: break
            val totalPages = response.header("x-pages")?.toIntOrNull() ?: break

            page++
        } while (currentPage < totalPages)

        return@fromCallable allChapters.toList()
    }

    private fun chapterListRequest(slug: String, page: Int) = GET("$baseUrl/comics/$slug/chapters?page=$page", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        // Free chapters have data-chapter-id; premium/locked ones link to /auth/login
        // and carry data-chapter-num / data-chapter-label but may lack data-chapter-id.
        // Use a combined selector so locked chapters are not dropped.
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

                // Preserve original chapter route when available (redirect param).
                // Otherwise keep the login URL so premium entries remain visible.
                // Free chapters keep their reader URL unchanged.
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
                    // Ensure a clear lock/coin marker is visible in the chapter list.
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
        // href may be absolute or relative like /auth/login?redirect=%2Fcomics%2F...
        return try {
            val httpUrl = href.toHttpUrl()
            httpUrl.queryParameter("redirect")
        } catch (_: Exception) {
            // Fallback manual parsing for relative URLs
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

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img.page-img, .page-wrap img").mapIndexed { index, element ->
            val imageUrl = element.attr("abs:data-src").ifEmpty { element.attr("abs:src") }
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(genresList),
    )
}
