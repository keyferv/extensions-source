package eu.kanade.tachiyomi.extension.es.leercapitulo

import eu.kanade.tachiyomi.source.model.Filter

class QueryFilter : Filter.Text("Title")

class GenreFilter :
    UriPartFilter(
        "Genre",
        arrayOf(
            Pair("<Select>", ""),
            Pair("Action", "action"),
            Pair("Adult", "adult"),
            Pair("Adventure", "adventure"),
            Pair("Boys' Love", "boys-love"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Doujinshi", "doujinshi"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Girls' Love", "girls-love"),
            Pair("Hentai", "hentai"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Lolicon", "lolicon"),
            Pair("Magical Girls", "magical-girls"),
            Pair("Mature", "mature"),
            Pair("Mecha", "mecha"),
            Pair("Medical", "medical"),
            Pair("Mystery", "mystery"),
            Pair("Philosophical", "philosophical"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shotacon", "shotacon"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Smut", "smut"),
            Pair("Sports", "sports"),
            Pair("Superhero", "superhero"),
            Pair("Thriller", "thriller"),
            Pair("Tragedy", "tragedy"),
            Pair("Wuxia", "wuxia"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

class ThemeFilter :
    UriPartFilter(
        "Theme",
        arrayOf(
            Pair("<Select>", ""),
            Pair("Aliens", "aliens"),
            Pair("Animals", "animals"),
            Pair("Cooking", "cooking"),
            Pair("Cross-dressing", "cross-dressing"),
            Pair("Delinquents", "delinquents"),
            Pair("Demons", "demons"),
            Pair("Genderswap", "genderswap"),
            Pair("Ghosts", "ghosts"),
            Pair("Gyaru", "gyaru"),
            Pair("Harem", "harem"),
            Pair("Incest", "incest"),
            Pair("Loli", "loli"),
            Pair("Mafia", "mafia"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Military", "military"),
            Pair("Monster Girls", "monster-girls"),
            Pair("Monsters", "monsters"),
            Pair("Music", "music"),
            Pair("Ninja", "ninja"),
            Pair("Office Workers", "office-workers"),
            Pair("Police", "police"),
            Pair("Post-Apocalyptic", "post-apocalyptic"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Reverse Harem", "reverse-harem"),
            Pair("Samurai", "samurai"),
            Pair("School Life", "school-life"),
            Pair("Shota", "shota"),
            Pair("Supernatural", "supernatural"),
            Pair("Survival", "survival"),
            Pair("Time Travel", "time-travel"),
            Pair("Traditional Games", "traditional-games"),
            Pair("Vampires", "vampires"),
            Pair("Video Games", "video-games"),
            Pair("Villainess", "villainess"),
            Pair("Virtual Reality", "virtual-reality"),
            Pair("Zombies", "zombies"),
        ),
    )

class TypeFilter :
    UriPartFilter(
        "Type",
        arrayOf(
            Pair("<Select>", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        arrayOf(
            Pair("<Select>", ""),
            Pair("Completed", "completed"),
            Pair("Ongoing", "ongoing"),
            Pair("Paused", "paused"),
            Pair("Cancelled", "cancelled"),
        ),
    )

class SortFilter :
    UriPartFilter(
        "Sort",
        arrayOf(
            Pair("A - Z", "az"),
            Pair("Z - A", "za"),
        ),
    )

open class UriPartFilter(
    displayName: String,
    private val vals: Array<Pair<String, String>>,
) : Filter.Select<String>(
    displayName,
    vals.map { it.first }.toTypedArray(),
) {
    fun toUriPart() = vals[state].second
}
