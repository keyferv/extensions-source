package eu.kanade.tachiyomi.extension.es.barmanga.test

import org.jsoup.Jsoup
import org.junit.Test

/**
 * Test para verificar la extracción de capítulos de BarManga
 *
 * Para ejecutar desde IntelliJ/Android Studio:
 * 1. Click derecho en la clase -> Run 'BarMangaChapterTest'
 *
 * Para ejecutar desde línea de comandos:
 * ./gradlew :src:es:barmanga:test --tests "*BarMangaChapterTest*"
 */
class BarMangaChapterTest {

    @Test
    fun testChapterExtraction() {
        println("\n=== TEST DE EXTRACCIÓN DE CAPÍTULOS ===\n")

        val mangaUrl = "https://libribar.com/manga/la-leyenda-de-la-estrella-general/"

        println("Conectando a: $mangaUrl")

        val document = Jsoup.connect(mangaUrl)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .get()

        println("✓ Página cargada\n")

        // Buscar capítulos
        val chapterElements = document.select("li.wp-manga-chapter")
        println("Elementos <li.wp-manga-chapter> encontrados: ${chapterElements.size}")

        assert(chapterElements.isNotEmpty()) { "❌ No se encontraron capítulos" }

        // Analizar primer capítulo
        val firstChapter = chapterElements.first()
        println("\n--- PRIMER CAPÍTULO ---")

        val link = firstChapter.selectFirst("span.chapter-link-container a")
        assert(link != null) { "❌ No se encontró enlace con selector 'span.chapter-link-container a'" }

        println("✓ Enlace encontrado:")
        println("  - href: ${link!!.attr("href")}")
        println("  - texto: '${link.text().trim()}'")

        // Extraer todos los capítulos
        println("\n--- EXTRAYENDO TODOS ---")
        var successCount = 0

        chapterElements.forEach { element ->
            val chapterLink = element.selectFirst("span.chapter-link-container a")
                ?: element.select("a").firstOrNull { !it.hasClass("c-new-tag") }

            if (chapterLink != null && chapterLink.attr("href").isNotEmpty()) {
                successCount++
            }
        }

        println("✓ Capítulos válidos: $successCount de ${chapterElements.size}")

        assert(successCount > 0) { "❌ No se extrajo ningún capítulo válido" }
        assert(successCount == chapterElements.size) {
            "⚠️ Solo se extrajeron $successCount de ${chapterElements.size} capítulos"
        }

        println("\n✅ TEST EXITOSO: Todos los capítulos extraídos correctamente\n")
    }
}

