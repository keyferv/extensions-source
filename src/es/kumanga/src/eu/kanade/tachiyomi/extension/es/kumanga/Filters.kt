package eu.kanade.tachiyomi.extension.es.kumanga

import eu.kanade.tachiyomi.source.model.Filter

class GenreFilter :
    Filter.Select<String>(
        "Género",
        GENRES.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = GENRES[state].second

    companion object {
        // Verified from detail page: Drama=10, Fantasía=13, Reencarnación=49, Romance=32, Shoujo=35
        // Remaining categories keep sequential fallback; unmapped genres are offered as Todos-only
        // to avoid sending wrong category ids.
        private val GENRES = arrayOf(
            "Todos" to "",
            "Drama" to "10",
            "Fantasía" to "13",
            "Reencarnación" to "49",
            "Romance" to "32",
            "Shoujo" to "35",
        )
    }
}

class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        TYPES.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = TYPES[state].second

    companion object {
        private val TYPES = arrayOf(
            "Todos" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "One shot" to "one_shot",
            "Doujinshi" to "doujinshi",
        )
    }
}

class YearFilter :
    Filter.Select<String>(
        "Año",
        YEARS.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = YEARS[state].second

    companion object {
        private val YEARS = run {
            val base = mutableListOf("Todos" to "")
            for (y in 2026 downTo 1980) base += y.toString() to y.toString()
            base.toTypedArray()
        }
    }
}

class StatusFilter :
    Filter.Select<String>(
        "Estado",
        STATUSES.map { it.first }.toTypedArray(),
    ) {
    fun toUriPart(): String = STATUSES[state].second

    companion object {
        private val STATUSES = arrayOf(
            "Todos" to "",
            "Activo" to "activo",
            "Finalizado" to "finalizado",
            "Inconcluso" to "inconcluso",
        )
    }
}
