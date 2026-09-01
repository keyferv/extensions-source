package eu.kanade.tachiyomi.extension.es.barmanga

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

class BarMangaInterceptor(
    private val chapterCache: MutableMap<String, Map<Int, ByteArray>>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        val fragment = url.fragment ?: return chain.proceed(request)
        if (!fragment.startsWith("barmanga-page-")) {
            return chain.proceed(request)
        }

        val cacheKey = url.toString().substringBefore("#")
        val pageParam = fragment.removePrefix("barmanga-page-").toIntOrNull()
            ?: throw IOException("Invalid page parameter")

        Log.d(TAG, "intercept page=$pageParam cacheKey=$cacheKey")

        val images = synchronized(chapterCache) {
            chapterCache[cacheKey]
        }
        if (images == null) {
            Log.e(TAG, "cache miss for $cacheKey — known keys=${chapterCache.keys}")
            throw IOException("Chapter ZIP not cached")
        }

        val imageData = images[pageParam]
        if (imageData == null) {
            Log.e(TAG, "page $pageParam not in ZIP — available=${images.keys}")
            throw IOException("Page $pageParam not found in ZIP")
        }

        val mimeType = getImageMimeType(imageData)
        Log.d(TAG, "serve page=$pageParam size=${imageData.size} mime=$mimeType magic=${imageData.take(8).joinToString(",") { "0x%02X".format(it) }}")
        val body = imageData.toResponseBody(mimeType.toMediaType())

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private fun getImageMimeType(bytes: ByteArray): String {
        if (bytes.size < 12) return "image/jpeg"

        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ) -> "image/png"
            bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) -> "image/webp"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val TAG = "BarManga"
    }
}
