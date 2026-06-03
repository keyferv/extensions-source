package eu.kanade.tachiyomi.extension.es.mangasnosekai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class MangasNoSekaiTest {
    @Test
    fun testAjaxChapters() {
        val client = OkHttpClient.Builder().build()
        val json = Json { ignoreUnknownKeys = true }
        val baseUrl = "https://mangasnosekai.com"
        val url = "/wp-json/muslitos/v1/getcaps7"
        val mangaId = "8821"

        val cookies = "cf_clearance=1AM8j1E6NSk8BNy2HnkYZz82R8RFyLJbOn9T27uJ3Ds-1778125151-1.2.1.1-Ya_vcxcepC5lnb00FU2mae0CBgsIrhLbWhLs47CyV4KfueTUU2ozSCG1z_3xO54GfzLJ0Xp8GvBFg46K5WSFn0h75.xzrzykN8cSkS_t5ISTDEyapHHga4TmMrLW2rS91tQe8IfZQeGrzNMSu8g_ucnhPqVj4CNREsXHX7Pl6w2oLvWUjomGUl2t0aEy20a1tKGztdD9m.upjH2BPvbmj5fg1NU4GJhHyab0flHbmJ8Lp9jQT_z0QoDhdf_7uXwSNAoq6NC235q.jDqLNi18IhvP8yPPliNAdzn247jer3b05zKx5_axQypBvT.mEtzfMNFINRjFnJMFNCq5hpTwQv420qe_xzkOhwezC4S6gy_b0AzHKr4fnWzhVv0bqfv1ZvpWgdQk9Yzl4rXZzsJpwpdXjhsaOsfcovj1X.jc6j0; wpdiscuz_nonce_2538839d3e42d30b107b38873f1a1e06=91d039e513"

        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Cookie", cookies)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        println("----- PROBANDO PÁGINA 1 -----")
        val form1 = FormBody.Builder()
            .add("action", "muslitos_anti_hack")
            .add("mangaid", mangaId)
            .add("page", "1")
            .add("secret", "mihonsuckmydick")
            .build()

        val req1 = Request.Builder().url(baseUrl + url).headers(headers).post(form1).build()
        val res1 = client.newCall(req1).execute()
        val body1 = res1.body?.string() ?: ""
        println("Status pág 1: ${res1.code}")
        println("Body pág 1: ${body1.take(100)}...")

        println("\n----- PROBANDO PÁGINA 2 -----")
        val form2 = FormBody.Builder()
            .add("action", "muslitos_anti_hack")
            .add("mangaid", mangaId)
            .add("page", "2")
            .add("secret", "mihonsuckmydick")
            .build()

        val req2 = Request.Builder().url(baseUrl + url).headers(headers).post(form2).build()
        val res2 = client.newCall(req2).execute()
        val body2 = res2.body?.string() ?: ""
        println("Status pág 2: ${res2.code}")
        println("Body pág 2: ${body2.take(100)}...")

        if (body2.startsWith("{")) {
            try {
                val parsed = json.decodeFromString<ChapterListResponse>(body2)
                println("¡JSON Pág 2 parseado con éxito! Capítulos: ${parsed.chaptersToDisplay.size}")
                println("Total Pages: ${parsed.totalPages}")
            } catch (e: Exception) {
                println("Error parseando JSON: ${e.message}")
            }
        }
    }
}
