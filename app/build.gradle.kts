plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nosmai.quickstart.zegocloud"
    compileSdk = 35

    defaultConfig {
        // A Nosmai license key only works with the application ID it was issued for.
        // Change this to your own application ID and use a key created for it.
        applicationId = "com.nosmai.quickstart.zegocloud"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The Nosmai SDK ships native libraries for these two ABIs only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Nosmai: effects pipeline and preview.
    implementation("com.nosmai:nosmai-sdk:3.0.7@aar")
    // ZEGOCLOUD Express Video: rooms, publishing and playing.
    implementation("im.zego:express-video:3.21.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
