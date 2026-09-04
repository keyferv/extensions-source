import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MHScans"
    theme = "madara"
    versionCode = 16
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl {
            custom("https://mhscans.com")
        }
    }
}
