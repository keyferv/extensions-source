package eu.kanade.tachiyomi.extension.es.mangalovers

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class CatalogResponseDto(
    val data: List<MangaDto>,
    val meta: MetaDto,
)

@Serializable
class MetaDto(
    val page: Int,
    val totalPages: Int,
)

@Serializable
class MangaDto(
    val name: String,
    val slug: String,
    val cover: String? = null,
    val fallbackCover: String? = null,
)

@Serializable
class LatestMangaDto(
    val name: String,
    val slug: String,
    val cover: String? = null,
    val fallbackCover: String? = null,
)

@Serializable
class MangaDetailsDto(
    val name: String,
    val slug: String,
    val cover: String? = null,
    val fallbackCover: String? = null,
    val status: String? = null,
    val type: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: Int,
    val name: String,
    val chapterNumber: Float,
    val publishedAt: String? = null,
)

@Serializable
class PagesResponseDto(
    val pages: List<PageDto>,
)

@Serializable
class PageDto(
    val id: Int,
    val url: String,
)

fun MangaDto.toSManga() = SManga.create().apply {
    title = name
    url = slug
    thumbnail_url = cover ?: fallbackCover
}

fun LatestMangaDto.toSManga() = SManga.create().apply {
    title = name
    url = slug
    thumbnail_url = cover ?: fallbackCover
}

fun MangaDetailsDto.toSManga() = SManga.create().apply {
    title = this@toSManga.name
    url = this@toSManga.slug
    thumbnail_url = this@toSManga.cover ?: this@toSManga.fallbackCover
    description = buildString {
        this@toSManga.summary?.takeIf { it.isNotBlank() }?.let(::append)
        this@toSManga.type?.takeIf { it.isNotBlank() }?.let {
            if (isNotEmpty()) append("\n\n")
            append("Type: ").append(it)
        }
    }.ifEmpty { null }
    status = parseStatus(this@toSManga.status)
    genre = this@toSManga.genres.joinToString().ifEmpty { null }
}

fun ChapterDto.toSChapter(mangaSlug: String) = SChapter.create().apply {
    url = "/manga/$mangaSlug/capitulo/$id"
    name = this@toSChapter.name
    chapter_number = this@toSChapter.chapterNumber
    date_upload = this@toSChapter.publishedAt
        ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        ?: 0L
}

private fun parseStatus(status: String?): Int {
    val normalizedStatus = status?.lowercase() ?: return SManga.UNKNOWN
    return when {
        normalizedStatus in setOf("ongoing", "en emisión", "en emision", "publishing", "activo") -> SManga.ONGOING
        normalizedStatus in setOf("completed", "complete", "finalizado", "terminado") -> SManga.COMPLETED
        normalizedStatus in setOf("hiatus", "en pausa", "pausado", "pausado por el autor (hiatus)") -> SManga.ON_HIATUS
        normalizedStatus in setOf("cancelled", "canceled", "cancelado", "abandonado", "abandonado por el scan") -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }
}
