package eu.kanade.tachiyomi.extension.es.hadesnofansub

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale

class HadesNoFansub : Madara(
    "Hades no Fansub",
    "https://lectorhades.latamtoon.com/",
    "es",
    dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale("es")),
) {
    override val useNewChapterEndpoint = true

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val mangaDetailsSelectorStatus = "div.summary_content > div.post-content div.post-content_item:has(div.summary-heading:contains(Status)) div.summary-content"

    override val mangaDetailsSelectorTag = "div.tags-content a.notUsed" // Site uses this for the scanlator

    // Override latest updates request to use the new /latest/ endpoint with pagination
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/latest/"
        } else {
            "$baseUrl/latest/page/$page/"
        }
        return GET(url, headers)
    }

    // Override selector to match the new HTML structure
    override fun popularMangaSelector() = "div.page-listing-item div.page-item-detail"

    override fun latestUpdatesSelector() = popularMangaSelector()

    // Override page list selector to match the chapter reading structure
    override val pageListParseSelector = "div.reading-content div.page-break img.wp-manga-chapter-img"
}
