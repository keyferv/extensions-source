package eu.kanade.tachiyomi.extension.es.jeazscans

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import keiyoushi.utils.applicationContext
import keiyoushi.utils.toJsonString
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proxies chapter image downloads through a WebView. The image endpoint
 * `/api/imagen-capitulo` sits behind Cloudflare's TLS-fingerprint check: a plain
 * OkHttp request returns 403, while a browser engine (WebView) passes and returns
 * the image. This interceptor fetches the image in a hidden WebView and hands the
 * decoded bytes back to OkHttp.
 *
 * Adapted from lycantoons' WebViewInterceptor.
 */
class WebViewInterceptor(
    private val baseUrl: String,
    private val userAgent: String?,
) : Interceptor {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val bridgeName = "JeazImageBridge"

    private var cachedWv: WebView? = null
    private var destroyWv: Runnable? = null
    private var latch: CountDownLatch? = null
    private var result: ByteArray? = null
    private var errorMessage: Throwable? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath != "/api/imagen-capitulo") {
            return chain.proceed(request)
        }

        var bytes = fetchImage(request.url.toString())
        if (bytes == null && errorMessage?.message == "HTTP 403") {
            resetWebView()
            bytes = fetchImage(request.url.toString())
        }
        bytes ?: throw IOException("Failed to download page image via WebView: ${errorMessage?.message ?: "timed out"}")

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "image/png")
            .body(bytes.toResponseBody("image/png".toMediaTypeOrNull()))
            .build()
    }

    private val webView: WebView
        get() {
            destroyWv?.let(mainHandler::removeCallbacks)
            if (cachedWv == null) {
                cachedWv = WebView(applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    if (!userAgent.isNullOrBlank()) settings.userAgentString = userAgent
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun passResult(base64: String) {
                                result = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
                                latch?.countDown()
                            }

                            @JavascriptInterface
                            fun passError(error: String) {
                                errorMessage = Exception(error)
                                latch?.countDown()
                            }
                        },
                        bridgeName,
                    )
                }
            }
            destroyWv = Runnable {
                cachedWv?.destroy()
                cachedWv = null
                destroyWv = null
            }.also { mainHandler.postDelayed(it, REUSE_TIMEOUT_MS) }
            return cachedWv!!
        }

    private fun resetWebView() {
        mainHandler.post {
            destroyWv?.let(mainHandler::removeCallbacks)
            cachedWv?.destroy()
            cachedWv = null
            destroyWv = null
        }
    }

    @Synchronized
    private fun fetchImage(url: String): ByteArray? {
        latch = CountDownLatch(1)
        result = null
        errorMessage = null

        mainHandler.post {
            runCatching {
                val wv = webView
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        view.evaluateJavascript(
                            """
                            (function() {
                                const image = document.getElementById('_image');
                                const toBase64 = blob => {
                                    const reader = new FileReader();
                                    reader.onload = () => window.$bridgeName.passResult(btoa(reader.result));
                                    reader.onerror = () => window.$bridgeName.passError('FileReader error');
                                    reader.readAsBinaryString(blob);
                                };
                                const fetchImage = () => fetch(image.src, {
                                    cache: 'force-cache',
                                    credentials: 'include'
                                })
                                    .then(r => {
                                        if (!r.ok) throw new Error('HTTP ' + r.status);
                                        return r.blob();
                                    })
                                    .then(toBase64)
                                    .catch(e => {
                                        const canvas = document.createElement('canvas');
                                        canvas.width = image.naturalWidth;
                                        canvas.height = image.naturalHeight;
                                        if (!canvas.width || !canvas.height) {
                                            window.$bridgeName.passError(e.message || 'image fetch error');
                                            return;
                                        }
                                        canvas.getContext('2d').drawImage(image, 0, 0);
                                        fetch(canvas.toDataURL('image/png'))
                                            .then(r => r.blob())
                                            .then(toBase64)
                                            .catch(() => window.$bridgeName.passError(e.message || 'image fetch error'));
                                    });
                                if (image.complete) fetchImage();
                                else image.onload = fetchImage;
                                image.onerror = () => window.$bridgeName.passError('HTTP 403');
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                wv.loadDataWithBaseURL(
                    baseUrl,
                    "<html><body><img id='_image' src=${url.toJsonString()}></body></html>",
                    "text/html",
                    "utf-8",
                    null,
                )
            }.onFailure { e ->
                errorMessage = e
                latch?.countDown()
            }
        }

        latch?.await(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        return result
    }

    private companion object {
        const val FETCH_TIMEOUT_SECONDS = 15L
        const val REUSE_TIMEOUT_MS = 30_000L
    }
}
