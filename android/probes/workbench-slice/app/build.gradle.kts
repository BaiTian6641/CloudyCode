plugins {
    id("com.android.application")
}

android {
    namespace = "dev.gbcode.slice"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.gbcode.slice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
