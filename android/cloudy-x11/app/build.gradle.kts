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
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.webkit:webkit:1.14.0")
}

tasks.register<Exec>("buildCloudyX11Jni") {
    workingDir = rootProject.projectDir
    commandLine("bash", "../../tools/device/build-x11-jni.sh")
}

tasks.named("preBuild").configure {
    dependsOn("buildCloudyX11Jni")
}
