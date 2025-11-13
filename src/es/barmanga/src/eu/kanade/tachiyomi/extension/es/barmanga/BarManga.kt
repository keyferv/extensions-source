package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class BarManga : Madara(
    "BarManga",
    "https://libribar.com",
    "es",
    SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // La descripción está en div.description-summary div.summary__content
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content"

    // El título en h1 es modificado por JavaScript, usar breadcrumb que es más confiable
    override val mangaDetailsSelectorTitle = ".breadcrumb li:last-child a, .post-title h1"

    override val popularMangaUrlSelector = "div.post-title h3 a, div.post-title a"

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")

    // Este sitio NO usa AJAX para cargar capítulos, están directamente en el HTML
    // Sobrescribir para parsear directamente sin buscar contenedor AJAX
    override fun chapterListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.source.model.SChapter> {
        val document = response.asJsoup()

        Log.d("BarManga", "=== CHAPTER LIST PARSE ===")
        Log.d("BarManga", "URL: ${response.request.url}")

        // Buscar directamente los elementos li.wp-manga-chapter
        val chapterElements = document.select(chapterListSelector())

        Log.d("BarManga", "Elementos <li> encontrados: ${chapterElements.size}")

        if (chapterElements.isEmpty()) {
            Log.e("BarManga", "NO SE ENCONTRARON ELEMENTOS CON SELECTOR: ${chapterListSelector()}")
            return emptyList()
        }

        // Parsear cada elemento a capítulo
        val chapters = chapterElements.mapNotNull { element ->
            try {
                val chapter = chapterFromElement(element)
                // Filtrar capítulos sin URL válida
                if (chapter.url.isNotEmpty() && chapter.name != "Capítulo sin enlace") {
                    chapter
                } else {
                    Log.w("BarManga", "Capítulo inválido filtrado: ${chapter.name}")
                    null
                }
            } catch (e: Exception) {
                Log.e("BarManga", "Error parseando capítulo: ${e.message}")
                null
            }
        }

        Log.d("BarManga", "Capítulos válidos: ${chapters.size}")
        if (chapters.isNotEmpty()) {
            Log.d("BarManga", "Primeros 3: ${chapters.take(3).map { "${it.name} -> ${it.url}" }}")
        }
        Log.d("BarManga", "======================")

        return chapters
    }

    // Sobrescribir chapterFromElement para manejar correctamente la extracción
    override fun chapterFromElement(element: Element): eu.kanade.tachiyomi.source.model.SChapter {
        val chapter = eu.kanade.tachiyomi.source.model.SChapter.create()

        with(element) {
            // NUEVO: El sitio ahora usa span.chapter-link-inner con data-href en lugar de enlaces <a>
            val linkElement = selectFirst("span.chapter-link-inner[data-href]")

            if (linkElement != null) {
                val url = linkElement.attr("data-href")
                chapter.url = url.let {
                    it.substringBefore("?style=paged") + if (!it.endsWith("?style=list")) "?style=list" else ""
                }
                chapter.name = linkElement.text().trim()
            } else {
                // Fallback antiguo: intentar con el selector anterior por compatibilidad
                val fallbackLink = selectFirst("span.chapter-link-container a")
                    ?: select("a").firstOrNull { !it.hasClass("c-new-tag") }

                if (fallbackLink != null) {
                    chapter.url = fallbackLink.attr("abs:href").let {
                        it.substringBefore("?style=paged") + if (!it.endsWith("?style=list")) "?style=list" else ""
                    }
                    chapter.name = fallbackLink.text().trim()
                } else {
                    // Si realmente no hay enlaces válidos, usar valores por defecto
                    chapter.url = ""
                    chapter.name = "Capítulo sin enlace"
                    Log.e("BarManga", "No se encontró enlace válido en: ${html().take(200)}")
                }
            }

            // Intentar obtener la fecha
            chapter.date_upload = selectFirst("span.chapter-release-date a[title]")?.attr("title")?.let {
                parseRelativeDate(it)
            }
                ?: selectFirst("span.chapter-release-date i")?.text()?.let {
                    parseChapterDate(it)
                }
                ?: parseChapterDate(selectFirst("span.chapter-release-date")?.text())
        }

        return chapter
    }

    // Sobrescribir para manejar el título correctamente
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            // Buscar el enlace del título - probar múltiples selectores
            val link = selectFirst("div.post-title h3 a")
                ?: selectFirst("div.post-title a")
                ?: selectFirst("a[href*='/manga/']")

            if (link != null) {
                manga.setUrlWithoutDomain(link.attr("abs:href"))

                // Intentar obtener el título de diferentes fuentes
                val titleFromText = link.text().trim()
                val titleFromAttr = link.attr("title").trim()
                val titleFromHref = link.attr("href")
                    .substringAfterLast("/manga/")
                    .substringBefore("/")
                    .replace("-", " ")
                    .split(" ")
                    .joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                    .trim()

                Log.d("BarManga", "=== DEBUG TITLE ===")
                Log.d("BarManga", "URL: ${link.attr("abs:href")}")
                Log.d("BarManga", "HTML del elemento: ${element.html().take(300)}")
                Log.d("BarManga", "Título de text(): '$titleFromText'")
                Log.d("BarManga", "Título de attr(title): '$titleFromAttr'")
                Log.d("BarManga", "Título de href parseado: '$titleFromHref'")

                // Lista de palabras sospechosas que indican contenido ofuscado
                val suspiciousWords = listOf("porno", "porn", "xxx", "4k", "hd")

                fun isSuspicious(text: String): Boolean {
                    val lower = text.lowercase()
                    return suspiciousWords.any { lower.contains(it) } ||
                        text.length < 3 || // Títulos muy cortos probablemente son basura
                        text.matches(Regex("^[0-9kKhHdD]+$")) // Solo números y letras comunes en "4k", "HD"
                }

                // Prioridad: title attribute > text() > href parsing
                // El atributo title es menos probable que sea modificado por JavaScript
                manga.title = when {
                    titleFromAttr.isNotEmpty() && !isSuspicious(titleFromAttr) -> {
                        Log.d("BarManga", "Usando título del atributo 'title'")
                        titleFromAttr
                    }
                    titleFromText.isNotEmpty() && !isSuspicious(titleFromText) -> {
                        Log.d("BarManga", "Usando título del text()")
                        titleFromText
                    }
                    titleFromHref.isNotEmpty() && !isSuspicious(titleFromHref) -> {
                        Log.d("BarManga", "Usando título parseado del href")
                        titleFromHref
                    }
                    else -> {
                        // Si todos son sospechosos, preferir href parseado sobre los demás
                        Log.w("BarManga", "Todos los títulos son sospechosos, usando href como último recurso")
                        if (titleFromHref.isNotEmpty()) titleFromHref else titleFromText.ifEmpty { "Unknown" }
                    }
                }

                Log.d("BarManga", "Título final seleccionado: '${manga.title}'")
                Log.d("BarManga", "==================")
            } else {
                Log.e("BarManga", "No se encontró enlace en elemento: ${element.html().take(300)}")
            }

            // Buscar la imagen - intentar múltiples fuentes
            selectFirst("img")?.let { img ->
                var finalImageUrl = ""

                // Primero intentar buscar en scripts si la imagen tiene ID
                val imgId = img.id()
                if (imgId.isNotEmpty()) {
                    // Buscar img.src = "..." en los scripts del elemento padre
                    val scriptPattern = """img\.src\s*=\s*["']([^"']+)["']""".toRegex()
                    this.select("script").forEach { script ->
                        val scriptText = script.html()
                        if (scriptText.contains(imgId)) {
                            val match = scriptPattern.find(scriptText)
                            if (match != null) {
                                val scriptImgUrl = match.groupValues[1]
                                if (scriptImgUrl.isNotEmpty() && scriptImgUrl.startsWith("http")) {
                                    finalImageUrl = scriptImgUrl
                                    Log.d("BarManga", "Imagen encontrada en script: '$finalImageUrl'")
                                    return@forEach
                                }
                            }
                        }
                    }
                }

                // Si no se encontró en script, usar los atributos normales
                if (finalImageUrl.isEmpty()) {
                    val imgSrc = imageFromElement(img)
                    val imgDataSrc = img.attr("data-src").trim()
                    val imgDataLazy = img.attr("data-lazy-src").trim()

                    Log.d("BarManga", "Imagen src: '$imgSrc'")
                    Log.d("BarManga", "Imagen data-src: '$imgDataSrc'")
                    Log.d("BarManga", "Imagen data-lazy-src: '$imgDataLazy'")

                    // Lista de palabras que indican imágenes placeholder/falsas
                    val suspiciousImagePaths = listOf("placeholder", "default", "loading", "blank")

                    fun isValidImage(url: String?): Boolean {
                        if (url.isNullOrEmpty()) return false
                        val lower = url.lowercase()
                        return url.startsWith("http") &&
                            suspiciousImagePaths.none { lower.contains(it) } &&
                            url.contains("/wp-content/uploads/") // Las imágenes reales están en wp-content
                    }

                    // Priorizar data-src y data-lazy-src sobre src, pero validar que sean reales
                    finalImageUrl = when {
                        isValidImage(imgDataSrc) -> imgDataSrc
                        isValidImage(imgDataLazy) -> imgDataLazy
                        isValidImage(imgSrc) -> imgSrc ?: ""
                        imgSrc?.isNotEmpty() == true -> imgSrc
                        else -> ""
                    }
                }

                manga.thumbnail_url = finalImageUrl
                Log.d("BarManga", "Imagen final: '$finalImageUrl'")
            }
        }

        return manga
    }

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    // Sobrescribir para manejar títulos ofuscados en la página de detalles
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        Log.d("BarManga", "=== MANGA DETAILS ===")
        Log.d("BarManga", "Título de super: '${manga.title}'")
        Log.d("BarManga", "Thumbnail de super: '${manga.thumbnail_url}'")

        // Si el título detectado contiene palabras sospechosas, intentar extraerlo de fuentes alternativas
        val suspiciousWords = listOf("porno", "porn", "xxx", "4k", "hd")
        val isSuspicious = suspiciousWords.any { manga.title.contains(it, ignoreCase = true) } ||
            manga.title.length < 3 ||
            manga.title.matches(Regex("^[0-9kKhHdD]+$"))

        if (isSuspicious) {
            Log.w("BarManga", "Título sospechoso en detalles: '${manga.title}'")

            // Intentar obtener el título de fuentes alternativas más confiables
            // 1. Breadcrumb (migas de pan) - menos probable que sea modificado por JS
            val breadcrumbTitle = document.selectFirst(".breadcrumb li:last-child a")?.text()?.trim()
            Log.d("BarManga", "Título de breadcrumb: '$breadcrumbTitle'")

            // 2. Schema.org metadata - generalmente confiable
            val schemaTitle = document.selectFirst("span.rate-title[property=name]")?.text()?.trim()
                ?: document.selectFirst("span[property=name]")?.text()?.trim()
            Log.d("BarManga", "Título de schema: '$schemaTitle'")

            // 3. Atributo title del schema
            val schemaTitleAttr = document.selectFirst("span.rate-title[property=name]")?.attr("title")?.trim()
                ?: document.selectFirst("span[property=name]")?.attr("title")?.trim()
            Log.d("BarManga", "Título de schema attr: '$schemaTitleAttr'")

            // 4. Parsear desde la URL como último recurso
            val urlTitle = document.location()
                .substringAfterLast("/manga/")
                .substringBefore("/")
                .replace("-", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
                .trim()
            Log.d("BarManga", "Título de URL: '$urlTitle'")

            // Priorizar fuentes que no sean sospechosas
            manga.title = when {
                !breadcrumbTitle.isNullOrEmpty() && !suspiciousWords.any { breadcrumbTitle.contains(it, ignoreCase = true) } -> {
                    Log.d("BarManga", "Usando título del breadcrumb")
                    breadcrumbTitle
                }
                !schemaTitleAttr.isNullOrEmpty() && !suspiciousWords.any { schemaTitleAttr.contains(it, ignoreCase = true) } -> {
                    Log.d("BarManga", "Usando título del schema attr")
                    schemaTitleAttr
                }
                !schemaTitle.isNullOrEmpty() && !suspiciousWords.any { schemaTitle.contains(it, ignoreCase = true) } -> {
                    Log.d("BarManga", "Usando título del schema text")
                    schemaTitle
                }
                urlTitle.isNotEmpty() -> {
                    Log.d("BarManga", "Usando título parseado de URL")
                    urlTitle
                }
                else -> manga.title // Mantener el original si todo falla
            }

            Log.d("BarManga", "Título final: '${manga.title}'")
        }

        // Verificar y corregir la imagen de portada si es necesaria
        // Buscar en el script la URL real de la imagen
        val coverImg = document.selectFirst("div.summary_image img")
        if (coverImg != null) {
            val imgSrc = imageFromElement(coverImg)
            val imgDataSrc = coverImg.attr("data-src").trim()
            val imgDataLazy = coverImg.attr("data-lazy-src").trim()

            Log.d("BarManga", "Cover img src: '$imgSrc'")
            Log.d("BarManga", "Cover img data-src: '$imgDataSrc'")
            Log.d("BarManga", "Cover img data-lazy-src: '$imgDataLazy'")

            // Buscar en el script la URL real si existe
            val imgId = coverImg.id()
            if (imgId.isNotEmpty()) {
                val scriptPattern = """img\.src\s*=\s*["']([^"']+)["']""".toRegex()
                document.select("script").forEach { script ->
                    val scriptText = script.html()
                    if (scriptText.contains(imgId)) {
                        val match = scriptPattern.find(scriptText)
                        if (match != null) {
                            val scriptImgUrl = match.groupValues[1]
                            Log.d("BarManga", "Imagen encontrada en script: '$scriptImgUrl'")
                            if (scriptImgUrl.isNotEmpty() && scriptImgUrl.startsWith("http")) {
                                manga.thumbnail_url = scriptImgUrl
                                Log.d("BarManga", "Usando imagen del script")
                                Log.d("BarManga", "=====================")
                                return manga
                            }
                        }
                    }
                }
            }

            // Si no se encontró en el script, usar prioridad de atributos
            manga.thumbnail_url = when {
                imgDataSrc.isNotEmpty() && imgDataSrc.startsWith("http") -> {
                    Log.d("BarManga", "Usando imagen de data-src")
                    imgDataSrc
                }
                imgDataLazy.isNotEmpty() && imgDataLazy.startsWith("http") -> {
                    Log.d("BarManga", "Usando imagen de data-lazy-src")
                    imgDataLazy
                }
                imgSrc?.isNotEmpty() == true && imgSrc.startsWith("http") -> {
                    Log.d("BarManga", "Usando imagen de src")
                    imgSrc
                }
                else -> manga.thumbnail_url // Mantener el original
            }

            Log.d("BarManga", "Thumbnail final: '${manga.thumbnail_url}'")
        }

        Log.d("BarManga", "=====================")
        return manga
    }

    override fun pageListParse(document: Document): List<Page> {
        Log.d("BarManga", "Entrando a pageListParse en: ${document.location()}")

        // Primero intentar con el método estándar para chapter protector
        val chapterProtector = document.selectFirst(chapterProtectorSelector)
        if (chapterProtector != null) {
            Log.d("BarManga", "Se detectó chapter protector, usando super.pageListParse")
            return super.pageListParse(document)
        } else {
            Log.d("BarManga", "No se detectó chapter protector")
        }

        val pages = mutableListOf<Page>()
        val pageBreaks = document.select("div.page-break, div.page-break.no-gaps")
        Log.d("BarManga", "Cantidad de page-breaks: ${pageBreaks.size}")

        // Regex para extraer las variables hex (h0a, h0b, h1a, h1b, etc.)
        val hexVarRegex = """var\s+(h\d+[a-z])\s*=\s*['"]([^'"]+)['"]""".toRegex()
        // Fallbacks antiguos por compatibilidad
        val imageSegmentsRegex = """imageSegments\s*=\s*\[([\s\S]*?)\]""".toRegex()
        val segmentRegex = """['"]([^'"]+)['"]""".toRegex()
        val u1Regex = """var\s+u1\s*=\s*['"]([^'"]+)['"]""".toRegex()
        val u2Regex = """var\s+u2\s*=\s*['"]([^'"]+)['"]""".toRegex()

        pageBreaks.forEachIndexed { index, pageBreak ->
            var imageUrl: String? = null

            // Buscar scripts dentro del page-break
            val scripts = pageBreak.select("script")
            scripts.forEach { script ->
                val scriptText = script.html()

                // 1) NUEVO: Intentar extraer variables hex (h0a, h0b, h0c, etc.)
                val hexMatches = hexVarRegex.findAll(scriptText).toList()
                if (hexMatches.isNotEmpty()) {
                    try {
                        // Ordenar las variables por nombre (h0a, h0b, h0c, ...)
                        val sortedHexVars = hexMatches.sortedBy { it.groupValues[1] }

                        // Decodificar cada variable hex y concatenar
                        val decodedUrl = sortedHexVars.joinToString("") { match ->
                            val hexString = match.groupValues[2]
                            hexToString(hexString)
                        }

                        if (decodedUrl.startsWith("http")) {
                            imageUrl = decodedUrl
                            Log.d("BarManga", "Imagen decodificada de hex en page $index: $imageUrl")
                            return@forEach
                        }
                    } catch (e: Exception) {
                        Log.e("BarManga", "Error decodificando variables hex en page $index: ${e.message}")
                    }
                }

                // 2) Fallback a imageSegments (método antiguo)
                val imageSegmentsMatch = imageSegmentsRegex.find(scriptText)
                if (imageSegmentsMatch != null) {
                    try {
                        val segmentsText = imageSegmentsMatch.groupValues[1]
                        val segments = segmentRegex.findAll(segmentsText)
                            .map { it.groupValues[1] }
                            .toList()

                        if (segments.isNotEmpty()) {
                            // Decodificar cada segmento y concatenarlos
                            val decoded = segments.joinToString("") { segment ->
                                String(Base64.decode(segment, Base64.DEFAULT))
                            }
                            imageUrl = decoded
                            Log.d("BarManga", "Imagen ensamblada (segments) en page $index: $imageUrl")
                            return@forEach
                        }
                    } catch (e: Exception) {
                        Log.e("BarManga", "Error decodificando imageSegments en page $index: ${e.message}")
                    }
                }

                // 3) Fallback a var u1 / u2 (método más antiguo)
                val u1Match = u1Regex.find(scriptText)
                val u2Match = u2Regex.find(scriptText)
                if (u1Match != null && u2Match != null) {
                    try {
                        val u1Decoded = String(Base64.decode(u1Match.groupValues[1], Base64.DEFAULT))
                        val u2Decoded = String(Base64.decode(u2Match.groupValues[1], Base64.DEFAULT))
                        imageUrl = u1Decoded + u2Decoded
                        Log.d("BarManga", "Imagen ensamblada (u1/u2) en page $index: $imageUrl")
                        return@forEach
                    } catch (e: Exception) {
                        Log.e("BarManga", "Error decodificando u1/u2 en page $index: ${e.message}")
                    }
                }
            }

            if (imageUrl != null) {
                pages.add(Page(index, document.location(), imageUrl))
            } else {
                Log.d("BarManga", "No se encontró imagen en page $index mediante scripts")
            }
        }

        return if (pages.isNotEmpty()) {
            Log.d("BarManga", "Se encontraron ${pages.size} páginas mediante scripts.")
            pages
        } else {
            Log.d("BarManga", "No se encontraron páginas en scripts, fallback a super.pageListParse")
            super.pageListParse(document)
        }
    }

    // Función auxiliar para decodificar strings hexadecimales
    private fun hexToString(hex: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < hex.length) {
            val hexPair = hex.substring(i, minOf(i + 2, hex.length))
            result.append(hexPair.toInt(16).toChar())
            i += 2
        }
        return result.toString()
    }
}
