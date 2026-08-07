import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ZonaTMO"
    versionCode = 7
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "es"
        baseUrl = "https://zonatmo.org"
    }

    deeplink {
        host("zonatmo.org")
        host("www.zonatmo.org")
        path("/library/..*")
    }
}
