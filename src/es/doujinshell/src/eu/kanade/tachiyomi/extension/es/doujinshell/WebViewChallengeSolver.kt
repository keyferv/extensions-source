package eu.kanade.tachiyomi.extension.es.doujinshell

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Resuelve el desafío de Cloudflare usando un WebView invisible y devuelve las cookies.
 * Implementación bloqueante (sin corrutinas) con timeout configurable.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewChallengeSolver(
    private val context: Context,
    private val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36",
) {

    /**
     * Carga la URL en un WebView con JS habilitado y espera a que Cloudflare complete el challenge.
     * Devuelve el mapa de cookies (nombre -> valor) o vacío si no hay cookies.
     */
    fun solveChallengeBlocking(url: String, timeoutMs: Long = 30_000L): Map<String, String> {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Map<String, String>>(1)
        val error = arrayOfNulls<Throwable>(1)

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = userAgent
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    try {
                        val cookieUrl = loadedUrl ?: url
                        val cookieStr = CookieManager.getInstance().getCookie(cookieUrl)
                        result[0] = parseCookies(cookieStr)
                    } catch (t: Throwable) {
                        error[0] = t
                    } finally {
                        try { webView.destroy() } catch (_: Throwable) {}
                        latch.countDown()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    error[0] = RuntimeException("WebView error: $description ($errorCode)")
                    try { webView.destroy() } catch (_: Throwable) {}
                    latch.countDown()
                }
            }

            webView.loadUrl(url)
        }

        // Esperar a que termine o timeout
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            throw RuntimeException("WebView challenge solving timed out after $timeoutMs ms")
        }

        error[0]?.let { throw it }
        return result[0] ?: emptyMap()
    }

    private fun parseCookies(cookieString: String?): Map<String, String> {
        if (cookieString.isNullOrBlank()) return emptyMap()
        return cookieString.split(";")
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val idx = trimmed.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = trimmed.substring(0, idx)
                val value = trimmed.substring(idx + 1)
                name to value
            }
            .toMap()
    }
}
