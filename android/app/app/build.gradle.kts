plugins {
    id("com.android.application")
}

android {
    namespace = "dev.gbcode.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.gbcode.app"
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.documentfile:documentfile:1.1.0")


    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
