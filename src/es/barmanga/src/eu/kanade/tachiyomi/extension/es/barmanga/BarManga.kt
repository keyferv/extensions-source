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
    "https://archiviumbar.com",
    "es",
    SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
) {
    override val useLoadMoreRequest = LoadMoreStrategy.Never

    // La descripción está en div.description-summary div.summary__content
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content"

    // El título en h1 es modificado por JavaScript, usar breadcrumb que es más confiable
    override val mangaDetailsSelectorTitle = ".breadcrumb li:last-child a, .post-title h1"

    override val popularMangaUrlSelector = "div.post-title h3 a, div.post-title a"

    // IMPORTANTE: Sobrescribir el selector de Madara para NO incluir ".reading-content .text-left... img"
    // porque ese selector captura imágenes basura como asd.png que están fuera de los page-breaks
    override val pageListParseSelector = "div.page-break, li.blocks-gallery-item"

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")

    override fun chapterListSelector() = "li.wp-manga-chapter"

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
                    // Log.w("BarManga", "Capítulo inválido filtrado: ${chapter.name}")
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

        // Filtrar capítulos falsos o ocultos
        if (element.hasClass("fake-chapter") || element.attr("style").contains("display: none")) {
            chapter.name = "Capítulo sin enlace"
            chapter.url = ""
            return chapter
        }

        with(element) {
            var url = ""
            var name = ""

            // 1. Intentar obtener URL del atributo data ofuscado en el LI (más robusto)
            // Formato: data-data_XXXXXX-url="BASE64"
            val dataUrlAttribute = attributes().asList().firstOrNull {
                it.key.startsWith("data-data_") && it.key.endsWith("-url")
            }

            if (dataUrlAttribute != null) {
                try {
                    val decodedUrl = String(Base64.decode(dataUrlAttribute.value, Base64.DEFAULT))
                    if (decodedUrl.startsWith("http")) {
                        url = decodedUrl
                        Log.d("BarManga", "URL decodificada de atributo ${dataUrlAttribute.key}: $url")
                    }
                } catch (e: Exception) {
                    Log.e("BarManga", "Error decodificando data-url: ${e.message}")
                }
            }

            // 2. Intentar obtener el nombre (Prioridad a .chapter-text-content)
            if (name.isEmpty()) {
                name = select("span.chapter-text-content").text().trim()
            }

            // 3. Buscar el enlace explícito
            val linkElement = selectFirst("a.chapter-real-link")
                ?: selectFirst("span.chapter-link-inner[data-href]") // Soporte legacy/alternativo
                ?: selectFirst("a[href*='/capitulo-']")
                ?: select("a").firstOrNull { !it.hasClass("c-new-tag") }

            if (linkElement != null) {
                if (name.isEmpty()) {
                    name = linkElement.text().trim()
                    if (name.isEmpty()) name = linkElement.select("span, p, i, b, strong").text().trim()
                    if (name.isEmpty()) name = ownText().trim()
                }
                if (url.isEmpty()) {
                    // Si es el span con data-href
                    if (linkElement.tagName() == "span" && linkElement.hasAttr("data-href")) {
                        url = linkElement.attr("data-href")
                    } else {
                        url = linkElement.attr("abs:href")
                    }
                }
            }

            chapter.name = name.ifEmpty { "Capítulo" }
            chapter.url = url.let {
                if (it.isNotEmpty()) {
                    it.substringBefore("?style=paged") + if (!it.endsWith("?style=list")) "?style=list" else ""
                } else {
                    ""
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

        // Filtro final de seguridad para URLs falsas
        if (chapter.url.isEmpty() || chapter.url == "#" || chapter.url.contains("fake-chapter")) {
            chapter.url = ""
            chapter.name = "Capítulo sin enlace"
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

    override fun imageRequest(page: Page): okhttp3.Request {
        val url = page.imageUrl!!
        if (url.contains("#token=")) {
            val token = url.substringAfter("#token=")
            val cleanUrl = url.substringBefore("#token=")
            val newPage = Page(page.index, page.url).apply { imageUrl = cleanUrl }
            val request = super.imageRequest(newPage)
            return request.newBuilder()
                .header("X-Security-Token", token)
                .build()
        }
        return super.imageRequest(page)
    }

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
            return BarMangaFilters.filterGarbage(super.pageListParse(document))
        } else {
            Log.d("BarManga", "No se detectó chapter protector")
        }

        val pages = mutableListOf<Page>()
        val seenImageUrls = mutableSetOf<String>()

        // Función para intentar reemplazar imagen existente si la nueva es mejor
        fun tryReplaceExisting(normalized: String, newUrl: String): Boolean {
            for (i in pages.indices) {
                val existing = pages[i]
                val existingNormalized = BarMangaUrlUtils.normalizeImageUrl(existing.imageUrl)
                if (existingNormalized == normalized) {
                    if (BarMangaUrlUtils.isBetterImage(newUrl, existing.imageUrl)) {
                        pages[i] = Page(existing.index, existing.url, newUrl)
                        Log.d("BarManga", "↺ Reemplazada imagen existente en índice ${existing.index}: ${existing.imageUrl} -> $newUrl")
                        return true
                    } else {
                        Log.d("BarManga", "↺ No se reemplaza la existente (${existing.imageUrl}) con la nueva ($newUrl) porque no es mejor")
                        return false
                    }
                }
            }
            return false
        }

        val pageBreaks = document.select("div.page-break, div.page-break.no-gaps")
        Log.d("BarManga", "Cantidad de page-breaks: ${pageBreaks.size}")

        pageBreaks.forEachIndexed { index, pageBreak ->
            var imageUrl: String? = null

            // 1. Intentar obtener imagen ofuscada (data-obfuscated + data-token)
            val imgObfuscated = pageBreak.selectFirst("img[data-obfuscated]")
            if (imgObfuscated != null) {
                val obfuscated = imgObfuscated.attr("data-obfuscated")
                val token = imgObfuscated.attr("data-token")
                imageUrl = BarMangaDeobfuscator.decodeObfuscatedImage(obfuscated, token)
                if (imageUrl != null) {
                    Log.d("BarManga", "✓ Imagen ofuscada encontrada en page $index: $imageUrl")
                }
            }

            // 2. Buscar en scripts usando el deobfuscator
            if (imageUrl == null) {
                val scripts = pageBreak.select("script")
                for (script in scripts) {
                    val result = BarMangaDeobfuscator.decodeFromScript(script.html())
                    if (result != null) {
                        imageUrl = result.first
                        val method = result.second
                        val prefix = if (method == "segments") "⚠" else "✓"
                        Log.d("BarManga", "$prefix Imagen ($method) en page $index: $imageUrl")
                        break
                    }
                }
            }

            if (imageUrl != null) {
                val normalized = BarMangaUrlUtils.normalizeImageUrl(imageUrl)
                if (normalized.isNotEmpty()) {
                    if (seenImageUrls.add(normalized)) {
                        val pageIndex = pages.size
                        pages.add(Page(pageIndex, document.location(), imageUrl))
                        Log.d("BarManga", "✓ [page-break $index] Imagen agregada en índice $pageIndex: $imageUrl")
                    } else {
                        if (tryReplaceExisting(normalized, imageUrl)) {
                            Log.d("BarManga", "↺ [page-break $index] Duplicado reemplazado con: $imageUrl")
                        } else {
                            Log.d("BarManga", "✗ [page-break $index] Imagen duplicada filtrada: $imageUrl")
                        }
                    }
                } else {
                    Log.d("BarManga", "✗ [page-break $index] Imagen vacía/no válida ignorada: $imageUrl")
                }
            } else {
                // FALLBACK: buscar <img> directamente
                val imgElement = pageBreak.selectFirst("img[src]")
                if (imgElement != null) {
                    val imgSrc = imgElement.attr("abs:src")
                    if (imgSrc.isNotEmpty() && imgSrc.startsWith("http")) {
                        val normalized = BarMangaUrlUtils.normalizeImageUrl(imgSrc)
                        if (normalized.isNotEmpty() && seenImageUrls.add(normalized)) {
                            val pageIndex = pages.size
                            pages.add(Page(pageIndex, document.location(), imgSrc))
                            Log.d("BarManga", "✓ [page-break $index] Imagen <img> fallback agregada en índice $pageIndex: $imgSrc")
                        } else {
                            Log.d("BarManga", "✗ [page-break $index] Imagen <img> fallback duplicada o vacía: $imgSrc")
                        }
                    } else {
                        Log.d("BarManga", "✗ [page-break $index] Imagen <img> sin src válido")
                    }
                } else {
                    Log.d("BarManga", "✗ [page-break $index] No se encontró imagen (ni scripts ni <img>)")
                }
            }
        }

        // Buscar imágenes adicionales fuera de page-breaks (como la imagen final del capítulo)
        // SOLO si no se encontraron páginas en los bloques estándar (para evitar basura como asd.png)
        val readingContent = document.selectFirst("div.reading-content")
        if (pages.isEmpty() && readingContent != null) {
            // Seleccionar imágenes directas que NO estén dentro de un div.page-break
            val directImages = readingContent.select("img").filter { img ->
                // IMPORTANTE: Verificar que la imagen NO esté dentro de ningún page-break
                // Esto evita duplicar imágenes que ya fueron procesadas en los page-breaks
                val isInsidePageBreak = img.parents().any { parent ->
                    parent.hasClass("page-break") ||
                        parent.hasClass("no-gaps") ||
                        parent.classNames().any { className -> className.contains("page-break") }
                }
                !isInsidePageBreak
            }

            Log.d("BarManga", "=== IMÁGENES DIRECTAS ===")
            Log.d("BarManga", "Imágenes directas encontradas fuera de page-breaks: ${directImages.size}")
            Log.d("BarManga", "Imágenes ya procesadas en page-breaks: ${seenImageUrls.size}")
            Log.d("BarManga", "URLs ya vistas: ${seenImageUrls.joinToString(", ") { it.takeLast(30) }}")

            directImages.forEachIndexed { idx, img ->
                val imgSrc = img.attr("abs:src")
                val imgClass = img.attr("class")
                val imgId = img.attr("id")

                Log.d("BarManga", "[$idx] Evaluando imagen directa - src: '$imgSrc', class: '$imgClass', id: '$imgId'")

                // Usar filtros centralizados
                if (BarMangaFilters.isGarbageUrl(imgSrc)) {
                    Log.d("BarManga", "✗ [$idx] Imagen basura filtrada: $imgSrc")
                    return@forEachIndexed
                }

                // FILTRO: Verificar si ya fue procesada
                val normalized = BarMangaUrlUtils.normalizeImageUrl(imgSrc)
                if (seenImageUrls.contains(normalized)) {
                    Log.d("BarManga", "✗ [$idx] Imagen YA PROCESADA en page-breaks, saltando: $imgSrc")
                    return@forEachIndexed
                }

                // FILTRO: Ignorar clases preloader/placeholder
                if (imgClass.contains("preloader", ignoreCase = true) ||
                    imgClass.contains("placeholder", ignoreCase = true)
                ) {
                    Log.d("BarManga", "✗ [$idx] Imagen preloader/placeholder ignorada: $imgSrc")
                    return@forEachIndexed
                }

                // FILTRO: Ignorar IDs con preload
                if (imgId.contains("preload", ignoreCase = true)) {
                    Log.d("BarManga", "✗ [$idx] Imagen con id preload ignorada: $imgSrc")
                    return@forEachIndexed
                }

                // Agregar imagen válida
                if (normalized.isNotEmpty() && seenImageUrls.add(normalized)) {
                    val pageIndex = pages.size
                    pages.add(Page(pageIndex, document.location(), imgSrc))
                    Log.d("BarManga", "✓ [$idx] Imagen directa legítima agregada en índice $pageIndex: $imgSrc")
                } else if (normalized.isNotEmpty()) {
                    if (tryReplaceExisting(normalized, imgSrc)) {
                        Log.d("BarManga", "↺ [$idx] Imagen directa duplicada reemplazada por: $imgSrc")
                    } else {
                        Log.d("BarManga", "✗ [$idx] Imagen directa duplicada filtrada (ya existe mejor): $imgSrc")
                    }
                }
            }

            Log.d("BarManga", "=== FIN IMÁGENES DIRECTAS ===")
        }

        val result = if (pages.isNotEmpty()) {
            Log.d("BarManga", "Se encontraron ${pages.size} páginas en total.")
            pages
        } else {
            Log.d("BarManga", "No se encontraron páginas en scripts, fallback a super.pageListParse")
            super.pageListParse(document)
        }

        return BarMangaFilters.filterGarbage(result)
    }
}
