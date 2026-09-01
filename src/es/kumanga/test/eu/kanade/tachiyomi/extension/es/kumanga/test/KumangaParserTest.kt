package eu.kanade.tachiyomi.extension.es.kumanga.test

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused parser regression tests for KuManga.
 *
 * Live contracts verified via Playwright snapshots:
 * - Catalog: /mangalist?page=1 contains /manga/{id}/{slug}
 * - Detail: h1 title, metadata/estado, genre links /mangalist?&categories=..., synopsis paragraph (>40 chars),
 *           chapter links /manga/{id}/capitulo/{numericId} with visible labels (including decimal)
 * - Reader: /manga/leer/{chapterId} uses img.lozad.fadepic / img.lozad and data-src proxy URLs /img.php?src=<hex>
 *           preserving order and deduplicating.
 *
 * No live network, no login, no Cloudflare. All fixtures are local Jsoup strings.
 * Tests verify selector/URL assumptions exactly as used in Kumanga.kt without widening
 * production visibility. If Kumanga.kt selectors change, these fixtures must be updated.
 *
 * Run: ./gradlew :src:es:kumanga:testDebugUnitTest --tests "*KumangaParserTest*"
 */
class KumangaParserTest {

    private val baseUrl = "https://www.kumanga.com"
    private val mangaDetailRegex = Regex("""^/manga/\d+/[^/]+/?$""")
    private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun buildCatalogHtml(): String = """
<!DOCTYPE html>
<html lang="es">
<head><title>Manga List</title></head>
<body>
<h2>Lista de mangas en español</h2>
<div class="manga-list">
    <a href="/manga/12032/el-protagonista-masculino-esta-obsesionado-con-mi-salud">
        <img data-src="$baseUrl/uploads/portadas/12032.jpg" alt="El Protagonista Masculino Está Obsesionado Con Mi Salud">
        El Protagonista Masculino Está Obsesionado Con Mi Salud
    </a>
    <a href="/manga/9999/otro-manga-de-prueba">
        <img src="$baseUrl/uploads/portadas/9999.jpg" alt="Otro Manga">
        Otro Manga de Prueba
    </a>
    <a href="/manga/8888/manga-con-decimal">
        <img data-src="$baseUrl/uploads/portadas/8888.jpg" alt="Manga Decimal">
        Manga Con Decimal
    </a>
    <a href="/manga/12032/capitulo/76">Capítulo 76 - must be ignored as non-detail link</a>
    <a href="/manga/c/743726">Must be ignored via /c/</a>
    <a href="/manga/leer/743726">Must be ignored via /leer/</a>
    <a href="/other/page">Irrelevant</a>
</div>
<div class="pagination">
    <a href="/mangalist?page=2">2</a>
    <a href="/mangalist?page=3">3</a>
</div>
</body>
</html>
""".trimIndent()

    private fun buildDetailHtml(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
    <title>El Protagonista Masculino Está Obsesionado Con Mi Salud - KuManga</title>
    <meta property="og:title" content="El Protagonista Masculino Está Obsesionado Con Mi Salud">
    <meta property="og:image" content="$baseUrl/uploads/portadas/12032_big.jpg">
    <meta name="description" content="Fallback description when synopsis paragraph is missing">
</head>
<body>
    <h1>El Protagonista Masculino Está Obsesionado Con Mi Salud Manhwa</h1>
    <div class="meta-left">
        <img src="$baseUrl/uploads/portadas/12032.jpg" alt="El Protagonista Masculino Está Obsesionado Con Mi Salud">
        <div>Tipo <span>Manhwa</span></div>
        <div>Año de lanzamiento <span>2025</span></div>
        <div>Estado del título <span>En emisión</span></div>
        <div>Capítulos disponibles <span>76</span></div>
    </div>
    <div class="genres">
        <a href="/mangalist?&categories=10&page=1">Drama</a>
        <a href="/mangalist?&categories=13&page=1">Fantasía</a>
        <a href="/mangalist?&categories=49&page=1">Reencarnación</a>
        <a href="/mangalist?&categories=32&page=1">Romance</a>
        <a href="/mangalist?&categories=35&page=1">Shoujo</a>
    </div>
    <p>Renacida como una extra condenada, acepta su destino en silencio hasta que el protagonista masculino empieza a obsesionarse con su salud. Lo que antes era una conexión insignificante se convierte en una atención abrumadora, y ahora su apacible vida se ve perturbada por su cariño apegado. ¿Podrá sobrevivir a la historia... y a su obsesión?</p>
    <p>Capítulo 1</p>
    <div class="chapters">
        <a href="/manga/12032/capitulo/76"><strong>Capítulo 76</strong></a>
        <a href="/manga/12032/capitulo/75"><strong>Capítulo 75</strong></a>
        <a href="/manga/12032/capitulo/12.5"><strong>Capítulo 12.5</strong></a>
        <a href="/manga/12032/capitulo/10"><strong>Capítulo 10</strong></a>
        <a href="/manga/12032/capitulo/10"><strong>Capítulo 10 Duplicado</strong></a>
    </div>
</body>
</html>
""".trimIndent()

    private fun buildReaderHtml(): String = """
<!DOCTYPE html>
<html lang="es">
<head><title>Capítulo 76 - Reader</title></head>
<body>
    <h1>El Protagonista Masculino Está Obsesionado Con Mi Salud Capítulo 76</h1>
    <div class="reader">
        <img class="lozad fadepic" data-src="$baseUrl/img.php?src=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4" alt="page 1">
        <img class="lozad fadepic" data-src="$baseUrl/img.php?src=deadbeefdeadbeefdeadbeefdeadbeef" alt="page 2">
        <img class="lozad" data-src="$baseUrl/img.php?src=cafebabecafebabecafebabecafebabe" alt="page 3">
        <!-- duplicate proxy url must be deduplicated while preserving first occurrence order -->
        <img class="lozad fadepic" data-src="$baseUrl/img.php?src=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4" alt="page 1 duplicate">
        <!-- fallback via src when data-src missing -->
        <img class="lozad" src="$baseUrl/img.php?src=ffffffffffffffffffffffffffffffff" alt="page 4 via src">
        <!-- lazy case: has data-src, empty src must still be taken from data-src -->
        <img class="lozad fadepic" data-src="$baseUrl/img.php?src=11223344556677889900aabbccddeeff" src="" alt="page 5 lazy">
        <!-- non-lozad image must be ignored by strict selector -->
        <img src="$baseUrl/img.php?src=shouldbeignored00000000000000000000" alt="not lozad">
    </div>
</body>
</html>
""".trimIndent()

    // ── Helpers mirroring production logic (kept local to avoid widening visibility) ──

    private fun extractCatalogMangas(document: Document): List<Pair<String, String>> {
        return document.select("a[href*='/manga/']").mapNotNull { el ->
            val href = el.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = runCatching { href.toHttpUrlPath() }.getOrNull() ?: href.substringBefore("?").substringBefore("#")
            if (!mangaDetailRegex.matches(path)) return@mapNotNull null
            if (path.contains("/capitulo/") || path.contains("/c/") || path.contains("/leer/")) return@mapNotNull null
            val title = el.text().trim().takeIf { it.isNotEmpty() }
                ?: el.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: el.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            href to title
        }.distinctBy { it.first }
    }

    private fun extractHomepageMangas(document: Document, selector: String): List<Pair<String, String>> {
        return document.select(selector).mapNotNull { element ->
            val path = element.absUrl("href").substringAfter(baseUrl).substringBefore("?").substringBefore("#")
            if (!mangaDetailRegex.matches(path)) return@mapNotNull null
            val title = element.text().trim().ifEmpty { element.attr("title").trim() }
            if (title.isEmpty()) return@mapNotNull null
            path to title
        }
    }

    private fun extractHomepageThumbnail(element: Element): String? {
        val backgroundElement = element.selectFirst("[style*='background-image']")
            ?: element.parent()?.selectFirst("a.mhu-card[style*='background-image']")
            ?: element.parent()?.selectFirst("[style*='background-image']")
        val backgroundUrl = backgroundElement?.attr("style")
            ?.let { Regex("background-image:\\s*url\\(['\"]?([^'\")]+)['\"]?\\)").find(it)?.groupValues?.get(1) }
        return backgroundUrl?.let { if (it.startsWith("http")) it else "$baseUrl/${it.removePrefix("/")}" }
    }

    private fun String.toHttpUrlPath(): String {
        // Minimal path extraction without pulling OkHttp for the test helper.
        // Jsoup absUrl already returns absolute; we strip scheme/host.
        val afterHost = substringAfter("://").substringAfter("/")
        return "/$afterHost".substringBefore("?").substringBefore("#")
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a[href*='page=']").any { it.attr("href").contains("page=${page + 1}") } ||
            document.selectFirst("a[href*='page=${page + 1}']") != null
    }

    private fun extractReaderImages(document: Document): List<String> {
        val seen = LinkedHashSet<String>()
        val pages = mutableListOf<String>()
        for (el in document.select("img.lozad.fadepic, img.lozad")) {
            val abs = when {
                el.hasAttr("data-src") && el.attr("data-src").isNotBlank() -> el.absUrl("data-src")
                el.attr("src").isNotBlank() -> el.absUrl("src")
                else -> null
            } ?: continue
            if (abs.isBlank()) continue
            if (!seen.add(abs)) continue
            pages += abs
        }
        return pages
    }

    private fun parseStatus(raw: String?): Int {
        val t = raw?.trim()?.lowercase().orEmpty()
        return when {
            t.isBlank() -> SManga.UNKNOWN
            t.contains("en emisión") || t.contains("en emision") || t.contains("en curso") || t.contains("activo") -> SManga.ONGOING
            t.contains("finalizado") || t.contains("completado") || t.contains("terminado") -> SManga.COMPLETED
            t.contains("pausa") || t.contains("hiatus") || t.contains("inconcluso") -> SManga.ON_HIATUS
            t.contains("cancelado") || t.contains("abandonado") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ── Catalog tests ──────────────────────────────────────────────────────

    @Test
    fun `catalog selector extracts only manga detail links matching slash manga id slash slug`() {
        val document = Jsoup.parse(buildCatalogHtml(), baseUrl)

        val mangas = extractCatalogMangas(document)

        assertEquals("Must extract exactly 3 detail mangas, ignoring chapter/reader links", 3, mangas.size)
        assertTrue(mangas.any { it.first.contains("/manga/12032/el-protagonista") })
        assertTrue(mangas.any { it.first.contains("/manga/9999/otro-manga-de-prueba") })
        assertTrue(mangas.any { it.first.contains("/manga/8888/manga-con-decimal") })
        assertFalse("Chapter link must not be treated as manga", mangas.any { it.first.contains("/capitulo/") })
        assertFalse("Reader /leer link must not be treated as manga", mangas.any { it.first.contains("/leer/") })
        assertFalse("Intermediate /c/ link must be ignored", mangas.any { it.first.contains("/manga/c/") })
    }

    @Test
    fun `catalog links expose absolute manga urls and visible titles`() {
        val document = Jsoup.parse(buildCatalogHtml(), baseUrl)

        val mangas = extractCatalogMangas(document)

        mangas.forEach { (href, title) ->
            assertTrue("Href must be absolute https url", href.startsWith("https://"))
            assertTrue("Title must be visible text or alt", title.isNotBlank())
        }
        assertEquals("El Protagonista Masculino Está Obsesionado Con Mi Salud", mangas.first().second)
    }

    @Test
    fun `catalog thumbnail prefers data-src over src`() {
        val document = Jsoup.parse(buildCatalogHtml(), baseUrl)

        val firstAnchor = document.selectFirst("a[href*='/manga/12032/el-protagonista']")!!
        val thumb = firstAnchor.selectFirst("img")!!.let { img ->
            img.absUrl("data-src").takeIf { it.isNotBlank() } ?: img.absUrl("src")
        }

        assertTrue("First manga thumbnail must come from data-src", thumb.contains("/uploads/portadas/12032.jpg"))
        assertEquals("$baseUrl/uploads/portadas/12032.jpg", thumb)
    }

    @Test
    fun `catalog pagination detects next page via page param`() {
        val document = Jsoup.parse(buildCatalogHtml(), baseUrl)

        assertTrue("page=2 link must signal hasNextPage for page=1", hasNextPage(document, 1))
        assertTrue("page=3 link present confirms pagination", document.selectFirst("a[href*='page=3']") != null)
        assertFalse("No page=5 link means page=4 has no next", hasNextPage(document, 4))
    }

    @Test
    fun `search cards expose manga URLs through onclick`() {
        val document = Jsoup.parse(
            """
            <ul class="listUl results">
                <li class="km-li-crd" onclick="window.open('https://www.kumanga.com/manga/14527/la-maga-blanca')">
                    <img class="km-img-crd" src="https://static.kumanga.com/manga/6/14527.jpg" alt="La maga blanca">
                    <p class="km-title-p-card">La maga blanca</p>
                </li>
            </ul>
            <a href="/mangalist?page=2&keywords=isekai"></a>
            """.trimIndent(),
            baseUrl,
        )
        val card = document.selectFirst("li.km-li-crd[onclick]")!!
        val href = Regex("""window\.open\(['\"]([^'\"]+)['\"]\)""")
            .find(card.attr("onclick"))!!.groupValues[1]

        assertEquals("$baseUrl/manga/14527/la-maga-blanca", href)
        assertEquals("La maga blanca", card.selectFirst(".km-title-p-card")?.text()?.trim())
        assertTrue(document.selectFirst("a[href*='page=2']") != null)
    }

    @Test
    fun `homepage selectors extract popular and recent manga`() {
        val document = Jsoup.parse(
            """
            <div class="main-hot-updates">
                <div class="h_move"><a class="mhu-card" href="manga/c/10" style="background-image: url(https://static.kumanga.com/manga/1/12032.jpg)"></a><a class="mhu-name" href="manga/12032/popular-manga">Popular Manga</a></div>
            </div>
            <div class="update_item">
                <div class="update_left"><a href="manga/9999/recent-manga" title="Recent Manga"><div style="background-image: url(https://static.kumanga.com/manga/1/9999.jpg)"></div></a></div>
                <div class="update_right"><h4><a href="manga/9999/recent-manga">Recent Manga</a></h4></div>
            </div>
            """.trimIndent(),
            baseUrl,
        )

        val popular = extractHomepageMangas(document, ".main-hot-updates a.mhu-name")
        val recent = extractHomepageMangas(document, ".update_item .update_left a[href*='manga/']")

        assertEquals(listOf("/manga/12032/popular-manga" to "Popular Manga"), popular)
        assertEquals(listOf("/manga/9999/recent-manga" to "Recent Manga"), recent)
        assertEquals("https://static.kumanga.com/manga/1/12032.jpg", extractHomepageThumbnail(document.selectFirst("a.mhu-name")!!))
        assertEquals("https://static.kumanga.com/manga/1/9999.jpg", extractHomepageThumbnail(document.selectFirst(".update_item .update_left a")!!))
    }

    // ── Detail tests ───────────────────────────────────────────────────────

    @Test
    fun `detail h1 exposes manga title`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()

        assertTrue("H1 must be non-empty", title.isNotBlank())
        assertEquals("El Protagonista Masculino Está Obsesionado Con Mi Salud Manhwa", title)
        assertTrue("Title contains expected manga name", title.contains("El Protagonista"))
    }

    @Test
    fun `detail og image and alt image provide thumbnail`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val og = document.selectFirst("meta[property='og:image']")?.attr("content")
        assertNotNull(og)
        assertTrue(og!!.startsWith("https://"))

        val imgAltFallback = document.selectFirst("img[alt]")?.absUrl("src")
        assertNotNull(imgAltFallback)
        assertTrue(imgAltFallback!!.contains("/uploads/portadas/"))
    }

    @Test
    fun `detail genre links use mangalist categories param`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val genreAnchors = document.select("a[href*='categories=']")
        assertEquals("5 genre links observed live must be matched", 5, genreAnchors.size)

        genreAnchors.forEach { a ->
            assertTrue(a.attr("href").contains("categories="))
            assertTrue(a.attr("abs:href").contains("/mangalist"))
            assertTrue(a.text().trim().isNotEmpty())
        }

        val genres = genreAnchors.map { it.text().trim() }
        assertEquals(listOf("Drama", "Fantasía", "Reencarnación", "Romance", "Shoujo"), genres)
    }

    @Test
    fun `detail synopsis selects long paragraph excluding capitulo and estado labels`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val candidates = document.select("p")
        val synopsis = candidates.firstOrNull { p ->
            val txt = p.text().trim()
            txt.length > 40 && !txt.contains("Capítulo", ignoreCase = true) && !txt.contains("Estado del título", ignoreCase = true)
        }?.text()?.trim()

        assertNotNull("Synopsis paragraph must be found", synopsis)
        assertTrue(synopsis!!.contains("Renacida como una extra condenada"))
        assertTrue(synopsis.length > 40)
    }

    @Test
    fun `detail status En emision maps to ONGOING`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val statusText = document.select("*").firstOrNull { it.tagName() != "script" && it.ownText().contains("Estado del título", ignoreCase = true) }
            ?.let { label ->
                label.nextElementSibling()?.text()?.trim()
                    ?: label.parent()?.ownText()?.substringAfter("Estado del título", "")?.trim()
                    ?: label.parent()?.text()?.substringAfter("Estado del título", "")?.trim()
            }

        assertNotNull("Estado del título label must exist", statusText)
        assertEquals(SManga.ONGOING, parseStatus(statusText))
        assertEquals(SManga.ONGOING, parseStatus("En emisión"))
        assertEquals(SManga.COMPLETED, parseStatus("Finalizado"))
        assertEquals(SManga.ON_HIATUS, parseStatus("En pausa"))
        assertEquals(SManga.CANCELLED, parseStatus("Cancelado"))
        assertEquals(SManga.UNKNOWN, parseStatus(null))
    }

    @Test
    fun `detail chapter links use slash manga id slash capitulo slash numericId with visible labels`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        var elements = document.select("a[href*='/capitulo/']")
        if (elements.isEmpty()) elements = document.select("a[href*='/manga/c/']")

        assertTrue("Chapter anchors must be found", elements.isNotEmpty())
        assertEquals("Fixture contains 5 anchors before distinct", 5, elements.size)

        val distinct = elements.mapNotNull { el ->
            val href = el.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = href.substringAfter(baseUrl)
            if (!path.contains("/capitulo/") && !path.contains("/manga/c/")) return@mapNotNull null
            val name = el.selectFirst("strong")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: el.text().trim()
            Triple(href, path, name)
        }.distinctBy { it.first }

        assertEquals("Duplicate href /capitulo/10 must be deduplicated", 4, distinct.size)
        distinct.forEach { (href, path, name) ->
            assertTrue("Href must be absolute", href.startsWith("https://"))
            assertTrue("Path must contain /capitulo/", path.contains("/capitulo/"))
            assertTrue("Visible label must be non-empty", name.isNotBlank())
            assertTrue("Label contains Capítulo", name.contains("Capítulo"))
        }
    }

    @Test
    fun `chapter decimal label 12_5 parses as float 12_5`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val elements = document.select("a[href*='/capitulo/']")

        val decimalEl = elements.first { it.attr("href").contains("12.5") }
        val name = decimalEl.selectFirst("strong")?.text()?.trim() ?: decimalEl.text().trim()
        assertEquals("Capítulo 12.5", name)

        val numberFromLabel = chapterNumberRegex.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        assertNotNull(numberFromLabel)
        assertEquals(12.5f, numberFromLabel!!, 0.001f)

        val pathNumber = decimalEl.attr("href").substringAfter("/capitulo/").substringBefore("/").toFloatOrNull()
        assertEquals(12.5f, pathNumber!!, 0.001f)
    }

    @Test
    fun `chapter integer label still parses via same regex`() {
        val document = Jsoup.parse(buildDetailHtml(), baseUrl)

        val el = document.select("a[href*='/capitulo/76']").first()!!
        val name = el.selectFirst("strong")?.text()?.trim() ?: el.text().trim()
        val number = chapterNumberRegex.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()

        assertEquals(76.0f, number!!, 0.001f)
    }

    @Test
    fun `chapter landing resolves relative reader link`() {
        val document = Jsoup.parse(
            "<a id=\"leer\" href=\"manga/leer/744291\">Iniciar lectura</a>",
            baseUrl,
        )

        val readerUrl = document.selectFirst("a[href*='manga/leer/']")?.absUrl("href")

        assertEquals("$baseUrl/manga/leer/744291", readerUrl)
    }

    @Test
    fun `large series merges visible and JSON-LD chapter links`() {
        val document = Jsoup.parse(
            """
            <div class="chapters">
                <a href="/manga/10501/capitulo/686"><strong>Capítulo 686</strong></a>
                <a href="/manga/10501/capitulo/685"><strong>Capítulo 685</strong></a>
            </div>
            <script type="application/ld+json">
                {"workExample":[
                    {"@type":"Chapter","url":"https://www.kumanga.com/manga/10501/capitulo/686"},
                    {"@type":"Chapter","url":"https://www.kumanga.com/manga/10501/capitulo/685"},
                    {"@type":"Chapter","url":"https://www.kumanga.com/manga/10501/capitulo/684"},
                    {"@type":"Chapter","url":"https://www.kumanga.com/manga/10501/capitulo/1"}
                ]}
            </script>
            """.trimIndent(),
            baseUrl,
        )
        val urlRegex = Regex("""(?:https?://[^/]+)?(/manga/\d+/capitulo/[^\"'\\]+)""")
        val visible = document.select("a[href*='/capitulo/']").map { it.absUrl("href") }
        val all = (visible + document.select("script[type='application/ld+json']").flatMap {
            urlRegex.findAll(it.data()).map { match -> "$baseUrl${match.groupValues[1]}" }.toList()
        }).distinct()

        assertEquals(4, all.size)
        assertTrue(all.any { it.endsWith("/capitulo/684") })
        assertTrue(all.any { it.endsWith("/capitulo/1") })
    }

    @Test
    fun `reader ignores Alpine loader placeholders`() {
        val document = Jsoup.parse(
            """
            <div id="rkx">
                <img class="lozad fadepic" src="/img.php?src=686" data-src="/img.php?src=686">
                <img class="lozad img_ph" src="/assets/img/image_loader.svg" :data-src="/img.php?src=687">
            </div>
            """.trimIndent(),
            baseUrl,
        )

        val images = document.select("#rkx img").mapNotNull { image ->
            val value = image.attr("data-src").ifBlank { image.attr("src") }
            value.takeIf { it.isNotBlank() && !it.contains("image_loader.svg") }
        }

        assertEquals(listOf("/img.php?src=686"), images)
    }

    // ── Reader tests ───────────────────────────────────────────────────────

    @Test
    fun `reader selector is strict to lozad fadepic and lozad preserving order`() {
        val document = Jsoup.parse(buildReaderHtml(), baseUrl)

        val strict = document.select("img.lozad.fadepic, img.lozad")
        // 5 lozad images + 1 duplicate = 6; the non-lozad img is excluded
        assertEquals("Strict selector must match 6 lozad images, ignoring plain img", 6, strict.size)

        val images = extractReaderImages(document)
        assertEquals("After dedup, 5 unique images remain in first-occurrence order", 5, images.size)
        assertTrue(images[0].contains("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))
        assertTrue(images[1].contains("deadbeefdeadbeefdeadbeefdeadbeef"))
        assertTrue(images[2].contains("cafebabecafebabecafebabecafebabe"))
        assertTrue(images[3].contains("ffffffffffffffffffffffffffffffff"))
        assertTrue(images[4].contains("11223344556677889900aabbccddeeff"))
    }

    @Test
    fun `reader lazy data-src is preferred over src and proxy url is preserved`() {
        val document = Jsoup.parse(buildReaderHtml(), baseUrl)

        val first = document.select("img.lozad.fadepic").first()!!
        assertTrue("Lazy image must have data-src", first.hasAttr("data-src"))
        val viaHelper = extractReaderImages(document).first()
        assertEquals(first.absUrl("data-src"), viaHelper)
        assertTrue("Proxy url pattern must be /img.php?src=<hex>", viaHelper.contains("/img.php?src="))
        assertTrue("Hex part must be non-empty", viaHelper.substringAfter("src=").isNotEmpty())
    }

    @Test
    fun `reader deduplicates identical proxy urls`() {
        val document = Jsoup.parse(buildReaderHtml(), baseUrl)

        val images = extractReaderImages(document)
        assertEquals("Duplicate proxy url appears only once", 1, images.count { it.contains("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4") })
        assertEquals(images.size, images.toSet().size)
    }

    @Test
    fun `reader fallback uses src when data-src is missing`() {
        val document = Jsoup.parse(buildReaderHtml(), baseUrl)

        val fallbackEl = document.select("img.lozad[src*='ffffffff']").first()!!
        assertFalse(fallbackEl.hasAttr("data-src"))
        assertTrue(fallbackEl.hasAttr("src"))

        val images = extractReaderImages(document)
        assertTrue("Fallback src image must be included", images.any { it.contains("ffffffffffffffffffffffffffffffff") })
    }

    @Test
    fun `reader ignores non-lozad images`() {
        val document = Jsoup.parse(buildReaderHtml(), baseUrl)

        val allImgs = document.select("img")
        assertEquals(7, allImgs.size)

        val strict = document.select("img.lozad.fadepic, img.lozad")
        assertEquals(6, strict.size)

        val images = extractReaderImages(document)
        assertFalse("Non-lozad proxy must not leak into page list", images.any { it.contains("shouldbeignored") })
    }

    @Test
    fun `reader proxy urls all match img_php hex pattern`() {
        val document = Jsoup.parse(buildReaderHtml(), baseUrl)

        val images = extractReaderImages(document)
        val hexRegex = Regex("""/img\.php\?src=[0-9a-fA-F]+$""")

        images.forEach { url ->
            assertTrue("Each reader url must match /img.php?src=<hex>: $url", hexRegex.containsMatchIn(url))
        }
    }
}
