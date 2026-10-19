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
        versionCode = 3
        versionName = "0.3.1"
    }

    sourceSets {
        getByName("main") {
            // web-src/web is a copy of the game-codex repo's web/ folder; android-assets adds the
            // small script that connects it to the tablet's storage.
            assets.setSrcDirs(listOf("web-src", "android-assets"))
        }
    }

    signingConfigs {
        // One fixed key so each new APK installs over the last one (Settings → Check for updates).
        // It's in this public repo; move it to a GitHub secret before handing the app to other people.
        create("gamecodex") {
            storeFile = file("signing/gamecodex.keystore")
            storePassword = "gamecodex-fire"
            keyAlias = "gamecodex"
            keyPassword = "gamecodex-fire"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gamecodex")
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("gamecodex")
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
        noCompress += listOf("html", "js", "css", "json", "svg", "woff")
    }
}
