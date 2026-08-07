package eu.kanade.tachiyomi.extension.es.mhscans

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class MHScans :
    Madara(),
    ConfigurableSource {
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    override val dateFormat = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es"))

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(1, 3.seconds) { it.host == baseUrl.toHttpUrl().host }
        .build()

    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Always
    override val sendViewCount = false

    private val defaultBaseUrl = "https://mhscans.com"
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun pageListParse(document: Document): List<Page> {
        super.pageListParse(document).also {
            if (it.isNotEmpty()) return it
        }

        document.selectFirst("form#rk_madara_redirect[method=post]")?.let { form ->
            val url = form.attr("action")
            val headers = headersBuilder().set("Referer", document.location()).build()
            val body = FormBody.Builder()
            form.select("input").forEach {
                body.add(it.attr("name"), it.attr("value"))
            }
            return pageListParse(client.newCall(POST(url, headers, body.build())).execute().asJsoup())
        }

        return document.select("div.rk-page-wrap img, img.rk-img").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("abs:src").ifEmpty { img.attr("abs:data-src") })
        }
    }

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
