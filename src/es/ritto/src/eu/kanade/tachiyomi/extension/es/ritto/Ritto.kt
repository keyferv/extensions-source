package eu.kanade.tachiyomi.extension.es.ritto

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.textOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File

@Source
abstract class Ritto : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(::pdfPageInterceptor)
    }

    override suspend fun getPopularManga(page: Int) = getCatalog(page) { addQueryParameter("orden", "vistas") }

    override suspend fun getLatestUpdates(page: Int) = getCatalog(page) { addQueryParameter("orden", "reciente") }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = getCatalog(page) {
        if (query.isNotBlank()) addQueryParameter("busqueda", query.trim())
        filters.filterIsInstance<UriPartFilter>().forEach { it.addTo(this) }
    }

    private suspend fun getCatalog(page: Int, parameters: HttpUrl.Builder.() -> Unit): MangasPage {
        val url = "$baseUrl/catalogo".toHttpUrl().newBuilder()
            .apply(parameters)
            .addQueryParameter("pagina", page.toString())
            .build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return MangasPage(document.workCards(), document.extractNextJs<PaginationDto>()?.hasNextPage() == true)
    }

    private fun Document.workCards() = select("a.ritto-work-card")
        .filterNot { card -> card.select("span").any { it.ownText() == NOVEL } }
        .map { card ->
            SManga.create().apply {
                setUrlWithoutDomain(card.absUrl("href"))
                title = card.selectFirst("h3")!!.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host) return MangasPage(emptyList(), false)
        return when (url.pathSegments.first()) {
            "catalogo" -> getCatalog(page) {
                url.queryParameterNames.filter { it != "pagina" && it != "vista" }.forEach { name ->
                    url.queryParameterValues(name).forEach { addQueryParameter(name, it) }
                }
            }

            "autor" -> MangasPage(client.get(url, Fetch.DOCUMENT).asJsoup().workCards(), false)

            // A scan's page lists all its works at once
            "scan" -> {
                val mangas = client.get(url, Fetch.DOCUMENT).asJsoup().select("article.sx-work")
                    .filterNot { it.selectFirst(".sx-work__badge")?.ownText() == NOVEL }
                    .map { work ->
                        SManga.create().apply {
                            val link = work.selectFirst(".sx-work__title a")!!
                            setUrlWithoutDomain(link.absUrl("href"))
                            title = link.text()
                            thumbnail_url = work.selectFirst("img")?.absUrl("src")
                        }
                    }
                MangasPage(mangas, false)
            }

            else -> super.getMangasByUrl(url, page)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "obra") return null
        val manga = SManga.create().apply { this.url = "/obra/${segments[1]}" }
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        // A missing work answers 200 with an "Obra no encontrada" page
        if (document.selectFirst("h1.rf-hero__title") == null || document.type() == NOVEL) return null
        return document.toSMangaDetails().apply {
            this.url = manga.url
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            manga = document.toSMangaDetails(),
            chapters = document.extractNextJs<List<ChapterDto>>().orEmpty().flatMap { it.toSChapters() },
        )
    }

    private fun Document.toSMangaDetails() = SManga.create().apply {
        title = selectFirst("h1.rf-hero__title")!!.text()
        thumbnail_url = selectFirst(".rf-hero__cover img")?.absUrl("src")
        val info = select(".rf-info-row").associate { it.child(0).text() to it.child(1).text() }
        author = info["Autor"]
        artist = info["Artista"]
        genre = select(".rf-hero__tags a").joinToString { it.text() }.ifEmpty { null }
        status = when (selectFirst("a.rf-hero__estado")?.absUrl("href")?.toHttpUrlOrNull()?.queryParameter("estado")) {
            "EN_EMISION" -> SManga.ONGOING
            "FINALIZADO" -> SManga.COMPLETED
            "PAUSADO" -> SManga.ON_HIATUS
            "CANCELADO" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = MarkdownDescription.Builder()
            .info(type(), info["Año"])
            .description(selectFirst(".rf-hero__synopsis .whitespace-pre-wrap"), keepNewlines = true)
            .field("Scan", select(".rf-hero__scans a").map { it.text() })
            .altTitles(listOfNotNull(selectFirst(".rf-hero__alt")?.textOrNull()))
            .build()
    }

    // "Manga · Proyecto fansub"
    private fun Document.type() = selectFirst(".rf-hero__tipo")?.text()?.substringBefore(" · ")

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter)
        val body = client.get(url, Fetch.DOCUMENT).use { it.body.string() }

        val archivoMatch = Regex(""""archivoUrl\\?":\\?"([^"]+)"""").find(body)
            ?: Regex("""archivoUrl[^"]*"[^"]*"([^"]+/archivo)""").find(body)

        if (archivoMatch != null || body.contains("/api/capitulos/")) {
            return handlePdfChapter(body)
        }

        val document = org.jsoup.Jsoup.parse(body, url)
        val images = document.select("img.reader-image")
        if (images.isNotEmpty()) {
            return images.mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src")) }
        }

        val rscPages = parsePagesFromRsc(body)
        if (rscPages.isNotEmpty()) return rscPages

        return emptyList()
    }

    private suspend fun handlePdfChapter(body: String): List<Page> {
        val urlMatch = Regex("""\\?"archivoUrl\\?":\\?"([^"]+)\\?"""").find(body)
            ?: throw Exception("No se pudo encontrar la URL del archivo PDF")

        val archivoUrl = urlMatch.groupValues[1].replace("\\/", "/")
        val fullUrl = if (archivoUrl.startsWith("http")) archivoUrl else "$baseUrl$archivoUrl"

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "ritto_pdf")
        cacheDir.mkdirs()
        val fileHash = fullUrl.hashCode().toString()
        val pdfFile = File(cacheDir, "chapter_$fileHash.pdf")

        if (!pdfFile.exists()) {
            client.get(fullUrl, Fetch.XHR, ensureSuccess = false).use { pdfResponse ->
                if (!pdfResponse.isSuccessful) throw Exception("Error al descargar PDF: ${pdfResponse.code}")
                pdfFile.writeBytes(pdfResponse.body.bytes())
            }
        }

        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount
        renderer.close()
        pfd.close()

        if (pageCount == 0) throw Exception("El PDF no tiene páginas")

        return (0 until pageCount).map { index ->
            Page(index, imageUrl = "$baseUrl/__pdf__/$fileHash/$index")
        }
    }

    private fun parsePagesFromRsc(body: String): List<Page> {
        val imagenesStart = body.indexOf("\\\"imagenes\\\":[")
        if (imagenesStart == -1) return emptyList()

        val arrayStart = body.indexOf('[', imagenesStart)
        var depth = 0
        var arrayEnd = arrayStart
        for (i in arrayStart until body.length) {
            when (body[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i
                        break
                    }
                }
            }
        }

        if (arrayEnd <= arrayStart) return emptyList()

        val rawArray = body.substring(arrayStart, arrayEnd + 1)
        val cleanArray = rawArray.replace("\\\"", "\"").replace("\\\\", "\\")

        val urlRegex = Regex(""""url":"([^"]+)"""")
        return urlRegex.findAll(cleanArray).mapIndexed { index, match ->
            val relativeUrl = match.groupValues[1]
            val imageUrl = "$CDN_BASE_URL/$relativeUrl"
            Page(index, imageUrl = imageUrl)
        }.toList()
    }

    private fun pdfPageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.encodedPath.startsWith("/__pdf__/")) {
            val segments = url.pathSegments
            val fileHash = segments[1]
            val pageIndex = segments[2].toInt()

            val cacheDir = File(System.getProperty("java.io.tmpdir"), "ritto_pdf")
            val pdfFile = cacheDir.listFiles()?.find { it.name.contains(fileHash) }
                ?: throw Exception("Archivo PDF no encontrado en caché")

            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val page = renderer.openPage(pageIndex)

            val scale = 2f
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()

            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            bitmap.recycle()

            val imageBytes = output.toByteArray()
            return Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(imageBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }

        return chain.proceed(request)
    }

    override fun getFilterList(data: JsonElement?) = getFilters()

    companion object {
        // Text novels, listed with the comics on the site
        private const val NOVEL = "Novela"
        private const val CDN_BASE_URL = "https://cdn.solitarionf.one"
    }
}
