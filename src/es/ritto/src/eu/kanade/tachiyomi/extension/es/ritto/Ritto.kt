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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Ritto : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(::pdfPageInterceptor)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = catalogParse(client.get("$baseUrl/catalogo?busqueda=&tipo=&estado=&genero=&categoria=&orden=vistas&pagina=$page").asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = catalogParse(client.get("$baseUrl/catalogo?busqueda=&tipo=&estado=&genero=&categoria=&orden=reciente&pagina=$page").asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val tipo = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue ?: ""
        val estado = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue ?: ""
        val genero = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue ?: ""
        val categoria = filters.firstInstanceOrNull<CategoryFilter>()?.selectedValue ?: ""
        val orden = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "reciente"

        val url = "$baseUrl/catalogo".toHttpUrl().newBuilder()
            .addQueryParameter("busqueda", query)
            .addQueryParameter("tipo", tipo)
            .addQueryParameter("estado", estado)
            .addQueryParameter("genero", genero)
            .addQueryParameter("categoria", categoria)
            .addQueryParameter("orden", orden)
            .addQueryParameter("pagina", page.toString())
            .build()

        return catalogParse(client.get(url).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.getOrNull(0) != "obra") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val manga = SManga.create().apply { this.url = slug }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        val body = client.get(url).use { it.body.string() }
        val document = org.jsoup.Jsoup.parse(body, url)
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga.url),
            chapters = parseChapterList(body, document),
        )
    }

    private fun parseMangaDetails(document: Document, mangaUrl: String): SManga = SManga.create().apply {
        url = mangaUrl
        title = document.selectFirst("h1")?.text()
            ?: throw Exception("Título no encontrado")
        description = document.selectFirst("p.ref-obra-description")?.text()
        thumbnail_url = document.selectFirst("img[src*=\"/covers/\"]")?.attr("abs:src")

        val genreLinks = document.select("a[href*=\"/catalogo?genero=\"]")
        genre = genreLinks.map { it.text() }.distinct().joinToString()

        val statusLink = document.selectFirst("a[href*=\"/catalogo?estado=\"]")
        val statusText = statusLink?.attr("href") ?: ""
        status = when {
            statusText.contains("EN_EMISION") -> SManga.ONGOING
            statusText.contains("FINALIZADO") -> SManga.COMPLETED
            statusText.contains("PAUSADO") -> SManga.ON_HIATUS
            statusText.contains("CANCELADO") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        author = document.selectFirst("a[href*=\"/autor/\"]")?.text()
    }

    private fun parseChapterList(body: String, document: Document): List<SChapter> {
        val chapters = parseChaptersFromRsc(body)

        if (chapters.isNotEmpty()) return chapters

        return parseChaptersFromHtml(document)
    }

    private fun parseChaptersFromRsc(body: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        val itemsStart = body.indexOf("\\\"items\\\":[")
        if (itemsStart == -1) return emptyList()

        val arrayStart = body.indexOf('[', itemsStart)
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

        val chapterRegex = Regex(
            """\{"id":"[^"]+","nombre":"((?:Cap|Vol)\.?[^"]*)".*?"href":"([^"]+)".*?"fechaLabel":"([^"]+)".*?"numero":(\d+(?:\.\d+)?)""",
        )
        val tituloExtraRegex = Regex(""""tituloExtra":"([^"]+)"""")

        for (match in chapterRegex.findAll(cleanArray)) {
            val nombre = match.groupValues[1]
            val href = match.groupValues[2]
            val fechaLabel = match.groupValues[3]
            val numero = match.groupValues[4].toFloatOrNull() ?: continue

            val tituloExtra = tituloExtraRegex
                .find(match.value)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val displayName = if (tituloExtra != null) "$nombre · $tituloExtra" else nombre

            chapters.add(
                SChapter.create().apply {
                    url = href
                    name = displayName
                    chapter_number = numero
                    date_upload = dateFormat.tryParseDate(fechaLabel)
                },
            )
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun parseChaptersFromHtml(document: Document): List<SChapter> {
        val chapters = document.select("article.obra-chapter-row")

        return chapters.mapNotNull { row ->
            val link = row.selectFirst("a[href*=\"/capitulo/\"]") ?: return@mapNotNull null
            SChapter.create().apply {
                url = link.attr("href")
                name = link.text()

                val numberText = row.selectFirst(".obra-chapter-number")?.text()
                chapter_number = numberText?.toFloatOrNull() ?: -1f

                val spans = row.select("span")
                for (span in spans) {
                    val text = span.text()
                    if (text.matches("\\d{1,2}\\s+\\w{3}\\s+\\d{4}".toRegex())) {
                        date_upload = dateFormat.tryParseDate(text)
                        break
                    }
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = baseUrl + chapter.url
        val body = client.get(url).use { it.body.string() }

        val archivoMatch = Regex(""""archivoUrl\\?":\\?"([^"]+)"""").find(body)
            ?: Regex("""archivoUrl[^"]*"[^"]*"([^"]+/archivo)""").find(body)

        if (archivoMatch != null || body.contains("/api/capitulos/")) {
            return handlePdfChapter(body)
        }

        val rscPages = parsePagesFromRsc(body)
        if (rscPages.isNotEmpty()) return rscPages

        val document = org.jsoup.Jsoup.parse(body, url)
        return parsePagesFromHtml(document)
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
            client.get(fullUrl, ensureSuccess = false).use { pdfResponse ->
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

    private fun parsePagesFromHtml(document: Document): List<Page> = document.select("img.reader-image").mapIndexed { index, img ->
        Page(index, imageUrl = img.attr("abs:src"))
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

    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("es"))

    companion object {
        private const val CDN_BASE_URL = "https://cdn.solitarionf.one"
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
        CategoryFilter(),
        SortFilter(),
    )

    private fun catalogParse(document: Document): MangasPage {
        val mangaCards = document.select("a[href*=\"/obra/\"]")

        val mangas = mangaCards.mapNotNull { card ->
            val href = card.attr("href")
            if (!href.startsWith("/obra/")) return@mapNotNull null

            val img = card.selectFirst("img[src*=\"cdn.solitarionf.one\"]") ?: return@mapNotNull null
            val h3 = card.selectFirst("h3") ?: return@mapNotNull null

            SManga.create().apply {
                url = href.removePrefix("/obra/")
                title = h3.text()
                thumbnail_url = img.attr("abs:src")
            }
        }

        val pageButtons = document.select("button.h-8.w-8")
        val activeIdx = pageButtons.indexOfFirst {
            it.className().contains("bg-[#D93025]")
        }
        val hasNextPage = if (pageButtons.isNotEmpty() && activeIdx >= 0) {
            activeIdx < pageButtons.size - 1
        } else {
            mangas.size >= 20
        }

        return MangasPage(mangas, hasNextPage)
    }
}
