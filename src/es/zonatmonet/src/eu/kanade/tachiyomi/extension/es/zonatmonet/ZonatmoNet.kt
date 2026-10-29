package eu.kanade.tachiyomi.extension.es.zonatmonet

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class ZonatmoNet : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = apiUrl.newBuilder()
            .addPathSegments("tops/views/month")
            .addQueryParameter("postType", "any")
            .addQueryParameter("postsPerPage", "50")
            .build()

        val dto = client.get(url).parseAs<TopViewsResponseDto>()
        val mangas = dto.data
            ?.items
            .orEmpty()
            .mapNotNull { it.toSManga() }

        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = apiUrl.newBuilder()
            .addPathSegments("listing/manga")
            .addQueryParameter("page", page.toString())
            .build()

        val dto = client.get(url).parseAs<ListingResponseDto>()
        val mangas = dto.data
            ?.items
            .orEmpty()
            .mapNotNull { it.toSManga() }

        val hasNextPage = dto.data?.pagination?.hasNext ?: false

        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        val selectedGenres = genreFilter?.state?.filter { it.state }?.map { it.value }.orEmpty()

        val typeFilter = filters.firstInstanceOrNull<TypeFilter>()
        val selectedTypes = typeFilter?.state?.filter { it.state }?.map { it.value }.orEmpty()

        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()
        val selectedStatuses = statusFilter?.state?.filter { it.state }?.map { it.value }.orEmpty()

        val url = listingMangaApiUrl(
            page = page,
            searchQuery = query.trim().takeIf { it.isNotEmpty() },
            genres = selectedGenres,
            types = selectedTypes,
            statuses = selectedStatuses,
        )

        return listingMangaParse(client.get(url).parseAs<ListingResponseDto>())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val baseHost = baseUrl.toHttpUrl().host
        if (!(url.host == baseHost || url.host.endsWith(".$baseHost"))) return null
        val slug = url.pathSegments.getOrNull(1)
            ?.takeIf { url.pathSegments.getOrNull(0) == "manga" && it.isNotBlank() }
            ?: return null
        return fetchMangaDetails(SManga.create().apply { this.url = slug })
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    private fun listingMangaParse(dto: ListingResponseDto): MangasPage {
        val mangas = dto.data
            ?.items
            .orEmpty()
            .mapNotNull { it.toSManga() }

        val hasNextPage = dto.data?.pagination?.hasNext ?: false

        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("manga")
        .addPathSegment(manga.url)
        .build()
        .toString()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsAsync = async {
            if (fetchDetails) fetchMangaDetails(manga) else manga
        }
        val chaptersAsync = async {
            if (fetchChapters) fetchChapterList(manga.url) else chapters
        }
        SMangaUpdate(detailsAsync.await(), chaptersAsync.await())
    }

    private suspend fun fetchMangaDetails(manga: SManga): SManga {
        val dto = client.get(singleMangaApiUrl(manga.url)).parseAs<SingleMangaResponseDto>()
        val data = dto.data ?: throw Exception("No se pudo obtener los detalles del manga")

        return data.toSManga() ?: throw Exception("Error al parsear los detalles del manga")
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slugs = chapter.url.split("/", limit = 2)
            .takeIf { it.size == 2 }
            ?: return baseUrl

        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(slugs[0])
            .addPathSegment(slugs[1])
            .build()
            .toString()
    }

    private suspend fun fetchChapterList(mangaSlug: String): List<SChapter> {
        val firstPageDto = client.get(chapterListApiUrl(mangaSlug = mangaSlug, page = 1)).parseAs<ChapterListResponseDto>()
        val chapters = firstPageDto.data?.items.orEmpty().toMutableList()

        val totalPages = firstPageDto.data?.pagination?.totalPages ?: 1
        for (page in 2..totalPages) {
            chapters += client.get(chapterListApiUrl(mangaSlug = mangaSlug, page = page))
                .parseAs<ChapterListResponseDto>().data?.items.orEmpty()
        }

        return chapters
            .distinctBy { item -> item.id }
            .sortedByDescending { item -> item.chapterNumber.toFloatOrNull() ?: -1f }
            .map { item -> item.toSChapter(mangaSlug) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl.toHttpUrl().resolve(chapter.url)!!

        val pathSegments = chapterUrl.pathSegments
        val mangaSlug = pathSegments[pathSegments.size - 2]
        val chapterSlug = pathSegments[pathSegments.size - 1]

        val data = client.get(singleChapterApiUrl(mangaSlug, chapterSlug)).parseAs<SingleChapterResponseDto>()
            .data
            ?.chapter
            ?: throw Exception("No se pudo obtener las páginas del capítulo")

        return data.images
            .sortedBy(ChapterImageDto::pageNumber)
            .mapIndexed { index, image ->
                Page(
                    index = index,
                    imageUrl = cdnImageUrl(data.jit, image.imageUrl),
                )
            }
    }

    private fun listingMangaApiUrl(
        page: Int,
        searchQuery: String? = null,
        genres: List<String> = emptyList(),
        types: List<String> = emptyList(),
        statuses: List<String> = emptyList(),
    ): String {
        val url = apiUrl.newBuilder()
            .addPathSegments("listing/manga")
            .addQueryParameter("page", page.toString())

        searchQuery?.let { url.addQueryParameter("search", it) }
        for (genreId in genres) {
            url.addQueryParameter("genres[]", genreId)
        }
        for (typeId in types) {
            url.addQueryParameter("type[]", typeId)
        }
        for (statusId in statuses) {
            url.addQueryParameter("status[]", statusId)
        }

        return url.build().toString()
    }

    private fun singleMangaApiUrl(mangaSlug: String): String = apiUrl.newBuilder()
        .addPathSegment("single")
        .addPathSegment("manga")
        .addPathSegment(mangaSlug)
        .build()
        .toString()

    private fun chapterListApiUrl(mangaSlug: String, page: Int): String = apiUrl.newBuilder()
        .addPathSegment("single")
        .addPathSegment("manga")
        .addPathSegment(mangaSlug)
        .addPathSegment("chapters")
        .addQueryParameter("page", page.toString())
        .addQueryParameter("postsPerPage", CHAPTERS_PER_PAGE.toString())
        .addQueryParameter("order", "asc")
        .build()
        .toString()

    private fun singleChapterApiUrl(mangaSlug: String, chapterSlug: String): String {
        val url = apiUrl.newBuilder()
            .addPathSegment("single")
            .addPathSegment("manga")
            .addPathSegment(mangaSlug)
            .addPathSegment(chapterSlug)

        return url.build().toString()
    }

    private fun cdnImageUrl(jit: String, imageName: String): String = CDN_URL.toHttpUrl().newBuilder()
        .addPathSegment("manga")
        .addPathSegments(jit)
        .addPathSegment(imageName)
        .build()
        .toString()

    private fun MangaDto.toSManga(): SManga? {
        val mangaSlug = slug.trim().takeIf(String::isNotEmpty) ?: return null
        val mangaTitle = title.trim().takeIf(String::isNotEmpty) ?: return null

        return SManga.create().apply {
            url = mangaSlug
            title = mangaTitle
            thumbnail_url = cover.toThumbnailUrl()
            description = overview?.trim().orEmpty().ifEmpty { null }
            genre = this@toSManga.genres
                ?.mapNotNull { id -> GENRES.firstOrNull { it.second == id.toString() }?.first }
                ?.joinToString()
                ?.ifEmpty { null }
            author = this@toSManga.author
                .orEmpty()
                .map { it.name }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString()
                .ifEmpty { null }
            status = parseStatus(this@toSManga.status)
        }
    }

    private fun parseStatus(status: List<Int>?): Int = when {
        status == null -> SManga.UNKNOWN
        status.contains(12) -> SManga.ONGOING
        status.contains(19) -> SManga.COMPLETED
        status.contains(174) -> SManga.ON_HIATUS
        status.contains(198) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun ChapterItemDto.toSChapter(mangaSlug: String): SChapter = SChapter.create().apply {
        url = "$mangaSlug/$slug#$id"
        val cleanTitle = title.trim()
        name = "#$chapterNumber" + if (cleanTitle.isNotBlank()) " - $cleanTitle" else ""
        chapter_number = chapterNumber.toFloatOrNull() ?: -1f
        date_upload = dateFormat.tryParseDateTime(releaseDate)
    }

    private fun String?.toThumbnailUrl(): String? {
        val path = this?.trim().orEmpty()
        if (path.isEmpty()) return null
        if (path.startsWith("http", ignoreCase = true)) return path

        return uploadsUrl.newBuilder()
            .addEncodedPathSegments(path.removePrefix("/"))
            .build()
            .toString()
    }

    companion object {
        private const val SOURCE_HOST = "zonatmo.net"
        private const val CDN_URL = "https://cdn.zonatmo.to"
        private const val CHAPTERS_PER_PAGE = 50

        private val apiUrl = "https://$SOURCE_HOST/wp-api/api".toHttpUrl()
        private val uploadsUrl = "https://$SOURCE_HOST/wp-content/uploads".toHttpUrl()

        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}
