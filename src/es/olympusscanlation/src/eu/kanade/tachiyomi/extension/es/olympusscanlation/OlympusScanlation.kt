package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

class OlympusScanlation :
    HttpSource(),
    ConfigurableSource {
    override val versionId = 5
    private val isCi = System.getenv("CI") == "true"

    override val baseUrl: String get() =
        when {
            isCi -> defaultBaseUrl
            preferences.fetchDomainPref() -> fetchedDomainUrl
            else -> preferences.prefBaseUrl
        }

    private val defaultBaseUrl: String = "https://olympusbiblioteca.com"

    private val fetchedDomainUrl: String by lazy {
        if (!preferences.fetchDomainPref()) return@lazy preferences.prefBaseUrl
        try {
            val initClient = network.cloudflareClient
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

    private val publicApiBaseUrl by lazy {
        fetchedDomainUrl
    }

    private val dashboardApiBaseUrl by lazy {
        fetchedDomainUrl.replace("https://", "https://dashboard.")
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
        network.cloudflareClient
            .newBuilder()
            .rateLimitHost(fetchedDomainUrl.toHttpUrl(), 1, 2)
            .rateLimitHost(dashboardApiBaseUrl.toHttpUrl(), 2, 1)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)

                // Only log for dashboard requests
                if (request.url.host.contains("dashboard")) {
                    Log.d("OlympusScanlation", "[OlympusDebug] Request: ${request.method} ${request.url}")
                    Log.d("OlympusScanlation", "[OlympusDebug] Headers: ${request.headers}")
                    Log.d("OlympusScanlation", "[OlympusDebug] Response: ${response.code} ${response.peekBody(1024).string()}")
                }

                response
            }
            .build()
    }

    private val json: Json by injectLazy()

    private val dateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
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

        val result = json.decodeFromString<PayloadSeriesDto>(body)

        // La API a veces devuelve la lista de mangas directamente en data, o dentro de data.series
        val seriesData = result.data.series ?: SeriesDto(
            current_page = result.data.current_page ?: 1,
            data = result.data.data ?: emptyList(),
            last_page = result.data.last_page ?: 1,
        )

        val mangaList =
            seriesData.data.map { dto ->
                cacheManager.updateMangaCache(dto)
                dto.toSManga(resolveStableId(dto.slug, dto.name, dto.id?.toString()))
            }
        val hasNextPage = seriesData.current_page < seriesData.last_page
        return MangasPage(mangaList, hasNextPage)
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

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = UrlUtils.buildMangaDetailsUrl(manga, baseUrl, cacheManager, apiHelper)
        return GET(url, headers)
            .newBuilder()
            .tag(MangaTitleTag::class.java, MangaTitleTag(manga.title))
            .tag(MangaRefTag::class.java, MangaRefTag(manga))
            .build()
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable
        .fromCallable {
            resolveAndUpdateMangaSlug(manga)
        }.flatMap {
            client
                .newCall(mangaDetailsRequest(manga))
                .asObservable()
                .map { response -> mangaDetailsParse(response) }
        }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun getMangaUrl(manga: SManga): String = UrlUtils.buildMangaDetailsUrl(manga, baseUrl, cacheManager, apiHelper)

    override fun chapterListRequest(manga: SManga): Request {
        val slug = apiHelper.resolveSlugForManga(manga, cacheManager) ?: throw Exception("No se pudo resolver el slug actualizado")
        return paginatedChapterListRequest(slug, 1)
            .newBuilder()
            .tag(MangaTitleTag::class.java, MangaTitleTag(manga.title))
            .tag(MangaRefTag::class.java, MangaRefTag(manga))
            .build()
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
        val mangaId =
            response.request
                .tag(MangaRefTag::class.java)
                ?.manga
                ?.let { UrlUtils.mangaIdFromUrl(it.url) }
        return data.data.map { it.toSChapter(slug, dateFormat, mangaId) }
    }

    private fun fetchMangaDetailsBySlug(slug: String): SManga {
        // Use public API endpoint instead of dashboard for manga details
        val apiUrl = "$publicApiBaseUrl/api/series/$slug?type=comic"
        val newRequest = GET(url = apiUrl, headers = headers)
        val newResponse = client.newCall(newRequest).execute()
        // Si la API devuelve 401, intentar scraping
        if (newResponse.code == 401) {
            return fetchMangaDetailsByScraping(slug)
        }
        val body = newResponse.body.string()
        if (apiHelper.isErrorPage(newResponse.code, body)) {
            return fetchMangaDetailsByScraping(slug)
        }
        val result = json.decodeMangaDetailPayload(body)
        cacheManager.updateMangaCache(result)
        return result.toSMangaDetails(resolveStableId(result.slug, result.name, result.id?.toString()))
    }

    private fun fetchMangaDetailsByScraping(
        slug: String,
        preferredMangaId: String? = null,
        preferredTitle: String? = null,
    ): SManga {
        val seriesUrl = "$baseUrl/series/comic-$slug"
        val document = client.newCall(GET(seriesUrl, headers)).execute().asJsoup()
        val extractedTitle = document.selectFirst("h1.title, .series-title, .manga-title")?.text()?.trim().orEmpty()
        val titleForLookup = extractedTitle.ifBlank { preferredTitle.orEmpty() }
        val resolvedId =
            preferredMangaId
                ?: resolveFallbackMangaId(slug = slug, title = titleForLookup)

        return SManga.create().apply {
            // Extraer título
            title = extractedTitle.ifBlank { preferredTitle ?: slug }

            // Extraer descripción
            description = document.selectFirst(".description, .summary, .synopsis")?.text()

            // Extraer portada
            val coverImg = document.selectFirst(".cover img[src], .series-cover img[src], .thumbnail img[src]")
            thumbnail_url = coverImg?.attr("abs:src")

            // Extraer géneros
            val genres = document.select(".genre, .genres .tag, .categories .category").map { it.text() }
            genre = genres.joinToString(", ")

            // Extraer estado
            val statusText = document.selectFirst(".status, .series-status")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("ongoing") || statusText.contains("publicando") -> SManga.ONGOING
                statusText.contains("completed") || statusText.contains("completado") -> SManga.COMPLETED
                statusText.contains("hiatus") || statusText.contains("pausado") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // URL actualizada
            url = if (!resolvedId.isNullOrBlank()) "/series/comic-$slug?mangaId=$resolvedId" else "/series/comic-$slug"

            if (!resolvedId.isNullOrBlank()) {
                cacheManager.updateMangaCache(resolvedId, title, slug)
            }
        }
    }

    private fun buildTrackedMangaUrlForFallback(
        href: String,
        title: String,
    ): String {
        val normalizedPath = href.substringBefore("?")
        val slug = UrlUtils.mangaSlugFromUrl(normalizedPath) ?: return normalizedPath
        val resolvedId = resolveStableId(slug, title)

        if (!resolvedId.isNullOrBlank()) {
            cacheManager.updateMangaCache(resolvedId, title, slug)
            return "$normalizedPath?mangaId=$resolvedId"
        }

        return normalizedPath
    }

    private fun resolveFallbackMangaId(
        slug: String,
        title: String,
    ): String? {
        val normalizedTitle = title.trim().lowercase()
        val idFromApiSlug = apiHelper.resolveIdBySlug(slug, cacheManager)
        if (!idFromApiSlug.isNullOrBlank()) {
            cacheManager.updateMangaCache(idFromApiSlug, title, slug)
            return idFromApiSlug
        }

        val idBySlug = cacheManager.getMangaIdBySlug(slug)
        val validatedIdBySlug =
            idBySlug?.takeIf { cachedId ->
                val cachedTitle = cacheManager.getMangaTitleById(cachedId)?.trim()?.lowercase().orEmpty()
                (normalizedTitle.isNotBlank() && cachedTitle.isNotBlank() && cachedTitle == normalizedTitle) ||
                    (normalizedTitle.isBlank() && cachedTitle.isBlank())
            }
        return validatedIdBySlug ?: title.takeIf { it.isNotBlank() }?.let { cacheManager.resolveIdByTitleFromList(it) }
    }

    private fun resolveStableId(
        slug: String,
        title: String,
        preferredId: String? = null,
    ): String? {
        if (!preferredId.isNullOrBlank()) return preferredId
        return resolveFallbackMangaId(slug, title)
    }

    private fun fetchChapterListBySlug(
        slug: String,
        mangaId: String? = null,
    ): List<SChapter> {
        val firstResponse = client.newCall(paginatedChapterListRequest(slug, 1)).execute()
        val firstBody = firstResponse.body.string()
        if (apiHelper.isErrorPage(firstResponse.code, firstBody)) {
            throw Exception("Error al obtener lista de capítulos con slug: $slug")
        }
        val data = json.decodeFromString<PayloadChapterDto>(firstBody)
        var resultSize = data.data.size
        var page = 2
        while (data.meta.total > resultSize) {
            val newRequest = paginatedChapterListRequest(slug, page)
            val newResponse = client.newCall(newRequest).execute()
            val newBody = newResponse.body.string()
            if (apiHelper.isErrorPage(newResponse.code, newBody)) {
                throw Exception("Error al obtener página $page de capítulos con slug: $slug")
            }
            val newData = json.decodeFromString<PayloadChapterDto>(newBody)
            data.data += newData.data
            resultSize += newData.data.size
            page += 1
        }
        val resolvedMangaId = mangaId ?: cacheManager.getMangaIdBySlug(slug)
        return data.data.map { it.toSChapter(slug, dateFormat, resolvedMangaId) }
    }

    private fun fetchChapterListByScraping(
        mangaSlug: String,
        mangaId: String? = null,
    ): List<SChapter> {
        // Scraping de /series/comic-{slug} para obtener la lista de capítulos
        val seriesUrl = "$baseUrl/series/comic-$mangaSlug"
        val document = client.newCall(GET(seriesUrl, headers)).execute().asJsoup()

        // Nuevo layout: anchors con href /capitulo/{id}/comic-{slug} dentro de contenedores grid
        val chapterElements =
            document.select(
                "div.grid a[href*=/capitulo/], .chapters a[href*=/capitulo/], a.sf-ripple-container[href*=/capitulo/], a[href*=/capitulo/]",
            )
        val uniqueChapters = linkedMapOf<String, SChapter>()

        chapterElements.forEach { element ->
            try {
                val href = element.attr("abs:href")
                if (!href.contains("/comic-$mangaSlug")) return@forEach

                // Extraer ID del capítulo de la URL: /capitulo/{id}/comic-{slug}
                val chapterIdMatch = Regex("/capitulo/(\\d+)").find(href)
                val chapterId = chapterIdMatch?.groupValues?.getOrNull(1) ?: return@forEach

                // Extraer nombre del capítulo con selectores actuales y fallback
                val name =
                    element.selectFirst(".chapter-name")?.text()?.trim()
                        ?.replace(Regex("\\s+"), " ")
                        ?: "Capítulo $chapterId"

                // Extraer fecha del atributo datetime cuando exista
                val datetime = element.selectFirst("time[datetime]")?.attr("datetime")
                val uploadDate =
                    runCatching { datetime?.let { dateFormat.parse(it)?.time } }
                        .getOrNull()
                        ?: 0L

                uniqueChapters.putIfAbsent(
                    chapterId,
                    SChapter.create().apply {
                        this.name = name
                        val baseChapterUrl = href.substringAfter(baseUrl)
                        url = if (!mangaId.isNullOrBlank()) "$baseChapterUrl?mangaId=$mangaId" else baseChapterUrl
                        date_upload = uploadDate
                    },
                )
            } catch (_: Exception) {
                // Ignorar entradas rotas y continuar
            }
        }

        val chapters = uniqueChapters.values.toList()

        if (chapters.isEmpty()) {
            throw Exception("No se pudieron obtener los capítulos via scraping")
        }
        return chapters
    }

    private fun resolveAndUpdateMangaSlug(manga: SManga): String? {
        val mangaId = UrlUtils.mangaIdFromUrl(manga.url)
        val resolvedSlug = apiHelper.resolveSlugForManga(manga, cacheManager)
        if (!resolvedSlug.isNullOrBlank()) {
            val currentSlug = UrlUtils.mangaSlugFromUrl(manga.url)
            val hasIdParam = manga.url.contains("mangaId=")

            if (currentSlug != resolvedSlug || (mangaId != null && !hasIdParam)) {
                manga.url = if (mangaId != null) "/series/comic-$resolvedSlug?mangaId=$mangaId" else "/series/comic-$resolvedSlug"
            }
            cacheManager.updateMangaCache(mangaId, manga.title, resolvedSlug)
        }
        return resolvedSlug
    }

    private data class MangaTitleTag(
        val title: String,
    )

    private data class MangaRefTag(
        val manga: SManga,
    )

    private fun updateTaggedMangaUrl(
        response: Response,
        slug: String,
    ) {
        if (slug.isBlank()) return
        val tag = response.request.tag(MangaRefTag::class.java) ?: return
        val currentSlug = UrlUtils.mangaSlugFromUrl(tag.manga.url)
        val cleanSlug = slug.trim().removeSuffix("/")
        val mangaId =
            UrlUtils.mangaIdFromUrl(tag.manga.url)
                ?: resolveFallbackMangaId(cleanSlug, tag.manga.title)
                ?: return
        val hasIdParam = tag.manga.url.contains("mangaId=")

        if (currentSlug != cleanSlug || !hasIdParam) {
            tag.manga.url = "/series/comic-$cleanSlug?mangaId=$mangaId"
        }
        cacheManager.updateMangaCache(mangaId, tag.manga.title, cleanSlug)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        // Ajustado para leer correctamente el ID de la nueva URL estable
        val id =
            chapter.url
                .substringAfter("/capitulo/")
                .substringBefore("/")
                .substringBefore("?")

        val slugFromUrl = UrlUtils.chapterSlugFromUrl(chapter.url)
        val mangaId = UrlUtils.chapterMangaIdFromUrl(chapter.url) ?: cacheManager.getMangaIdBySlug(slugFromUrl)
        val resolvedSlug =
            if (mangaId != null) {
                apiHelper.resolveSlugById(mangaId, cacheManager.getMangaTitleById(mangaId), cacheManager) ?: slugFromUrl
            } else {
                slugFromUrl
            }
        return GET("$dashboardApiBaseUrl/api/series/$resolvedSlug/chapters/$id?type=comic")
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

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { domain ->
            if (domain != defaultBaseUrl) {
                preferences
                    .edit()
                    .putString("overrideBaseUrl", defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }
}
