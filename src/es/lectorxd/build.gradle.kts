import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "lectorxd"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "LectorXD"
        baseUrl = "https://lectorxd.com"
        lang = "es"
    }

    deeplink {
        path("/..*")
    }
}
