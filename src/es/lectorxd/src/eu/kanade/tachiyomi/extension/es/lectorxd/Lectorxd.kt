package eu.kanade.tachiyomi.extension.es.lectorxd

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
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Source
abstract class Lectorxd : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/catalogo?adult=safe&orderBy=views&filters=true&page=$page").asJsoup()

        val mangas = document.select(".manga-grid a[href]").mapNotNull { card ->
            val title = card.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null

            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(card.absUrl("href"))
                thumbnail_url = card.selectFirst("img[src]")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("a[aria-label='Next page']") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/catalogo?page=$page").asJsoup()

        val mangas = document.select(".manga-grid a[href]").mapNotNull { card ->
            val title = card.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null

            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(card.absUrl("href"))
                thumbnail_url = card.selectFirst("img[src]")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("a[aria-label='Next page']") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/catalogo".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        val document = client.get(url).asJsoup()

        val mangas = document.select(".manga-grid a[href]").mapNotNull { card ->
            val title = card.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null

            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(card.absUrl("href"))
                thumbnail_url = card.selectFirst("img[src]")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("a[aria-label='Next page']") != null

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        val slugIndex = segments.indexOfFirst { it == "manga" || it == "manhwa" || it == "manhua" }
        if (slugIndex == -1 || slugIndex + 1 >= segments.size) return null
        val slug = segments[slugIndex + 1]

        val response = client.get("$baseUrl/api/mangas/preview?slug=$slug").parseAs<MangaPreviewResponse>()
        if (!response.success) return null
        return response.manga?.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val resultingManga = if (fetchDetails) {
            getMangaByUrl("$baseUrl${manga.url}".toHttpUrl()) ?: manga
        } else {
            manga
        }

        val resultingChapters = if (fetchChapters) {
            fetchChapters(manga.url)
        } else {
            chapters
        }

        return SMangaUpdate(manga = resultingManga, chapters = resultingChapters)
    }

    private suspend fun fetchChapters(mangaUrl: String): List<SChapter> {
        val bridgeName = (1..(10..20).random())
            .map { (('a'..'z') + ('A'..'Z')).random() }
            .joinToString("")
        val extractionScript =
            """
            (function () {
                const island = document.querySelector("astro-island[component-url*='ChapterList']");
                if (!island) return;

                const props = island.getAttribute("props");
                if (!props) return;

                const root = JSON.parse(props);
                const table = Array.isArray(root) && root[0] && typeof root[0] === "object" ? root : null;
                const decoding = new Set();
                const decode = value => {
                    if (table && typeof value === "number" && Number.isInteger(value) && value >= 0 && value < table.length) {
                        if (decoding.has(value)) return null;
                        decoding.add(value);
                        const decoded = decode(table[value]);
                        decoding.delete(value);
                        return decoded;
                    }

                    if (Array.isArray(value)) {
                        if (value[0] === 1) return value.slice(1).map(decode);
                        if (value[0] === 3) return new Date(value[1]).toISOString();
                        if (value[0] === 0) return decode(value[1]);
                        return value.map(decode);
                    }

                    if (value && typeof value === "object") {
                        return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, decode(entry)]));
                    }

                    return value;
                };

                const decoded = decode(table ? root[0] : root);
                const chapters = Array.isArray(decoded?.chapters)
                    ? decoded.chapters.length === 1 && Array.isArray(decoded.chapters[0])
                        ? decoded.chapters[0]
                        : decoded.chapters
                    : [];
                window.$bridgeName.post(JSON.stringify(chapters));
            })()
            """.trimIndent()

        val decodedChapters = runWebView<List<LectorxdChapterDto>> {
            jsBridge(bridgeName) { value ->
                resolve(value.parseAs<List<LectorxdChapterDto>>())
            }

            onPageFinished {
                evaluateJs(extractionScript)
            }

            poll(1.seconds) {
                evaluateJs(extractionScript)
            }

            loadUrl("$baseUrl$mangaUrl")
        }

        return decodedChapters.map { chapter ->
            SChapter.create().apply {
                chapter_number = chapter.chapter.toFloat()
                name = chapter.title?.takeIf { it.isNotBlank() } ?: "Cap. ${chapter.chapter}"
                url = chapter.readerPath?.takeIf { it.isNotBlank() } ?: "$mangaUrl/leer/${chapter.chapter}"
                date_upload = Instant.parseOrNull(chapter.publishAt ?: chapter.dateUpload.orEmpty())
                    ?.toEpochMilliseconds() ?: 0L
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val bridgeName = (1..(10..20).random())
            .map { (('a'..'z') + ('A'..'Z')).random() }
            .joinToString("")

        val imageUrls = runWebView {
            jsBridge(bridgeName) { value ->
                resolve(value.parseAs<List<String>>())
            }

            onPageFinished {
                evaluateJs(
                    """
					(function () {
						const urls = Array.from(document.querySelectorAll('#reader-content img[data-src], #reader-content img[data-original-src]'))
							.map(image => image.dataset.src || image.dataset.originalSrc)
							.filter(Boolean);
						window.$bridgeName.post(JSON.stringify(urls));
					})();
                    """.trimIndent(),
                )
            }

            loadUrl("$baseUrl${chapter.url}")
        }

        return imageUrls.mapIndexed { index, url ->
            Page(index = index, imageUrl = url)
        }
    }
}

@Serializable
class MangaPreviewResponse(
    val success: Boolean,
    val manga: PreviewMangaDto? = null,
)

@Serializable
class PreviewMangaDto(
    private val title: String,
    private val slug: String,
    private val coverImage: String? = null,
    private val description: String? = null,
    private val type: String? = null,
    private val demographic: String? = null,
    private val status: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        this.title = this@PreviewMangaDto.title
        thumbnail_url = coverImage
        this.description = this@PreviewMangaDto.description
        genre = listOfNotNull(type, demographic).joinToString()
        this.status = when (this@PreviewMangaDto.status) {
            "en_emision" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class LectorxdChapterDto(
    val chapter: String,
    val title: String? = null,
    val publishAt: String? = null,
    val dateUpload: String? = null,
    private val url: String? = null,
    private val path: String? = null,
    private val href: String? = null,
) {
    val readerPath: String?
        get() = listOf(url, path, href).firstOrNull { !it.isNullOrBlank() }
}
