import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LectorHentai"
    versionCode = 9
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://lectorhentai.com"
    }

    deeplink {
        host("lectorhentai.com")
        host("www.lectorhentai.com")
        path("/..*")
    }
}
