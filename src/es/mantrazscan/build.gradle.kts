import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Mantraz Scan"
    versionCode = 61
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl {
            custom("https://mantrazscan.co")
        }
        id = 7172992930543738693L
    }
}
