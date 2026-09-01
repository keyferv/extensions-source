import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "KuManga"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://www.kumanga.com"
        lang = "es"
    }

    deeplink {
        host("www.kumanga.com")
        host("kumanga.com")
        path("/manga/..*")
        path("/mangalist/..*")
    }
}

android {
    sourceSets.getByName("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
    }
}

tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach {
    enabled = false
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(libs.bundles.common)
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
    testImplementation(libs.jsoup)
}
