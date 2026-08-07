package eu.kanade.tachiyomi.extension.es.olympusscanlation

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import kotlin.concurrent.thread

class FilterManager(
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
) {
    private val json: Json by injectLazy()

    private var genresList: List<Pair<String, Int>> = emptyList()
    private var statusesList: List<Pair<String, Int>> = emptyList()
    private var fetchFiltersAttempts = 0
    private var filtersState = FiltersState.NOT_FETCHED

    private enum class FiltersState { NOT_FETCHED, FETCHING, FETCHED }

    fun getFilterList(
        headers: Map<String, String>,
        apiBaseUrl: String,
    ): FilterList {
        fetchFilters(headers, apiBaseUrl)
        val filters =
            mutableListOf<Filter<*>>(
                Filter.Header("Los filtros no funcionan en la búsqueda por texto"),
                Filter.Separator(),
                SortFilter(),
            )

        if (filtersState == FiltersState.FETCHED) {
            filters +=
                listOf(
                    Filter.Separator(),
                    Filter.Header("Filtrar por género"),
                    GenreFilter(genresList),
                )

            filters +=
                listOf(
                    Filter.Separator(),
                    Filter.Header("Filtrar por estado"),
                    StatusFilter(statusesList),
                )
        } else {
            filters +=
                listOf(
                    Filter.Separator(),
                    Filter.Header("Presione 'Reiniciar' para intentar cargar los filtros"),
                )
        }

        return FilterList(filters)
    }

    private fun fetchFilters(
        headers: Map<String, String>,
        apiBaseUrl: String,
    ) {
        if (filtersState != FiltersState.NOT_FETCHED || fetchFiltersAttempts >= 3) return
        filtersState = FiltersState.FETCHING
        fetchFiltersAttempts++
        thread {
            try {
                val request =
                    Request
                        .Builder()
                        .url("$apiBaseUrl/api/genres-statuses")
                        .headers(
                            okhttp3.Headers
                                .Builder()
                                .apply { headers.forEach { (k, v) -> add(k, v) } }
                                .build(),
                        ).build()
                val response = client.newCall(request).execute()
                val filters = json.decodeFromString<GenresStatusesDto>(response.body.string())

                genresList = filters.genres.map { it.name.trim() to it.id }
                statusesList = filters.statuses.map { it.name.trim() to it.id }

                filtersState = FiltersState.FETCHED
            } catch (e: Throwable) {
                filtersState = FiltersState.NOT_FETCHED
            }
        }
    }

    fun setupPreferenceScreen(
        screen: PreferenceScreen,
        defaultBaseUrl: String,
    ) {
        CheckBoxPreference(screen.context)
            .apply {
                key = FETCH_DOMAIN_PREF
                title = FETCH_DOMAIN_PREF_TITLE
                summary = FETCH_DOMAIN_PREF_SUMMARY
                setDefaultValue(FETCH_DOMAIN_PREF_DEFAULT)
            }.also { screen.addPreference(it) }

        EditTextPreference(screen.context)
            .apply {
                key = BASE_URL_PREF
                title = BASE_URL_PREF_TITLE
                summary = BASE_URL_PREF_SUMMARY
                dialogTitle = BASE_URL_PREF_TITLE
                dialogMessage = "URL por defecto:\n$defaultBaseUrl"
                setDefaultValue(defaultBaseUrl)
                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(screen.context, RESTART_APP_MESSAGE, Toast.LENGTH_LONG).show()
                    true
                }
            }.also { screen.addPreference(it) }
    }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Editar URL de la fuente"
        private const val BASE_URL_PREF_SUMMARY = "Para uso temporal, si la extensión se actualiza se perderá el cambio."
        private const val RESTART_APP_MESSAGE = "Reinicie la aplicación para aplicar los cambios"

        private const val FETCH_DOMAIN_PREF = "fetchDomain"
        private const val FETCH_DOMAIN_PREF_TITLE = "Buscar dominio automáticamente"
        private const val FETCH_DOMAIN_PREF_SUMMARY = "Intenta buscar el dominio automáticamente al abrir la fuente."
        private const val FETCH_DOMAIN_PREF_DEFAULT = true
    }
}

class SortFilter :
    Filter.Sort(
        "Ordenar",
        arrayOf("Alfabético"),
        Filter.Sort.Selection(0, false),
    )

class GenreFilter(
    genres: List<Pair<String, Int>>,
) : UriPartFilter(
    "Género",
    arrayOf(
        Pair("Todos", 9999),
        *genres.toTypedArray(),
    ),
)

class StatusFilter(
    statuses: List<Pair<String, Int>>,
) : UriPartFilter(
    "Estado",
    arrayOf(
        Pair("Todos", 9999),
        *statuses.toTypedArray(),
    ),
)

open class UriPartFilter(
    displayName: String,
    private val vals: Array<Pair<String, Int>>,
) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}
