package eu.kanade.tachiyomi.extension.es.mantrazscan

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class GenreFilter :
    UriPartFilter(
        "Género",
        arrayOf(
            Pair("Todos", ""),
            Pair("Acción", "accion"),
            Pair("Romance", "romance"),
            Pair("Drama", "drama"),
            Pair("Fantasía", "fantasia"),
            Pair("Comedia", "comedia"),
            Pair("Aventura", "aventura"),
            Pair("Harem", "harem"),
            Pair("Ecchi", "ecchi"),
            Pair("Adulto", "adulto"),
            Pair("Maduro", "maduro"),
            Pair("Smut", "smut"),
            Pair("BL", "bl"),
            Pair("Yaoi", "yaoi"),
            Pair("Boys Love", "boys-love"),
            Pair("Shoujo", "shoujo"),
            Pair("Josei", "josei"),
            Pair("Shounen", "shounen"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Reencarnación", "reencarnacion"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Misterio", "misterio"),
            Pair("Psicológico", "psicologico"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Supervivencia", "supervivencia"),
            Pair("Tragedia", "tragedia"),
            Pair("Familia", "familia"),
            Pair("Demonios", "demonios"),
            Pair("Histórico", "historico"),
            Pair("Magia", "magia"),
            Pair("Sistema", "sistema"),
            Pair("Regresión", "regresion"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhwa 19", "manhwa-19"),
        ),
    )
