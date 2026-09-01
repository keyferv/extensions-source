package eu.kanade.tachiyomi.extension.es.tojimangas

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
import kotlinx.serialization.Serializable
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

@Source
abstract class TojiMangas : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Accept-Language", "es-ES,es;q=0.9")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host == "cdn.lectortmoo.com") {
            chain.proceed(
                request.newBuilder()
                    .removeHeader("Referer")
                    .removeHeader("Origin")
                    .build(),
            )
        } else {
            chain.proceed(request)
        }
    }

    private fun buildCatalogUrl(page: Int, order: String?, query: String? = null): HttpUrl {
        val builder = "$baseUrl/manga".toHttpUrl().newBuilder()
        if (page > 1) builder.addQueryParameter("page", page.toString())
        if (!order.isNullOrBlank()) builder.addQueryParameter("order", order)
        if (!query.isNullOrBlank()) builder.addQueryParameter("search", query)
        return builder.build()
    }

    private fun parseCatalog(document: Document): MangasPage {
        val mangas = document.select("a.serie-card[href^='/manga/']").mapNotNull { card ->
            val href = card.attr("abs:href")
            if (href.isBlank()) return@mapNotNull null
            // Extract slug from /manga/{slug}
            val url = runCatching { href.toHttpUrl() }.getOrNull()
                ?.encodedPath
                ?.takeIf { it.startsWith("/manga/") }
                ?: href.substringAfter(baseUrl).substringBefore("?").substringBefore("#")
            val normalizedUrl = if (url.startsWith("/manga/")) url else "/manga/$url"
            // Title is optional: some cards omit cover/title metadata
            val title = card.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: card.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: card.attr("alt").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val thumb = card.selectFirst(".cover-wrap img[src]")?.attr("abs:src")
                ?: card.selectFirst("img[src]")?.attr("abs:src")

            SManga.create().apply {
                this.title = title
                this.url = normalizedUrl.substringBefore("?")
                thumbnail_url = thumb?.takeIf { it.isNotBlank() }
            }
        }

        val hasNextPage = document.selectFirst("button:contains(Cargar más)") != null ||
            document.selectFirst("a:contains(Cargar más)") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = buildCatalogUrl(page, order = null)
        val document = client.get(url).asJsoup()
        return parseCatalog(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // Force network to avoid default 10-minute cache masking updates
        val url = buildCatalogUrl(page, order = "latest")
        val document = client.get(url, CacheControl.FORCE_NETWORK).asJsoup()
        return parseCatalog(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()
        return if (trimmed.isNotEmpty()) {
            // Search via catalog search flow; site exposes /manga?search=
            val url = buildCatalogUrl(page, order = null, query = trimmed)
            val document = client.get(url).asJsoup()
            // Fallback: some deployments expose /manga?title= or ?q= ; if no results try ?s=
            val result = parseCatalog(document)
            if (result.mangas.isNotEmpty() || page > 1) {
                result
            } else {
                val altUrl = "$baseUrl/manga".toHttpUrl().newBuilder().apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                    addQueryParameter("s", trimmed)
                    addQueryParameter("post_type", "manga")
                }.build()
                val altDoc = client.get(altUrl).asJsoup()
                parseCatalog(altDoc)
            }
        } else {
            // No query: delegate to catalog parsing (filters not currently exposed)
            val url = buildCatalogUrl(page, order = null)
            val document = client.get(url).asJsoup()
            parseCatalog(document)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val detailInfo = document.selectFirst("article div.flex-1.min-w-0") ?: document

        title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("—").trim()

        thumbnail_url = document.selectFirst("article img[src]")?.attr("abs:src")
            ?: document.selectFirst("main img[src]")?.attr("abs:src")
            ?: document.selectFirst(".aspect-card img[src]")?.attr("abs:src")
            ?: document.selectFirst("figure img[src]")?.attr("abs:src")

        // Prefer full synopsis in .prose-tmo; do not replace with short "En resumen" when prose-tmo exists
        val synopsis = document.selectFirst(".prose-tmo")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("#synopsis-content")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                // Fallback: element after SINOPSIS kicker
                val sinopsisHeader = document.selectFirst(":containsOwn(SINOPSIS), :containsOwn(Sinopsis)")
                sinopsisHeader?.parent()?.selectFirst(".prose-tmo")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            }
        val shortSummary = document.selectFirst("#tldr-box p")?.text()?.trim()
        description = synopsis ?: shortSummary

        genre = detailInfo.select("a.genre-chip").map { it.text().trim() }.filter { it.isNotEmpty() }
            .ifEmpty {
                // Fallback for legacy markup
                detailInfo.select("a[href*='genre=']").map { it.text().trim() }.filter { it.isNotEmpty() }
            }
            .joinToString(", ").ifBlank { null }

        val statusText = detailInfo.select("div.flex.flex-wrap.items-center.gap-2.mb-3 *")
            .map { it.text().trim() }
            .firstOrNull { text ->
                text.contains("En curso", ignoreCase = true) ||
                    text.contains("Finalizado", ignoreCase = true) ||
                    text.contains("Completado", ignoreCase = true) ||
                    text.contains("Pausa", ignoreCase = true) ||
                    text.contains("Hiatus", ignoreCase = true) ||
                    text.contains("Abandonado", ignoreCase = true) ||
                    text.contains("Cancelado", ignoreCase = true) ||
                    text.contains("En emisión", ignoreCase = true)
            }
        status = parseStatus(statusText)
    }

    private fun parseStatus(text: String?): Int = when {
        text.isNullOrBlank() -> SManga.UNKNOWN
        text.contains("En curso", ignoreCase = true) || text.contains("en emisión", ignoreCase = true) -> SManga.ONGOING
        text.contains("Completado", ignoreCase = true) || text.contains("Finalizado", ignoreCase = true) -> SManga.COMPLETED
        text.contains("Pausa", ignoreCase = true) || text.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        text.contains("Abandonado", ignoreCase = true) || text.contains("Cancel", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private val chapterSlugRegex = Regex("""-capitulo-(\d+)(?:-(\d+))?$""")

    private fun parseChapterNumber(href: String): Float? {
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull() ?: href.substringBefore("?").substringBefore("#")
        val match = chapterSlugRegex.find(path) ?: return null
        val major = match.groupValues[1]
        val minor = match.groupValues[2]
        return if (minor.isNotEmpty()) {
            "$major.$minor".toFloatOrNull() ?: major.toFloatOrNull()
        } else {
            major.toFloatOrNull()
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = document.select("a.chapter-btn[href]").mapNotNull { link ->
            val href = link.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapterNumber = parseChapterNumber(href) ?: return@mapNotNull null
            val name = link.selectFirst("span.font-bold")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Capítulo ${chapterNumber.toString().removeSuffix(".0")}"

            SChapter.create().apply {
                this.name = name
                url = href.substringAfter(baseUrl)
                this.chapter_number = chapterNumber
            }
        }.sortedByDescending { it.chapter_number }

        if (chapters.isNotEmpty()) return chapters

        val startReadingLink = document.selectFirst("#start-reading-btn[href]") ?: return emptyList()
        return listOf(
            SChapter.create().apply {
                name = "Capítulo 0"
                url = startReadingLink.attr("abs:href").substringAfter(baseUrl)
                chapter_number = 0f
            },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.url
            .substringBefore("?")
            .substringBefore("#")
            .let { raw ->
                val path = runCatching { raw.toHttpUrl().encodedPath }.getOrNull()
                    ?: runCatching { (baseUrl + raw).toHttpUrl().encodedPath }.getOrNull()
                    ?: raw
                path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: raw.trimEnd('/').substringAfterLast('/')
            }

        val response = client.get("$baseUrl/api/chapters/$slug")
        val dto = response.parseAs<ChapterApiResponse>()
        return dto.pages.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/manga/")) return null
        val document = client.get(url.toString()).asJsoup()
        return parseMangaDetails(document).apply {
            this.url = path
        }
    }
}

@Serializable
class ChapterApiResponse(private val data: ChapterApiData) {
    val pages: List<String> get() = data.pages
}

@Serializable
class ChapterApiData(val pages: List<String> = emptyList())
