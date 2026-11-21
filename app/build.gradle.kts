plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.freqazoid"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        // Same identity and signing key as every earlier Freqazoid build, so it installs right over them.
        applicationId = "com.freakazoid.watch"
        minSdk = 30
        targetSdk = 35
        versionCode = 10
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs.getByName("debug") {
        storeFile = file("../debug-signing/freakazoid-debug.jks")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
// No libraries: plain Android View + platform audio, so the watch app stays small.
