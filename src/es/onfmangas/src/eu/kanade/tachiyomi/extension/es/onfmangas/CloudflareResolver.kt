package eu.kanade.tachiyomi.extension.es.onfmangas

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Solves a Cloudflare challenge page by loading the target URL in a hidden WebView.
 *
 * The site serves a JS challenge ("ONF MANGAS - Verificando...") that OkHttp cannot solve.
 * This resolver creates a WebView, lets Cloudflare's JS execute, and waits for the
 * `cf_clearance` cookie to appear. Once set, OkHttp can use it for subsequent requests.
 *
 * Based on the YuriGarden CloudflareResolver pattern.
 */
object CloudflareResolver {

    private const val TAG = "OnfMangas"
    private const val TIMEOUT_SECONDS = 45L
    private const val POLL_INTERVAL_MS = 500L
    private const val WEBVIEW_WIDTH = 1080
    private const val WEBVIEW_HEIGHT = 1920
    private const val CLEARANCE_COOKIE = "cf_clearance"

    /**
     * Resolve a Cloudflare challenge for the given URL.
     * Returns true if cf_clearance cookie was obtained.
     */
    @Synchronized
    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(loadUrl: String, cookieUrl: String = loadUrl, userAgent: String? = null): Boolean {
        val cookieManager = CookieManager.getInstance()

        // Check if we already have the clearance cookie
        if (hasClearance(cookieManager, cookieUrl)) {
            Log.d(TAG, "CF: already have cf_clearance for $cookieUrl")
            return true
        }

        Log.d(TAG, "CF: resolving challenge for $loadUrl")

        val context = Injekt.get<Application>()
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        lateinit var poll: Runnable

        handler.post {
            val wv = WebView(context)
            webView = wv

            // Force a realistic layout so Cloudflare's fingerprinting sees a real screen
            wv.layoutParams = ViewGroup.LayoutParams(WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, WEBVIEW_WIDTH, WEBVIEW_HEIGHT)

            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                blockNetworkImage = false
                mediaPlaybackRequiresUserGesture = false
                // cf_clearance is bound to the UA that solved it — keep both sides aligned
                if (!userAgent.isNullOrBlank()) userAgentString = userAgent
            }

            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "CF: page finished: $url")
                }
            }

            poll = Runnable {
                if (latch.count == 0L) return@Runnable
                if (hasClearance(cookieManager, cookieUrl)) {
                    Log.d(TAG, "CF: clearance obtained!")
                    latch.countDown()
                } else {
                    handler.postDelayed(poll, POLL_INTERVAL_MS)
                }
            }

            wv.loadUrl(loadUrl)
            handler.postDelayed(poll, POLL_INTERVAL_MS)
        }

        val solved = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        handler.post {
            handler.removeCallbacks(poll)
            webView?.stopLoading()
            webView?.destroy()
        }

        if (!solved) {
            Log.w(TAG, "CF: timed out after ${TIMEOUT_SECONDS}s")
        }

        return hasClearance(cookieManager, cookieUrl)
    }

    private fun hasClearance(cookieManager: CookieManager, url: String): Boolean {
        val cookies = cookieManager.getCookie(url) ?: return false
        return cookies.split(';').any { it.trim().startsWith("$CLEARANCE_COOKIE=") }
    }
}
