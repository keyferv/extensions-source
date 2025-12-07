package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import org.jsoup.nodes.Document
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class BarManga : Madara(
    "BarManga",
    "https://libribar.com",
    "es",
    SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val mangaDetailsSelectorDescription = "div.flamesummary > div.manga-excerpt"

    override val pageListParseSelector = "div.page-break"

    private val imageSegmentsRegex = """var\s+imageSegments\s*=\s*\[\s*(['"][A-Za-z0-9+/=]+['"](?:\s*,\s*['"][A-Za-z0-9+/=]+['"])*)\s*];""".toRegex()
    private val base64ItemRegex = """['"]([A-Za-z0-9+/=]+)['"]""".toRegex()

    override fun pageListParse(document: Document): List<Page> {
        launchIO { countViews(document) }

        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()

        // Primero procesar los bloques con `imageSegments` dentro de los `page-break`
        document.select(pageListParseSelector).forEach { element ->
            val scriptData = element.select("script").firstNotNullOfOrNull { script ->
                val data = script.data()
                if (data.contains("var imageSegments")) data else null
            } ?: return@forEach

            val match = imageSegmentsRegex.find(scriptData) ?: return@forEach
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
}
