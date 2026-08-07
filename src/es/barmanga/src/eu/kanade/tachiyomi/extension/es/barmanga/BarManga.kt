package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

@Source
abstract class BarManga : Madara() {
    override val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    override val useLoadMoreRequest = LoadMoreStrategy.Always

    override val filterNonMangaItems = false

    override val mangaDetailsSelectorDescription = "div.flamesummary > div.manga-excerpt"

    override val pageListParseSelector = "div.page-break"

    override val chapterUrlSelector = "a.ch-link"

    // Caché en memoria para los ZIP de capítulos descargados
    private val chapterCache = mutableMapOf<String, Map<Int, ByteArray>>()

    override val client: OkHttpClient by lazy {
        super.client.newBuilder()
            .addInterceptor(BarMangaInterceptor(chapterCache))
            .build()
    }

    // Breadcrumb título en página de detalle
    override val mangaDetailsSelectorTitle = ".breadcrumb > li:last-child > a"

    private val imageSegmentsRegex = """var\s+imageSegments\s*=\s*\[\s*(['"][A-Za-z0-9+/=]+['"](?:\s*,\s*['"][A-Za-z0-9+/=]+['"])*)\s*];""".toRegex()
    private val base64ItemRegex = """['"]([A-Za-z0-9+/=]+)['"]""".toRegex()

    override fun pageListParse(document: Document): List<Page> {
        launchIO { countViews(document) }

        Log.d(TAG, "pageListParse start url=${document.location()}")

        // ── 1. PRIMARIO: imágenes directas en page-break (método estándar Madara) ──
        val directPages = parseDirectImages(document)
        if (directPages.isNotEmpty()) {
            Log.d(TAG, "✓ used DIRECT IMAGES: ${directPages.size} pages")
            return directPages
        }

        // ── 2. FALLBACK: ZIP bundle (si el sitio vuelve a activarlo) ──
        val zipPages = parseZipBundle(document)
        if (zipPages.isNotEmpty()) {
            Log.d(TAG, "✓ used ZIP BUNDLE: ${zipPages.size} pages")
            return zipPages
        }

        // ── 3. FALLBACK: imageSegments (legacy) ──
        val segmentPages = parseImageSegments(document)
        if (segmentPages.isNotEmpty()) {
            Log.d(TAG, "✓ used IMAGE SEGMENTS: ${segmentPages.size} pages")
            return segmentPages
        }

        // ── 4. FALLBACK: imágenes estáticas fuera de page-break ──
        val staticPages = parseStaticImages(document)
        Log.d(TAG, "✓ used STATIC IMAGES: ${staticPages.size} pages")
        return staticPages
    }

    /**
     * PRIMARIO: Parsea <img> directos dentro de div.page-break.
     * Es el método estándar de Madara y lo que el sitio usa actualmente.
     */
    private fun parseDirectImages(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val pageBreaks = document.select(pageListParseSelector)
        Log.d(TAG, "parseDirectImages: ${pageBreaks.size} page-breaks found in ${document.location()}")

        pageBreaks.forEachIndexed { index, pageBreak ->
            val img = pageBreak.selectFirst("img.wp-manga-chapter-img, img")
            if (img != null) {
                val rawSrc = img.attr("src")
                val dataSrc = img.attr("data-src")
                val dataLazy = img.attr("data-lazy-src")
                val src = rawSrc.trim().ifEmpty { dataSrc.trim() }.ifEmpty { dataLazy.trim() }

                Log.d(TAG, "page[$index]: raw='${rawSrc.take(100)}' dataSrc='${dataSrc.take(50)}' lazy='${dataLazy.take(50)}' -> final='${src.take(100)}'")

                if (src.isNotEmpty() && !src.startsWith("blob:") && !src.startsWith("data:")) {
                    val page = Page(pages.size, document.location(), src)
                    Log.d(TAG, "page[$index]: OK url=${page.url} imageUrl=${page.imageUrl}")
                    pages.add(page)
                } else {
                    Log.w(TAG, "page[$index]: SKIPPED (empty/blob/data)")
                }
            } else {
                Log.w(TAG, "page[$index]: NO IMG in page-break. HTML=${pageBreak.html().take(200)}")
            }
        }

        Log.d(TAG, "parseDirectImages: ${pages.size}/${pageBreaks.size} pages extracted")
        if (pages.isNotEmpty()) {
            Log.d(TAG, "first page: url=${pages.first().url} imageUrl=${pages.first().imageUrl}")
            Log.d(TAG, "last page: url=${pages.last().url} imageUrl=${pages.last().imageUrl}")
        }
        return pages
    }

    /**
     * FALLBACK: Descarga ZIP bundle con nonce/bundleAction/chapterKey.
     * El sitio usaba esto antes de junio 2026. Se mantiene por si lo reactivan.
     */
    private fun parseZipBundle(document: Document): List<Page> {
        val scriptData = document.select("script")
            .map { it.data() }
            .find { it.contains("const _cfg") && it.contains("bundleAction") } ?: return emptyList()

        Log.d(TAG, "ZIP script found, size=${scriptData.length}")

        val nonce = extractJsValue(scriptData, "nonce")
        val bundleAction = extractJsValue(scriptData, "bundleAction")
        val chapterKey = extractJsValue(scriptData, "chapterKey")
        val endpoint = extractJsValue(scriptData, "endpoint") ?: "$baseUrl/wp-admin/admin-ajax.php"

        if (nonce == null || bundleAction == null || chapterKey == null) {
            Log.w(TAG, "ZIP: missing tokens")
            return emptyList()
        }

        return try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("action", bundleAction)
                .addFormDataPart("nonce", nonce)
                .addFormDataPart("chapter_key", chapterKey)
                .build()

            val endpointUrl = endpoint.toHttpUrl()
            val request = Request.Builder()
                .url(endpointUrl)
                .headers(xhrHeaders)
                .header("Origin", baseUrl)
                .header("Referer", document.location())
                .header("Accept", "*/*")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .post(multipart)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                Log.w(TAG, "ZIP: response code=${response.code}")
                return emptyList()
            }

            val zipBytes = response.body.bytes()
            response.close()

            val zis = ZipInputStream(ByteArrayInputStream(zipBytes))
            val images = mutableMapOf<Int, ByteArray>()
            generateSequence { zis.nextEntry }.forEach { entry ->
                val pageNum = entry.name.substringBefore(".").toIntOrNull() ?: return@forEach
                images[pageNum] = zis.readBytes()
            }
            zis.close()

            if (images.isEmpty()) return emptyList()

            val cacheKey = document.location()
            synchronized(chapterCache) { chapterCache[cacheKey] = images }

            images.keys.sorted().mapIndexed { index, pageNum ->
                Page(index, document.location(), "$cacheKey#barmanga-page-$pageNum")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ZIP: fetch failed", e)
            emptyList()
        }
    }

    /**
     * FALLBACK: Decodifica imageSegments (Base64 en scripts dentro de page-break).
     * Método legacy que algunos sitios Madara aún usan.
     */
    private fun parseImageSegments(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()
        var segmentsFound = 0

        document.select(pageListParseSelector).forEach { element ->
            val innerScriptData = element.select("script").firstNotNullOfOrNull { script ->
                val data = script.data()
                if (data.contains("var imageSegments")) data else null
            } ?: return@forEach

            segmentsFound++
            val match = imageSegmentsRegex.find(innerScriptData) ?: return@forEach
            val arrayContent = match.groupValues[1]
            val segments = base64ItemRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
            if (segments.isEmpty()) return@forEach

            val imageUrl = String(Base64.decode(segments.joinToString(""), Base64.DEFAULT))
            if (seenUrls.add(imageUrl)) {
                pages.add(Page(pages.size, document.location(), imageUrl))
            }
        }

        Log.d(TAG, "parseImageSegments: $segmentsFound scripts with imageSegments, ${pages.size} pages")
        return pages
    }

    /**
     * FALLBACK: Imágenes estáticas en div.reading-content fuera de page-break.
     * Último recurso cuando ninguno de los métodos anteriores funciona.
     */
    private fun parseStaticImages(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()
        var totalImgs = 0
        var skippedInPageBreak = 0

        document.select("div.reading-content img").forEach { img ->
            totalImgs++
            if (img.closest(pageListParseSelector) != null) {
                skippedInPageBreak++
                return@forEach
            }

            val possibleAttrs = listOf("data-src", "data-lazy-src", "data-original", "src", "srcset", "data-srcset")
            var raw = ""
            for (attr in possibleAttrs) {
                val v = img.attr(attr).orEmpty().trim()
                if (v.isNotEmpty()) {
                    raw = v
                    break
                }
            }
            if (raw.isBlank()) return@forEach

            if (raw.contains(",") || raw.contains(" ")) {
                raw = raw.split(",").first().trim().split(Regex("\\s+"))[0]
            }

            val src = try {
                when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    else -> URL(URL(document.location()), raw).toString()
                }
            } catch (_: Exception) {
                raw
            }

            if (src.isBlank() || src.startsWith("blob:") || src.startsWith("data:")) return@forEach
            if (seenUrls.add(src)) {
                pages.add(Page(pages.size, document.location(), src))
            }
        }

        Log.d(TAG, "parseStaticImages: $totalImgs total imgs, $skippedInPageBreak skipped (in page-break), ${pages.size} pages")
        return pages
    }

    private fun extractJsValue(script: String, key: String): String? = """$key:\s*"(.+?)"""".toRegex().find(script)?.groupValues?.get(1)

    companion object {
        private const val TAG = "BarManga"
    }
}
