plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jayswansocool48.kiln"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jayswansocool48.kiln"
        // Kiln's 3D viewer needs a modern WebView (ES modules + import maps), so Android 8+.
        // Built for the Fire HD 8 (8th gen) on LineageOS 21 (Android 14).
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0"
    }

    sourceSets {
        getByName("main") {
            // web-src/web holds Kiln's web app; three.js is added to web-src/web/vendor by sync-web.sh in CI.
            assets.setSrcDirs(listOf("web-src"))
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        noCompress += listOf("html", "js", "css", "json", "svg", "ttf", "glb")
    }
}

dependencies {
    // Tiny embedded web server: serves Kiln's page and its /api, exactly like server.py does on the desktop.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
