plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.pdfmanager"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.pdfmanager"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
}
