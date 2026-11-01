import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MHScans"
    versionCode = 17
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl {
            custom("https://mhscans.com")
        }
    }

    deeplink {
        host("mhscans.com")
        path("/comics/..*")
        path("/leer/..*")
        path("/series/..*")
    }
}
