package eu.kanade.tachiyomi.extension.es.mhscans

import eu.kanade.tachiyomi.source.model.SChapter
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale

internal const val MAX_CHAPTER_PAGES = 100

internal val NUMBER_REGEX = Regex("""\d+""")
internal val CHAPTER_NUM_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)""")
internal val OLD_CHAPTER_REGEX = Regex("""/series/([^/]+)/capitulo-([0-9.]+)""", RegexOption.IGNORE_CASE)

internal fun normalizeMangaPath(rawUrl: String, baseUrl: String = ""): String {
    val clean = if (baseUrl.isNotEmpty()) rawUrl.substringAfter(baseUrl).trimStart('/') else rawUrl.trimStart('/')
    if (clean.startsWith("series/")) {
        val slug = clean.removePrefix("series/").trim('/')
        return if (slug.startsWith("mhscans-")) "/comics/$slug" else "/comics/mhscans-$slug"
    }
    if (clean.startsWith("comics/")) {
        return "/$clean"
    }
    if (!clean.contains('/')) {
        return "/comics/$clean"
    }
    return if (clean.startsWith("/")) clean else "/$clean"
}

internal fun parseChapterNumber(name: String, numStr: String = ""): Float = numStr.toFloatOrNull() ?: CHAPTER_NUM_REGEX.find(name)?.value?.toFloatOrNull() ?: -1f

internal fun parseRelativeDate(dateStr: String?, knownDate: Long = 0L): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val trimmed = dateStr.trim().lowercase(Locale.ROOT)
    val number = NUMBER_REGEX.find(trimmed)?.value?.toIntOrNull() ?: return 0L
    val calendarField = when {
        // "sem" must come before the bare "m" shorthand: "Hace 3 sem" also ends with "m".
        trimmed.contains("sem") || trimmed.endsWith("w") -> Calendar.WEEK_OF_YEAR
        trimmed.contains("min") || trimmed.endsWith("m") -> Calendar.MINUTE
        trimmed.contains("hora") || trimmed.endsWith("h") -> Calendar.HOUR_OF_DAY
        trimmed.contains("día") || trimmed.contains("dia") || trimmed.endsWith("d") -> Calendar.DAY_OF_MONTH
        trimmed.contains("mes") -> Calendar.MONTH
        trimmed.contains("año") || trimmed.contains("ano") || trimmed.endsWith("y") -> Calendar.YEAR
        else -> return 0L
    }

    val newest = Calendar.getInstance().apply { add(calendarField, -number) }
    val oldest = Calendar.getInstance().apply { add(calendarField, -(number + 1)) }

    // The site only renders relative labels and caches chapter pages (s-maxage=60), so a date
    // already stored by the app is kept while it still fits the label instead of drifting forward.
    val oldestWithSlack = oldest.timeInMillis - PAGE_CACHE_SLACK_MS
    return knownDate.takeIf { it in oldestWithSlack..newest.timeInMillis } ?: newest.timeInMillis
}

internal const val MILLIS_PER_MINUTE = 60_000L
internal const val MILLIS_PER_DAY = 24L * 60L * MILLIS_PER_MINUTE
private const val PAGE_CACHE_SLACK_MS = 5L * MILLIS_PER_MINUTE

internal fun parseChapterElements(
    elements: List<Element>,
    knownDates: Map<String, Long> = emptyMap(),
    chapterFactory: () -> SChapter = { SChapter.create() },
): List<SChapter> {
    val chapters = mutableListOf<SChapter>()
    for (element in elements) {
        val href = element.attr("href")
        if (!href.startsWith("/leer/")) continue

        val chapter = chapterFactory().apply {
            url = href
            val label = element.attr("data-chapter-label")
            name = label.ifEmpty { element.selectFirst("[data-cl-title], .text-sm")?.text().orEmpty() }

            val numStr = element.attr("data-chapter-num")
            chapter_number = parseChapterNumber(name, numStr)

            val dateText = element.selectFirst("[data-cl-title] + div > span, .text-\\[0\\.65rem\\], span:containsOwn(Hace)")?.text()
            date_upload = parseRelativeDate(dateText, knownDates[href] ?: 0L)
        }
        chapters.add(chapter)
    }
    return chapters
}

internal suspend fun walkChapterPages(
    initialPage: Int = 1,
    maxPages: Int = MAX_CHAPTER_PAGES,
    fetchPage: suspend (page: Int) -> Pair<List<SChapter>, Int?>,
): List<SChapter> {
    val chapters = mutableListOf<SChapter>()
    var page = initialPage
    var totalPages = 1
    var fetched = 0

    while (page <= totalPages && fetched < maxPages) {
        val (pageChapters, reportedTotalPages) = fetchPage(page)
        if (pageChapters.isEmpty()) break
        chapters += pageChapters
        fetched++

        if (reportedTotalPages != null && reportedTotalPages > totalPages) {
            totalPages = reportedTotalPages
        }
        page++
    }

    return chapters
}
