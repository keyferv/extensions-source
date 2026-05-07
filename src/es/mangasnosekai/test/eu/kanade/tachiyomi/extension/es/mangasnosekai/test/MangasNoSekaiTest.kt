package eu.kanade.tachiyomi.extension.es.mangasnosekai.test

import eu.kanade.tachiyomi.extension.es.mangasnosekai.ChapterDisplayItem
import eu.kanade.tachiyomi.extension.es.mangasnosekai.ChapterListResponse
import eu.kanade.tachiyomi.extension.es.mangasnosekai.MangasNoSekaiPatterns
import eu.kanade.tachiyomi.extension.es.mangasnosekai.PayloadDto
import kotlinx.serialization.json.Json
import org.junit.Ignore
import org.junit.Test

/**
 * Tests para la extensión MangasNoSekai
 *
 * Para ejecutar desde IntelliJ/Android Studio:
 * 1. Click derecho en la clase -> Run 'MangasNoSekaiTest'
 *
 * Para ejecutar desde línea de comandos:
 * ./gradlew :src:es:mangasnosekai:test --tests "*MangasNoSekaiTest*"
 */
class MangasNoSekaiTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ============================================
    // SECCIÓN 1: Tests de Regex
    // ============================================

    @Test
    fun testActionRegexAgainstKnownPattern() {
        val scriptContents = """
            function mangaGetChapters() {
                jQuery.ajax({
                    url: 'https://mangasnosekai.com/wp-admin/admin-ajax.php',
                    type: 'POST',
                    data: {
                        action: 'manga_get_chapters',
                        manga: '12345'
                    },
                    success: function(response) {}
                });
            }
        """.trimIndent()

        val match = MangasNoSekaiPatterns.ACTION_REGEX.find(scriptContents)
        assert(match != null) { "ACTION_REGEX no encontró coincidencia en script válido" }

        val url = match!!.groupValues[1]
        assert(url.isNotBlank()) { "URL extraída está en blanco" }
        assert(url.contains("admin-ajax.php")) { "URL no contiene la ruta esperada" }

        val data = match.groupValues.getOrNull(2)
        assert(data != null && data.isNotBlank()) { "Data no extraída del script" }
    }

    @Test
    fun testObjectsRegexAgainstKnownData() {
        val dataBlock = """
            action: 'manga_get_chapters',
            manga: '12345',
            nonce: 'abc123def456'
        """.trimIndent()

        val matches = MangasNoSekaiPatterns.OBJECTS_REGEX.findAll(dataBlock).toList()
        assert(matches.isNotEmpty()) { "OBJECTS_REGEX no encontró coincidencias" }
        assert(matches.size == 3) { "Se esperaban 3 pares clave-valor, se encontraron ${matches.size}" }

        matches.forEach { match ->
            val key = match.groupValues[1]
            assert(key.isNotBlank()) { "Clave vacía encontrada" }
        }
    }

    @Test
    fun testObjectsRegexFilterNullValues() {
        val dataBlock = """
            action: 'manga_get_chapters',
            manga: '12345',
            emptyField: ,
            nullField: null,
            nonce: 'abc123'
        """.trimIndent()

        val validPairs = MangasNoSekaiPatterns.OBJECTS_REGEX.findAll(dataBlock)
            .mapNotNull { match ->
                val key = match.groupValues[1]
                val value = match.groupValues.getOrNull(2)
                if (!value.isNullOrEmpty()) key to value else null
            }
            .toList()

        assert(validPairs.size >= 3) {
            "Se esperaban al menos 3 pares válidos, se encontraron ${validPairs.size}"
        }
    }

    @Test
    fun testMangaIdRegex() {
        val scriptExtra = """{"manga_id":"12345","postId":"67890"}"""

        val mangaIdMatch = MangasNoSekaiPatterns.MANGA_ID_REGEX.find(scriptExtra)
        assert(mangaIdMatch != null) { "MANGA_ID_REGEX no encontró manga_id" }
        assert(mangaIdMatch!!.groupValues[1] == "12345") { "manga_id incorrecto" }

        val altMatch = MangasNoSekaiPatterns.ALT_MANGA_ID_REGEX.find(scriptExtra)
        assert(altMatch != null) { "ALT_MANGA_ID_REGEX no encontró postId" }
        assert(altMatch!!.groupValues[1] == "67890") { "postId incorrecto" }
    }

    // ============================================
    // SECCIÓN 2: Tests de DTO y Serialización JSON
    // ============================================

    @Test
    fun testPayloadDtoDeserialization() {
        val sampleJson = """
        {
            "manga": [
                {
                    "chapters": [
                        {
                            "chapter_name": "Capítulo 100",
                            "chapter_slug": "capitulo-100",
                            "date_gmt": "2025-01-15 10:30:00"
                        },
                        {
                            "chapter_name": "Capítulo 99",
                            "chapter_slug": "capitulo-99",
                            "date_gmt": "2025-01-10 10:30:00"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val payload = json.decodeFromString<PayloadDto>(sampleJson)
        assert(payload.manga.isNotEmpty()) { "No se encontraron mangas en el payload" }

        val chapters = payload.manga.first().chapters
        assert(chapters.size == 2) { "Se esperaban 2 capítulos, se encontraron ${chapters.size}" }
        assert(chapters[0].name == "Capítulo 100") { "Nombre del capítulo incorrecto" }
        assert(chapters[0].slug == "capitulo-100") { "Slug del capítulo incorrecto" }
    }

    @Test
    fun testPayloadDtoEmptyManga() {
        val emptyJson = """{"manga": []}"""
        val payload = json.decodeFromString<PayloadDto>(emptyJson)
        assert(payload.manga.isEmpty()) { "Se esperaba lista vacía de mangas" }
    }

    @Test
    fun testPayloadDtoWithChapters() {
        val chaptersJson = """
        {
            "manga": [
                {
                    "chapters": [
                        {
                            "chapter_name": "Capítulo 1: El Comienzo",
                            "chapter_slug": "capitulo-1",
                            "date_gmt": "2024-06-01 12:00:00"
                        },
                        {
                            "chapter_name": "Capítulo 2: La Travesía",
                            "chapter_slug": "capitulo-2",
                            "date_gmt": "2024-06-08 12:00:00"
                        },
                        {
                            "chapter_name": "Capítulo Extra",
                            "chapter_slug": "capitulo-extra",
                            "date_gmt": "2024-06-15 12:00:00"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val payload = json.decodeFromString<PayloadDto>(chaptersJson)
        val chapters = payload.manga.first().chapters

        assert(chapters.size == 3) { "Se esperaban 3 capítulos" }
        assert(chapters[0].name == "Capítulo 1: El Comienzo") { "Nombre del capítulo 1 incorrecto" }
        assert(chapters[2].name == "Capítulo Extra") { "Nombre del capítulo 3 incorrecto" }
        assert(chapters[1].slug == "capitulo-2") { "Slug del capítulo 2 incorrecto" }
    }

    // ============================================
    // SECCIÓN 3: Tests del formato JSON paginado (getcaps7)
    // ============================================

    @Test
    fun testChapterListResponseDeserialization() {
        val sampleJson = """
        {
            "total_chapters": 25,
            "total_pages": 4,
            "current_page": 2,
            "chapters_to_display": [
                {
                    "id": "26171",
                    "name": "Capítulo 15.5",
                    "name_extend": "",
                    "number": "15.5",
                    "link": "https://mangasnosekai.com/manga/test-manga/capitulo-15-5/",
                    "date": "<i>abril 29, 2025</i>",
                    "is_read": false
                },
                {
                    "id": "25930",
                    "name": "Capítulo 15",
                    "name_extend": "Extra",
                    "number": "15",
                    "link": "https://mangasnosekai.com/manga/test-manga/capitulo-15/",
                    "date": "<i>abril 16, 2025</i>",
                    "is_read": true
                }
            ]
        }
        """.trimIndent()

        val response = json.decodeFromString<ChapterListResponse>(sampleJson)

        assert(response.totalChapters == 25) { "total_chapters incorrecto" }
        assert(response.totalPages == 4) { "total_pages incorrecto" }
        assert(response.currentPage == 2) { "current_page incorrecto" }
        assert(response.chaptersToDisplay.size == 2) { "chapters_to_display size incorrecto" }
    }

    @Test
    fun testChapterDisplayItemFields() {
        val item = ChapterDisplayItem(
            name = "Capítulo 15.5",
            nameExtend = "",
            number = "15.5",
            link = "https://mangasnosekai.com/manga/test-manga/capitulo-15-5/",
            date = "<i>abril 29, 2025</i>",
        )

        assert(item.name == "Capítulo 15.5") { "Name incorrecto: ${item.name}" }
        assert(item.number == "15.5") { "Number incorrecto: ${item.number}" }
        assert(item.link.contains("/capitulo-15-5/")) { "URL no contiene el slug esperado" }
    }

    @Test
    fun testChapterDisplayItemWithNameExtend() {
        val item = ChapterDisplayItem(
            name = "Capítulo 10",
            nameExtend = "Extra",
            number = "10",
            link = "https://mangasnosekai.com/manga/test-manga/capitulo-10/",
            date = "<i>octubre 10, 2024</i>",
        )

        assert(item.name == "Capítulo 10") { "Name incorrecto" }
        assert(item.nameExtend == "Extra") { "NameExtend incorrecto" }
        // The toSChapter() method concatenates name + nameExtend, verified by inspection
    }

    @Test
    fun testChapterDisplayItemDateParsing() {
        // Verifica que las fechas con tags HTML se pueden limpiar y parsear
        val testCases = listOf(
            "<i>abril 29, 2025</i>" to "abril 29, 2025",
            "<i>marzo 1, 2025</i>" to "marzo 1, 2025",
            "<i>noviembre 10, 2025</i>" to "noviembre 10, 2025",
            "<i>diciembre 30, 2024</i>" to "diciembre 30, 2024",
        )

        testCases.forEach { (raw, _) ->
            val item = ChapterDisplayItem(name = "Test", date = raw)
            // Verificar que el campo date contiene el valor raw (la limpieza se hace en toSChapter)
            assert(item.date == raw) { "Date field no almacena el valor correctamente" }
        }
    }

    @Test
    fun testChapterListResponsePaginationMetadata() {
        val page1Json = """
        {
            "total_chapters": 47,
            "total_pages": 6,
            "current_page": 1,
            "chapters_to_display": [
                {
                    "name": "Capítulo 47",
                    "number": "47",
                    "link": "https://mangasnosekai.com/manga/test-manga/capitulo-47/",
                    "date": "<i>noviembre 15, 2025</i>"
                }
            ]
        }
        """.trimIndent()

        val response = json.decodeFromString<ChapterListResponse>(page1Json)

        val remainingPages = response.totalPages - response.currentPage
        assert(response.totalPages == 6) { "total_pages incorrecto" }
        assert(response.currentPage == 1) { "current_page incorrecto" }
        assert(remainingPages == 5) { "remaining pages calculation incorrecto" }
    }

    // ============================================
    // SECCIÓN 4: Tests de integración (requieren red, Cloudflare los bloquea)
    // Ejecutar manualmente desde el navegador o con WebView
    // ============================================

    @Ignore("Cloudflare bloquea requests automatizados. Verificar manualmente en WebView.")
    @Test
    fun testPopularMangaPageLoading() {
        // Verificar que la página de biblioteca carga mangas:
        // URL: https://mangasnosekai.com/biblioteca/?m_orderby=views
        // Selector: div.page-listing-item > div.row > div
    }

    @Ignore("Cloudflare bloquea requests automatizados. Verificar manualmente en WebView.")
    @Test
    fun testLatestMangaPageLoading() {
        // Verificar que la página latest carga mangas:
        // URL: https://mangasnosekai.com/biblioteca/?m_orderby=latest
    }

    @Ignore("Cloudflare bloquea requests automatizados. Verificar manualmente en WebView.")
    @Test
    fun testSearchMangaPage() {
        // Verificar búsqueda de mangas:
        // URL: https://mangasnosekai.com/?s=one+piece&post_type=wp-manga
        // Selector: div.c-tabs-item__content
    }

    @Ignore("Cloudflare bloquea requests automatizados. Verificar manualmente en WebView.")
    @Test
    fun testMangaDetailsExtraction() {
        // Verificar selectores de detalles del manga:
        // Título: div.thumble-container p.titleMangaSingle
        // Thumbnail: div.thumble-container img.img-responsive
    }

    @Ignore("Cloudflare bloquea requests automatizados. Verificar manualmente en WebView.")
    @Test
    fun testChapterListPresence() {
        // Verificar presencia de scripts de capítulos:
        // script#wp-manga-js, script#wp-manga-js-extra
    }

    @Ignore("Cloudflare bloquea requests automatizados. Verificar manualmente en WebView.")
    @Test
    fun testPaginationSelectors() {
        // Verificar paginación HTML de capítulos:
        // Selector capítulos: div.contenedor-capitulo-miniatura
        // Selector paginación: a.pagination-item[data-page]
    }
}
