import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("es", "all", "en", "ja", "zh", "ko", "ru", "fr", "de", "pt", "it").forEach { language ->
        source {
            baseUrl = "https://e-hentai.org"
            lang = language
        }
    }

    deeplink {
        path("/g/.*")
    }
}
