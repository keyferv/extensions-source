package eu.kanade.tachiyomi.extension.es.leercapitulo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Dto(
    val name: String,
    val uri: String,
    @SerialName("cover_uri") val coverUri: String,
)
