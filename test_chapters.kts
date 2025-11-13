@file:DependsOn("org.jsoup:jsoup:1.17.2")

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Script de prueba para extraer capítulos de BarManga
 *
 * Para ejecutar (usando Kotlin Scripting):
 * kotlinc -script test_chapters.kts
 *
 * O si tienes kotlin command line:
 * kotlin test_chapters.kts
 *
 * NOTA: El @file:DependsOn descargará Jsoup automáticamente
 */

fun main() {
    println("=== TEST DE EXTRACCIÓN DE CAPÍTULOS ===")

    // URL del manga a probar
    val mangaUrl = "https://libribar.com/manga/la-leyenda-de-la-estrella-general/"

    println("Conectando a: $mangaUrl")

    try {
        // Hacer la petición HTTP
        val document = Jsoup.connect(mangaUrl)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .get()

        println("✓ Página cargada exitosamente")
        println()

        // 1. Buscar los elementos <li class="wp-manga-chapter">
        val chapterListSelector = "li.wp-manga-chapter"
        val chapterElements = document.select(chapterListSelector)

        println("--- ANÁLISIS INICIAL ---")
        println("Selector usado: '$chapterListSelector'")
        println("Elementos <li> encontrados: ${chapterElements.size}")
        println()

        if (chapterElements.isEmpty()) {
            println("❌ NO SE ENCONTRARON CAPÍTULOS")
            println("\nBuscando alternativas...")

            // Debug: buscar otros selectores posibles
            val allLis = document.select("li")
            println("Total de <li> en la página: ${allLis.size}")

            if (allLis.isNotEmpty()) {
                println("\nPrimeras 5 clases de <li>:")
                allLis.take(5).forEach { li ->
                    println("  - ${li.className()}")
                }
            }

            return
        }

        // 2. Analizar el primer capítulo en detalle
        println("--- ANÁLISIS DEL PRIMER CAPÍTULO ---")
        val firstChapter = chapterElements.first()
        println("HTML del primer <li>:")
        println(firstChapter.html().take(500))
        println()

        // 3. Probar el selector específico
        println("--- PROBANDO SELECTOR: 'span.chapter-link-container a' ---")
        val linkWithContainer = firstChapter.selectFirst("span.chapter-link-container a")

        if (linkWithContainer != null) {
            println("✓ Enlace encontrado")
            println("  - href: ${linkWithContainer.attr("href")}")
            println("  - abs:href: ${linkWithContainer.attr("abs:href")}")
            println("  - texto: '${linkWithContainer.text().trim()}'")
            println("  - clase: ${linkWithContainer.className()}")
        } else {
            println("❌ NO se encontró con 'span.chapter-link-container a'")

            // Probar fallback
            println("\n--- PROBANDO FALLBACK: primer <a> que NO sea c-new-tag ---")
            val fallbackLinks = firstChapter.select("a")
            println("Total de enlaces <a>: ${fallbackLinks.size}")

            fallbackLinks.forEachIndexed { index, link ->
                println("  Enlace ${index + 1}:")
                println("    - href: ${link.attr("href")}")
                println("    - texto: '${link.text().trim()}'")
                println("    - clase: ${link.className()}")
                println("    - hasClass('c-new-tag'): ${link.hasClass("c-new-tag")}")
            }

            val validLink = fallbackLinks.firstOrNull { !it.hasClass("c-new-tag") }
            if (validLink != null) {
                println("\n✓ Enlace válido encontrado (sin c-new-tag):")
                println("  - href: ${validLink.attr("href")}")
                println("  - texto: '${validLink.text().trim()}'")
            }
        }

        // 4. Extraer TODOS los capítulos
        println("\n--- EXTRAYENDO TODOS LOS CAPÍTULOS ---")

        var successCount = 0
        var failCount = 0
        val chapters = mutableListOf<ChapterData>()

        chapterElements.forEachIndexed { index, element ->
            val link = element.selectFirst("span.chapter-link-container a")
                ?: element.select("a").firstOrNull { !it.hasClass("c-new-tag") }

            if (link != null && link.attr("href").isNotEmpty()) {
                val chapterData = ChapterData(
                    index = index,
                    name = link.text().trim(),
                    url = link.attr("href"),
                    absUrl = link.attr("abs:href")
                )
                chapters.add(chapterData)
                successCount++

                // Mostrar solo los primeros 5
                if (index < 5) {
                    println("✓ Capítulo ${index + 1}: ${chapterData.name} -> ${chapterData.url}")
                }
            } else {
                failCount++
                if (index < 5) {
                    println("❌ Capítulo ${index + 1}: NO se encontró enlace válido")
                }
            }
        }

        println("\n--- RESUMEN ---")
        println("✓ Capítulos extraídos exitosamente: $successCount")
        println("❌ Capítulos que fallaron: $failCount")
        println("📊 Total: ${chapterElements.size}")
        println()

        // 5. Probar extracción de fechas del primer capítulo
        println("--- EXTRACCIÓN DE FECHAS ---")
        val dateWithTitle = firstChapter.selectFirst("span.chapter-release-date a[title]")
        val dateWithI = firstChapter.selectFirst("span.chapter-release-date i")
        val dateSpan = firstChapter.selectFirst("span.chapter-release-date")

        println("Fecha con a[title]: ${dateWithTitle?.attr("title") ?: "NO ENCONTRADO"}")
        println("Fecha con <i>: ${dateWithI?.text() ?: "NO ENCONTRADO"}")
        println("Fecha en span: ${dateSpan?.text()?.trim() ?: "NO ENCONTRADO"}")
        println()

        // 6. Verificar si hay contenedor AJAX (para debug)
        println("--- VERIFICANDO CONTENEDOR AJAX ---")
        val ajaxContainer = document.select("div[id^=manga-chapters-holder]")
        println("Contenedor 'div[id^=manga-chapters-holder]' encontrado: ${ajaxContainer.size}")
        if (ajaxContainer.isNotEmpty()) {
            ajaxContainer.forEach { div ->
                println("  - id: ${div.id()}")
                println("  - data-id: ${div.attr("data-id")}")
            }
        }
        println()

        // 7. Mostrar estadísticas finales
        println("=== RESULTADO FINAL ===")
        if (successCount == chapterElements.size) {
            println("✅ ÉXITO TOTAL: Todos los capítulos extraídos correctamente")
        } else if (successCount > 0) {
            println("⚠️ PARCIAL: ${successCount}/${chapterElements.size} capítulos extraídos")
        } else {
            println("❌ FALLO: No se pudo extraer ningún capítulo")
        }
        println()

        // Mostrar los últimos 3 capítulos para verificar
        if (chapters.isNotEmpty()) {
            println("Últimos 3 capítulos:")
            chapters.takeLast(3).forEach { chapter ->
                println("  - ${chapter.name} -> ${chapter.url}")
            }
        }

    } catch (e: Exception) {
        println("❌ ERROR: ${e.message}")
        e.printStackTrace()
    }
}

data class ChapterData(
    val index: Int,
    val name: String,
    val url: String,
    val absUrl: String
)

// Ejecutar el test
main()

