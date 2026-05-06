package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

class MangaCacheManager(
    private val preferences: SharedPreferences,
) {
    private val mangaSlugById: MutableMap<String, String> = ConcurrentHashMap()
    private val mangaIdBySlug: MutableMap<String, String> = ConcurrentHashMap()
    private val mangaTitleById: MutableMap<String, String> = ConcurrentHashMap()
    private val mangaNormalizedTitleById: MutableMap<String, String> = ConcurrentHashMap()
    private val mangaIdByTitle: MutableMap<String, String> = ConcurrentHashMap()
    private val ambiguousTitles: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var cachedSeriesList: List<MangaDto>? = null

    companion object {
        private const val MANGA_SLUG_PREF_PREFIX = "mangaSlugById_"
    }

    fun updateMangaCache(dto: MangaDto) {
        updateMangaCache(dto.id?.toString(), dto.name, dto.slug)
    }

    fun updateMangaCache(dto: LatestMangaDto) {
        updateMangaCache(dto.id?.toString(), dto.name, dto.slug)
    }

    fun updateMangaCache(
        id: String?,
        title: String?,
        slug: String?,
    ) {
        if (id.isNullOrBlank()) return
        if (!title.isNullOrBlank()) {
            val normalizedTitle = title.trim().lowercase()
            if (normalizedTitle.isNotBlank()) {
                val previousNormalizedTitle = mangaNormalizedTitleById[id]
                if (!previousNormalizedTitle.isNullOrBlank() && previousNormalizedTitle != normalizedTitle) {
                    if (mangaIdByTitle[previousNormalizedTitle] == id) {
                        mangaIdByTitle.remove(previousNormalizedTitle)
                    }
                }

                mangaTitleById[id] = title
                mangaNormalizedTitleById[id] = normalizedTitle

                val existingId = mangaIdByTitle[normalizedTitle]
                when {
                    existingId.isNullOrBlank() && !ambiguousTitles.contains(normalizedTitle) -> {
                        mangaIdByTitle[normalizedTitle] = id
                    }
                    existingId == id -> {
                        // Mantener mapeo estable para el mismo título+id.
                    }
                    existingId != id -> {
                        mangaIdByTitle.remove(normalizedTitle)
                        ambiguousTitles.add(normalizedTitle)
                    }
                }
            }
        }
        if (!slug.isNullOrBlank()) {
            val oldSlug = mangaSlugById[id]
            mangaSlugById[id] = slug
            mangaIdBySlug[slug] = id
            cacheSlugForId(id, slug)
            if (!oldSlug.isNullOrBlank() && oldSlug != slug) {
                mangaIdBySlug.remove(oldSlug)
            }
        }
    }

    fun getCachedSlugForId(id: String): String? {
        val cached = mangaSlugById[id]
        if (!cached.isNullOrBlank()) return cached
        val prefSlug = preferences.getString("$MANGA_SLUG_PREF_PREFIX$id", null)
        if (!prefSlug.isNullOrBlank()) {
            mangaSlugById[id] = prefSlug
        }
        return prefSlug
    }

    private fun cacheSlugForId(
        id: String,
        slug: String,
    ) {
        preferences.edit().putString("$MANGA_SLUG_PREF_PREFIX$id", slug).apply()
    }

    fun resolveIdByTitleFromList(title: String): String? {
        val normalized = title.trim().lowercase()
        if (normalized.isBlank()) return null

        if (ambiguousTitles.contains(normalized)) {
            return resolveUniqueIdFromCachedSeriesByTitle(normalized)
        }

        return mangaIdByTitle[normalized] ?: resolveUniqueIdFromCachedSeriesByTitle(normalized)
    }

    private fun resolveUniqueIdFromCachedSeriesByTitle(normalizedTitle: String): String? {
        val matches =
            cachedSeriesList
                ?.asSequence()
                ?.filter { dto -> dto.name.trim().lowercase() == normalizedTitle }
                ?.mapNotNull { dto -> dto.id?.toString() }
                ?.distinct()
                ?.toList()
                ?: return null
        return if (matches.size == 1) matches.first() else null
    }

    fun getCachedSeriesList(): List<MangaDto>? = cachedSeriesList

    fun setCachedSeriesList(series: List<MangaDto>) {
        cachedSeriesList = series
        series.forEach { updateMangaCache(it) }
    }

    fun clearCachedSeriesList() {
        cachedSeriesList = null
    }

    fun getMangaSlugById(id: String): String? = mangaSlugById[id]

    fun getMangaIdBySlug(slug: String): String? = mangaIdBySlug[slug]

    fun getMangaTitleById(id: String): String? = mangaTitleById[id]
}
