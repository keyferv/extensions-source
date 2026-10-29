import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ZonaTMO.NET"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://zonatmo.net"
    }

    deeplink {
        host("zonatmo.net")
        host("www.zonatmo.net")
        path("/manga/..*")
    }
}
