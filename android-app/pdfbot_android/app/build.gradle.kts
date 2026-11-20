plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace="com.example.pdfmanager"
 compileSdk=35
 defaultConfig { applicationId="com.example.pdfmanager"; minSdk=26; targetSdk=35; versionCode=5; versionName="0.5.0" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies { implementation("androidx.documentfile:documentfile:1.0.1") }