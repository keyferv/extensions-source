package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Log
import eu.kanade.tachiyomi.source.model.Page

/**
 * Filtros para detectar y eliminar imágenes basura/placeholder en BarManga.
 * 
 * El sitio libribar.com incluye imágenes "trampa" como asd.png al final de los capítulos
 * que tienen onerror="this.style.display='none'" - funcionan en navegador pero fallan en scrapers.
 */
object BarMangaFilters {

    private const val TAG = "BarManga"

    // Patrones de URLs que son basura/placeholder
    private val GARBAGE_PATTERNS = listOf(
        "asd.png",
        "/asd.",
        "asd-",
        "blob:",
        "1x1.",
        "placeholder",
        "loading.gif",
        "spacer.",
    )

    // Regex para nombres de archivo muy cortos (ej: asd.png, ab.jpg)
    private val SHORT_FILENAME_REGEX = Regex(".*/[a-z]{1,4}\\.(png|jpg|jpeg|gif|webp)$")

    /**
     * Verifica si una URL es basura/placeholder que debe ser filtrada.
     * 
     * Detecta:
     * - URLs con patrones conocidos (asd.png, placeholder, etc.)
     * - URLs blob (no accesibles fuera del navegador)
     * - Data URIs vacías
     * - Imágenes con nombres muy cortos (probables placeholders)
     */
    fun isGarbageUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return true
        
        val lower = url.lowercase()
        
        // Verificar patrones conocidos
        if (GARBAGE_PATTERNS.any { lower.contains(it) }) return true
        
        // Verificar data URIs vacías (sin base64)
        if (lower.startsWith("data:") && !lower.contains("base64")) return true
        
        // Verificar si termina con "/asd"
        if (lower.endsWith("/asd")) return true
        
        // Verificar nombres de archivo muy cortos
        if (SHORT_FILENAME_REGEX.matches(lower)) return true
        
        return false
    }

    /**
     * Analiza heurísticamente si la última página parece ser basura.
     * 
     * Compara la última imagen con las demás para detectar anomalías:
     * - Nombre de archivo muy corto (≤7 caracteres como "asd.png")
     * - Ubicada en una carpeta diferente a las demás páginas
     * 
     * Inspirado en MangaHere.kt del repo oficial de Keiyoushi.
     */
    fun shouldDropLast(pages: List<Page>): Boolean {
        if (pages.size < 2) return false
        
        val lastUrl = pages.last().imageUrl ?: return false
        
        // Verificar si el nombre del archivo es muy corto (posible placeholder)
        val lastFileName = lastUrl.substringAfterLast("/").substringBefore("?")
        if (lastFileName.length <= 7) { // ej: asd.png = 7 chars
            Log.d(TAG, "Última imagen con nombre corto sospechoso: $lastFileName")
            return true
        }
        
        // Verificar si la última imagen está en una carpeta diferente a las demás
        val lastPath = lastUrl.substringBeforeLast("/")
        val otherPaths = pages.dropLast(1)
            .mapNotNull { it.imageUrl?.substringBeforeLast("/") }
            .distinct()
        
        if (otherPaths.isNotEmpty()) {
            val lastFolder = lastPath.substringAfterLast("uploads/")
            val otherFolders = otherPaths.map { it.substringAfterLast("uploads/") }
            
            // Si la carpeta de la última imagen no coincide con ninguna otra
            if (lastFolder !in otherFolders && 
                !otherFolders.any { lastFolder.startsWith(it.substringBefore("/")) }) {
                Log.d(TAG, "Última imagen en carpeta diferente: $lastFolder vs $otherFolders")
                return true
            }
        }
        
        return false
    }

    /**
     * Filtra páginas basura de una lista y reindexalas.
     * 
     * Proceso:
     * 1. Elimina páginas con URLs detectadas como basura por isGarbageUrl()
     * 2. Verifica si la última página restante sigue siendo sospechosa con shouldDropLast()
     * 3. Reindexa las páginas para mantener índices consecutivos
     */
    fun filterGarbage(pages: List<Page>): List<Page> {
        // Paso 1: Filtrar por patrones de URL
        var filtered = pages.filterNot { page ->
            val url = page.imageUrl ?: ""
            val isGarbage = isGarbageUrl(url)
            if (isGarbage && url.isNotEmpty()) {
                Log.d(TAG, "Filtrando imagen basura: $url")
            }
            isGarbage
        }
        
        // Paso 2: Verificar si la última imagen sigue siendo sospechosa
        if (filtered.isNotEmpty() && shouldDropLast(filtered)) {
            Log.d(TAG, "Eliminando última página sospechosa: ${filtered.last().imageUrl}")
            filtered = filtered.dropLast(1)
        }
        
        // Paso 3: Reindexar si hubo cambios
        return if (filtered.size != pages.size) {
            Log.d(TAG, "Se filtraron ${pages.size - filtered.size} páginas basura de ${pages.size} totales.")
            filtered.mapIndexed { i, p -> Page(i, p.url, p.imageUrl) }
        } else {
            filtered
        }
    }
}
