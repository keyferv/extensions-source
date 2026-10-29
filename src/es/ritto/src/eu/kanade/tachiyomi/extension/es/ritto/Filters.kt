package eu.kanade.tachiyomi.extension.es.ritto

import eu.kanade.tachiyomi.source.model.Filter

class TypeFilter :
    Filter.Select<String>(
        "Tipo",
        arrayOf("Todos", "Manga", "Manhua", "Manwha", "Novela", "One shot", "Doujinshi", "Oel", "Otro"),
    ) {
    val selectedValue: String
        get() = when (state) {
            1 -> "MANGA"
            2 -> "MANHUA"
            3 -> "MANHWA"
            4 -> "NOVELA"
            5 -> "ONE_SHOT"
            6 -> "DOUJINSHI"
            7 -> "OEL"
            8 -> "OTRO"
            else -> ""
        }
}

class StatusFilter :
    Filter.Select<String>(
        "Estado",
        arrayOf("Todos", "En emisión", "Finalizado", "Pausado", "Cancelado"),
    ) {
    val selectedValue: String
        get() = when (state) {
            1 -> "EN_EMISION"
            2 -> "FINALIZADO"
            3 -> "PAUSADO"
            4 -> "CANCELADO"
            else -> ""
        }
}

class GenreFilter :
    Filter.Select<String>(
        "Género",
        arrayOf(
            "Todos", "Acción", "Animación", "Apocalíptico", "Artes Marciales", "Aventura",
            "Boys Love", "Ciberpunk", "Ciencia Ficción", "Comedia", "Crimen", "Demonios",
            "Deporte", "Drama", "Ecchi", "Extranjero", "Familia", "Fantasia", "Género Bender",
            "Girls Love", "Gore", "Guerra", "Harem", "Historia", "Horror", "Magia", "Mecha",
            "Militar", "Misterio", "Musica", "Niños", "Oeste", "Original", "Parodia",
            "Policiaco", "Psicológico", "Realidad", "Realidad Virtual", "Recuentos de la vida",
            "Reencarnación", "Romance", "Samurái", "Sobrenatural", "Superpoderes",
            "Supervivencia", "Telenovela", "Thriller", "Tragedia", "Traps", "Vampiros",
            "Vida Escolar", "Yaoi", "Yuri",
        ),
    ) {
    private val filterValues = arrayOf(
        "", "accion", "animacion", "apocaliptico", "artes-marciales", "aventura",
        "boys-love", "ciberpunk", "ciencia-ficcion", "comedia", "crimen", "demonios",
        "deporte", "drama", "ecchi", "extranjero", "familia", "fantasia", "genero-bender",
        "girls-love", "gore", "guerra", "harem", "historia", "horror", "magia", "mecha",
        "militar", "misterio", "musica", "ninos", "oeste", "original", "parodia",
        "policiaco", "psicologico", "realidad", "realidad-virtual", "recuentos-de-la-vida",
        "reencarnacion", "romance", "samurai", "sobrenatural", "superpoderes",
        "supervivencia", "telenovela", "thriller", "tragedia", "traps", "vampiros",
        "vida-escolar", "yaoi", "yuri",
    )

    val selectedValue: String
        get() = filterValues[state]
}

class CategoryFilter :
    Filter.Select<String>(
        "Categoría",
        arrayOf("Todas", "Originales", "Seinen", "Shoujo", "Shounen", "Josei", "Kodomo"),
    ) {
    private val filterValues = arrayOf("", "originales", "seinen", "shoujo", "shounen", "josei", "kodomo")

    val selectedValue: String
        get() = filterValues[state]
}

class SortFilter :
    Filter.Select<String>(
        "Ordenar por",
        arrayOf("Más recientes", "Populares", "Más favoritas", "Título A-Z"),
    ) {
    private val filterValues = arrayOf("reciente", "vistas", "favoritos", "titulo")

    val selectedValue: String
        get() = filterValues[state]
}
