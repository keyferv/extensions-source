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
    return if (clean.startsWith("/")) clean else "/$clean"
}

internal fun parseChapterNumber(name: String, numStr: String = ""): Float = numStr.toFloatOrNull() ?: CHAPTER_NUM_REGEX.find(name)?.value?.toFloatOrNull() ?: -1f

internal fun parseRelativeDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    val trimmed = dateStr.trim().lowercase(Locale.ROOT)
    val number = NUMBER_REGEX.find(trimmed)?.value?.toIntOrNull() ?: return 0L
    val now = Calendar.getInstance()
    when {
        trimmed.contains("min") || trimmed.endsWith("m") -> now.add(Calendar.MINUTE, -number)
        trimmed.contains("hora") || trimmed.endsWith("h") -> now.add(Calendar.HOUR_OF_DAY, -number)
        trimmed.contains("día") || trimmed.contains("dia") || trimmed.endsWith("d") -> now.add(Calendar.DAY_OF_MONTH, -number)
        trimmed.contains("sem") || trimmed.endsWith("w") -> now.add(Calendar.WEEK_OF_YEAR, -number)
        trimmed.contains("mes") -> now.add(Calendar.MONTH, -number)
        trimmed.contains("año") || trimmed.contains("ano") || trimmed.endsWith("y") -> now.add(Calendar.YEAR, -number)
        else -> return 0L
    }
    return now.timeInMillis
}

internal fun parseChapterElements(
    elements: List<Element>,
    chapterFactory: () -> SChapter = { SChapter.create() },
): List<SChapter> {
    val chapters = mutableListOf<SChapter>()
    for (element in elements) {
        val href = element.attr("href")
        if (!href.startsWith("/leer/")) continue

        val chapter = chapterFactory().apply {
            url = href
            val label = element.attr("data-chapter-label")
            name = label.ifEmpty { element.selectFirst(".text-sm")?.text().orEmpty() }

            val numStr = element.attr("data-chapter-num")
            chapter_number = parseChapterNumber(name, numStr)

            val dateText = element.selectFirst(".text-\\[0\\.65rem\\], span:containsOwn(Hace)")?.text()
            date_upload = parseRelativeDate(dateText)
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
