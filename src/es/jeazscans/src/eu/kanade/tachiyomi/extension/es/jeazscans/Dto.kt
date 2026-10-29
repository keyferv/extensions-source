package eu.kanade.tachiyomi.extension.es.jeazscans

import android.util.Base64
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class ChaptersPageDto(
    val success: Boolean = false,
    val chapters: List<ChaptersApiChapter> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_offset") val nextOffset: Int? = null,
) {
    fun toChapterPage(): ChapterPage = ChapterPage(chapters, hasMore, nextOffset)
}

@Serializable
class ChaptersApiChapter(
    val id: Long? = null,
    val number: String? = null,
    val title: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("is_locked") val isLocked: Boolean = false,
    @SerialName("price") val price: JsonElement? = null,
    @SerialName("payment_until") val paymentUntil: String? = null,
) {
    /**
     * Pure mapping of an API chapter record into [ChapterData], free of any
     * host-app runtime type.
     *
     * Unlocked records require a float-parseable `number` because it builds the
     * reader URL; such records return `null` and are skipped. Locked (paid)
     * records are always kept so they surface in the chapter list: a best-effort
     * number is derived from `title`/`id` when `number` is missing or malformed,
     * and they receive a unique non-readable [LOCKED_READER_URL] path so they can
     * never be opened by accident or deduplicated with another locked chapter.
     */
    fun toChapterData(slug: String, baseUrl: String): ChapterData? {
        val chapterNumber = number?.toFloatOrNull()

        if (!isLocked) {
            val unlockedNumber = chapterNumber ?: return null
            val baseName = title?.takeIf { it.isNotBlank() }
                ?: "Chapter ${unlockedNumber.toString().removeSuffix(".0")}"
            val chapterUrl = "$baseUrl/leer/$slug/capitulo-$number"
            return ChapterData(
                readerUrl = chapterUrl.substringAfter(baseUrl),
                chapterNumber = unlockedNumber,
                name = baseName,
                dateUpload = parseChapterDate(publishedAt),
            )
        }

        val resolvedNumber = chapterNumber
            ?: title?.let { CHAPTER_TITLE_NUMBER_REGEX.find(it)?.value?.toFloatOrNull() }
            ?: id?.toFloat()
        val baseName = title?.takeIf { it.isNotBlank() }
            ?: resolvedNumber?.let { "Chapter ${it.toString().removeSuffix(".0")}" }
            ?: "Chapter"
        val lockedUrl = "$LOCKED_READER_URL/${id ?: number ?: resolvedNumber}"
        return ChapterData(
            readerUrl = lockedUrl,
            chapterNumber = resolvedNumber ?: 0f,
            name = baseName,
            dateUpload = parseChapterDate(publishedAt),
            isLocked = true,
            priceCoins = (price as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt(),
            paymentUntilEpoch = parsePaymentUntil(paymentUntil),
        )
    }

    /**
     * Materialize a host-app [SChapter], delegating the pure mapping to
     * [toChapterData]. Locked chapters use the decorated display name from
     * [ChapterData.displayName].
     */
    fun toSChapter(slug: String, baseUrl: String): SChapter? {
        val data = toChapterData(slug, baseUrl) ?: return null
        return SChapter.create().apply {
            url = data.readerUrl
            chapter_number = data.chapterNumber
            name = data.displayName()
            date_upload = data.dateUpload
        }
    }
}

/**
 * Lock symbol prepended to locked (paid) chapter names in the chapter list.
 */
internal const val LOCK_SYMBOL = "🔒"

/**
 * Non-readable relative URL prefix used for locked (paid) chapters so tapping
 * them never opens a reader. The suffix keeps each locked chapter unique because
 * the app deduplicates chapters with identical URLs. The site emits
 * `href="javascript:void(0)"` for locked chapters, but a `javascript:` scheme is
 * not a safe relative URL for request building. This extension-controlled path
 * never matches a real reader route; `pageListRequest` guards on it and throws a
 * clear error before any request URL is constructed.
 */
internal const val LOCKED_READER_URL = "/locked"

/**
 * First decimal-looking number inside a chapter title (e.g. `17` in
 * `Capítulo 17`). Used as a fallback number for locked records whose API
 * `number` field is missing or malformed, so such records stay in the list
 * with a sensible chapter number instead of being dropped.
 */
private val CHAPTER_TITLE_NUMBER_REGEX: Regex = Regex("""\d+(?:\.\d+)?""")

/**
 * Pure, host-app-free representation of a parsed API chapter. Unit tests can
 * verify the mapping without touching `SChapter.create()`.
 */
class ChapterData(
    val readerUrl: String,
    val chapterNumber: Float,
    val name: String,
    val dateUpload: Long,
    val isLocked: Boolean = false,
    val priceCoins: Int? = null,
    val paymentUntilEpoch: Long? = null,
) {
    /**
     * Chapter-list display name. Unlocked chapters are returned verbatim.
     * Locked chapters are prefixed with [LOCK_SYMBOL] (e.g. `🔒 Capítulo 17`).
     * Price/coin and free-until countdown metadata is intentionally not rendered
     * in the list: the lock marker alone identifies paid content.
     */
    fun displayName(): String {
        if (!isLocked) return name
        return "$LOCK_SYMBOL $name"
    }
}

class ChapterPage(
    val chapters: List<ChaptersApiChapter> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
) {
    val isEmpty: Boolean get() = chapters.isEmpty()
}

@Serializable
class ApiLectorResponse(
    val success: Boolean = false,
    @SerialName("manga_titulo") val mangaTitulo: String = "",
    @SerialName("manga_slug") val mangaSlug: String = "",
    @SerialName("manga_portada") val mangaPortada: String = "",
    @SerialName("manga_tipo") val mangaTipo: String = "",
    @SerialName("cap_numero") val capNumero: String = "",
    val paginas: List<ApiLectorPage> = emptyList(),
    @SerialName("total_paginas") val totalPaginas: Int = 0,
    val anterior: String? = null,
    val siguiente: String? = null,
)

@Serializable
class ApiLectorPage(
    val orden: Int,
    @SerialName("data_verify") val dataVerify: String,
) {
    fun decodeImageUrl(): String {
        val decoded = Base64.decode(dataVerify, Base64.DEFAULT)
        return String(decoded, Charsets.UTF_8).reversed()
    }
}

@Serializable
class SearchResponseItem(
    private val id: Int,
    private val titulo: String,
    private val portada: String?,
    private val tipo: String? = null,
) {
    fun toSManga(baseUrl: String): SManga? {
        if (id == -1 || titulo.isBlank()) return null
        return SManga.create().apply {
            url = "/manga.php?id=$id"
            title = titulo
            if (!portada.isNullOrBlank()) {
                thumbnail_url = if (portada.startsWith("http")) portada else "$baseUrl/${portada.trimStart('/')}"
            }
        }
    }
}
