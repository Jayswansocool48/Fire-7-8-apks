plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jayswansocool48.gamecodex"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jayswansocool48.gamecodex"
        minSdk = 21
        // 34 = Android 14, what LineageOS 21 is. Keeps the app out of Android 15's forced edge-to-edge.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets {
        getByName("main") {
            // web-src/web is a copy of the game-codex repo's web/ folder; android-assets adds the
            // small script that connects it to the tablet's storage.
            assets.setSrcDirs(listOf("web-src", "android-assets"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        noCompress += listOf("html", "js", "css", "json", "svg")
    }
}
