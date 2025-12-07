package eu.kanade.tachiyomi.extension.es.mhscans

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MHScans :
    Madara(
        "MHScans",
        "https://curiosidadtop.com/",
        "es",
        dateFormat = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es")),
    ),
    ConfigurableSource {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(1, 3, TimeUnit.SECONDS)
        .build()

    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val mangaSubString = "series"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = super.chapterFromElement(element)

        // Detectar si es premium
        if (element.hasClass("premium") || element.selectFirst(".premium-icon") != null) {
            chapter.name = "🔒 ${chapter.name}" // Indicador visual
        }

        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        // Detectar si el capítulo está bloqueado
        val isLocked = document.selectFirst(".chapter-locked, .premium-required, .taels-required") != null

        if (isLocked) {
            throw Exception("⚠️ Capítulo premium - Requiere Taels. Desbloquéalo en el WebView.")
        }

        return super.pageListParse(document)
    }

    private val defaultBaseUrl = "https://curiosidadtop.com/"
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Editar URL de la fuente"
            summary = "Para uso temporal, si la extensión se actualiza se perderá el cambio."
            dialogTitle = "Editar URL de la fuente"
            dialogMessage = "URL por defecto:\n$defaultBaseUrl"
            setDefaultValue(defaultBaseUrl)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Reinicie la aplicación para aplicar los cambios", Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
    }
}
