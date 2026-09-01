package eu.kanade.tachiyomi.extension.es.barmanga

import org.jsoup.Jsoup

/**
 * Test simple para verificar la extracción de capítulos
 *
 * Para ejecutar:
 * 1. Abre este archivo en Android Studio
 * 2. Click derecho en main() -> Run 'MainKt'
 *
 * O desde terminal:
 * gradlew.bat :src:es:barmanga:run (si está configurado)
 */

fun main() {
    println("=== TEST DE EXTRACCIÓN DE CAPÍTULOS ===\n")

    val mangaUrl = "https://libribar.com/manga/la-leyenda-de-la-estrella-general/"

    println("Conectando a: $mangaUrl")

    try {
        // Hacer la petición HTTP
        val document = Jsoup.connect(mangaUrl)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .get()

        println("✓ Página cargada exitosamente\n")

        // 1. Buscar los elementos <li class="wp-manga-chapter">
        val chapterListSelector = "li.wp-manga-chapter"
        val chapterElements = document.select(chapterListSelector)

        println("--- ANÁLISIS INICIAL ---")
        println("Selector usado: '$chapterListSelector'")
        println("Elementos <li> encontrados: ${chapterElements.size}\n")

        if (chapterElements.isEmpty()) {
            println("❌ NO SE ENCONTRARON CAPÍTULOS")
            println("\nBuscando alternativas...")

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
        val firstChapter = chapterElements.first()!!
        println("HTML del primer <li>:")
        println(firstChapter.html().take(500))
        println()

        // 3. Probar el selector específico (NUEVO: data-href en span)
        println("--- PROBANDO SELECTOR: 'span.chapter-link-inner[data-href]' ---")
        val linkWithDataHref = firstChapter.selectFirst("span.chapter-link-inner[data-href]")

        if (linkWithDataHref != null) {
            println("✓ Enlace encontrado con data-href")
            println("  - data-href: ${linkWithDataHref.attr("data-href")}")
            println("  - texto: '${linkWithDataHref.text().trim()}'")
            println("  - clase: ${linkWithDataHref.className()}\n")
        } else {
            println("❌ NO se encontró con 'span.chapter-link-inner[data-href]'\n")

            // Probar fallback antiguo (por si acaso)
            println("--- PROBANDO FALLBACK: buscar <a> tradicionales ---")
            val fallbackLinks = firstChapter.select("a")
            println("Total de enlaces <a>: ${fallbackLinks.size}")

            if (fallbackLinks.isNotEmpty()) {
                fallbackLinks.forEachIndexed { index, link ->
                    println("  Enlace ${index + 1}:")
                    println("    - href: ${link.attr("href")}")
                    println("    - texto: '${link.text().trim()}'")
                    println("    - clase: ${link.className()}")
                }
            }
            println()
        }

        // 4. Extraer TODOS los capítulos
        println("--- EXTRAYENDO TODOS LOS CAPÍTULOS ---")

        var successCount = 0
        var failCount = 0
        val chapters = mutableListOf<ChapterData>()

        chapterElements.forEachIndexed { index, element ->
            // NUEVO: Buscar span con data-href
            val linkElement = element.selectFirst("span.chapter-link-inner[data-href]")
            val link = linkElement?.attr("data-href")
            val title = linkElement?.text()?.trim().orEmpty()

            if (!link.isNullOrEmpty()) {
                val chapterData = ChapterData(
                    index = index,
                    name = title.ifEmpty { "Capítulo ${index + 1}" },
                    url = link,
                    absUrl = link, // ya es absoluto
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
        println("📊 Total: ${chapterElements.size}\n")

        // 5. Probar extracción de fechas del primer capítulo
        println("--- EXTRACCIÓN DE FECHAS ---")
        val dateWithTitle = firstChapter.selectFirst("span.chapter-release-date a[title]")
        val dateWithI = firstChapter.selectFirst("span.chapter-release-date i")
        val dateSpan = firstChapter.selectFirst("span.chapter-release-date")

        println("Fecha con a[title]: ${dateWithTitle?.attr("title") ?: "NO ENCONTRADO"}")
        println("Fecha con <i>: ${dateWithI?.text() ?: "NO ENCONTRADO"}")
        println("Fecha en span: ${dateSpan?.text()?.trim() ?: "NO ENCONTRADO"}\n")

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
            println("⚠️ PARCIAL: $successCount/${chapterElements.size} capítulos extraídos")
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
    val absUrl: String,
)
