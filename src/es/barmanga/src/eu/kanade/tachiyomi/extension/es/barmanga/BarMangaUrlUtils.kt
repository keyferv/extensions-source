package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Log

/**
 * Utilidades para normalizar y comparar URLs de imágenes en BarManga.
 * 
 * Funcionalidades:
 * - Normalización de URLs (elimina duplicados http/https, sufijos de tamaño)
 * - Scoring de URLs para elegir la "mejor" cuando hay duplicados
 * - Preservación de tokens de seguridad
 */
object BarMangaUrlUtils {

    private const val TAG = "BarManga"

    // Tokens que indican URLs de baja calidad
    private val BAD_TOKENS = listOf(
        "placeholder", "loading", "fake", "decoy", "trap", "blob:", "asd"
    )

    // Regex para eliminar sufijos de tamaño como -150x150 o _200x300
    private val SIZE_SUFFIX_REGEX = Regex("[-_]\\d{1,4}x\\d{1,4}(?=\\.[a-zA-Z]{2,4}$)")

    /**
     * Normaliza una URL de imagen para comparación/deduplicación.
     * 
     * Proceso:
     * 1. Filtra URLs basura (delegando a BarMangaFilters)
     * 2. Preserva tokens de seguridad (#token=...)
     * 3. Elimina query strings y fragments
     * 4. Normaliza esquema a https
     * 5. Elimina sufijos de tamaño de imagen
     * 
     * @return URL normalizada, o cadena vacía si es inválida
     */
    fun normalizeImageUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        
        // Filtrar URLs basura
        if (BarMangaFilters.isGarbageUrl(url)) {
            Log.d(TAG, "Filtrando URL basura en normalizeImageUrl: $url")
            return ""
        }
        
        return try {
            // Preservar token si existe
            val token = if (url.contains("#token=")) {
                "#token=" + url.substringAfter("#token=")
            } else ""

            // Quitar fragment y query
            var normalized = url
                .substringBefore("#")
                .substringBefore("?")
                .trimEnd('/')

            // Normalizar esquema a https para evitar duplicados
            normalized = when {
                normalized.startsWith("https://") -> normalized
                normalized.startsWith("http://") -> "https://" + normalized.removePrefix("http://")
                else -> normalized
            }

            // Eliminar sufijos de tamaño (ej: imagen-150x150.jpg -> imagen.jpg)
            normalized = normalized.replace(SIZE_SUFFIX_REGEX, "")

            normalized + token
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizando URL: ${e.message}")
            url
        }
    }

    /**
     * Calcula un score de calidad para una URL de imagen.
     * 
     * Puntuación:
     * - +2 si usa HTTPS
     * - +3 si está en /wp-content/uploads/
     * - -5 si es data URI
     * - -10 si contiene tokens sospechosos
     * - +length/50 (preferencia por URLs más largas/descriptivas)
     */
    fun scoreUrl(url: String): Int {
        var score = 0
        
        if (url.startsWith("https://")) score += 2
        if (url.contains("/wp-content/uploads/")) score += 3
        if (url.contains("data:image")) score -= 5
        
        val lower = url.lowercase()
        if (BAD_TOKENS.any { lower.contains(it) }) score -= 10
        
        // Ligera preferencia por URLs más largas (más descriptivas)
        score += url.length / 50
        
        return score
    }

    /**
     * Determina si una URL nueva es "mejor" que una existente.
     * 
     * Útil cuando se encuentran múltiples URLs para la misma imagen
     * (por ejemplo, una ofuscada y una directa).
     */
    fun isBetterImage(newUrl: String, oldUrl: String?): Boolean {
        if (oldUrl.isNullOrEmpty()) return true
        return scoreUrl(newUrl) > scoreUrl(oldUrl)
    }

    /**
     * Extrae el token de seguridad de una URL si existe.
     * 
     * Formato esperado: url#token=XXXXX
     */
    fun extractToken(url: String): String? {
        return if (url.contains("#token=")) {
            url.substringAfter("#token=")
        } else null
    }

    /**
     * Limpia una URL removiendo el token de seguridad.
     */
    fun cleanUrl(url: String): String {
        return url.substringBefore("#token=")
    }
}
