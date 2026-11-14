import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "AnimeBBG"
    versionCode = 17
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://animebbg.net"
    }
}
