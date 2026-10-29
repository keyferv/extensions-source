package eu.kanade.tachiyomi.extension.es.zonatmo

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf("Me gusta", "Alfabético", "Puntuación", "Creación", "Fecha estreno", "Núm. Capítulos"),
    )

class SortOrderFilter :
    Filter.Select<String>(
        "Orden",
        arrayOf("Descendente", "Ascendente"),
    )

val TYPES = arrayOf(
    "Manga" to "manga",
    "Manhua" to "manhua",
    "Manhwa" to "manhwa",
    "Webtoon" to "webtoon",
    "Novela" to "novela",
    "Comic" to "comic",
    "One shot" to "one_shot",
    "Doujinshi" to "doujinshi",
    "OEL" to "oel",
)

class TypeFilter :
    Filter.Group<CheckBoxFilter>(
        "Tipo",
        TYPES.map { CheckBoxFilter(it.first, it.second) },
    )

val DEMOGRAPHIES = arrayOf(
    "Seinen" to "seinen",
    "Shoujo" to "shoujo",
    "Shounen" to "shounen",
    "Josei" to "josei",
    "Kodomo" to "kodomo",
)

class DemographyFilter :
    Filter.Group<CheckBoxFilter>(
        "Demografía",
        DEMOGRAPHIES.map { CheckBoxFilter(it.first, it.second) },
    )

val STATUSES = arrayOf(
    "En emisión" to "1",
    "Completado" to "2",
    "Finalizado" to "3",
    "En pausa" to "4",
    "Cancelado" to "5",
)

class StatusFilter :
    Filter.Group<CheckBoxFilter>(
        "Estado",
        STATUSES.map { CheckBoxFilter(it.first, it.second) },
    )

val GENRES = arrayOf(
    "+18" to "27",
    "Acción" to "1",
    "Artes Marciales" to "26",
    "Aventura" to "3",
    "Boys Love" to "30",
    "Ciencia Ficción" to "21",
    "Comedia" to "4",
    "Crimen" to "41",
    "Demonios" to "88",
    "Deporte" to "37",
    "Drama" to "15",
    "Ecchi" to "32",
    "Familia" to "1027",
    "Fantasía" to "5",
    "Girls Love" to "22",
    "Gore" to "181",
    "Guerra" to "1109",
    "Gender Bender" to "183",
    "Harem" to "8",
    "Historia" to "81",
    "Horror" to "82",
    "Isekai" to "89",
    "Josei" to "100",
    "Magia" to "6",
    "Mecha" to "144",
    "Militar" to "342",
    "Misterio" to "40",
    "Musica" to "403",
    "Psicológico" to "36",
    "Reencarnación" to "60",
    "Romance" to "16",
    "Samurái" to "99",
    "Seinen" to "101",
    "Shoujo" to "102",
    "Shounen" to "103",
    "Sobrenatural" to "12",
    "Superpoderes" to "116",
    "Supervivencia" to "112",
    "Telenovela" to "470",
    "Thriller" to "49",
    "Tragedia" to "46",
    "Vampiros" to "345",
    "Vida Escolar" to "23",
    "Yaoi" to "31",
    "Adulto" to "33",
    "Maduro" to "34",
)

class GenreFilter :
    Filter.Group<CheckBoxFilter>(
        "Géneros",
        GENRES.map { CheckBoxFilter(it.first, it.second) },
    )

class CheckBoxFilter(name: String, val value: String) : Filter.CheckBox(name)
