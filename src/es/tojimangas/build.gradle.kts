import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LectorTMOo"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "LectorTMOo"
        lang = "es"
        baseUrl = "https://lectortmo.online"
        id = 6976108800986842339
    }
}
