package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class OlympusScanlation :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val cacheManager = MangaCacheManager(preferences)
    private val apiHelper by lazy { ApiHelper(client, headers) }
    private val filterManager = FilterManager()

    private val defaultBaseUrl: String = "https://olympusxyz.com"
    private val isCi = System.getenv("CI") == "true"
    private val shouldFetchDomain: Boolean get() = preferences.getBoolean(FETCH_DOMAIN_PREF, FETCH_DOMAIN_PREF_DEFAULT)

    override val supportsLatest: Boolean = true

    override val supportsFilterFetching: Boolean = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addNetworkInterceptor { chain ->
            val request = chain.request()
            val startTime = System.currentTimeMillis()
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "${response.code} ${request.method} ${request.url} (${duration}ms)")
            response
        }
        rateLimit(1, 2.seconds) { it.host.contains("olympus", ignoreCase = true) && !it.host.startsWith("panel.") }
        rateLimit(2, 1.seconds) { it.host.startsWith("panel.") || it.host.contains("panel.") }
    }

    private suspend fun effectiveBaseUrl(): String {
        if (isCi) return defaultBaseUrl
        if (!shouldFetchDomain) return baseUrl
        val stored = preferences.getString("overrideBaseUrl", defaultBaseUrl) ?: defaultBaseUrl
        if (stored != defaultBaseUrl && stored.isNotBlank()) return baseUrl
        return try {
            val doc = client.get("https://olympus.pages.dev", headers, ensureSuccess = false).asJsoup()
            val domain = doc.selectFirst("meta[property=og:url]")?.attr("content") ?: return baseUrl
            val host = client.get(domain, headers, ensureSuccess = false).request.url.host
            val newDomain = "https://$host"
            if (newDomain != baseUrl && newDomain.isNotBlank()) {
                preferences.edit().putString("overrideBaseUrl", newDomain).apply()
                newDomain
            } else {
                baseUrl
            }
        } catch (_: Exception) {
            baseUrl
        }
    }

    private suspend fun publicBaseUrl(): String = effectiveBaseUrl()

    private suspend fun dashboardBaseUrl(): String = effectiveBaseUrl().replace("https://", "https://panel.")

    private var SharedPreferences.slugMap: Map<Int, String>
        get() = runCatching {
            jsonInstance.decodeFromString<Map<Int, String>>(getString(SLUG_MAP, "{}") ?: "{}")
        }.getOrDefault(emptyMap())
        set(value) {
            edit().putString(SLUG_MAP, jsonInstance.encodeToString(value)).apply()
        }

    private var SharedPreferences.chapterCountMap: Map<Int, Int>
        get() = runCatching {
            jsonInstance.decodeFromString<Map<Int, Int>>(getString(CHAPTER_COUNT_MAP, "{}") ?: "{}")
        }.getOrDefault(emptyMap())
        set(value) {
            edit().putString(CHAPTER_COUNT_MAP, jsonInstance.encodeToString(value)).apply()
        }

    @Volatile
    private var seriesList: List<MangaDto> = emptyList()

    @Volatile
    private var lastFetchTime: Long = 0L

    @Volatile
    private var chapterNameToIdCache: Map<String, Int> = emptyMap()

    private suspend fun fetchSeriesList() {
        val now = System.currentTimeMillis()
        if (seriesList.isNotEmpty() && (now - lastFetchTime) < CACHE_DURATION_MS) return
        try {
            val comics = fetchSeriesListFromListEndpoint()
            synchronized(this) {
                seriesList = comics
                lastFetchTime = now
            }
            val newSlugMap = comics.mapNotNull { dto -> dto.id?.let { it to dto.slug } }.toMap()
            preferences.slugMap = preferences.slugMap + newSlugMap + fetchHomepageSlugs()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh series list, falling back to homepage slugs", e)
            try {
                val homepageSlugs = fetchHomepageSlugs()
                if (homepageSlugs.isNotEmpty()) {
                    preferences.slugMap = preferences.slugMap + homepageSlugs
                }
            } catch (homepageError: Exception) {
                Log.w(TAG, "Failed to refresh homepage slugs", homepageError)
            }
        }
    }

    private suspend fun fetchSeriesListFromListEndpoint(): List<MangaDto> {
        val base = publicBaseUrl()
        val response = client.get("$base/api/series/list", ensureSuccess = false)
        val body = response.body.string()
        if (!response.isSuccessful) throw Exception("Failed to fetch series list: HTTP ${response.code}")
        if (apiHelper.isErrorPage(response.code, body)) throw Exception("Error page for series list")
        return jsonInstance.decodeMangaListPayload(body).filter { it.type == "comic" }
    }

    private suspend fun fetchHomepageSlugs(): Map<Int, String> = try {
        val base = publicBaseUrl()
        val dto = client.get("$base/api/homepage", ensureSuccess = false).parseAs<HomepageDto>()
        val slugs = mutableMapOf<Int, String>()
        dto.data.newChapters?.filter { it.type == "comic" }?.forEach { slugs[it.id] = it.slug }
        dto.rankings?.filter { it.type == "comic" }?.forEach { slugs[it.id] = it.slug }
        slugs
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse homepage slugs", e)
        emptyMap()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        fetchSeriesList()
        return fetchPopularMangaByScraping()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val base = publicBaseUrl()
        return try {
            val url = "$base/api/new-chapters".toHttpUrl().newBuilder().addQueryParameter("page", page.toString()).build()
            val payload = client.get(url, ensureSuccess = false).parseAs<NewChaptersDto>()
            val mangaList = payload.data.filter { it.type == "comic" }.mapNotNull { dto ->
                val mangaId = dto.id ?: return@mapNotNull null
                cacheManager.updateMangaCache(dto)
                preferences.slugMap = preferences.slugMap + (mangaId to dto.slug)
                dto.toSManga(mangaId.toString())
            }
            MangasPage(mangaList, hasNextPage = payload.current_page < payload.last_page)
        } catch (e: Exception) {
            Log.w(TAG, "getLatestUpdates API failed, falling back to HTML", e)
            fetchLatestUpdatesByScraping(page)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val base = publicBaseUrl()
        if (query.isNotEmpty()) {
            if (query.length < 3) throw Exception("La búsqueda debe tener al menos 3 caracteres")
            if (query.toHttpUrlOrNull() != null) {
                val byUrl = getMangaByUrl(query.toHttpUrl())
                return if (byUrl != null) MangasPage(listOf(byUrl), false) else MangasPage(emptyList(), false)
            }
            val normalizedQuery = query.trim().lowercase()
            val body = client.get("$base/api/series/list", ensureSuccess = false).body.string()
            val mangaList = jsonInstance.decodeMangaListPayload(body).filter { it.type == "comic" }.filter { it.name.lowercase().contains(normalizedQuery) }.map { dto ->
                cacheManager.updateMangaCache(dto)
                dto.toSManga(resolveStableId(dto.slug, dto.name, dto.id?.toString()))
            }
            return MangasPage(mangaList, hasNextPage = false)
        }

        val urlBuilder = "$base/api/series".toHttpUrl().newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.state?.ascending == true) urlBuilder.addQueryParameter("direction", "desc") else urlBuilder.addQueryParameter("direction", "asc")
                }
                is GenreFilter -> {
                    if (filter.toUriPart() != 9999) urlBuilder.addQueryParameter("genres", filter.toUriPart().toString())
                }
                is StatusFilter -> {
                    if (filter.toUriPart() != 9999) urlBuilder.addQueryParameter("status", filter.toUriPart().toString())
                }
                else -> {}
            }
        }
        urlBuilder.addQueryParameter("type", "comic")
        urlBuilder.addQueryParameter("page", page.toString())
        val response = client.get(urlBuilder.build(), ensureSuccess = false)
        val body = response.body.string()
        if (response.code == 401) {
            logHttpIssue("searchManga#401", response.code, response.request.url.toString())
            throw Exception("Error en la búsqueda: sesión no autorizada (401)")
        }
        if (apiHelper.isErrorPage(response.code, body)) {
            logHttpIssue("searchManga", response.code, response.request.url.toString())
            throw Exception("Error en la búsqueda: respuesta HTML inesperada")
        }
        val mangaList = jsonInstance.decodeMangaListPayload(body).filter { it.type == "comic" }.map { dto ->
            cacheManager.updateMangaCache(dto)
            dto.toSManga(resolveStableId(dto.slug, dto.name, dto.id?.toString()))
        }
        return MangasPage(mangaList, hasNextPage = false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val effective = try {
            effectiveBaseUrl()
        } catch (_: Exception) {
            baseUrl
        }
        val baseHost = try {
            baseUrl.toHttpUrl().host
        } catch (_: Exception) {
            ""
        }
        val effectiveHost = try {
            effective.toHttpUrl().host
        } catch (_: Exception) {
            baseHost
        }
        if (url.host != baseHost && url.host != effectiveHost && !url.host.endsWith(".$effectiveHost") && !url.host.contains("olympus")) return null
        val path = url.encodedPath
        if (path == "/" || path == "/explorar" || path.startsWith("/explorar/")) return null
        val slug = UrlUtils.mangaSlugFromUrl(url.toString()) ?: return null
        if (slug.isBlank() || slug.startsWith("http")) return null
        try {
            apiHelper.ensureSeriesListLoaded(cacheManager, effective)
        } catch (_: Exception) { }
        // Try numeric ID from cache first
        val cachedId = cacheManager.getMangaIdBySlug(slug)
        if (cachedId != null) {
            val title = cacheManager.getMangaTitleById(cachedId) ?: slug
            val dto = apiHelper.resolveMangaById(cachedId, cacheManager) ?: apiHelper.resolveMangaBySlug(slug, cacheManager)
            if (dto != null) {
                preferences.slugMap = preferences.slugMap + (dto.id!! to dto.slug)
                cacheManager.updateMangaCache(dto)
                return dto.toSMangaDetails(dto.id.toString())
            }
            return SManga.create().apply {
                this.title = title
                this.url = cachedId
                this.thumbnail_url = null
            }
        }
        // Resolve via API cache
        val match = apiHelper.resolveMangaBySlug(slug, cacheManager)
        if (match != null) {
            preferences.slugMap = preferences.slugMap + (match.id!! to match.slug)
            cacheManager.updateMangaCache(match)
            return match.toSMangaDetails(match.id.toString())
        }
        // Fallback: try fetch details by slug directly
        return try {
            val base = publicBaseUrl()
            val dto = client.get("$base/api/series/$slug?type=comic", ensureSuccess = false).let { resp ->
                val b = resp.body.string()
                if (apiHelper.isErrorPage(resp.code, b) || resp.code == 401) null else jsonInstance.decodeMangaDetailPayload(b)
            } ?: return null
            preferences.slugMap = preferences.slugMap + (dto.id!! to dto.slug)
            cacheManager.updateMangaCache(dto)
            dto.toSMangaDetails(dto.id.toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveStableId(slug: String, name: String, idString: String?): String {
        val id = idString?.toIntOrNull() ?: apiHelper.resolveIdBySlug(slug, cacheManager)?.toIntOrNull() ?: throw Exception("Unable to resolve Olympus manga ID for $name")
        preferences.slugMap = preferences.slugMap + (id to slug)
        cacheManager.updateMangaCache(id.toString(), name, slug)
        return id.toString()
    }

    private fun parseMangaId(url: String): Int {
        val idFromParam = url.substringAfter("mangaId=", "").substringBefore("&").takeIf { it.isNotEmpty() }
        val rawId = idFromParam ?: url.substringBefore("/").substringBefore("?")
        return rawId.trim().toIntOrNull() ?: throw IllegalArgumentException("Unable to parse Olympus manga ID from URL: $url")
    }

    private fun parseMangaIdOrNull(url: String): Int? = runCatching { parseMangaId(url) }.getOrNull()

    private fun normalizedMangaId(url: String): String = parseMangaId(url).toString()

    private fun parseChapterIds(url: String): Pair<String, String> {
        val mangaId = normalizedMangaId(url)
        val chapterId = if (url.contains("/capitulo/")) {
            url.substringAfter("/capitulo/").substringBefore("/").substringBefore("?")
        } else {
            url.substringAfter("/", "").substringBefore("?")
        }.normalizeChapterIdentifier()
        if (chapterId.isEmpty()) throw IllegalArgumentException("Unable to parse Olympus chapter ID from URL: $url")
        return mangaId to chapterId
    }

    private fun String.normalizeChapterIdentifier(): String = trim().removePrefix("Capitulo").removePrefix("Capítulo").removePrefix("capitulo").removePrefix("capítulo").trim()

    override fun getMangaUrl(manga: SManga): String {
        val mangaId = parseMangaIdOrNull(manga.url)
        val slug = if (mangaId != null) {
            preferences.slugMap[mangaId] ?: cacheManager.getCachedSlugForId(mangaId.toString()) ?: UrlUtils.mangaSlugFromUrl(manga.url) ?: manga.url.substringBefore("?")
        } else {
            UrlUtils.mangaSlugFromUrl(manga.url) ?: throw Exception("Slug not found for manga ${manga.title}")
        }
        return "$baseUrl/series/comic-$slug"
    }

    override fun getChapterUrl(chapter: SChapter): String = try {
        val (mangaId, chapterIdentifier) = parseChapterIds(chapter.url)
        val parsedId = parseMangaId(mangaId)
        val mangaSlug = preferences.slugMap[parsedId] ?: cacheManager.getCachedSlugForId(parsedId.toString()) ?: UrlUtils.chapterSlugFromUrl(chapter.url).takeIf { it.isNotBlank() } ?: "unknown"
        val backendChapterId = chapterNameToIdCache["$mangaId/$chapterIdentifier"]?.toString() ?: chapterIdentifier
        "$baseUrl/capitulo/$backendChapterId/comic-$mangaSlug"
    } catch (_: Exception) {
        baseUrl + chapter.url
    }

    private suspend fun resolveSlugForMangaId(mangaId: Int, title: String? = null): String {
        val id = mangaId.toString()
        try {
            val base = publicBaseUrl()
            apiHelper.ensureSeriesListLoaded(cacheManager, base)
        } catch (_: Exception) { }
        val fromHelper = apiHelper.resolveSlugById(id, title, cacheManager)
        if (!fromHelper.isNullOrBlank()) {
            preferences.slugMap = preferences.slugMap + (mangaId to fromHelper)
            return fromHelper
        }
        return preferences.slugMap[mangaId] ?: throw Exception("Slug not found for manga $mangaId")
    }

    private suspend fun updateTaggedMangaUrl(slug: String, taggedManga: SManga?) {
        if (taggedManga == null) return
        val mangaId = UrlUtils.mangaIdFromUrl(taggedManga.url) ?: return
        preferences.slugMap = preferences.slugMap + (mangaId.toInt() to slug)
        taggedManga.url = mangaId
        cacheManager.updateMangaCache(mangaId, taggedManga.title, slug)
    }

    private suspend fun fetchMangaDetailsBySlug(slug: String): SManga {
        val base = publicBaseUrl()
        val body = client.get("$base/api/series/$slug?type=comic", ensureSuccess = false).body.string()
        if (body.isBlank()) throw Exception("Empty body for $slug")
        val dto = jsonInstance.decodeMangaDetailPayload(body)
        cacheManager.updateMangaCache(dto)
        persistChapterCount(dto.id, dto.chapterCount, "fetchMangaDetailsBySlug")
        return dto.toSMangaDetails(resolveStableId(dto.slug, dto.name, dto.id?.toString()))
    }

    private suspend fun fetchMangaDtoBySlug(slug: String): MangaDto {
        val base = publicBaseUrl()
        val response = client.get("$base/api/series/$slug?type=comic", ensureSuccess = false)
        val body = response.body.string()
        if (apiHelper.isErrorPage(response.code, body)) throw Exception("Error al obtener detalles de Olympus")
        return jsonInstance.decodeMangaDetailPayload(body)
    }

    private suspend fun fetchMangaDetailsByScraping(slug: String, preferredMangaId: String?, preferredTitle: String?): SManga {
        val base = publicBaseUrl()
        val document = client.get("$base/series/comic-$slug", ensureSuccess = false).asJsoup()
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { preferredTitle ?: slug }
        val id = preferredMangaId ?: apiHelper.resolveIdBySlug(slug, cacheManager) ?: throw Exception("Unable to resolve Olympus manga ID for $title")
        preferences.slugMap = preferences.slugMap + (id.toInt() to slug)
        cacheManager.updateMangaCache(id, title, slug)
        return SManga.create().apply {
            this.title = title
            url = id
            thumbnail_url = document.selectFirst("img[src*=/storage/], img[src]")?.attr("abs:src")?.trim()
            description = document.selectFirst("p")?.text()?.trim()
        }
    }

    private fun mangaSlugFromDetailsRequest(url: String): String = UrlUtils.mangaSlugFromUrl(url) ?: throw Exception("Unable to parse Olympus manga slug from $url")

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val mangaId = parseMangaIdOrNull(manga.url)
        val slug = if (mangaId != null) {
            try {
                resolveSlugForMangaId(mangaId, manga.title)
            } catch (_: Exception) {
                UrlUtils.mangaSlugFromUrl(manga.url) ?: throw Exception("Slug not found for manga ${manga.title}")
            }
        } else {
            UrlUtils.mangaSlugFromUrl(manga.url) ?: throw Exception("Slug not found for manga ${manga.title}")
        }

        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        // When both needed, fetch concurrently
        return coroutineScope {
            val detailsDeferred = if (fetchDetails) async { fetchMangaDetailsWithFallback(slug, manga) } else null
            val chaptersDeferred = if (fetchChapters) async { fetchChapterListWithFallback(slug, manga) } else null

            val updatedManga = detailsDeferred?.await() ?: manga
            val updatedChapters = chaptersDeferred?.await() ?: chapters

            SMangaUpdate(updatedManga, updatedChapters)
        }
    }

    private suspend fun fetchMangaDetailsWithFallback(slug: String, manga: SManga): SManga {
        val base = publicBaseUrl()
        return try {
            val response = client.get("$base/api/series/$slug?type=comic", ensureSuccess = false)
            val body = response.body.string()
            val taggedId = UrlUtils.mangaIdFromUrl(manga.url)
            if (response.code == 401) {
                logHttpIssue("mangaDetails#401", response.code, response.request.url.toString())
                return fetchMangaDetailsByScraping(slug, taggedId, manga.title)
            }
            if (apiHelper.isErrorPage(response.code, body)) {
                logHttpIssue("mangaDetails#errorPage", response.code, response.request.url.toString())
                val match = manga.title.let { apiHelper.resolveMangaByName(it, taggedId, cacheManager) }
                if (match != null) {
                    cacheManager.updateMangaCache(match)
                    val details = fetchMangaDetailsBySlug(match.slug)
                    updateTaggedMangaUrl(match.slug, manga)
                    return details
                }
                return fetchMangaDetailsByScraping(slug, taggedId, manga.title)
            }
            val dto = jsonInstance.decodeMangaDetailPayload(body)
            cacheManager.updateMangaCache(dto)
            persistChapterCount(dto.id, dto.chapterCount, "mangaDetails")
            val resolvedId = resolveStableId(dto.slug, dto.name, dto.id?.toString())
            val details = dto.toSMangaDetails(resolvedId)
            updateTaggedMangaUrl(dto.slug, manga)
            details
        } catch (e: Exception) {
            Log.w(TAG, "fetchMangaDetails fallback to scraping", e)
            val taggedId = UrlUtils.mangaIdFromUrl(manga.url)
            fetchMangaDetailsByScraping(slug, taggedId, manga.title)
        }
    }

    private suspend fun fetchChapterListWithFallback(slug: String, manga: SManga): List<SChapter> = try {
        val base = dashboardBaseUrl()
        val mangaId = normalizedMangaId(manga.url)
        fetchChapterListPaginated(slug, mangaId, base, manga)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch API chapters for manga ${manga.url}, falling back to HTML", e)
        val (chapters, parsedTotal) = fetchChapterListFromHtml(manga)
        val validated = validateChapterList(normalizedMangaId(manga.url), UrlUtils.mangaSlugFromUrl(manga.url), chapters, "html-fallback", parsedTotal, 1)
        persistChapterCount(parseMangaIdOrNull(normalizedMangaId(manga.url)), parsedTotal, "html-fallback")
        validated
    }

    private suspend fun fetchChapterListPaginated(slug: String, mangaId: String, dashboardBase: String, manga: SManga): List<SChapter> {
        val firstUrl = "$dashboardBase/api/series/$slug/chapters?page=1&direction=desc&type=comic"
        val firstResp = client.get(firstUrl, ensureSuccess = false)
        val firstBody = firstResp.body.string()
        if (firstResp.code == 401) {
            logHttpIssue("chapterList#401", firstResp.code, firstResp.request.url.toString())
            return fetchChapterListByScraping(slug, mangaId)
        }
        if (apiHelper.isErrorPage(firstResp.code, firstBody)) {
            logHttpIssue("chapterList#errorPage", firstResp.code, firstResp.request.url.toString())
            val retry = forceRefreshAndRetryChapterList(mangaId, firstResp.request.url.toString())
            if (retry != null) return retry
            val match = mangaId.let { apiHelper.resolveMangaById(it, cacheManager) } ?: manga.title.let { apiHelper.resolveMangaByName(it, mangaId, cacheManager) }
            if (match != null) {
                cacheManager.updateMangaCache(match)
                updateTaggedMangaUrl(match.slug, manga)
                return fetchChapterListBySlug(match.slug, match.id?.toString() ?: mangaId)
            }
            return fetchChapterListByScraping(slug, mangaId)
        }
        val data = jsonInstance.decodeFromString<PayloadChapterDto>(firstBody)
        Log.d(TAG, "Chapter page loaded: mangaId=$mangaId slug=$slug page=1 pageCount=${data.data.size} reportedTotal=${data.meta.total}")
        var resultSize = data.data.size
        var page = 2
        while (data.meta.total > resultSize) {
            val newUrl = "$dashboardBase/api/series/$slug/chapters?page=$page&direction=desc&type=comic"
            val resp = client.get(newUrl, ensureSuccess = false)
            val body = resp.body.string()
            if (apiHelper.isErrorPage(resp.code, body)) throw Exception("Error al obtener página $page de capítulos")
            val newData = jsonInstance.decodeFromString<PayloadChapterDto>(body)
            Log.d(TAG, "Chapter page loaded: mangaId=$mangaId slug=$slug page=$page pageCount=${newData.data.size} reportedTotal=${newData.meta.total}")
            if (newData.data.isEmpty()) throw Exception("Olympus chapter pagination stopped with empty page: mangaId=$mangaId slug=$slug page=$page loaded=$resultSize reportedTotal=${data.meta.total}")
            data.data += newData.data
            resultSize += newData.data.size
            page += 1
        }
        synchronized(this) {
            val cacheUpdates = mutableMapOf<String, Int>()
            data.data.forEach { dto -> cacheUpdates["$mangaId/${dto.name}"] = dto.id }
            chapterNameToIdCache = chapterNameToIdCache + cacheUpdates
        }
        val chapters = data.data.map { it.toSChapter(slug, mangaId) }
        persistChapterCount(parseMangaIdOrNull(mangaId), data.meta.total, "chapterList-api")
        return validateChapterList(mangaId, slug, chapters, "api", data.meta.total, page - 1)
    }

    private suspend fun fetchChapterListBySlug(slug: String, mangaId: String): List<SChapter> {
        val base = dashboardBaseUrl()
        return fetchChapterListPaginated(
            slug,
            mangaId,
            base,
            SManga.create().apply {
                url = mangaId
                title = cacheManager.getMangaTitleById(mangaId) ?: slug
            },
        )
    }

    private suspend fun fetchChapterListByScraping(slug: String, preferredMangaId: String?): List<SChapter> {
        val mangaId = preferredMangaId ?: apiHelper.resolveIdBySlug(slug, cacheManager) ?: throw Exception("Unable to resolve Olympus manga ID for $slug")
        val (chapters, parsedTotal) = fetchChapterListFromHtml(
            SManga.create().apply {
                title = cacheManager.getMangaTitleById(mangaId) ?: slug
                url = mangaId
            },
        )
        val validated = validateChapterList(mangaId, slug, chapters, "html-fallback-internal", parsedTotal, 1)
        persistChapterCount(parseMangaIdOrNull(mangaId), parsedTotal, "html-fallback-internal")
        return validated
    }

    private suspend fun forceRefreshAndRetryChapterList(taggedMangaId: String?, originalUrl: String): List<SChapter>? {
        if (taggedMangaId == null) return null
        Log.d(TAG, "Stale-slug recovery: force-refreshing series list for mangaId=$taggedMangaId")
        val base = publicBaseUrl()
        try {
            apiHelper.forceRefreshSeriesList(cacheManager, base)
        } catch (e: Exception) {
            Log.w(TAG, "Stale-slug recovery: force-refresh failed", e)
            return null
        }
        val match = apiHelper.resolveMangaById(taggedMangaId, cacheManager) ?: return null
        val newSlug = match.slug
        val oldSlug = originalUrl.substringAfter("/series/").substringBefore("/chapters")
        if (newSlug == oldSlug) {
            Log.d(TAG, "Stale-slug recovery: slug unchanged ($newSlug), skipping retry")
            return null
        }
        Log.d(TAG, "Stale-slug recovery: retrying with new slug=$newSlug (was=$oldSlug)")
        cacheManager.updateMangaCache(match)
        val mangaId = match.id?.toString() ?: taggedMangaId
        return fetchChapterListBySlug(newSlug, mangaId)
    }

    private suspend fun fetchChapterListFromHtml(manga: SManga): Pair<List<SChapter>, Int?> {
        val mangaId = normalizedMangaId(manga.url)
        val slug = resolveSlugForMangaId(parseMangaId(mangaId), manga.title)
        val base = publicBaseUrl()
        val pageUrl = "$base/series/comic-$slug"
        val document = client.get(pageUrl, ensureSuccess = false).asJsoup()
        val parsedTotal = CHAPTER_COUNT_TEXT_REGEX.find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val chapters = document.select("a[href*=/capitulo/]").mapNotNull { element ->
            val href = element.attr("href")
            val chapterId = href.substringAfter("/capitulo/").substringBefore("/").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val chapterNameEl = element.selectFirst(".chapter-name")
            val chapterNameText = chapterNameEl?.text()?.trim()
            val chapterNumber = chapterNameText?.let { text -> CHAPTER_NUMBER_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1) ?: text.toFloatOrNull()?.toString() } ?: "-1"
            val timeEl = element.selectFirst("time[datetime]")
            val dateStr = timeEl?.attr("datetime") ?: ""
            val backendId = chapterId.toIntOrNull()
            SChapter.create().apply {
                name = "Capitulo $chapterNumber"
                url = "$mangaId/$chapterNumber"
                chapter_number = chapterNumber.toFloatOrNull() ?: -1f
                date_upload = try {
                    kotlin.time.Instant.parseOrNull(dateStr)?.toEpochMilliseconds() ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }.also {
                if (backendId != null) {
                    synchronized(this) { chapterNameToIdCache = chapterNameToIdCache + mapOf("$mangaId/$chapterNumber" to backendId) }
                }
            }
        }
        Log.d(TAG, "HTML chapter list loaded: mangaId=$mangaId slug=$slug chapterCount=${chapters.size} parsedTotal=$parsedTotal")
        return Pair(chapters, parsedTotal)
    }

    private fun persistChapterCount(mangaId: Int?, chapterCount: Int?, source: String) {
        if (mangaId == null || chapterCount == null || chapterCount <= 0) return
        val existing = preferences.chapterCountMap[mangaId]
        if (existing == null || chapterCount > existing) {
            preferences.chapterCountMap = preferences.chapterCountMap + (mangaId to chapterCount)
            Log.d(TAG, "Persisted expected chapter count: mangaId=$mangaId count=$chapterCount source=$source previous=$existing")
        }
    }

    private fun validateChapterList(mangaId: String, slug: String?, chapters: List<SChapter>, source: String, reportedTotal: Int?, pagesFetched: Int): List<SChapter> {
        val parsedMangaId = parseMangaIdOrNull(mangaId)
        if (parsedMangaId == null) {
            Log.d(TAG, "Chapter list accepted without count guard: mangaId=$mangaId slug=$slug source=$source current=${chapters.size} reportedTotal=$reportedTotal pagesFetched=$pagesFetched")
            return chapters
        }
        val previousCount = preferences.chapterCountMap[parsedMangaId]
        val maxChapterNumber = chapters.maxOfOrNull { it.chapter_number }
        val maxChapterFloor = maxChapterNumber?.toInt()?.takeIf { it > 0 }
        val bestExpectedCount = listOfNotNull(reportedTotal, previousCount, maxChapterFloor).maxOrNull()
        Log.d(TAG, "Chapter list summary: mangaId=$parsedMangaId slug=$slug source=$source current=${chapters.size} previous=$previousCount reportedTotal=$reportedTotal bestExpected=$bestExpectedCount pagesFetched=$pagesFetched maxChapterNumber=$maxChapterNumber maxChapterFloor=$maxChapterFloor")
        if (bestExpectedCount != null && bestExpectedCount > 1) {
            val minimumAccepted = bestExpectedCount * MIN_ACCEPTED_CHAPTER_PERCENT / 100
            if (minimumAccepted > 1 && chapters.size < minimumAccepted) {
                throw Exception("Olympus chapter list looks incomplete: mangaId=$parsedMangaId slug=$slug source=$source bestExpected=$bestExpectedCount current=${chapters.size} minimumAccepted=$minimumAccepted reportedTotal=$reportedTotal previous=$previousCount pagesFetched=$pagesFetched maxChapterNumber=$maxChapterNumber")
            }
        }
        if (chapters.isNotEmpty()) {
            persistChapterCount(parsedMangaId, chapters.size, source)
            Log.d(TAG, "Stored good chapter count: mangaId=$parsedMangaId count=${chapters.size} source=$source")
        }
        return chapters
    }

    private suspend fun resolveChapterId(mangaId: String, chapterIdentifier: String, mangaSlug: String): String {
        val normalized = chapterIdentifier.normalizeChapterIdentifier()
        val cacheKey = "$mangaId/$normalized"
        chapterNameToIdCache[cacheKey]?.let { return it.toString() }
        val parsedMangaId = parseMangaId(mangaId)
        try {
            val base = dashboardBaseUrl()
            val first = client.get("$base/api/series/$mangaSlug/chapters?page=1&direction=desc&type=comic", ensureSuccess = false).parseAs<PayloadChapterDto>()
            val allChapters = mutableListOf<ChapterDto>()
            allChapters += first.data
            var resultSize = first.data.size
            var page = 2
            while (first.meta.total > resultSize) {
                val newData = client.get("$base/api/series/$mangaSlug/chapters?page=$page&direction=desc&type=comic", ensureSuccess = false).parseAs<PayloadChapterDto>()
                allChapters += newData.data
                resultSize += newData.data.size
                page += 1
            }
            synchronized(this) {
                val cacheUpdates = mutableMapOf<String, Int>()
                allChapters.forEach { dto -> cacheUpdates["$mangaId/${dto.name}"] = dto.id }
                chapterNameToIdCache = chapterNameToIdCache + cacheUpdates
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve chapter ID via chapter list for manga $parsedMangaId", e)
        }
        chapterNameToIdCache[cacheKey]?.let { return it.toString() }
        normalized.toIntOrNull()?.let { return normalized }
        throw Exception("Unable to resolve chapter ID for $chapterIdentifier in manga $mangaId")
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (mangaId, chapterIdentifier) = parseChapterIds(chapter.url)
        val parsedId = parseMangaId(mangaId)
        val mangaSlug = try {
            resolveSlugForMangaId(parsedId)
        } catch (_: Exception) {
            UrlUtils.chapterSlugFromUrl(chapter.url).takeIf { it.isNotBlank() } ?: throw Exception("Unable to resolve slug")
        }
        val backendChapterId = resolveChapterId(mangaId, chapterIdentifier, mangaSlug)
        val base = publicBaseUrl()
        val apiUrl = "$base/api/capitulo/comic-$mangaSlug/$backendChapterId"
        val response = client.get(apiUrl, ensureSuccess = false)
        val body = response.body.string()
        if (response.code == 401 || apiHelper.isErrorPage(response.code, body)) {
            logHttpIssue("pageList", response.code, response.request.url.toString())
            val chapterId = getChapterIdFromUrl(response.request.url.toString()) ?: backendChapterId
            val slug = getMangaSlugFromUrl(response.request.url.toString()) ?: mangaSlug
            return fetchChapterPagesByScraping(slug, chapterId)
        }
        return try {
            jsonInstance.decodeFromString<PayloadPagesDto>(body).chapter.pages.mapIndexed { i, img -> Page(i, imageUrl = img) }
        } catch (e: Exception) {
            logHttpIssue("pageList#parse", response.code, response.request.url.toString())
            fetchChapterPagesByScraping(mangaSlug, backendChapterId)
        }
    }

    private fun getChapterIdFromUrl(url: String): String? = when {
        "/api/capitulo/comic-" in url -> url.substringAfterLast("/").substringBefore("?")
        "/chapters/" in url -> url.substringAfter("/chapters/").substringBefore("?").substringBefore("/")
        "/capitulo/" in url -> url.substringAfter("/capitulo/").substringBefore("/").substringBefore("?")
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun getMangaSlugFromUrl(url: String): String? {
        if ("/api/capitulo/comic-" in url) return url.substringAfter("/api/capitulo/comic-").substringBefore("/").substringBefore("?")
        if ("/capitulo/" in url && "/comic-" in url) return url.substringAfter("/comic-").substringBefore("/").substringBefore("?")
        val match = Regex("/series/([^/]+)/chapters").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    private suspend fun fetchChapterPagesByScraping(mangaSlug: String, chapterId: String): List<Page> {
        val base = publicBaseUrl()
        val chapterUrl = "$base/capitulo/$chapterId/comic-$mangaSlug"
        val document = client.get(chapterUrl, ensureSuccess = false).asJsoup()
        val imgElements = document.select("section img[src], div.flex.flex-col img[src], div.relative img[src], img[src*=/storage/comics/]")
        val uniqueImageUrls = linkedSetOf<String>()
        imgElements.forEach { img ->
            val src = img.attr("abs:src").trim()
            if (src.isNotBlank() && src.contains("/storage/comics/", ignoreCase = true)) uniqueImageUrls.add(src)
        }
        val images = uniqueImageUrls.mapIndexed { i, src -> Page(i, imageUrl = src) }
        if (images.isEmpty()) throw Exception("No se pudieron extraer las páginas del capítulo")
        return images
    }

    private fun logHttpIssue(stage: String, code: Int, url: String) {
        val host = try {
            url.toHttpUrl().host
        } catch (_: Exception) {
            "unknown"
        }
        Log.w("OlympusScanlation", "HTTP issue stage=$stage code=$code host=$host url=$url")
    }

    private suspend fun fetchPopularMangaByScraping(): MangasPage {
        val base = publicBaseUrl()
        val document = client.get(base, ensureSuccess = false).asJsoup()
        val section = document.selectFirst("section:has(h2:matchesOwn((?i)Popular Del Dia))") ?: document.selectFirst("section:has(h2:matchesOwn((?i)Popular))") ?: throw Exception("No se encontró la sección de populares en la web")
        val mangaList = section.select("figure a[href^=/series/comic-], a[href^=/series/comic-]").mapNotNull { link ->
            val href = link.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = link.selectFirst("figcaption")?.text()?.trim() ?: link.attr("title").trim().ifBlank { link.attr("aria-label").trim() }.ifBlank { link.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val imageElement = link.selectFirst("img[src]") ?: link.closest("figure")?.selectFirst("img[src]") ?: link.parent()?.selectFirst("img[src]")
            val thumbnail = imageElement?.attr("abs:src")?.trim().orEmpty()
            val trackedUrl = buildTrackedMangaUrlForFallback(href, title)
            SManga.create().apply {
                this.title = title
                this.url = trackedUrl
                this.thumbnail_url = thumbnail.ifBlank { null }
            }
        }.distinctBy { it.url }
        if (mangaList.isEmpty()) throw Exception("No se pudieron obtener populares via fallback HTML")
        return MangasPage(mangaList, hasNextPage = false)
    }

    private suspend fun fetchLatestUpdatesByScraping(page: Int): MangasPage {
        val base = publicBaseUrl()
        val updatesUrl = "$base/capitulos".toHttpUrl().newBuilder().apply { if (page > 1) addQueryParameter("page", page.toString()) }.build()
        val document = client.get(updatesUrl, ensureSuccess = false).asJsoup()
        val primaryLinks = document.select("div.grid.md\\:grid-cols-2.gap-4 div.bg-gray-800 a[href^=/series/comic-]")
        val links = if (primaryLinks.isNotEmpty()) primaryLinks else document.select("div.grid a[href^=/series/comic-], a[href^=/series/comic-]")
        val mangaList = links.mapNotNull { link ->
            val href = link.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null
            val title = link.selectFirst("figcaption")?.text()?.trim() ?: link.attr("title").trim().ifBlank { link.attr("aria-label").trim() }.ifBlank { link.closest(".bg-gray-800")?.selectFirst("figcaption")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val imageElement = link.selectFirst("img[src]") ?: link.closest(".bg-gray-800")?.selectFirst("img[src]")
            val thumbnail = imageElement?.attr("abs:src")?.trim().orEmpty()
            val trackedUrl = buildTrackedMangaUrlForFallback(href, title)
            SManga.create().apply {
                this.title = title
                this.url = trackedUrl
                this.thumbnail_url = thumbnail.ifBlank { null }
            }
        }.distinctBy { it.url }
        if (mangaList.isEmpty()) throw Exception("No se pudieron obtener recientes via fallback HTML")
        val maxPageFromLinks = document.select("a[href^=/capitulos?page=]").mapNotNull { anchor -> anchor.attr("href").substringAfter("page=", "").substringBefore("&").toIntOrNull() }.maxOrNull()
        val nextPageFromArrow = document.selectFirst("a[title*=siguiente], a[name*=siguiente], a:has(i.i-heroicons-arrow-right-20-solid)")?.attr("href")?.substringAfter("page=", "")?.substringBefore("&")?.toIntOrNull()
        val hasNextPage = when {
            nextPageFromArrow != null -> nextPageFromArrow > page
            maxPageFromLinks != null -> page < maxPageFromLinks
            else -> mangaList.size >= 10
        }
        return MangasPage(mangaList, hasNextPage = hasNextPage)
    }

    private suspend fun buildTrackedMangaUrlForFallback(href: String, title: String): String {
        val slug = href.substringAfter("/series/comic-").substringBefore("?").substringBefore("/")
        val match = runCatching { fetchMangaDtoBySlug(slug) }.getOrNull() ?: runCatching { apiHelper.resolveMangaByName(title, null, cacheManager) }.getOrNull()
        val id = match?.id
        if (slug.isNotBlank() && id != null) {
            preferences.slugMap = preferences.slugMap + (id to slug)
            cacheManager.updateMangaCache(id.toString(), title, slug)
        }
        return id?.toString() ?: "/series/comic-$slug"
    }

    override suspend fun fetchFilterData(): JsonElement {
        val base = dashboardBaseUrl()
        val response = client.get("$base/api/genres-statuses", ensureSuccess = false)
        val body = response.body.string()
        if (!response.isSuccessful || apiHelper.isErrorPage(response.code, body)) throw Exception("Failed to fetch filters")
        return jsonInstance.parseToJsonElement(body)
    }

    override fun getFilterList(data: JsonElement?): FilterList = filterManager.getFilterList(data)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = FETCH_DOMAIN_PREF_TITLE
            summary = FETCH_DOMAIN_PREF_SUMMARY
            setDefaultValue(FETCH_DOMAIN_PREF_DEFAULT)
        }.also(screen::addPreference)
        // Base URL EditText is provided by generated CustomUrlPreferences (source { baseUrl { custom(...) } })
    }

    companion object {
        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val FETCH_DOMAIN_PREF_DEFAULT = true
        private const val FETCH_DOMAIN_PREF_TITLE = "Buscar dominio automáticamente"
        private const val FETCH_DOMAIN_PREF_SUMMARY = "Intenta buscar el dominio automáticamente al abrir la fuente."
        private const val SLUG_MAP = "slugMap"
        private const val CHAPTER_COUNT_MAP = "chapterCountMap"
        private const val MIN_ACCEPTED_CHAPTER_PERCENT = 70
        private const val TAG = "OlympusScanlation"
        private const val CACHE_DURATION_MS = 60 * 60 * 1000L
        private val CHAPTER_COUNT_TEXT_REGEX = Regex("(\\d+)\\s+cap[ií]tulos?\\s+en\\s+total", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_TEXT_REGEX = Regex("cap[ií]tulo\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    }
}
