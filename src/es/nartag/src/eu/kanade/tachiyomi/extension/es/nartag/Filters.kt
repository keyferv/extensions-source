package eu.kanade.tachiyomi.extension.es.nartag

import eu.kanade.tachiyomi.source.model.Filter

class TypeFilter : Filter.Select<String>("Tipo", types)

class StatusFilter : Filter.Select<String>("Estado", statuses)

class SortFilter : Filter.Select<String>("Ordenar", sortOptions.map { it.name }.toTypedArray())

class GenreFilter : Filter.Select<String>("Géneros", genres)

data class SortOption(val name: String, val value: String)

val sortOptions = arrayOf(
    SortOption("Más reciente", "latest"),
    SortOption("Actualizado", "updated"),
    SortOption("Más visto", "views"),
    SortOption("Mejor valorado", "rating"),
    SortOption("A-Z", "title"),
)

val types = arrayOf("Todos", "Manga", "Manhwa", "Manhua", "Novel", "Other")

val statuses = arrayOf("Todos", "Ongoing", "Completed", "Hiatus", "Cancelled")

val genres = arrayOf(
    "Todos",
    "Acción",
    "Adventure",
    "Aventura",
    "ciencia ficción",
    "Comedia",
    "Cultivación",
    "Drama",
    "Fantasia",
    "Fantasía",
    "Harem",
    "Love",
    "Manhua",
    "Reencarnacion",
    "Romance",
    "Sistema",
    "Supernatural",
    "+15",
)
