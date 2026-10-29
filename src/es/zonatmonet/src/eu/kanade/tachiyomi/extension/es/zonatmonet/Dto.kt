package eu.kanade.tachiyomi.extension.es.zonatmonet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListingResponseDto(
    val data: ListingDataDto? = null,
)

@Serializable
class ListingDataDto(
    val items: List<MangaDto>,
    val pagination: PaginationDto? = null,
)

@Serializable
class TopViewsResponseDto(
    val data: TopViewsDataDto? = null,
)

@Serializable
class TopViewsDataDto(
    val items: List<MangaDto>,
)

@Serializable
class SingleMangaResponseDto(
    val data: MangaDto? = null,
)

@Serializable
class SingleChapterResponseDto(
    val data: SingleDataDto? = null,
)

@Serializable
class SingleDataDto(
    val manga: MangaDto? = null,
    val chapter: ChapterDetailsDto? = null,
)

@Serializable
class ChapterListResponseDto(
    val data: ChapterListDataDto? = null,
)

@Serializable
class ChapterListDataDto(
    val items: List<ChapterItemDto>,
    val pagination: PaginationDto? = null,
)

@Serializable
class PaginationDto(
    @SerialName("has_next")
    val hasNext: Boolean,
    @SerialName("total_pages")
    val totalPages: Int,
)

@Serializable
class MangaDto(
    val slug: String,
    val title: String,
    val overview: String? = null,
    val cover: String? = null,
    val author: List<AuthorDto>? = null,
    val status: List<Int>? = null,
    val genres: List<Int>? = null,
    @SerialName("alt_titles")
    val altTitles: List<String>? = null,
    val synonyms: List<SynonymDto>? = null,
)

@Serializable
class AuthorDto(
    val name: String,
)

@Serializable
class SynonymDto(
    val text: String,
)

@Serializable
class ChapterItemDto(
    val id: Int,
    @SerialName("chapter_number")
    val chapterNumber: String,
    val title: String,
    val slug: String,
    @SerialName("release_date")
    val releaseDate: String? = null,
)

@Serializable
class ChapterDetailsDto(
    val jit: String,
    val images: List<ChapterImageDto>,
)

@Serializable
class ChapterImageDto(
    @SerialName("image_url")
    val imageUrl: String,
    @SerialName("page_number")
    val pageNumber: Int,
)
