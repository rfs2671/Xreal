plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ultraxr"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.ultraxr"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs {
        // A fixed debug key kept in the repo so every build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    androidResources { noCompress += "onnx" }
    packaging {
        // Other apps dlopen libopenxr_monado.so from this app's library directory.
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(files("libs/monado/monado-ipc.aar", "libs/monado/monado-auxiliary.aar"))
    implementation("androidx.annotation:annotation:1.8.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
