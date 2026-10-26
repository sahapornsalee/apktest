plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.sahaporn.autoplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sahaporn.autoplay"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Phones only; keeps the VLC and FFmpeg native libraries from bloating the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "autoplay123"
            keyAlias = "autoplay"
            keyPassword = "autoplay123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-decoder:1.5.1")
    // FFmpeg audio decoders (AC3, E-AC3, DTS, TrueHD, ...) for the main player.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    // Backup player that decodes nearly any format, in software when needed.
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
