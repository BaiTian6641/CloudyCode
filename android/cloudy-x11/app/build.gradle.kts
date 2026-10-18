plugins {
    id("com.android.application")
}

android {
    namespace = "dev.cloudy.x11"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.cloudy.x11"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
