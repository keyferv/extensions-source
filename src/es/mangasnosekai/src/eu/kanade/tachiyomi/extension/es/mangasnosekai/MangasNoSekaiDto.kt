package eu.kanade.tachiyomi.extension.es.mangasnosekai

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
data class PayloadDto(
    val manga: List<MangaDto> = emptyList(),
)

@Serializable
data class MangaDto(
    val chapters: List<ChapterDto> = emptyList(),
)

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("es"))

@Serializable
data class ChapterDto(
    @SerialName("chapter_name") val name: String,
    @SerialName("chapter_slug") val slug: String,
    @SerialName("date_gmt") val date: String,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        name = this@ChapterDto.name
        url = "$mangaSlug/$slug"
        date_upload = try {
            dateFormat.parse(date)?.time ?: 0
        } catch (e: ParseException) {
            0
        }
    }
}

private val chapterDisplayDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("es"))

@Serializable
data class ChapterListResponse(
    @SerialName("total_chapters") val totalChapters: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("chapters_to_display") val chaptersToDisplay: List<ChapterDisplayItem> = emptyList(),
)

@Serializable
data class ChapterDisplayItem(
    val name: String = "",
    @SerialName("name_extend") val nameExtend: String = "",
    val number: String = "",
    val link: String = "",
    val date: String = "",
) {
    fun toSChapter() = SChapter.create().apply {
        val displayName = this@ChapterDisplayItem.name.trim()
        val extend = this@ChapterDisplayItem.nameExtend.trim()
        name = if (extend.isNotEmpty()) "$displayName $extend" else displayName

        url = try {
            java.net.URI(this@ChapterDisplayItem.link.trim()).path
        } catch (_: Exception) {
            this@ChapterDisplayItem.link.trim()
        }

        chapter_number = this@ChapterDisplayItem.number.toFloatOrNull() ?: 0f

        date_upload = try {
            val cleanDate = this@ChapterDisplayItem.date.replace(Regex("<[^>]*>"), "").trim()
            chapterDisplayDateFormat.parse(cleanDate)?.time ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
