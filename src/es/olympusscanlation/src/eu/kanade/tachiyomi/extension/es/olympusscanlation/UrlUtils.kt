package eu.kanade.tachiyomi.extension.es.olympusscanlation

import eu.kanade.tachiyomi.source.model.SManga

object UrlUtils {
    fun mangaSlugFromUrl(url: String): String? {
        val value = when {
            "/series/comic-" in url -> url.substringAfter("/series/comic-")
            "/api/series/" in url -> url.substringAfter("/api/series/")
            url.startsWith("comic-") -> url.removePrefix("comic-")
            else -> url
        }

        return value
            .substringBefore("/")
            .substringBefore("?")
            .takeIf { it.isNotBlank() && !it.startsWith("http") }
    }

    fun mangaIdFromUrl(url: String): String? {
        val paramId =
            url
                .substringAfter("mangaId=", "")
                .substringBefore("&")
                .takeIf { it.isNotBlank() }
        if (paramId != null) return paramId

        val raw = url.substringAfterLast("/")
        val value = raw.substringBefore("?")
        return value.takeIf { it.isNotBlank() && it.all { ch -> ch.isDigit() } }
    }

    fun chapterSlugFromUrl(url: String): String = url
        .substringAfter("comic-")
        .substringBefore("/chapters")
        .substringBefore("/comic")
        .substringBefore("?")

    fun chapterMangaIdFromUrl(url: String): String? = mangaIdFromUrl(url)

    fun buildMangaDetailsUrl(
        manga: SManga,
        baseUrl: String,
        cacheManager: MangaCacheManager,
        apiHelper: ApiHelper,
    ): String {
        val id = mangaIdFromUrl(manga.url)
        val slug = apiHelper.resolveSlugForManga(manga, cacheManager)
        val baseUrlWithSlug =
            if (!slug.isNullOrBlank()) {
                "$baseUrl/series/comic-$slug"
            } else if (manga.url.startsWith("/")) {
                val path = manga.url.substringBefore("?")
                baseUrl + path
            } else {
                "$baseUrl/series/comic-${manga.url.substringBefore("?")}"
            }
        return if (id != null) "$baseUrlWithSlug?mangaId=$id" else baseUrlWithSlug
    }
}
