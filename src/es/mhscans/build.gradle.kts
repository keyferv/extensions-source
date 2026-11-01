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
