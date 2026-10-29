package eu.kanade.tachiyomi.extension.es.olympusscanlation

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement

class FilterManager {
    fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            Filter.Header("Los filtros no funcionan en la búsqueda por texto"),
            Filter.Separator(),
            SortFilter(),
        )

        if (data != null) {
            try {
                val dto = data.parseAs<GenresStatusesDto>(jsonInstance)
                val genres = dto.genres.map { it.name.trim() to it.id }
                val statuses = dto.statuses.map { it.name.trim() to it.id }
                filters += listOf(
                    Filter.Separator(),
                    Filter.Header("Filtrar por género"),
                    GenreFilter(genres),
                )
                filters += listOf(
                    Filter.Separator(),
                    Filter.Header("Filtrar por estado"),
                    StatusFilter(statuses),
                )
                return FilterList(filters)
            } catch (_: Exception) {
                // fall through to hint
            }
        }

        filters += listOf(
            Filter.Separator(),
            Filter.Header("Presione 'Reiniciar' para intentar cargar los filtros"),
        )
        return FilterList(filters)
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
