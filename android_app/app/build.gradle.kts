plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radiacode.ble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.radiacode.ble"
        minSdk = 26
        targetSdk = 34
        versionCode = 114
        versionName = "1.40"
    }

    // The repo’s audio files live in app/audio/. Copy any provided sound assets into res/raw
    // at build time so SoundManager can load them as Android raw resources.
    //
    // Supported filenames in app/audio/:
    // - sound_data_tick.wav
    // - sound_connected.wav
    // - sound_disconnected.wav
    // - sound_alarm.wav
    // - sound_anomaly.wav
    // - ambient_drone.wav (or the original sci-fi ambient drone filename)
    val audioSrcDir = file("audio")
    val rawDestDir = file("src/main/res/raw")

    tasks.register<Copy>("syncAudioToRaw") {
        from(audioSrcDir) {
            include(
                "sound_data_tick.*",
                "sound_connected.*",
                "sound_disconnected.*",
                "sound_alarm.*",
                "sound_anomaly.*",
                "ambient_drone.*",
            )
        }
        into(rawDestDir)
    }

    tasks.named("preBuild") {
        dependsOn("syncAudioToRaw")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Splash screen (Android 12+ compatible)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Fragment + ViewPager2 for tab-based navigation
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // SwipeRefreshLayout for pull-to-refresh gesture
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Preferences for proper settings screen
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Fused Location Provider (battery-friendly location)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    
    // Map library for live location tracking
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    // EXIF interface for photo geotagging
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
