package eu.kanade.tachiyomi.extension.es.omniscan

import eu.kanade.tachiyomi.source.model.Filter

class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "En emisión", "Finalizado")) {
    val selectedValue: String
        get() = when (state) {
            1 -> "En emisión"
            2 -> "Finalizado"
            else -> ""
        }
}

class SortFilter : Filter.Select<String>("Sort by", arrayOf("Recent", "Most visited", "A–Z")) {
    val selectedValue: String
        get() = when (state) {
            1 -> "visits"
            2 -> "az"
            else -> "recent"
        }
}
