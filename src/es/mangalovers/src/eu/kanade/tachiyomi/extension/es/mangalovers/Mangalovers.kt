package eu.kanade.tachiyomi.extension.es.mangalovers

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class Mangalovers : KeiSource() {

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("search", "")
            .addQueryParameter("status", "")
            .addQueryParameter("type", "")
            .addQueryParameter("provider", "")
            .addQueryParameter("sort", "chapters")
            .addQueryParameter("order", "desc")
            .addQueryParameter("genres", "")
            .addQueryParameter("excludeGenres", "")
            .addQueryParameter("read", "")
            .addQueryParameter("limit", "32")
            .build()
        val response = client.get(url)
        val result = response.parseAs<CatalogResponseDto>()
        return MangasPage(result.data.map(MangaDto::toSManga), result.meta.page < result.meta.totalPages)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        // The latest endpoint has no page parameter, so only its first result set is exposed.
        val result = client.get("$baseUrl/api/manga/latest?limit=30").parseAs<List<LatestMangaDto>>()
        return MangasPage(result.map(LatestMangaDto::toSManga), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("limit", "32")
            .addQueryParameter("sort", "az")
            .addQueryParameter("page", page.toString())
            .build()
        val result = client.get(url).parseAs<CatalogResponseDto>()
        return MangasPage(result.data.map(MangaDto::toSManga), result.meta.page < result.meta.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.takeIf { it.size == 2 && it.first() == "manga" }?.last() ?: return null
        return client.get("$baseUrl/api/manga/$slug").parseAs<MangaDetailsDto>().toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val result = client.get("$baseUrl/api/manga/${manga.url}").parseAs<MangaDetailsDto>()
        return SMangaUpdate(
            manga = if (fetchDetails) result.toSManga() else manga,
            chapters = if (fetchChapters) result.chapters.map { it.toSChapter(result.slug) } else chapters,
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val path = chapter.url.removePrefix("/manga/")
        val segments = path.split("/", limit = 4)
        val slug = segments.getOrNull(0) ?: return emptyList()
        val chapterId = segments.getOrNull(2) ?: return emptyList()
        val result = client.get("$baseUrl/api/manga/capitulo/$slug/$chapterId/pages")
            .parseAs<PagesResponseDto>()
        return result.pages.mapIndexed { index, page -> Page(index, imageUrl = page.url) }
    }
}
