package eu.kanade.tachiyomi.extension.es.aquellosquetraducen

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

@Source
abstract class AquellosQueTraducen :
    KeiSource(),
    ConfigurableSource {

    private val firebaseProjectId = "aquellosquetraducen-40706"
    private val preferences by getPreferencesLazy()

    private val driveApiKey: String
        get() = preferences.getString(DRIVE_API_KEY_PREF, "").orEmpty().trim()

    @Volatile
    private var cachedDriveApiKey: String? = null

    private fun buildMangaFromFirestore(doc: FirestoreDocument): SManga? {
        val fields = doc.fields ?: return null
        val titulo = fields.titulo?.stringValue ?: return null
        val docId = doc.name?.substringAfterLast("/") ?: return null
        return SManga.create().apply {
            title = titulo
            url = docId
            thumbnail_url = fields.portadaURL?.stringValue?.let { optimizeCoverUrl(it) }
            description = fields.sinopsis?.stringValue ?: ""
            genre = fields.generos?.values?.joinToString { it.stringValue.orEmpty() }.orEmpty()
            status = parseStatus(fields.estado?.stringValue)
        }
    }

    private suspend fun fetchCatalog(): MangasPage {
        val url = "https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras".toHttpUrl()
        val firestoreResponse = client.get(url).parseAs<FirestoreResponse>()
        val works = firestoreResponse.documents.mapNotNull { buildMangaFromFirestore(it) }
        return MangasPage(works, false)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchCatalog()

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchCatalog()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchCatalog()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.lastOrNull() != "obra.html") return null
        val id = url.queryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
        val doc = client.get("https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras/$id".toHttpUrl()).parseAs<FirestoreDocument>()
        return buildMangaFromFirestore(doc)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra.html?id=${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get("https://firestore.googleapis.com/v1/projects/$firebaseProjectId/databases/(default)/documents/obras/${manga.url}".toHttpUrl()).parseAs<FirestoreDocument>()
        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(doc) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: FirestoreDocument): SManga {
        val fields = doc.fields ?: throw Exception("No se encontraron datos")

        return SManga.create().apply {
            title = fields.titulo?.stringValue ?: throw Exception("Título no encontrado")
            author = "Desconocido"
            thumbnail_url = fields.portadaURL?.stringValue?.let { optimizeCoverUrl(it) }
            description = fields.sinopsis?.stringValue ?: ""
            genre = fields.generos?.values?.joinToString { it.stringValue.orEmpty() }.orEmpty()
            status = parseStatus(fields.estado?.stringValue)
        }
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
        "emision" -> SManga.ONGOING
        "finalizado" -> SManga.COMPLETED
        "pausa" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private suspend fun fetchChapterList(doc: FirestoreDocument): List<SChapter> {
        val fields = doc.fields ?: return emptyList()
        val driveFolderId = fields.driveFolderId?.stringValue ?: return emptyList()
        val driveApiKey = requireDriveApiKey()

        val driveUrl = "https://www.googleapis.com/drive/v3/files?q='$driveFolderId'+in+parents+and+trashed=false&fields=files(id,name,createdTime)&key=$driveApiKey&pageSize=1000&orderBy=name".toHttpUrl()
        val driveData = client.get(driveUrl).parseAs<DriveResponse>()

        return driveData.files
            .sortedWith(
                compareByDescending<DriveFile> { it.name.extractChapterNumber() ?: -1f }
                    .thenByDescending { it.name },
            )
            .map { folder ->
                SChapter.create().apply {
                    name = "Capítulo ${folder.name}"
                    url = folder.id
                    date_upload = folder.createdTime?.let { Instant.tryParse(it) } ?: 0L
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val driveApiKey = requireDriveApiKey()
        val url = "https://www.googleapis.com/drive/v3/files?q='${chapter.url}'+in+parents+and+trashed=false&fields=files(id,name,mimeType)&key=$driveApiKey&orderBy=name".toHttpUrl()
        val driveData = client.get(url).parseAs<DriveResponse>()

        return driveData.files
            .filter { it.mimeType?.startsWith("image/") == true }
            .sortedBy { it.name }
            .mapIndexed { index, file ->
                Page(index, imageUrl = "https://drive.google.com/uc?export=view&id=${file.id}")
            }
    }

    private suspend fun requireDriveApiKey(): String {
        val key = driveApiKey.ifBlank { cachedDriveApiKey.orEmpty() }.ifBlank { fetchDriveApiKeyFromSite() }
        if (key.isBlank()) {
            throw Exception("Google Drive API key not found on the site. Configure one in the source settings")
        }
        return key
    }

    private suspend fun fetchDriveApiKeyFromSite(): String {
        val body = client.get(baseUrl).use { it.body.string() }
        return GOOGLE_API_KEY_REGEX.find(body)?.value.orEmpty()
            .also { key -> cachedDriveApiKey = key.ifBlank { null } }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DRIVE_API_KEY_PREF
            title = "Google Drive API key override"
            summary = "Optional. Leave empty to use the public key exposed by the site at runtime."
            dialogTitle = "Google Drive API key override"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

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
        private val GOOGLE_API_KEY_REGEX = Regex("AIza[0-9A-Za-z_-]{35}")
    }

    @Serializable
    class FirestoreResponse(
        val documents: List<FirestoreDocument> = emptyList(),
    )

    @Serializable
    class FirestoreDocument(
        val name: String? = null,
        val fields: WorkFields? = null,
    )

    @Serializable
    class WorkFields(
        val titulo: FieldValue? = null,
        val portadaURL: FieldValue? = null,
        val sinopsis: FieldValue? = null,
        val estado: FieldValue? = null,
        val generos: ArrayFieldValue? = null,
        val driveFolderId: FieldValue? = null,
    )

    @Serializable
    class FieldValue(
        val stringValue: String? = null,
    )

    @Serializable
    class ArrayFieldValue(
        val values: List<FieldValue> = emptyList(),
    )

    @Serializable
    class DriveResponse(
        val files: List<DriveFile> = emptyList(),
    )

    @Serializable
    class DriveFile(
        val id: String = "",
        val name: String = "",
        val createdTime: String? = null,
        val mimeType: String? = null,
    )
}
