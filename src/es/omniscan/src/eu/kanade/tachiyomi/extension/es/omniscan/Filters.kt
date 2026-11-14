package eu.kanade.tachiyomi.extension.es.omniscan

import eu.kanade.tachiyomi.source.model.Filter

class StatusFilter : Filter.Select<String>("Estado", arrayOf("Todos", "En progreso", "Finalizado", "En pausa")) {
    val selectedValue: String
        get() = when (state) {
            1 -> "progreso"
            2 -> "finalizado"
            3 -> "pausado"
            else -> ""
        }
}

class TypeFilter : Filter.Select<String>("Tipo", arrayOf("Todos", "Manhwa", "Manhua", "Manga")) {
    val selectedValue: String
        get() = when (state) {
            1 -> "manhwa"
            2 -> "manhua"
            3 -> "manga"
            else -> ""
        }
}

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf("Recientes", "Más visitadas", "Más capítulos", "A – Z", "Z – A"),
    ) {
    val selectedValue: String
        get() = when (state) {
            1 -> "visits"
            2 -> "caps"
            3 -> "az"
            4 -> "za"
            else -> "recent"
        }
}
