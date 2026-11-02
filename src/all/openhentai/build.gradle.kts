import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "OpenHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("es", "all", "en", "ja", "zh", "fr", "de", "pt", "ru", "it", "ko", "vi", "id").forEach { language ->
        source {
            baseUrl = "https://openhentai.org"
            lang = language
        }
    }

    deeplink {
        path("/.*/mangas/.*")
    }
}
