package eu.kanade.tachiyomi.extension.all.openhentai

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        sortOptions.map { it.first }.toTypedArray(),
    ) {
    fun toValue(): String = sortOptions[state].second

    companion object {
        val sortOptions = listOf(
            Pair("Más recientes", "recent_to_old"),
            Pair("Más antiguos", "old_to_recent"),
            Pair("Más vistos", "decrease_view"),
            Pair("Menos vistos", "increase_view"),
            Pair("Más páginas", "decrease_count_page"),
            Pair("Menos páginas", "increase_count_page"),
        )
    }
}

class AuthorFilter : Filter.Text("Autor")

class TagsFilter : Filter.Text("Etiquetas")

class LanguageFilter :
    Filter.Select<String>(
        "Idioma",
        languageOptions.map { it.first }.toTypedArray(),
    ) {
    fun toValue(): String = languageOptions[state].second

    companion object {
        val languageOptions = listOf(
            Pair("Por defecto", ""),
            Pair("Todos", "all"),
            Pair("Español", "spanish"),
            Pair("Inglés", "english"),
            Pair("Japonés", "japanese"),
            Pair("Chino", "chinese"),
            Pair("Francés", "french"),
            Pair("Alemán", "german"),
            Pair("Portugués", "portuguese"),
            Pair("Ruso", "russian"),
            Pair("Italiano", "italian"),
            Pair("Coreano", "korean"),
            Pair("Vietnamita", "vietnamese"),
            Pair("Indonesio", "indonesian"),
        )
    }
}
