package eu.kanade.tachiyomi.extension.es.barmanga

import org.jsoup.Jsoup

/**
 * Test para verificar la extracción de páginas (imágenes) de capítulos en BarManga.
 *
 * Situación actual (junio 2026): el sitio quitó el sistema ZIP bundle y ahora
 * usa imágenes directas <img> dentro de div.page-break.no-gaps.
 *
 * Para ejecutar:
 *   Click derecho en main() -> Run 'MainKt'
 */
fun main() {
    println("=== TEST DE EXTRACCIÓN DE PÁGINAS (IMÁGENES DIRECTAS) ===\n")

    val testUrl = "https://archiviumbar.com/manga/me-vi-inmerso-en-un-manga-desconocido/capitulo-1/"
    println("URL: $testUrl\n")

    try {
        val document = Jsoup.connect(testUrl)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .get()

        println("✓ Página cargada\n")

        // ── Verificar que NO hay ZIP bundle ──
        val scriptData = document.select("script")
            .map { it.data() }
            .find { it.contains("const _cfg") && it.contains("bundleAction") }

        println("ZIP bundle (const _cfg + bundleAction): ${if (scriptData != null) "ENCONTRADO" else "NO ENCONTRADO (esperado)"}\n")

        // ── Buscar imágenes directas en page-break ──
        val pageBreaks = document.select("div.page-break")
        println("div.page-break encontrados: ${pageBreaks.size}")

        val images = mutableListOf<String>()
        pageBreaks.forEach { pageBreak ->
            val img = pageBreak.selectFirst("img.wp-manga-chapter-img")
            if (img != null) {
                val src = img.attr("src").trim()
                if (src.isNotEmpty()) {
                    images.add(src)
                }
            }
        }

        println("Imágenes encontradas: ${images.size}\n")

        // ── Mostrar primeras 5 URLs ──
        println("--- PRIMERAS 5 IMÁGENES ---")
        images.take(5).forEachIndexed { index, url ->
            println("  [${index + 1}] $url")
        }

        // ── Verificar que las URLs son válidas ──
        println("\n--- VERIFICACIÓN ---")
        val validUrls = images.filter { it.startsWith("http") }
        println("URLs válidas (http/https): ${validUrls.size}/${images.size}")

        val mangaDataUrls = images.filter { it.contains("/WP-manga/data/") }
        println("URLs con /WP-manga/data/: ${mangaDataUrls.size}/${images.size}")

        // ── Resultado ──
        if (images.isNotEmpty() && validUrls.size == images.size) {
            println("\n✅ TEST EXITOSO: ${images.size} páginas extraídas correctamente")
        } else {
            println("\n❌ TEST FALLÓ: No se extrajeron imágenes válidas")
        }

        // ── Verificar imágenes fuera de page-break (fallback) ──
        println("\n=== TEST: IMÁGENES FUERA DE page-break (fallback) ===\n")
        val outsideImages = document.select("div.reading-content img").filter { img ->
            img.closest("div.page-break") == null
        }
        println("Imágenes fuera de page-break: ${outsideImages.size}")
        outsideImages.forEachIndexed { index, img ->
            val src = img.attr("src").ifEmpty { img.attr("data-src") }
            println("  [${index + 1}] ${img.attr("class")} -> $src")
        }
    } catch (e: Exception) {
        println("❌ ERROR: ${e.message}")
        e.printStackTrace()
    }
}
