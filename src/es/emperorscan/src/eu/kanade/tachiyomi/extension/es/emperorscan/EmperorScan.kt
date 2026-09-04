package eu.kanade.tachiyomi.extension.es.emperorscan

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.lib.randomua.addRandomUAPreference
import keiyoushi.lib.randomua.setRandomUserAgent
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class EmperorScan :
    MadaraNoAjax(),
    ConfigurableSource {

    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.forLanguageTag("es"))

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2) { it.host == baseUrl.toHttpUrl().host }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = setRandomUserAgent()

    override fun getMangaUrl(manga: SManga): String = memoPath(manga)
        ?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
        ?: manga.url.takeIf { !it.all(Char::isDigit) }?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
        ?: "$baseUrl/?p=${mangaId(manga) ?: manga.url}"

    override fun archiveSelector() = "div#mkAgrid > a.acard"

    override fun parseArchive(document: Document): List<SManga> = document.select(archiveSelector()).mapNotNull { element ->
        val href = element.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val path = href.toHttpUrl().encodedPath
        SManga.create().apply {
            setUrlWithoutDomain(href)
            title = element.selectFirst("div.ac-t")?.ownText()?.takeIf(String::isNotBlank)
                ?: element.attr("title").takeIf(String::isNotBlank)
                ?: element.text().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            element.selectFirst("img")?.let { img ->
                thumbnail_url = processThumbnail(imageFromElement(img), true)
            }
            memo = mangaMemo(path, emptyList())
        }
    }

    override val mangaDetailsSelectorTitle = "div.hcol > .htitle"
    override val mangaDetailsSelectorStatus = "div.hcol > .htags > .htag--status"
    override val mangaDetailsSelectorDescription = "div#syn > p"
    override val mangaDetailsSelectorThumbnail = "div.hposter__card > img"
    override val mangaDetailsSelectorGenre = "div.hcol > .hchips--genres > a.chip"
    override val mangaDetailsSelectorTag = "div.hcol > .hchips--tags > a.chip"

    override fun parseDetails(document: Document, id: String, preserveUrl: String?): SManga {
        val manga = super.parseDetails(document, id, preserveUrl)

        manga.description = manga.description?.replace("HAZ CLICK AQUÍ PARA UNIRTE A NUESTRO DISCORD", "", ignoreCase = false)?.trim()

        val removePremium = preferences.getBoolean(REMOVE_PREMIUM_CHAPTERS, REMOVE_PREMIUM_CHAPTERS_DEFAULT)
        if (removePremium && !manga.genre.isNullOrEmpty()) {
            val allCategories = manga.genre!!.split(",").map { it.trim() }

            val filteredCategories = allCategories.filterNot { item ->
                item.contains("Vip", ignoreCase = true) ||
                    item.contains("Premium", ignoreCase = true) ||
                    item.contains("Emperor scan", ignoreCase = true)
            }

            manga.genre = filteredCategories.joinToString(", ")
        }

        return manga
    }

    override fun parseChapterList(document: Document, mangaPath: String): List<SChapter> {
        val scriptData = document.selectFirst("script#mk-chapters-data")?.data()?.takeIf(String::isNotBlank)
            ?: return super.parseChapterList(document, mangaPath)
        val dto = scriptData.parseAs<ChapterListDto>()

        val removePremium = preferences.getBoolean(REMOVE_PREMIUM_CHAPTERS, REMOVE_PREMIUM_CHAPTERS_DEFAULT)
        val chapters = dto.items

        val filteredChapters = if (removePremium) {
            chapters.filterNot { chapter ->
                chapter.name.contains("Vip", ignoreCase = true) ||
                    chapter.name.contains("Soberano", ignoreCase = true) ||
                    chapter.name.contains("Premium", ignoreCase = true) ||
                    chapter.url.contains("/membership-levels/", ignoreCase = true) ||
                    chapter.st.contains("locked", ignoreCase = true)
            }
        } else {
            chapters
        }

        return filteredChapters.map { chapterDto ->
            SChapter.create().apply {
                setUrlWithoutDomain(chapterDto.url)
                name = chapterDto.name
                date_upload = parseChapterDate(chapterDto.ago)
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        if (chapter.url.contains('/')) {
            return baseUrl.toHttpUrl().resolve(chapter.url)?.toString() ?: baseUrl + chapter.url
        }
        return super.getChapterUrl(chapter)
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addRandomUAPreference()

        SwitchPreferenceCompat(screen.context).apply {
            key = REMOVE_PREMIUM_CHAPTERS
            title = "Filtrar capítulos VIP"
            summary = "Oculta automáticamente los capítulos VIP"
            setDefaultValue(REMOVE_PREMIUM_CHAPTERS_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Para aplicar los cambios, actualiza la lista de capítulos", Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val REMOVE_PREMIUM_CHAPTERS = "removePremiumChapters"
        private const val REMOVE_PREMIUM_CHAPTERS_DEFAULT = true
    }
}
