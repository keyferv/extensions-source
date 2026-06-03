package eu.kanade.tachiyomi.extension.es.jeazscans

import android.util.Base64
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ApiLectorResponse(
    val success: Boolean = false,
    @SerialName("manga_titulo") val mangaTitulo: String = "",
    @SerialName("manga_slug") val mangaSlug: String = "",
    @SerialName("manga_portada") val mangaPortada: String = "",
    @SerialName("manga_tipo") val mangaTipo: String = "",
    @SerialName("cap_numero") val capNumero: String = "",
    val paginas: List<ApiLectorPage> = emptyList(),
    @SerialName("total_paginas") val totalPaginas: Int = 0,
    val anterior: String? = null,
    val siguiente: String? = null,
)

@Serializable
class ApiLectorPage(
    val orden: Int,
    @SerialName("data_verify") val dataVerify: String,
) {
    fun decodeImageUrl(): String {
        val decoded = Base64.decode(dataVerify, Base64.DEFAULT)
        return String(decoded, Charsets.UTF_8).reversed()
    }
}

@Serializable
class SearchResponseItem(
    private val id: Int,
    private val titulo: String,
    private val portada: String?,
    private val tipo: String? = null,
) {
    fun toSManga(baseUrl: String): SManga? {
        if (id == -1 || titulo.isBlank()) return null
        return SManga.create().apply {
            url = "/manga.php?id=$id"
            title = titulo
            if (!portada.isNullOrBlank()) {
                thumbnail_url = if (portada.startsWith("http")) portada else "$baseUrl/${portada.trimStart('/')}"
            }
        }
    }
}
