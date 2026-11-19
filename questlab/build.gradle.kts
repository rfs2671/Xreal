plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Quest compatibility lab: the API layer that answers Meta's mixed-reality OpenXR extensions on top of any OpenXR
// runtime (libuxr_meta_layer.so), and a test app that calls those extensions the way a Quest game does.
android {
    namespace = "dev.ultraxr.questlab"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.ultraxr.questlab"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { prefab = true }
    signingConfigs { getByName("debug") { storeFile = file("../app/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.42")
}
