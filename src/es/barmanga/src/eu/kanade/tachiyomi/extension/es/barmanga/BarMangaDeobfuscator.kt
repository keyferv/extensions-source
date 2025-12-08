package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Base64
import android.util.Log

/**
 * Decodificador de URLs ofuscadas para BarManga.
 * 
 * El sitio libribar.com usa múltiples técnicas de ofuscación:
 * 1. data-obfuscated + data-token: Base64 con token de seguridad
 * 2. Variables hex (h1a, h2b, etc.): URL codificada en hexadecimal
 * 3. Variables u1/u2: URL dividida en dos partes Base64
 * 4. imageSegments: Array de segmentos Base64 (puede contener señuelos)
 */
object BarMangaDeobfuscator {

    private const val TAG = "BarManga"

    // Regex patterns para extracción de datos ofuscados
    private val HEX_VARS_REGEX = """var\s+(h\d+[a-z])\s*=\s*['"]([^'"]+)['"]""".toRegex()
    private val U1_REGEX = """var\s+u1\s*=\s*['"]([^'"]+)['"]""".toRegex()
    private val U2_REGEX = """var\s+u2\s*=\s*['"]([^'"]+)['"]""".toRegex()
    private val IMAGE_SEGMENTS_REGEX = """imageSegments\s*=\s*\[([\s\S]*?)\]""".toRegex()
    private val SEGMENT_REGEX = """['"]([^'"]+)['"]""".toRegex()

    /**
     * Decodifica una cadena Base64.
     * 
     * @return String decodificado o null si falla
     */
    fun decodeBase64(encoded: String): String? {
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando Base64: ${e.message}")
            null
        }
    }

    /**
     * Decodifica variables hexadecimales de un script.
     * 
     * Busca patrones como: var h1a = "68"; var h2b = "74"; ...
     * Los concatena y convierte de hex a string.
     * 
     * @return URL decodificada o null si no se encuentra
     */
    fun decodeHexVars(scriptText: String): String? {
        val hexMatches = HEX_VARS_REGEX.findAll(scriptText).toList()
        if (hexMatches.isEmpty()) return null

        return try {
            val hexString = hexMatches.joinToString("") { it.groupValues[2] }
            val decoded = hexString.chunked(2)
                .map { it.toInt(16).toChar() }
                .joinToString("")

            if (decoded.startsWith("http")) decoded else null
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando hex vars: ${e.message}")
            null
        }
    }

    /**
     * Decodifica variables u1/u2 de un script.
     * 
     * Busca: var u1 = "BASE64_PART1"; var u2 = "BASE64_PART2";
     * Concatena las partes decodificadas.
     * 
     * @return URL decodificada o null si no se encuentra
     */
    fun decodeU1U2(scriptText: String): String? {
        val u1Match = U1_REGEX.find(scriptText) ?: return null
        val u2Match = U2_REGEX.find(scriptText) ?: return null

        return try {
            val u1Decoded = decodeBase64(u1Match.groupValues[1]) ?: return null
            val u2Decoded = decodeBase64(u2Match.groupValues[1]) ?: return null
            u1Decoded + u2Decoded
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando u1/u2: ${e.message}")
            null
        }
    }

    /**
     * Decodifica imageSegments de un script.
     * 
     * ⚠️ ADVERTENCIA: Este método puede devolver URLs señuelo/falsas.
     * Solo usar como último recurso cuando hex vars y u1/u2 fallan.
     * 
     * Busca: imageSegments = ["BASE64_1", "BASE64_2", ...]
     * Concatena todos los segmentos decodificados.
     * 
     * @return URL decodificada o null si no se encuentra
     */
    fun decodeImageSegments(scriptText: String): String? {
        val segmentsMatch = IMAGE_SEGMENTS_REGEX.find(scriptText) ?: return null

        return try {
            val segmentsText = segmentsMatch.groupValues[1]
            val segments = SEGMENT_REGEX.findAll(segmentsText)
                .map { it.groupValues[1] }
                .toList()

            if (segments.isEmpty()) return null

            segments.joinToString("") { segment ->
                decodeBase64(segment) ?: ""
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando imageSegments: ${e.message}")
            null
        }
    }

    /**
     * Intenta decodificar una URL de imagen de un script usando todos los métodos.
     * 
     * Orden de prioridad (de más confiable a menos):
     * 1. Variables hex (h1a, h2b, etc.) - URLs reales
     * 2. Variables u1/u2 - URLs divididas
     * 3. imageSegments - ⚠️ Puede contener señuelos
     * 
     * @return Par de (URL decodificada, método usado) o null si falla todo
     */
    fun decodeFromScript(scriptText: String): Pair<String, String>? {
        // Prioridad 1: Variables hex (más confiable)
        decodeHexVars(scriptText)?.let {
            return it to "hex"
        }

        // Prioridad 2: Variables u1/u2
        decodeU1U2(scriptText)?.let {
            return it to "u1/u2"
        }

        // Prioridad 3: imageSegments (último recurso, puede ser señuelo)
        decodeImageSegments(scriptText)?.let {
            return it to "segments"
        }

        return null
    }

    /**
     * Decodifica una imagen ofuscada con data-obfuscated y data-token.
     * 
     * @param obfuscated Valor del atributo data-obfuscated (Base64)
     * @param token Valor del atributo data-token
     * @return URL con token adjunto (url#token=XXX) o null si falla
     */
    fun decodeObfuscatedImage(obfuscated: String, token: String): String? {
        if (obfuscated.isEmpty() || token.isEmpty()) return null

        return try {
            val decoded = decodeBase64(obfuscated)
            if (decoded != null && decoded.isNotEmpty()) {
                "$decoded#token=$token"
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error decodificando imagen ofuscada: ${e.message}")
            null
        }
    }
}
