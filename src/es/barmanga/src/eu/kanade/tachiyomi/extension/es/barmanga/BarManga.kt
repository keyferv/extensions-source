package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
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

class BarManga :
    Madara(
        "BarManga",
        "https://archiviumbar.com",
        "es",
        SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
    ) {
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

        val scriptData = document.select("script")
            .map { it.data() }
            .find { it.contains("const _cfg") && it.contains("bundleAction") }

        Log.d(TAG, "scriptData found=${scriptData != null} size=${scriptData?.length ?: 0}")

        if (scriptData != null) {
            val nonce = extractJsValue(scriptData, "nonce")
            val bundleAction = extractJsValue(scriptData, "bundleAction")
            val chapterKey = extractJsValue(scriptData, "chapterKey")
            val endpoint = extractJsValue(scriptData, "endpoint") ?: "$baseUrl/wp-admin/admin-ajax.php"

            Log.d(TAG, "extracted nonce=$nonce bundleAction=$bundleAction chapterKey=$chapterKey endpoint=$endpoint")

            if (nonce != null && bundleAction != null && chapterKey != null) {
                try {
                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("action", bundleAction)
                        .addFormDataPart("nonce", nonce)
                        .addFormDataPart("chapter_key", chapterKey)
                        .build()

                    val endpointUrl = endpoint.toHttpUrl()
                    val cookiesForEndpoint = client.cookieJar.loadForRequest(endpointUrl)
                    val cookiesForPage = client.cookieJar.loadForRequest(document.location().toHttpUrl())
                    Log.d(TAG, "cookies(endpoint=$endpoint)=${cookiesForEndpoint.joinToString { "${it.name}=${it.value}" }}")
                    Log.d(TAG, "cookies(page=${document.location()})=${cookiesForPage.joinToString { "${it.name}=${it.value}" }}")

                    val request = Request.Builder()
                        .url(endpointUrl)
                        .headers(xhrHeaders)
                        .header("Origin", baseUrl)
                        .header("Referer", document.location())
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en,de;q=0.9")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Dest", "empty")
                        .post(multipart)
                        .build()
                    Log.d(TAG, "POST $endpoint reqHeaders=${request.headers}")
                    val response = client.newCall(request).execute()
                    Log.d(TAG, "response code=${response.code} contentType=${response.header("Content-Type")} contentLength=${response.header("Content-Length")}")

                    if (response.isSuccessful) {
                        val zipBytes = response.body.bytes()
                        response.close()
                        Log.d(TAG, "zipBytes size=${zipBytes.size} magic=${zipBytes.take(4).joinToString(",") { "0x%02X".format(it) }}")

                        val zis = ZipInputStream(ByteArrayInputStream(zipBytes))
                        val images = mutableMapOf<Int, ByteArray>()

                        generateSequence { zis.nextEntry }.forEach { entry ->
                            val pageNum = entry.name.substringBefore(".").toIntOrNull()
                            val bytes = zis.readBytes()
                            Log.d(TAG, "zip entry name=${entry.name} pageNum=$pageNum size=${bytes.size} magic=${bytes.take(4).joinToString(",") { "0x%02X".format(it) }}")
                            if (pageNum == null) return@forEach
                            images[pageNum] = bytes
                        }
                        zis.close()

                        Log.d(TAG, "extracted images count=${images.size} keys=${images.keys.sorted()}")

                        if (images.isNotEmpty()) {
                            val cacheKey = document.location()
                            synchronized(chapterCache) {
                                chapterCache[cacheKey] = images
                            }

                            return images.keys.sorted().mapIndexed { index, pageNum ->
                                Page(index, document.location(), "$cacheKey#barmanga-page-$pageNum")
                            }
                        }
                    } else {
                        Log.w(TAG, "response not successful code=${response.code}")
                        response.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "bundle fetch failed", e)
                }
            } else {
                Log.w(TAG, "missing tokens — falling back to legacy parser")
            }
        } else {
            Log.w(TAG, "no MSP script — falling back to legacy parser")
        }

        // ── FALLBACK: comportamiento anterior (imageSegments + img estáticas)
        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()

        // Primero procesar los bloques con `imageSegments` dentro de los `page-break`
        document.select(pageListParseSelector).forEach { element ->
            val innerScriptData = element.select("script").firstNotNullOfOrNull { script ->
                val data = script.data()
                if (data.contains("var imageSegments")) data else null
            } ?: return@forEach

            val match = imageSegmentsRegex.find(innerScriptData) ?: return@forEach
            val arrayContent = match.groupValues[1]

            val segments = base64ItemRegex.findAll(arrayContent).map { it.groupValues[1] }.toList()
            if (segments.isEmpty()) return@forEach

            val joinedBase64 = segments.joinToString("")
            val imageUrl = String(Base64.decode(joinedBase64, Base64.DEFAULT))

            if (seenUrls.add(imageUrl)) {
                pages.add(Page(pages.size, document.location(), imageUrl))
            }
        }

        // Luego, añadir imágenes estáticas que estén dentro de `div.reading-content` pero fuera de `page-break`
        document.select("div.reading-content img").forEach { img ->
            // Si la imagen está dentro de un `page-break`, ya fue procesada arriba
            if (img.closest(pageListParseSelector) != null) return@forEach

            // Buscar en varios atributos comunes (data-src, data-original, srcset, etc.)
            val possibleAttrs = listOf("data-src", "data-lazy-src", "data-original", "data-src", "src", "srcset", "data-srcset")
            var raw = ""
            for (attr in possibleAttrs) {
                val v = img.attr(attr).orEmpty().trim()
                if (v.isNotEmpty()) {
                    raw = v
                    break
                }
            }

            if (raw.isBlank()) return@forEach

            // Si es un srcset, tomar la primera URL antes del espacio o coma
            if (raw.contains(",") || raw.contains(" ")) {
                raw = raw.split(",").first().trim().split(Regex("\\s+"))[0]
            }

            // Resolver URLs relativas y protocol-relative
            val src = try {
                when {
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    else -> URL(URL(document.location()), raw).toString()
                }
            } catch (_: Exception) {
                raw
            }

            if (src.isBlank()) return@forEach
            if (src.startsWith("blob:") || src.startsWith("data:")) return@forEach

            if (seenUrls.add(src)) {
                pages.add(Page(pages.size, document.location(), src))
            }
        }

        return pages
    }

    private fun extractJsValue(script: String, key: String): String? = """$key:\s*"(.+?)"""".toRegex().find(script)?.groupValues?.get(1)

    companion object {
        private const val TAG = "BarManga"
    }
}
