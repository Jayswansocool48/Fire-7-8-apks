plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jayswansocool48.codeling"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jayswansocool48.codeling"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"
    }

    sourceSets {
        getByName("main") {
            // web-dist is the web app rewritten for older WebViews (made by tools/prepare-web.mjs
            // in CI). Without it, the original files in web-src are packaged as they are.
            assets.setSrcDirs(listOf(if (file("web-dist").exists()) "web-dist" else "web-src"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // Keep the web files byte-for-byte.
        noCompress += listOf("html", "js", "css", "json", "webmanifest", "svg")
    }
}
