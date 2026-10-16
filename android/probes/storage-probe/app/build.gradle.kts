plugins {
    id("com.android.application")
}

android {
    namespace = "dev.gbcode.storageprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.gbcode.storageprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.1.0")
}
