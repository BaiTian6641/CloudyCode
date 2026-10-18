plugins {
    id("com.android.library")
}

android {
    namespace = "dev.cloudy.x11.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    buildFeatures {
        aidl = true
    }
}
