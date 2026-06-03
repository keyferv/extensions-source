package eu.kanade.tachiyomi.extension.es.barmanga

import org.jsoup.Jsoup

fun main() {
    println("=== TEST DE EXTRACCIÓN DE PÁGINAS ===\n")

    val testUrl = "https://libribar.com/manga/la-leyenda-de-la-estrella-general/capitulo-311/?style=list"
    println("Conectando a: $testUrl")

    try {
        val document = Jsoup.connect(testUrl)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .get()

        println("✓ Página cargada exitosamente\n")

        val pageBreaks = document.select("div.page-break, div.page-break.no-gaps")
        println("Total de page-breaks encontrados: ${pageBreaks.size}\n")

        if (pageBreaks.isEmpty()) {
            println("❌ NO SE ENCONTRARON PAGE-BREAKS")
            return
        }

        // Analizar primer page-break
        val firstPageBreak = pageBreaks.first()!!
        val scripts = firstPageBreak.select("script")
        println("Scripts en primer page-break: ${scripts.size}\n")

        // Regex para variables hex
        val hexVarRegex = """var\s+(h\d+[a-z])\s*=\s*['"]([^'"]+)['"]""".toRegex()

        // Probar con el segundo script (que tiene las variables hex)
        val hexScript = if (scripts.size > 1) scripts[1] else scripts.firstOrNull()
        if (hexScript != null) {
            val scriptText = hexScript.html()
            println("Script de ${scriptText.length} caracteres")
            println("Contiene 'h0a': ${scriptText.contains("h0a")}")
            println("Contiene 'var h0a': ${scriptText.contains("var h0a")}\n")

            val hexMatches = hexVarRegex.findAll(scriptText).toList()
            println("Variables hex encontradas: ${hexMatches.size}\n")

            if (hexMatches.isNotEmpty()) {
                println("--- DECODIFICANDO HEX ---")
                hexMatches.take(3).forEach { match ->
                    println("  ${match.groupValues[1]} = ${hexToString(match.groupValues[2])}")
                }

                val fullUrl = hexMatches.sortedBy { it.groupValues[1] }
                    .joinToString("") { hexToString(it.groupValues[2]) }
                println("\nURL completa: $fullUrl\n")
            }
        }

        // Extraer todas las páginas
        println("--- EXTRAYENDO TODAS LAS PÁGINAS ---")
        var successCount = 0

        pageBreaks.forEachIndexed { index, pageBreak ->
            val scripts = pageBreak.select("script")
            var imageUrl: String? = null

            scripts.forEach { script ->
                val scriptText = script.html()
                val hexMatches = hexVarRegex.findAll(scriptText).toList()

                if (hexMatches.isNotEmpty()) {
                    try {
                        val decodedUrl = hexMatches.sortedBy { it.groupValues[1] }
                            .joinToString("") { hexToString(it.groupValues[2]) }

                        if (decodedUrl.startsWith("http")) {
                            imageUrl = decodedUrl
                            return@forEach
                        }
                    } catch (_: Exception) {
                        // Ignorar errores
                    }
                }
            }

            if (imageUrl != null) {
                successCount++
                if (index < 5) {
                    println("✓ Página ${index + 1}: $imageUrl")
                }
            } else if (index < 5) {
                println("❌ Página ${index + 1}: NO se encontró URL")
            }
        }

        println("\n--- RESUMEN ---")
        println("✓ Páginas extraídas: $successCount/${pageBreaks.size}")
    } catch (e: Exception) {
        println("❌ ERROR: ${e.message}")
        e.printStackTrace()
    }
}

fun hexToString(hex: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < hex.length) {
        val hexPair = hex.substring(i, minOf(i + 2, hex.length))
        result.append(hexPair.toInt(16).toChar())
        i += 2
    }
    return result.toString()
}
