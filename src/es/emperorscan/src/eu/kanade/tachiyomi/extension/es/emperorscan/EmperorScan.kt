package eu.kanade.tachiyomi.extension.es.emperorscan

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class EmperorScan :
    Madara(),
    ConfigurableSource {
    override val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("es"))

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true

    override val client = super.client.newBuilder()
        .rateLimit(2) { it.host == baseUrlHost }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .setRandomUserAgent()

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun popularMangaSelector() = "#mkAgrid a.acard, a.acard"

    override fun popularMangaFromElement(element: Element) = mangaFromACard(element)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = mangaFromACard(element)

    private fun mangaFromACard(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst(".ac-t")?.text()
            ?: element.attr("title")

        element.selectFirst("img.ac-cover, img")?.let {
            thumbnail_url = processThumbnail(imageFromElement(it), true)
        }
    }

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".htitle")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("title")!!.text().substringBefore(" – ").substringBefore(" - ")

        thumbnail_url = document.selectFirst(".hposter img, meta[property=og:image]")?.let {
            processThumbnail(imageFromElement(it), true)
        }

        description = document.selectFirst(".syn")?.text()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        status = when (document.selectFirst(".htag--status, .sir:has(.l:contains(Estado)) .v")?.text()) {
            "En Curso" -> SManga.ONGOING
            "Completado", "Finalizado" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        genre = document.select(".hchips--genres .chip").eachText().joinToString()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapterData = document.selectFirst("script#mk-chapters-data")?.data()
            ?: return document.select(chapterListSelector()).map(::chapterFromElement)

        return chapterRegex.findAll(chapterData).map { match ->
            val name = unescapeJsonValue(match.groups[2]!!.value)
            val url = unescapeJsonValue(match.groups[3]!!.value)
            val date = unescapeJsonValue(match.groups[4]!!.value)

            SChapter.create().apply {
                this.name = name
                this.url = url.substringBefore("?style=paged") + chapterUrlSuffix
                date_upload = parseRelativeDate(date)
            }
        }.toList()
    }

    private fun unescapeJsonValue(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\\"", "\"")

    private val chapterRegex = Regex(
        """\{"id":\d+,"num":"([^"]*)","name":"([^"]*)","url":"([^"]*)","ago":"([^"]*)""",
    )

    override val mangaDetailsSelectorDescription = "div.summary_content div.post-content_item:has(h5:contains(Sinopsis)) div"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()
    }
}
