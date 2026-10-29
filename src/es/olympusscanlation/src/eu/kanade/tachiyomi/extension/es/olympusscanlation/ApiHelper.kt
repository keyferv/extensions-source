package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.util.Log
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.get
import keiyoushi.utils.jsonInstance
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class ApiHelper(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val json: Json get() = jsonInstance

    fun resolveMangaByName(
        title: String,
        currentId: String? = null,
        cacheManager: MangaCacheManager,
    ): MangaDto? {
        val query = title.trim()
        if (query.length < 3) return null

        Log.d("OlympusScanlation", "Resolviendo manga por lista local/cacheada para '$query'")
        val series = cacheManager.getCachedSeriesList() ?: return null
        val normalized = query.lowercase()
        return currentId?.let { id -> series.firstOrNull { it.id?.toString() == id } }
            ?: series.firstOrNull { it.name.lowercase() == normalized }
            ?: series.firstOrNull { it.name.lowercase().contains(normalized) }
            ?: series.firstOrNull { normalized.contains(it.name.lowercase()) }
    }

    fun resolveMangaById(
        id: String,
        cacheManager: MangaCacheManager,
    ): MangaDto? = cacheManager.getCachedSeriesList()?.firstOrNull { it.id?.toString() == id }

    fun resolveMangaBySlug(
        slug: String,
        cacheManager: MangaCacheManager,
    ): MangaDto? {
        val cleanSlug = slug.trim().removeSuffix("/")
        return cacheManager.getCachedSeriesList()?.firstOrNull { it.slug.trim().removeSuffix("/") == cleanSlug }
    }

    suspend fun forceRefreshSeriesList(cacheManager: MangaCacheManager, websiteBaseUrl: String) {
        synchronized(this) {
            cacheManager.clearCachedSeriesList()
        }
        loadSeriesList(cacheManager, websiteBaseUrl)
    }

    suspend fun ensureSeriesListLoaded(cacheManager: MangaCacheManager, websiteBaseUrl: String) {
        if (cacheManager.getCachedSeriesList() != null) return
        loadSeriesList(cacheManager, websiteBaseUrl)
    }

    private suspend fun loadSeriesList(cacheManager: MangaCacheManager, websiteBaseUrl: String) {
        val apiUrl = "$websiteBaseUrl/api/series/list"
        try {
            val response = client.get(apiUrl, headers, ensureSuccess = false)
            val body = response.body.string()
            if (response.code != 401 && !isErrorPage(response.code, body)) {
                val series = json.decodeMangaListPayload(body)
                cacheManager.setCachedSeriesList(series)
                Log.d("OlympusScanlation", "Lista completa cargada: ${series.size} series")
            } else {
                Log.e("OlympusScanlation", "Error al cargar lista completa: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("OlympusScanlation", "Excepción al cargar lista completa: ${e.message}")
        }
    }

    fun resolveIdBySlug(
        slug: String,
        cacheManager: MangaCacheManager,
    ): String? = resolveMangaBySlug(slug, cacheManager)?.id?.toString()

    fun resolveSlugById(
        id: String,
        title: String?,
        cacheManager: MangaCacheManager,
    ): String? {
        val cached = cacheManager.getCachedSlugForId(id)
        if (!cached.isNullOrBlank()) {
            val match = cacheManager.getCachedSeriesList()?.firstOrNull { it.id?.toString() == id }
            if (match != null && match.slug != cached) {
                cacheManager.updateMangaCache(match)
                return match.slug
            }
            return cached
        }

        val match =
            resolveMangaById(id, cacheManager)
                ?: title?.let { resolveMangaByName(it, id, cacheManager) }
                ?: return null

        cacheManager.updateMangaCache(match)
        return match.slug
    }

    fun resolveSlugForManga(
        manga: SManga,
        cacheManager: MangaCacheManager,
    ): String? {
        var id = UrlUtils.mangaIdFromUrl(manga.url)

        if (id == null) {
            id = cacheManager.resolveIdByTitleFromList(manga.title)
        }

        val cachedSlug = id?.let { cacheManager.getCachedSlugForId(it) }
        if (!cachedSlug.isNullOrBlank()) {
            val match = cacheManager.getCachedSeriesList()?.firstOrNull { it.id?.toString() == id }
            if (match != null && match.slug != cachedSlug) {
                cacheManager.updateMangaCache(match)
                return match.slug
            }
            return cachedSlug
        }

        if (id != null) {
            val matchById = resolveMangaById(id, cacheManager)
            if (matchById != null) {
                cacheManager.updateMangaCache(matchById)
                return matchById.slug
            }
        }

        val match = resolveMangaByName(manga.title, id, cacheManager)
        if (match != null) {
            cacheManager.updateMangaCache(match)
            return match.slug
        }
        return UrlUtils.mangaSlugFromUrl(manga.url)
    }

    fun isErrorPage(
        responseCode: Int,
        body: String,
    ): Boolean {
        if (responseCode == 404 || responseCode == 500) return true
        if (body.contains("Something went wrong", ignoreCase = true)) return true
        return body.contains(">500<")
    }
}
