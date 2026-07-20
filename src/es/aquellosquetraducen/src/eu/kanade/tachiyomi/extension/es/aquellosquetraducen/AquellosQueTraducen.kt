package eu.kanade.tachiyomi.extension.es.aquellosquetraducen

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

@Source
class AquellosQueTraducen(
    override val lang: String,
    override val id: Long,
) : HttpSource(),
    ConfigurableSource {

    override val name = "Aquellos Que Traducen"

    override val baseUrl = "https://aquellosquetraducen.com"

    override val supportsLatest = true

    private val firebaseProjectId = "aquellosquetraducen-40706"
    private val preferences: SharedPreferences = getPreferences()

    private val driveApiKey: String
        get() = preferences.getString(DRIVE_API_KEY_PREF, "").orEmpty().trim()

    private val json = Json { ignoreUnknownKeys = true }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0")

    private fun buildMangaFromFirestore(doc: FirestoreDocument): SManga? {
        val fields = doc.fields ?: return null
        val titulo = fields.titulo?.stringValue ?: return null
        val docId = doc.name?.substringAfterLast("/") ?: return null
        return SManga.create().apply {
            title = titulo
            url = docId
            thumbnail_url = fields.portadaURL?.stringValue?.let { optimizeCoverUrl(it) }
            description = fields.sinopsis?.stringValue ?: ""
            genre = fields.generos?.values?.joinToString(", ") { it.stringValue.orEmpty() }.orEmpty()
            status = parseStatus(fields.estado?.stringValue)
        }
    }

    // ──── Popular ────

    override fun popularMangaRequest(page: Int): Request {
        val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras".toHttpUrl()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val firestoreResponse = response.parseAs<FirestoreResponse>()
        val works = firestoreResponse.documents.mapNotNull { buildMangaFromFirestore(it) }
        return MangasPage(works, false)
    }

    // ──── Latest ────

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras".toHttpUrl()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val firestoreResponse = response.parseAs<FirestoreResponse>()
        val works = firestoreResponse.documents.mapNotNull { buildMangaFromFirestore(it) }
        return MangasPage(works, false)
    }

    // ──── Search ────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras".toHttpUrl()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val firestoreResponse = response.parseAs<FirestoreResponse>()
        val works = firestoreResponse.documents.mapNotNull { buildMangaFromFirestore(it) }
        return MangasPage(works, false)
    }

    // ──── Details ────

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras/${manga.url}".toHttpUrl()
        return GET(url, headers)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra.html?id=${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.parseAs<FirestoreDocument>()
        val fields = doc.fields ?: throw Exception("No se encontraron datos")

        return SManga.create().apply {
            title = fields.titulo?.stringValue ?: throw Exception("Título no encontrado")
            author = "Desconocido"
            thumbnail_url = fields.portadaURL?.stringValue?.let { optimizeCoverUrl(it) }
            description = fields.sinopsis?.stringValue ?: ""
            genre = fields.generos?.values?.joinToString(", ") { it.stringValue.orEmpty() }.orEmpty()
            status = parseStatus(fields.estado?.stringValue)
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "emision" -> SManga.ONGOING
        "finalizado" -> SManga.COMPLETED
        "pausa" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ──── Chapters ────

    override fun chapterListRequest(manga: SManga): Request {
        val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras/${manga.url}".toHttpUrl()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.parseAs<FirestoreDocument>()
        val fields = doc.fields ?: return emptyList()
        val driveFolderId = fields.driveFolderId?.stringValue ?: return emptyList()
        val driveApiKey = requireDriveApiKey()

        val driveUrl = "https://www.googleapis.com/drive/v3/files?q='$driveFolderId'+in+parents+and+trashed=false&fields=files(id,name,createdTime)&key=$driveApiKey&pageSize=1000&orderBy=name".toHttpUrl()
        val driveRequest = GET(driveUrl, headers)
        val driveResponse = client.newCall(driveRequest).execute()
        val driveData = driveResponse.parseAs<DriveResponse>(json)

        return driveData.files
            .sortedWith(
                compareByDescending<DriveFile> { it.name.extractChapterNumber() ?: -1f }
                    .thenByDescending { it.name },
            )
            .map { folder ->
                SChapter.create().apply {
                    name = "Capítulo ${folder.name}"
                    url = folder.id
                    date_upload = folder.createdTime?.let { parseDriveDate(it) } ?: 0L
                    chapter_number = folder.name.extractChapterNumber() ?: -1f
                }
            }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/lector.html?cap=${chapter.url}"

    private fun String.extractChapterNumber(): Float? {
        val cleaned = this.replace(",", ".")
            .replace("[^0-9.]".toRegex(), "")
        return cleaned.toFloatOrNull()
    }

    private fun parseDriveDate(dateStr: String): Long = try {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        formatter.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    // ──── Pages ────

    override fun pageListRequest(chapter: SChapter): Request {
        val driveApiKey = requireDriveApiKey()
        val url = "https://www.googleapis.com/drive/v3/files?q='${chapter.url}'+in+parents+and+trashed=false&fields=files(id,name,mimeType)&key=$driveApiKey&orderBy=name".toHttpUrl()
        return GET(url, headers)
    }

    private fun requireDriveApiKey(): String {
        val key = driveApiKey
        if (key.isBlank()) {
            throw Exception("Configure a Google Drive API key in the source settings")
        }
        return key
    }

    override fun pageListParse(response: Response): List<Page> {
        val driveData = response.parseAs<DriveResponse>(json)

        return driveData.files
            .filter { it.mimeType?.startsWith("image/") == true }
            .sortedBy { it.name }
            .mapIndexed { index, file ->
                Page(index, imageUrl = "https://drive.google.com/uc?export=view&id=${file.id}")
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DRIVE_API_KEY_PREF
            title = "Google Drive API key"
            summary = "Required to list chapter folders from Google Drive. Do not share this key."
            dialogTitle = "Google Drive API key"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // ──── Helpers ────

    private fun optimizeCoverUrl(url: String): String {
        val regex = Regex("[-\\w]{25,}")
        val match = regex.find(url)
        return if (match != null) {
            "https://drive.google.com/thumbnail?id=${match.value}&sz=w600"
        } else {
            url
        }
    }

    companion object {
        private const val DRIVE_API_KEY_PREF = "driveApiKey"
    }

    // ──── DTOs ────

    @Serializable
    data class FirestoreResponse(
        val documents: List<FirestoreDocument> = emptyList(),
    )

    @Serializable
    data class FirestoreDocument(
        val name: String? = null,
        val fields: WorkFields? = null,
    )

    @Serializable
    data class WorkFields(
        val titulo: FieldValue? = null,
        val portadaURL: FieldValue? = null,
        val sinopsis: FieldValue? = null,
        val estado: FieldValue? = null,
        val generos: ArrayFieldValue? = null,
        val driveFolderId: FieldValue? = null,
        val tipo: FieldValue? = null,
    )

    @Serializable
    data class FieldValue(
        val stringValue: String? = null,
        val integerValue: String? = null,
        val doubleValue: Double? = null,
    )

    @Serializable
    data class ArrayFieldValue(
        val values: List<FieldValue> = emptyList(),
    )

    @Serializable
    data class DriveResponse(
        val files: List<DriveFile> = emptyList(),
    )

    @Serializable
    data class DriveFile(
        val id: String = "",
        val name: String = "",
        val createdTime: String? = null,
        val mimeType: String? = null,
    )
}
