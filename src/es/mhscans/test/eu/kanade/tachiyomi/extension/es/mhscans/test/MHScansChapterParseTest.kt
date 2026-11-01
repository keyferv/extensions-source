package eu.kanade.tachiyomi.extension.es.mhscans.test

import eu.kanade.tachiyomi.extension.es.mhscans.normalizeMangaPath
import eu.kanade.tachiyomi.extension.es.mhscans.parseChapterElements
import eu.kanade.tachiyomi.extension.es.mhscans.parseChapterNumber
import eu.kanade.tachiyomi.extension.es.mhscans.parseRelativeDate
import eu.kanade.tachiyomi.extension.es.mhscans.walkChapterPages
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

private class TestChapter(
    override var url: String = "",
    override var name: String = "",
    override var date_upload: Long = 0L,
    override var chapter_number: Float = -1f,
    override var scanlator: String? = null,
    override var memo: JsonObject = buildJsonObject {},
) : SChapter

class MHScansChapterParseTest {

    @Test
    fun `walkChapterPages fetches every page through totalPages`() = runBlocking {
        val pageCalls = mutableListOf<Int>()
        val chapters = walkChapterPages(initialPage = 1) { page ->
            pageCalls.add(page)
            val fakeChapters = listOf(
                TestChapter(url = "/leer/page_${page}_ch_1", name = "Capítulo $page.1"),
                TestChapter(url = "/leer/page_${page}_ch_2", name = "Capítulo $page.2"),
            )
            Pair(fakeChapters, 3)
        }

        assertEquals(listOf(1, 2, 3), pageCalls)
        assertEquals(6, chapters.size)
    }

    @Test
    fun `walkChapterPages stops when a page is empty`() = runBlocking {
        val pageCalls = mutableListOf<Int>()
        val chapters = walkChapterPages(initialPage = 1) { page ->
            pageCalls.add(page)
            Pair(emptyList(), 5)
        }

        assertEquals(listOf(1), pageCalls)
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `walkChapterPages stops within maxPages on a runaway loop`() = runBlocking {
        var callCount = 0
        val chapters = walkChapterPages(initialPage = 1, maxPages = 5) { page ->
            callCount++
            val fakeChapter = listOf(
                TestChapter(url = "/leer/ch_$page", name = "Capítulo $page"),
            )
            Pair(fakeChapter, 999)
        }

        assertEquals(5, callCount)
        assertEquals(5, chapters.size)
    }

    @Test
    fun `walkChapterPages stops when reportedTotalPages is 1`() = runBlocking {
        val pageCalls = mutableListOf<Int>()
        val chapters = walkChapterPages(initialPage = 1) { page ->
            pageCalls.add(page)
            val fakeChapters = listOf(
                TestChapter(url = "/leer/ch_1", name = "Capítulo 1"),
            )
            Pair(fakeChapters, 1)
        }

        assertEquals(listOf(1), pageCalls)
        assertEquals(1, chapters.size)
    }

    @Test
    fun `normalizeMangaPath converts old series URL to new comics URL`() {
        assertEquals(
            "/comics/mhscans-mis-discipulos-son-todos-grandes-villanos",
            normalizeMangaPath("/series/mis-discipulos-son-todos-grandes-villanos/"),
        )
        assertEquals(
            "/comics/mhscans-tengo-la-j-por-jefe",
            normalizeMangaPath("series/tengo-la-j-por-jefe"),
        )
        assertEquals(
            "/comics/mhscans-already-prefixed",
            normalizeMangaPath("/series/mhscans-already-prefixed/"),
        )
        assertEquals(
            "/comics/monarca-del-caos",
            normalizeMangaPath("/comics/monarca-del-caos"),
        )
        assertEquals(
            "/comics/mhscans-test",
            normalizeMangaPath("https://mhscans.com/series/test/", "https://mhscans.com"),
        )
    }

    @Test
    fun `parseChapterNumber extracts exact decimal`() {
        assertEquals(376f, parseChapterNumber("Capítulo 376", "376"), 0.001f)
        assertEquals(0.1f, parseChapterNumber("Capítulo 0.1", "0.1"), 0.001f)
        assertEquals(15.5f, parseChapterNumber("Capítulo 15.5"), 0.001f)
        assertEquals(1f, parseChapterNumber("Capítulo 1"), 0.001f)
    }

    @Test
    fun `parseRelativeDate parses multiple Spanish formats`() {
        assertTrue(parseRelativeDate("Hace 4 días") > 0L)
        assertTrue(parseRelativeDate("Hace 1 sem") > 0L)
        assertTrue(parseRelativeDate("Hace 2 semanas") > 0L)
        assertTrue(parseRelativeDate("6h") > 0L)
        assertTrue(parseRelativeDate("2d") > 0L)
        assertTrue(parseRelativeDate("15 min") > 0L)
        assertEquals(0L, parseRelativeDate(null))
        assertEquals(0L, parseRelativeDate(""))
    }

    @Test
    fun `parseChapterElements skips auth login and parses valid leer chapters`() {
        val sampleHtml = """
            <div>
                <a href="/auth/login?redirect=%2Fcomics%2Ftest" data-chapter-num="386" data-chapter-label="Capítulo 386">
                    <span class="text-sm">Capítulo 386</span>
                </a>
                <a href="/leer/ZNzCmoVRMbmw7RBmHWuYp" data-chapter-id="105355" data-chapter-num="376" data-chapter-label="Capítulo 376">
                    <span class="text-sm">Capítulo 376</span>
                    <span class="text-[0.65rem]">Hace 1 sem</span>
                </a>
                <a href="/leer/IO_JD8y7XynjnG4nzcCU4" data-chapter-id="105354" data-chapter-num="375" data-chapter-label="Capítulo 375">
                    <span class="text-sm">Capítulo 375</span>
                    <span class="text-[0.65rem]">Hace 1 sem</span>
                </a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml)
        val chapters = parseChapterElements(doc.select("a[data-chapter-num]")) { TestChapter() }

        assertEquals(2, chapters.size)
        assertEquals("/leer/ZNzCmoVRMbmw7RBmHWuYp", chapters[0].url)
        assertEquals("Capítulo 376", chapters[0].name)
        assertEquals(376f, chapters[0].chapter_number, 0.001f)
        assertTrue(chapters[0].date_upload > 0L)

        assertEquals("/leer/IO_JD8y7XynjnG4nzcCU4", chapters[1].url)
        assertEquals("Capítulo 375", chapters[1].name)
        assertEquals(375f, chapters[1].chapter_number, 0.001f)
    }
}
