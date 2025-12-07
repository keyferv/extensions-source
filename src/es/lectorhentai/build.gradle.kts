plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = 33
    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
    sourceSets["main"].java.srcDirs("src")
    sourceSets["main"].res.srcDirs("res")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lib:madara"))
}
