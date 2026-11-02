package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.Filter

class Category(name: String, val bit: Int, default: Boolean = true) : Filter.CheckBox(name, default)

class CategoryFilter(categories: List<Category>) : Filter.Group<Category>("Categories", categories)

class RatingFilter :
    Filter.Select<String>(
        "Minimum Rating",
        arrayOf("Any Rating", "2 Stars", "3 Stars", "4 Stars", "5 Stars"),
    ) {
    fun toValue(): String? = when (state) {
        1 -> "2"
        2 -> "3"
        3 -> "4"
        4 -> "5"
        else -> null
    }
}

class AdvancedOption(name: String, val param: String) : Filter.CheckBox(name, false)

class AdvancedOptionsFilter(options: List<AdvancedOption>) : Filter.Group<AdvancedOption>("Advanced Options", options)

class PageMinFilter : Filter.Text("Minimum Pages")
class PageMaxFilter : Filter.Text("Maximum Pages")

fun getCategoriesList(): List<Category> = listOf(
    Category("Doujinshi", 2),
    Category("Manga", 4),
    Category("Artist CG", 8),
    Category("Game CG", 16),
    Category("Western", 512),
    Category("Non-H", 256),
    Category("Image Set", 32),
    Category("Cosplay", 64),
    Category("Asian Porn", 128),
    Category("Misc", 1),
)

fun getAdvancedOptionsList(): List<AdvancedOption> = listOf(
    AdvancedOption("Browse Expunged Galleries", "f_sh"),
    AdvancedOption("Require Gallery Torrent", "f_sto"),
)
