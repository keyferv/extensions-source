import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Jeaz Scans"
    theme = "madara"
    versionCode = 68
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl = "https://lectorhub.j5z.xyz"
        id = 5292079548510508306L
        versionId = 2
    }
}
