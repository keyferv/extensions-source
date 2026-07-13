package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

class OlympusScanlation :
    HttpSource(),
    ConfigurableSource {
    private val fetchedDomainUrlHost by lazy { fetchedDomainUrl.toHttpUrl().host }
    private val apiBaseUrlHost by lazy { apiBaseUrl.toHttpUrl().host }

    override val versionId = 5
    private val isCi = System.getenv("CI") == "true"

    override val baseUrl: String get() = when {
        isCi -> defaultBaseUrl
        else -> preferences.prefBaseUrl
    }

    private val defaultBaseUrl: String = "https://olympusxyz.com"

    private val fetchedDomainUrl: String by lazy {
        if (!preferences.fetchDomainPref()) return@lazy preferences.prefBaseUrl
        try {
            val initClient = network.client
            val headers = super.headersBuilder().build()
            val document = initClient.newCall(GET("https://olympus.pages.dev", headers)).execute().asJsoup()
            val domain = document.selectFirst("meta[property=og:url]")?.attr("content")
                ?: return@lazy preferences.prefBaseUrl
            val host = initClient.newCall(GET(domain, headers)).execute().request.url.host
            val newDomain = "https://$host"
            preferences.prefBaseUrl = newDomain
            newDomain
        } catch (_: Exception) {
            preferences.prefBaseUrl
        }
    }

    private val apiBaseUrl by lazy {
        fetchedDomainUrl.replace("https://", "https://panel.")
    }

    override val lang: String = "es"
    override val name: String = "Olympus Scanlation"

    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences = getPreferences {
        this.getString(DEFAULT_BASE_URL_PREF, null).let { domain ->
            if (domain != defaultBaseUrl) {
                this.edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    override val client by lazy {
        val logger = Interceptor { chain ->
            val request = chain.request()
            val startTime = System.currentTimeMillis()
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "${response.code} ${request.method} ${request.url} (${duration}ms)")
            response
        }

        val client = network.client.newBuilder()
            .addNetworkInterceptor(logger)
            .rateLimit(1, 2.seconds) { it.host == fetchedDomainUrlHost }
            .rateLimit(2, 1.seconds) { it.host == apiBaseUrlHost }
            .build()

        return@lazy client
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var seriesList: List<MangaDto> = emptyList()

    @Volatile
    private var lastFetchTime: Long = 0L

    @Volatile
    private var chapterNameToIdCache: Map<String, Int> = emptyMap()

    @Synchronized
    private fun fetchSeriesList() {
        val now = System.currentTimeMillis()

        if (seriesList.isNotEmpty() && (now - lastFetchTime) < CACHE_DURATION_MS) {
            return
        }

        val comics = try {
            fetchSeriesListPaginated()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh paginated series list, falling back to homepage slugs", e)
            // Paginated endpoint failed — fall back to homepage for slug updates only.
            // Don't overwrite seriesList so search still works with previously cached data.
            try {
                val homepageSlugs = fetchHomepageSlugs()
                if (homepageSlugs.isNotEmpty()) {
                    preferences.slugMap += homepageSlugs
                }
            } catch (homepageError: Exception) {
                Log.w(TAG, "Failed to refresh homepage slugs", homepageError)
                // Both endpoints down, keep existing data intact
            }
            return
        }

        seriesList = comics
        lastFetchTime = now

        val newSlugMap = comics.associate { it.id to it.slug }

        preferences.slugMap += newSlugMap + fetchHomepageSlugs()
    }

    /** Fetch all comics by paginating through /api/series?page=N */
    private fun fetchSeriesListPaginated(): List<MangaDto> {
        var page = 1
        val allComics = mutableListOf<MangaDto>()

        while (true) {
            val response = client.newCall(GET("$baseUrl/api/series?page=$page", headers)).execute()
            if (!response.isSuccessful) {
                throw Exception("Failed to fetch paginated series page $page: HTTP ${response.code}")
            }
            val payload = response.parseAs<PayloadSeriesDto>()
            val seriesPage = payload.data.series

            allComics += seriesPage.data.filter { it.type == "comic" }

            if (!seriesPage.hasNextPage()) break
            page++
        }

        return allComics
    }

    private fun fetchHomepageSlugs(): Map<Int, String> = try {
        val homepage = client.newCall(GET("$baseUrl/api/homepage", headers)).execute()
            .parseAs<HomepageDto>()

        val slugs = mutableMapOf<Int, String>()

        homepage.data.newChapters
            ?.filter { it.type == "comic" }
            ?.forEach { slugs[it.id] = it.slug }

        homepage.rankings
            ?.filter { it.type == "comic" }
            ?.forEach { slugs[it.id] = it.slug }

        slugs
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse homepage slugs", e)
        emptyMap()
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        fetchSeriesList()
        return super.fetchPopularManga(page)
    }

    override fun popularMangaRequest(page: Int): Request {
        val apiUrl = "$baseUrl/api/rankings?page=$page&period=total_ranking".toHttpUrl().newBuilder()
            .build()
        return GET(apiUrl, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<RankingDto>()
        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = result.data
            .filter { it.type == "comic" }
            .map {
                slugMap[it.id] = it.slug
                it.toSManga()
            }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, hasNextPage = result.hasNextPage())
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        fetchSeriesList()
        return super.fetchLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val apiUrl = "$baseUrl/api/new-chapters?page=$page".toHttpUrl().newBuilder()
            .build()
        return GET(apiUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<NewChaptersDto>()
        val slugMap = preferences.slugMap.toMutableMap()
        val mangaList = result.data.filter { it.type == "comic" }
            .map {
                slugMap[it.id] = it.slug
                it.toSManga()
            }
        preferences.slugMap = slugMap
        return MangasPage(mangaList, result.hasNextPage())
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        fetchSeriesList()
        return Observable.just(parseSearchManga(page, query))
    }

    private fun parseSearchManga(page: Int, query: String): MangasPage {
        val filteredList = seriesList.filter { it.name.contains(query, ignoreCase = true) }
        val paginatedList = filteredList.drop((page - 1) * 20).take(20)
        val hasNextPage = page * 20 < filteredList.size
        return MangasPage(paginatedList.map { it.toSManga() }, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

    private fun parseMangaId(url: String): Int {
        // Handles: "123", "/series/comic-slug?mangaId=123", "123/456", and legacy chapter URLs.
        val idFromParam = url.substringAfter("mangaId=", "")
            .substringBefore("&")
            .takeIf { it.isNotEmpty() }
        val rawId = idFromParam ?: url.substringBefore("/").substringBefore("?")
        return rawId.trim().toIntOrNull()
            ?: throw IllegalArgumentException("Unable to parse Olympus manga ID from URL: $url")
    }

    private fun normalizedMangaId(url: String): String = parseMangaId(url).toString()

    private fun parseChapterIds(url: String): Pair<String, String> {
        val mangaId = normalizedMangaId(url)
        val chapterId = if (url.contains("/capitulo/")) {
            url.substringAfter("/capitulo/").substringBefore("/").substringBefore("?")
        } else {
            url.substringAfter("/", "").substringBefore("?")
        }.normalizeChapterIdentifier()

        if (chapterId.isEmpty()) {
            throw IllegalArgumentException("Unable to parse Olympus chapter ID from URL: $url")
        }

        return mangaId to chapterId
    }

    private fun String.normalizeChapterIdentifier(): String = trim()
        .removePrefix("Capitulo")
        .removePrefix("Capítulo")
        .removePrefix("capitulo")
        .removePrefix("capítulo")
        .trim()

    override fun getMangaUrl(manga: SManga): String {
        val mangaId = parseMangaId(manga.url)
        val slug = preferences.slugMap[mangaId] ?: throw Exception("Slug not found for manga $mangaId")
        return "$baseUrl/series/comic-$slug"
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        fetchSeriesList()
        return super.fetchMangaDetails(manga)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = parseMangaId(manga.url)
        val slug = preferences.slugMap[mangaId] ?: throw Exception("Slug not found for manga $mangaId")

        val apiUrl = "$baseUrl/api/series/$slug?type=comic"
        return GET(url = apiUrl, headers = headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<MangaDetailDto>()
        return result.data.toSMangaDetails()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (mangaId, chapterIdentifier) = parseChapterIds(chapter.url)
        val parsedId = parseMangaId(mangaId)
        val mangaSlug = preferences.slugMap[parsedId] ?: throw Exception("Slug not found for manga $parsedId")
        val backendChapterId = resolveChapterId(mangaId, chapterIdentifier, mangaSlug)
        return "$baseUrl/capitulo/$backendChapterId/comic-$mangaSlug"
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        fetchSeriesList()
        return super.fetchChapterList(manga)
            .onErrorReturn { error ->
                Log.w(TAG, "Failed to fetch API chapters for manga ${manga.url}, falling back to HTML", error)
                fetchChapterListFromHtml(manga)
            }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = normalizedMangaId(manga.url)
        val parsedId = parseMangaId(mangaId)
        val mangaSlug = preferences.slugMap[parsedId] ?: throw Exception("Slug not found for manga $parsedId")

        return paginatedChapterListRequest(mangaSlug, mangaId, 1)
    }

    private fun paginatedChapterListRequest(mangaSlug: String, mangaId: String, page: Int): Request = GET(
        url = "$apiBaseUrl/api/series/$mangaSlug/chapters?page=$page&direction=desc&type=comic#$mangaId",
        headers = headers,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.fragment ?: ""
        val slug = response.request.url.toString()
            .substringAfter("/series/")
            .substringBefore("/chapters")

        val data = response.parseAs<PayloadChapterDto>()
        var resultSize = data.data.size
        var page = 2
        while (data.meta.total > resultSize) {
            val newRequest = paginatedChapterListRequest(slug, mangaId, page)
            val newResponse = client.newCall(newRequest).execute()
            val newData = newResponse.parseAs<PayloadChapterDto>()
            data.data += newData.data
            resultSize += newData.data.size
            page += 1
        }

        synchronized(this) {
            val cacheUpdates = mutableMapOf<String, Int>()
            data.data.forEach { dto ->
                cacheUpdates["$mangaId/${dto.name}"] = dto.id
            }
            chapterNameToIdCache = chapterNameToIdCache + cacheUpdates
        }

        return data.data.map { it.toSChapter(mangaId, dateFormat) }
    }

    private fun fetchChapterListFromHtml(manga: SManga): List<SChapter> {
        val mangaId = normalizedMangaId(manga.url)
        val slug = preferences.slugMap[parseMangaId(mangaId)]
            ?: throw Exception("Slug no encontrado para el manga $mangaId")
        val pageUrl = "$baseUrl/series/comic-$slug"

        val document = client.newCall(GET(pageUrl, headers)).execute().asJsoup()

        return document.select("a[href*=/capitulo/]").mapNotNull { element ->
            val href = element.attr("href")
            val chapterId = href.substringAfter("/capitulo/").substringBefore("/")
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val chapterNameEl = element.selectFirst(".chapter-name")
            val chapterNumber = chapterNameEl?.text()?.trim()
                ?.split("\\s+".toRegex())?.lastOrNull()
                ?: chapterId

            val timeEl = element.selectFirst("time[datetime]")
            val dateStr = timeEl?.attr("datetime") ?: ""

            val backendId = chapterId.toIntOrNull()

            SChapter.create().apply {
                name = "Capitulo $chapterNumber"
                url = "$mangaId/$chapterNumber"
                chapter_number = chapterNumber.toFloatOrNull() ?: -1f
                date_upload = try {
                    dateFormat.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }.also {
                if (backendId != null) {
                    synchronized(this) {
                        chapterNameToIdCache = chapterNameToIdCache + mapOf("$mangaId/$chapterNumber" to backendId)
                    }
                }
            }
        }
    }

    private fun resolveChapterId(mangaId: String, chapterIdentifier: String, mangaSlug: String): String {
        val normalizedChapterIdentifier = chapterIdentifier.normalizeChapterIdentifier()
        val cacheKey = "$mangaId/$normalizedChapterIdentifier"

        chapterNameToIdCache[cacheKey]?.let { return it.toString() }

        val parsedMangaId = parseMangaId(mangaId)
        try {
            val page1Request = paginatedChapterListRequest(mangaSlug, mangaId, 1)
            val firstResponse = client.newCall(page1Request).execute()
            val firstPage = firstResponse.parseAs<PayloadChapterDto>()
            val allChapters = mutableListOf<ChapterDto>()
            allChapters += firstPage.data

            var resultSize = firstPage.data.size
            var page = 2
            while (firstPage.meta.total > resultSize) {
                val newRequest = paginatedChapterListRequest(mangaSlug, mangaId, page)
                val newResponse = client.newCall(newRequest).execute()
                val newData = newResponse.parseAs<PayloadChapterDto>()
                allChapters += newData.data
                resultSize += newData.data.size
                page += 1
            }

            synchronized(this) {
                val cacheUpdates = mutableMapOf<String, Int>()
                allChapters.forEach { dto ->
                    cacheUpdates["$mangaId/${dto.name}"] = dto.id
                }
                chapterNameToIdCache = chapterNameToIdCache + cacheUpdates
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve chapter ID via chapter list for manga $parsedMangaId", e)
        }

        chapterNameToIdCache[cacheKey]?.let { return it.toString() }

        normalizedChapterIdentifier.toIntOrNull()?.let { return normalizedChapterIdentifier }

        throw Exception("Unable to resolve chapter ID for $chapterIdentifier in manga $mangaId")
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (mangaId, chapterIdentifier) = parseChapterIds(chapter.url)
        val parsedId = parseMangaId(mangaId)
        val mangaSlug = preferences.slugMap[parsedId] ?: throw Exception("Slug not found for manga $parsedId")
        val backendChapterId = resolveChapterId(mangaId, chapterIdentifier, mangaSlug)

        return GET("$baseUrl/api/capitulo/comic-$mangaSlug/$backendChapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> = response.parseAs<PayloadPagesDto>().chapter.pages.mapIndexed { i, img ->
        Page(i, imageUrl = img)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = FETCH_DOMAIN_PREF
            title = "Buscar dominio automáticamente"
            summary = "Intenta buscar el dominio automáticamente al abrir la fuente."
            setDefaultValue(FETCH_DOMAIN_PREF_DEFAULT)
        }.also { screen.addPreference(it) }

        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Editar URL de la fuente"
            summary = "Para uso temporal, si la extensión se actualiza se perderá el cambio."
            dialogTitle = "Editar URL de la fuente"
            dialogMessage = "URL por defecto:\n$defaultBaseUrl"
            setDefaultValue(defaultBaseUrl)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Reinicie la aplicación para aplicar los cambios", Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    private var cachedBaseUrl: String? = null
    private var SharedPreferences.prefBaseUrl: String
        get() {
            if (cachedBaseUrl == null) {
                cachedBaseUrl = getString(BASE_URL_PREF, defaultBaseUrl)!!
            }
            return cachedBaseUrl!!
        }
        set(value) {
            cachedBaseUrl = value
            edit().putString(BASE_URL_PREF, value).apply()
        }

    private fun SharedPreferences.fetchDomainPref() = getBoolean(FETCH_DOMAIN_PREF, FETCH_DOMAIN_PREF_DEFAULT)

    private var slugMapCache: Map<Int, String>? = null
    private var SharedPreferences.slugMap: Map<Int, String>
        get() {
            slugMapCache?.let { return it }
            val json = getString(SLUG_MAP, "{}")!!
            slugMapCache = try {
                json.parseAs<Map<Int, String>>()
            } catch (_: SerializationException) {
                emptyMap()
            }
            return slugMapCache!!
        }
        set(map) {
            slugMapCache = map
            edit().putString(SLUG_MAP, map.toJsonString()).apply()
        }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"

        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val FETCH_DOMAIN_PREF_DEFAULT = true

        private const val SLUG_MAP = "slugMap"

        private const val TAG = "OlympusScanlation"

        private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour
    }
}
