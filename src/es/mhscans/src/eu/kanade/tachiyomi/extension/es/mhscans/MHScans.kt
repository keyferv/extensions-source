package eu.kanade.tachiyomi.extension.es.mhscans

import android.util.Log
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MHScans : Madara() {

    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es"))

    override fun OkHttpClient.Builder.configureClient() = rateLimit(1, 3.seconds) { it.host == baseUrl.toHttpUrl().host }

    override val chapterMode = ChapterMode.MangaAjax

    override val sendViewCount = false

    override fun archiveManga(element: Element, id: String): SManga? {
        val manga = super.archiveManga(element, id) ?: return null
        val href = element.selectFirst(archiveUrlSelector)?.attr("abs:href").orEmpty()
        val path = runCatching { href.toHttpUrl().encodedPath }.getOrNull()

        if (path.isNullOrBlank()) {
            Log.w(TAG, "archive manga=${manga.title}: unable to resolve path, keeping numeric id=$id")
            return manga
        }

        manga.url = path
        Log.d(TAG, "archive manga=${manga.title}: id=$id url=$path")
        return manga
    }

    override suspend fun fetchChapterDocument(chapterUrl: String): Document {
        val document = client.get(chapterUrl).asJsoup()
        document.selectFirst("form#rk_madara_redirect[method=post]")?.let { form ->
            val url = form.attr("abs:action").ifEmpty { form.attr("action") }
            val body = FormBody.Builder().apply {
                form.select("input").forEach { input ->
                    add(input.attr("name"), input.attr("value"))
                }
            }.build()
            val headers = headersBuilder().set("Referer", document.location()).build()
            return client.post(url, headers, body).asJsoup()
        }
        return document
    }

    override fun parsePages(document: Document): List<Page> {
        super.parsePages(document).takeIf { it.isNotEmpty() }?.let { return it }
        return document.select("div.rk-page-wrap img, img.rk-img").mapIndexed { index, img ->
            Page(index, imageUrl = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") })
        }
    }

    private companion object {
        const val TAG = "MHScans"
    }
}
