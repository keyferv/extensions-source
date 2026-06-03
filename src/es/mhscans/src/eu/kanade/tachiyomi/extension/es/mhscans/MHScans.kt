package eu.kanade.tachiyomi.extension.es.mhscans

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MHScans :
    Madara(
        "MHScans",
        "https://mhscans.com",
        "es",
        dateFormat = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es")),
    ),
    ConfigurableSource {
    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(3, 1, TimeUnit.SECONDS)
        .addInterceptor(::imageRetryInterceptor)
        .build()

    private fun imageRetryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isSuccessful || !request.url.host.contains("wsrv.nl")) return response
        response.close()
        var retries = 0
        while (retries < MAX_RETRIES) {
            retries++
            Thread.sleep(RETRY_DELAY_MS)
            val retryResponse = chain.proceed(request)
            if (retryResponse.isSuccessful) return retryResponse
            retryResponse.close()
        }
        return chain.proceed(request)
    }

    override fun processThumbnail(url: String?, fromSearch: Boolean): String? {
        if (url == null) return null
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!parsed.host.contains("wsrv.nl")) return url
        return parsed.newBuilder()
            .setQueryParameter("output", "webp")
            .setQueryParameter("q", "80")
            .setQueryParameter("default", "1")
            .build()
            .toString()
    }

    override val useNewChapterEndpoint = true
    override val useLoadMoreRequest = LoadMoreStrategy.Always

    private val defaultBaseUrl = "https://mhscans.com"
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
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 1000L
    }
}
