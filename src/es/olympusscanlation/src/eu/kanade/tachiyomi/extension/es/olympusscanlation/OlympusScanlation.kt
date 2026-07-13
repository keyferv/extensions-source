package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
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

    override val baseUrl: String get() =
        when {
            isCi -> defaultBaseUrl
            preferences.fetchDomainPref() -> fetchedDomainUrl
            else -> preferences.prefBaseUrl
        }

    private val defaultBaseUrl: String = "https://olympusxyz.com"

    private val fetchedDomainUrl: String by lazy {
        if (!preferences.fetchDomainPref()) return@lazy preferences.prefBaseUrl
        try {
            val initClient = network.client
            val headers = super.headersBuilder().build()
            val document = initClient.newCall(GET("https://olympus.pages.dev", headers)).execute().asJsoup()
            val domain =
                document.selectFirst("meta[property=og:url]")?.attr("content")
                    ?: return@lazy preferences.prefBaseUrl
            val host =
                initClient
                    .newCall(GET(domain, headers))
                    .execute()
                    .request.url.host
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

    private val preferences: SharedPreferences = getPreferences()
    private val cacheManager = MangaCacheManager(preferences)
    private val apiHelper by lazy { ApiHelper(client, headersMap, dashboardApiBaseUrl) }
    private val filterManager by lazy { FilterManager(preferences, client) }

    private val headersMap: Map<String, String>
        get() {
            val map = mutableMapOf<String, String>()
            for (i in 0 until headers.size) {
                map[headers.name(i)] = headers.value(i)
            }
            return map
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
    }

    private val json: Json by injectLazy()

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
        // HTML-first strategy: directly use base site scraping
        return GET(baseUrl, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        // HTML-first strategy: directly use existing scraping helper
        return fetchPopularMangaByScraping()
    }

    override fun latestUpdatesRequest(page: Int): Request {
        // HTML-first strategy: use /capitulos scraping path
        val updatesUrl =
            "$baseUrl/capitulos"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }.build()
        return GET(updatesUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        // HTML-first strategy: directly use existing scraping helper
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return fetchLatestUpdatesByScraping(page)
    }

    private fun fetchPopularMangaByScraping(): MangasPage {
        // El fallback de scraping siempre debe usar el sitio web (no dashboard API)
        val document = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
        val section =
            document.selectFirst("section:has(h2:matchesOwn((?i)Popular Del Dia))")
                ?: document.selectFirst("section:has(h2:matchesOwn((?i)Popular))")
                ?: throw Exception("No se encontró la sección de populares en la web")

        val mangaList =
            section.select("figure a[href^=/series/comic-], a[href^=/series/comic-]")
                .mapNotNull { link ->
                    val href = link.attr("href").trim()
                    if (href.isBlank()) return@mapNotNull null

                    val title =
                        link.selectFirst("figcaption")?.text()?.trim()
                            ?: link.attr("title").trim()
                                .ifBlank { link.attr("aria-label").trim() }
                                .ifBlank { link.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
                    if (title.isBlank()) return@mapNotNull null

                    val imageElement =
                        link.selectFirst("img[src]")
                            ?: link.closest("figure")?.selectFirst("img[src]")
                            ?: link.parent()?.selectFirst("img[src]")

                    val thumbnail = imageElement?.attr("abs:src")?.trim().orEmpty()
                    val trackedUrl = buildTrackedMangaUrlForFallback(href, title)

                    SManga.create().apply {
                        this.title = title
                        this.url = trackedUrl
                        this.thumbnail_url = thumbnail.ifBlank { null }
                    }
                }.distinctBy { it.url }

        if (mangaList.isEmpty()) {
            throw Exception("No se pudieron obtener populares via fallback HTML")
        }
        return MangasPage(mangaList, hasNextPage = false)
    }

    private fun fetchLatestUpdatesByScraping(page: Int): MangasPage {
        val updatesUrl =
            "$baseUrl/capitulos"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (page > 1) addQueryParameter("page", page.toString())
                }.build()

        val document = client.newCall(GET(updatesUrl, headers)).execute().asJsoup()

        val primaryLinks = document.select("div.grid.md\\:grid-cols-2.gap-4 div.bg-gray-800 a[href^=/series/comic-]")
        val links = if (primaryLinks.isNotEmpty()) primaryLinks else document.select("div.grid a[href^=/series/comic-], a[href^=/series/comic-]")

        val mangaList =
            links
                .mapNotNull { link ->
                    val href = link.attr("href").trim()
                    if (href.isBlank()) return@mapNotNull null

                    val title =
                        link.selectFirst("figcaption")?.text()?.trim()
                            ?: link.attr("title").trim()
                                .ifBlank { link.attr("aria-label").trim() }
                                .ifBlank { link.closest(".bg-gray-800")?.selectFirst("figcaption")?.text()?.trim().orEmpty() }
                    if (title.isBlank()) return@mapNotNull null

                    val imageElement =
                        link.selectFirst("img[src]")
                            ?: link.closest(".bg-gray-800")?.selectFirst("img[src]")
                    val thumbnail = imageElement?.attr("abs:src")?.trim().orEmpty()
                    val trackedUrl = buildTrackedMangaUrlForFallback(href, title)

                    SManga.create().apply {
                        this.title = title
                        this.url = trackedUrl
                        this.thumbnail_url = thumbnail.ifBlank { null }
                    }
                }.distinctBy { it.url }

        if (mangaList.isEmpty()) {
            throw Exception("No se pudieron obtener recientes via fallback HTML")
        }

        val maxPageFromLinks =
            document.select("a[href^=/capitulos?page=]")
                .mapNotNull { anchor ->
                    anchor.attr("href")
                        .substringAfter("page=", "")
                        .substringBefore("&")
                        .toIntOrNull()
                }.maxOrNull()

        val nextPageFromArrow =
            document.selectFirst(
                "a[title*=siguiente], a[name*=siguiente], a:has(i.i-heroicons-arrow-right-20-solid)",
            )?.attr("href")
                ?.substringAfter("page=", "")
                ?.substringBefore("&")
                ?.toIntOrNull()

        val hasNextPage =
            when {
                nextPageFromArrow != null -> nextPageFromArrow > page
                maxPageFromLinks != null -> page < maxPageFromLinks
                else -> mangaList.size >= 10
            }

        return MangasPage(mangaList, hasNextPage = hasNextPage)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        if (query.isNotEmpty()) {
            if (query.length < 3) {
                throw Exception("La búsqueda debe tener al menos 3 caracteres")
            }
            // Use validated public API endpoint
            val apiUrl =
                "$publicApiBaseUrl/api/series"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("name", query.substring(0, min(query.length, 40)))
                    .addQueryParameter("type", "comic")
                    .build()
            return GET(apiUrl, headers)
        }

        val url = "$publicApiBaseUrl/api/series".toHttpUrl().newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.state?.ascending == true) {
                        url.addQueryParameter("direction", "desc")
                    } else {
                        url.addQueryParameter("direction", "asc")
                    }
                }
                is GenreFilter -> {
                    if (filter.toUriPart() != 9999) {
                        url.addQueryParameter("genres", filter.toUriPart().toString())
                    }
                }
                is StatusFilter -> {
                    if (filter.toUriPart() != 9999) {
                        url.addQueryParameter("status", filter.toUriPart().toString())
                    }
                }
                else -> {}
            }
        }
        url.addQueryParameter("type", "comic")
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        if (response.code == 401) {
            logHttpIssue("searchMangaParse#401", response)
            throw Exception("Error en la búsqueda: sesión no autorizada (401)")
        }
        if (apiHelper.isErrorPage(response.code, body)) {
            logHttpIssue("searchMangaParse", response)
            throw Exception("Error en la búsqueda: respuesta HTML inesperada")
        }

        // Manejar el resultado de la búsqueda por ID personalizado
        val searchId = response.request.tag(String::class.java)?.let { tag ->
            if (tag.startsWith("id_search:")) tag.substringAfter("id_search:") else null
        }

        if (searchId != null) {
            val seriesList = json.decodeMangaListPayload(body)
            val match = seriesList.firstOrNull { it.id?.toString() == searchId }
            return if (match != null) {
                cacheManager.updateMangaCache(match)
                MangasPage(listOf(match.toSManga(resolveStableId(match.slug, match.name, match.id?.toString()))), false)
            } else {
                MangasPage(emptyList(), false)
            }
        }
        if (response.request.url
                .toString()
                .startsWith("$publicApiBaseUrl/api/series")
        ) {
            val mangaList =
                json.decodeMangaListPayload(body).filter { it.type == "comic" }.map { dto ->
                    cacheManager.updateMangaCache(dto)
                    dto.toSManga(resolveStableId(dto.slug, dto.name, dto.id?.toString()))
                }
            return MangasPage(mangaList, hasNextPage = false)
        }

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

        // La API a veces devuelve la lista de mangas directamente en data, o dentro de data.series
        val seriesData = result.data.series ?: SeriesDto(
            current_page = result.data.current_page ?: 1,
            data = result.data.data ?: emptyList(),
            last_page = result.data.last_page ?: 1,
        )

    override fun mangaDetailsRequest(manga: SManga): Request {
        val mangaId = parseMangaId(manga.url)
        val slug = preferences.slugMap[mangaId] ?: throw Exception("Slug not found for manga $mangaId")

        val apiUrl = "$baseUrl/api/series/$slug?type=comic"
        return GET(url = apiUrl, headers = headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        val taggedManga = response.request.tag(MangaRefTag::class.java)?.manga
        val taggedId = taggedManga?.let { UrlUtils.mangaIdFromUrl(it.url) }
        // Si la API devuelve 401, intentar scraping
        if (response.code == 401) {
            logHttpIssue("mangaDetailsParse#401", response)
            val slug = response.request.url.toString()
                .substringAfter("/series/comic-")
                .substringBefore("/chapters")
                .substringBefore("?")
            return fetchMangaDetailsByScraping(
                slug = slug,
                preferredMangaId = taggedId,
                preferredTitle = taggedManga?.title,
            )
        }
        if (apiHelper.isErrorPage(response.code, body)) {
            logHttpIssue("mangaDetailsParse#errorPage", response)
            val title = response.request.tag(MangaTitleTag::class.java)?.title
            val currentId = taggedId
            val match = title?.let { apiHelper.resolveMangaByName(it, currentId, cacheManager) }
            if (match != null) {
                cacheManager.updateMangaCache(match)
                val details = fetchMangaDetailsBySlug(match.slug)
                updateTaggedMangaUrl(response, match.slug)
                return details
            }
            // Intentar scraping como último recurso
            val slugFromUrl = response.request.url.toString()
                .substringAfter("/series/comic-")
                .substringBefore("/chapters")
                .substringBefore("?")
            return fetchMangaDetailsByScraping(
                slug = slugFromUrl,
                preferredMangaId = currentId,
                preferredTitle = title,
            )
        }

        val slug =
            response.request.url
                .toString()
                .substringAfter("/series/comic-")
                .substringBefore("/chapters")
                .substringBefore("?")
        val details = fetchMangaDetailsBySlug(slug)
        updateTaggedMangaUrl(response, slug)
        return details
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

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable
        .fromCallable {
            resolveAndUpdateMangaSlug(manga)
        }.flatMap {
            client
                .newCall(chapterListRequest(manga))
                .asObservable()
                .map { response -> chapterListParse(response) }
        }

    private fun paginatedChapterListRequest(
        mangaUrl: String,
        page: Int,
    ): Request = GET(
        url = "$dashboardApiBaseUrl/api/series/$mangaUrl/chapters?page=$page&direction=desc&type=comic",
        headers = headers,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val taggedManga = response.request.tag(MangaRefTag::class.java)?.manga
        val taggedMangaId = taggedManga?.let { UrlUtils.mangaIdFromUrl(it.url) }
        // Si la API devuelve 401, intentar scraping fallback
        if (response.code == 401) {
            logHttpIssue("chapterListParse#401", response)
            val slug = response.request.url.toString().substringAfter("/series/").substringBefore("/chapters")
            return fetchChapterListByScraping(slug, taggedMangaId)
        }
        if (apiHelper.isErrorPage(response.code, body)) {
            logHttpIssue("chapterListParse#errorPage", response)
            val title = response.request.tag(MangaTitleTag::class.java)?.title
            val currentId = taggedMangaId
            val match =
                currentId?.let { apiHelper.resolveMangaById(it, cacheManager) }
                    ?: title?.let { apiHelper.resolveMangaByName(it, currentId, cacheManager) }
            if (match != null) {
                cacheManager.updateMangaCache(match)
                updateTaggedMangaUrl(response, match.slug)
                return fetchChapterListBySlug(match.slug, match.id?.toString() ?: currentId)
            }
            // Intentar scraping como último recurso
            val slugFromUrl = response.request.url.toString().substringAfter("/series/").substringBefore("/chapters")
            return fetchChapterListByScraping(slugFromUrl, currentId)
        }

        val slug =
            response.request.url
                .toString()
                .substringAfter("/series/")
                .substringBefore("/chapters")
        val data = json.decodeFromString<PayloadChapterDto>(body)
        var resultSize = data.data.size
        var page = 2
        while (data.meta.total > resultSize) {
            val newRequest = paginatedChapterListRequest(slug, page)
            val newResponse = client.newCall(newRequest).execute()
            val newBody = newResponse.body.string()
            if (apiHelper.isErrorPage(newResponse.code, newBody)) {
                throw Exception("Error al obtener página $page de capítulos")
            }
            val newData = json.decodeFromString<PayloadChapterDto>(newBody)
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

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable
        .fromCallable {
            val slugFromUrl = UrlUtils.chapterSlugFromUrl(chapter.url)
            val mangaId = UrlUtils.chapterMangaIdFromUrl(chapter.url) ?: cacheManager.getMangaIdBySlug(slugFromUrl)
            val resolvedSlug =
                if (mangaId != null) {
                    apiHelper.resolveSlugById(mangaId, cacheManager.getMangaTitleById(mangaId), cacheManager) ?: slugFromUrl
                } else {
                    slugFromUrl
                }
            if (resolvedSlug != slugFromUrl && resolvedSlug.isNotBlank()) {
                chapter.url = chapter.url.replace(Regex("comic-[^/?]+"), "comic-$resolvedSlug")
                if (mangaId != null) {
                    cacheManager.updateMangaCache(mangaId, cacheManager.getMangaTitleById(mangaId), resolvedSlug)
                }
            }
        }.flatMap {
            client
                .newCall(pageListRequest(chapter))
                .asObservable()
                .map { response -> pageListParse(response) }
        }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        // Si la API devuelve 401 (Unauthorized), hacer scraping del HTML
        if (response.code == 401 || apiHelper.isErrorPage(response.code, body)) {
            logHttpIssue("pageListParse", response)
            // Intentar scraping del capítulo via HTML
            val chapterId = getChapterIdFromUrl(response.request.url.toString())
            val mangaSlug = getMangaSlugFromUrl(response.request.url.toString())
            if (chapterId != null && mangaSlug != null) {
                return fetchChapterPagesByScraping(mangaSlug, chapterId)
            }
            throw Exception("Error al cargar páginas del capítulo: API retornó 401")
        }
        return json.decodeFromString<PayloadPagesDto>(body).chapter.pages.mapIndexed { i, img ->
            Page(i, imageUrl = img)
        }
    }

    private fun getChapterIdFromUrl(url: String): String? = url.substringAfter("/chapters/").substringBefore("?").substringBefore("/")

    private fun getMangaSlugFromUrl(url: String): String? {
        val match = Regex("/series/([^/]+)/chapters").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    private fun fetchChapterPagesByScraping(mangaSlug: String, chapterId: String): List<Page> {
        val chapterUrl = "$baseUrl/capitulo/$chapterId/comic-$mangaSlug"
        val document = client.newCall(GET(chapterUrl, headers)).execute().asJsoup()

        // Nuevo layout: múltiples contenedores con <img src="...storage/comics/..."> dentro de section principal.
        val imgElements =
            document.select(
                "section img[src], div.flex.flex-col img[src], div.relative img[src], img[src*=/storage/comics/]",
            )

        val uniqueImageUrls = linkedSetOf<String>()
        imgElements.forEach { img ->
            val src = img.attr("abs:src").trim()
            if (src.isNotBlank() && src.contains("/storage/comics/", ignoreCase = true)) {
                uniqueImageUrls.add(src)
            }
        }

        val images = uniqueImageUrls.mapIndexed { i, src -> Page(i, imageUrl = src) }
        if (images.isEmpty()) {
            throw Exception("No se pudieron extraer las páginas del capítulo")
        }
        return images
    }

    private fun logHttpIssue(
        stage: String,
        response: Response,
    ) {
        val requestUrl = response.request.url.toString()
        val host = response.request.url.host
        val code = response.code
        Log.w("OlympusScanlation", "HTTP issue stage=$stage code=$code host=$host url=$requestUrl")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = filterManager.getFilterList(headersMap, dashboardApiBaseUrl)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        filterManager.setupPreferenceScreen(screen, defaultBaseUrl)
    }

    private var cachedBaseUrl: String? = null
    private var SharedPreferences.prefBaseUrl: String
        get() {
            if (cachedBaseUrl == null) {
                cachedBaseUrl = getString("overrideBaseUrl", defaultBaseUrl)!!
            }
            return cachedBaseUrl!!
        }
        set(value) {
            cachedBaseUrl = value
            edit().putString("overrideBaseUrl", value).apply()
        }

    private fun SharedPreferences.fetchDomainPref() = getBoolean("fetchDomain", true)

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
    }

        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val FETCH_DOMAIN_PREF_DEFAULT = true

        private const val SLUG_MAP = "slugMap"

        private const val TAG = "OlympusScanlation"

        private const val CACHE_DURATION_MS = 60 * 60 * 1000L // 1 hour
    }
}
