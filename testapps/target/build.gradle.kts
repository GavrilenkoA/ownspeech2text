// A separate "other app" for emulator end-to-end tests (WP-20): the bubble is hidden inside Murmur's own UI
// (DESIGN 5.2), so the dictation flow must be driven against a different package. Never shipped.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.murmur.testtarget"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        applicationId = "app.murmur.testtarget"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
