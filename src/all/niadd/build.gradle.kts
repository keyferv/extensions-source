import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Niadd"
    versionCode = 8
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://www.niadd.com"

        source {
            name = "Niadd (ES)"
            lang = "es"
            baseUrl = "https://es.niadd.com"
            id = 2697843818318465608L
        }
    }
    deeplink {
        host("niadd.com")
        host("www.niadd.com")
        host("es.niadd.com")
        host("br.niadd.com")
        host("it.niadd.com")
        host("ru.niadd.com")
        host("de.niadd.com")
        host("fr.niadd.com")
        path("/manga/..*")
        path("/chapter/..*")
        path("/original/..*")
    }
}
