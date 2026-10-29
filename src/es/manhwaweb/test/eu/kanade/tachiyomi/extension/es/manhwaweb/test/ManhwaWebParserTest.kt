package eu.kanade.tachiyomi.extension.es.manhwaweb.test

import eu.kanade.tachiyomi.extension.es.manhwaweb.ComicDetailsDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Focused regression tests for the optional-metadata contract.
 *
 * The backend omits `_extras` for authorless titles and stores chapter links in
 * `chapters[].versions[]` instead of top-level `link`/`link_raw`/`create`.
 * Before the fix these shapes threw `MissingFieldException` during decode, so
 * each test pins a previously-crashing payload shape. Fixtures are minimal
 * hand-written shapes of those payloads, not live dumps.
 *
 * DTO-to-model mapping (`toSManga`, chapter expansion) cannot run on plain JVM
 * unit tests because `SManga.create()` is an Android stub (`Stub!`); that
 * behavior is covered by live verification instead
 * (see odd/tasks/manhwaweb-optional-metadata.md).
 *
 * Run: ./gradlew :src:es:manhwaweb:testDebugUnitTest --tests "*ManhwaWebParserTest*"
 */
class ManhwaWebParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun authorlessDetailsDecode() {
        val payload = """
        {
          "_id": "caballero-del-equilibrio_1789948355579",
          "real_id": "caballero-del-equilibrio_1789948355579",
          "name_esp": "Caballero del Equilibrio",
          "_sinopsis": "Sinopsis breve",
          "_status": "publicandose",
          "_imagen": "https://img1mw.xyz/cover.webp",
          "_categoris": [{"3": "Accion"}],
          "autor": [],
          "artista": [],
          "others_name": [],
          "chapters": []
        }
        """.trimIndent()

        json.decodeFromString<ComicDetailsDto>(payload)
    }

    @Test
    fun legacyDetailsDecode() {
        val payload = """
        {
          "_id": "gon-no-ry_1772942862926",
          "real_id": "gon-no-ry_1772942862926",
          "name_esp": "Gon no Ry",
          "_name": "Gon no Ryū",
          "_sinopsis": "Sinopsis breve",
          "_status": "finalizado",
          "_imagen": "https://img1mw.xyz/cover.webp",
          "_categoris": [{"3": "Accion"}],
          "_extras": {"autores": ["Togame"]},
          "chapters": []
        }
        """.trimIndent()

        json.decodeFromString<ComicDetailsDto>(payload)
    }

    @Test
    fun emptyAuthorsDetailsDecode() {
        val payload = """
        {
          "_id": "titulo-sin-autor_1789948355579",
          "real_id": "titulo-sin-autor_1789948355579",
          "name_esp": "Titulo sin autor",
          "_status": "publicandose",
          "_imagen": "https://img1mw.xyz/cover.webp",
          "_categoris": [{"3": "Accion"}],
          "_extras": {"autores": []},
          "chapters": []
        }
        """.trimIndent()

        json.decodeFromString<ComicDetailsDto>(payload)
    }

    @Test
    fun versionBasedAndLegacyChaptersDecode() {
        val payload = json.decodeFromString<ComicDetailsDto>(
            """
            {
              "_id": "caballero-del-equilibrio_1789948355579",
              "real_id": "caballero-del-equilibrio_1789948355579",
              "name_esp": "Caballero del Equilibrio",
              "_sinopsis": "Sinopsis breve",
              "_status": "publicandose",
              "_imagen": "https://img1mw.xyz/cover.webp",
              "_categoris": [{"3": "Accion"}],
              "chapters": [
                {
                  "chapter": 1,
                  "img": [],
                  "versions": [
                    {
                      "link": "https://manhwaweb.com/leer/caballero-del-equilibrio_1789948355579-1_01",
                      "joint": [{"name": "errr diablo"}],
                      "create": 1790043469814
                    }
                  ]
                },
                {
                  "chapter": 2,
                  "img": [],
                  "versions": [
                    {
                      "link": "https://manhwaweb.com/leer/caballero-del-equilibrio_1789948355579-2_01",
                      "joint": [{"name": "scan a"}, {"name": "scan b"}],
                      "create": 1790043476464
                    },
                    {
                      "link": "https://manhwaweb.com/leer/caballero-del-equilibrio_1789948355579-2_02",
                      "joint": [],
                      "create": 1790043477000
                    }
                  ]
                },
                {
                  "chapter": 1,
                  "img": [],
                  "link": "https://manhwaweb.com/leer/gon-no-ry_1772942862926-1",
                  "create": 1772967153136,
                  "versions": [
                    {
                      "link": "https://manhwaweb.com/leer/gon-no-ry_1772942862926-1",
                      "joint": [{"name": "Ūgon no Ryū"}],
                      "create": 1772967153136
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, payload.chapters.size)

        val versionOnly = payload.chapters[0]
        assertNull(versionOnly.espUrl)
        assertNull(versionOnly.rawUrl)
        assertNull(versionOnly.createdAt)
        assertEquals(
            "https://manhwaweb.com/leer/caballero-del-equilibrio_1789948355579-1_01",
            versionOnly.versions.single().link,
        )
        assertEquals(1790043469814, versionOnly.versions.single().createdAt)
        assertEquals("errr diablo", versionOnly.versions.single().joint.single().name)

        val multiVersion = payload.chapters[1]
        assertEquals(2, multiVersion.versions.size)
        assertEquals(
            listOf("scan a", "scan b"),
            multiVersion.versions[0].joint.map { it.name },
        )
        assertEquals(0, multiVersion.versions[1].joint.size)

        val legacy = payload.chapters[2]
        assertEquals("https://manhwaweb.com/leer/gon-no-ry_1772942862926-1", legacy.espUrl)
        assertEquals(1772967153136, legacy.createdAt)
        assertEquals(1, legacy.versions.size)
    }
}
