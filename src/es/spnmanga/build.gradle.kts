import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "SpnManga"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://www.spnmanga.com"
        lang = "es"
    }

    deeplink {
        path("/..*")
        path("/leer/..*")
    }
}
