package eu.kanade.tachiyomi.extension.es.lectorhentai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class LectorHentaiUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val item = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", "${LectorHentai.PREFIX_ID_SEARCH}$item")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("LectorHentaiUrlActivity", e.toString())
            }
        } else {
            Log.e("LectorHentaiUrlActivity", "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }

    companion object {
        private const val PREFIX_ID_SEARCH = "id:"
    }
}
