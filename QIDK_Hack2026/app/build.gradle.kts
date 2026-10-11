plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.qidk_hack2026"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.qidk_hack2026"
        // GenieX (SlmFormExtractor's on-device LLM) requires 31+, same floor geniex_chat_android uses.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The target boards are arm64, and QNN/HTP only exists for arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // QNN (Piper) and GenieX load their native libs by file path (std::filesystem can't resolve a path
        // inside the APK zip), so they must be extracted to disk.
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    implementation(project(":whisper"))
    implementation(libs.kotlinx.coroutines.android)
    // Piper TTS: decoder on the Hexagon NPU via ORT's QNN provider, CPU fallback. Models in assets/tts.
    // GenieX already bundles a newer QNN runtime (2.45, same .so names), so drop ORT's own copy
    // (2.33) rather than ship two; ORT's QNN provider dlopens libQnnHtp.so from nativeLibraryDir.
    implementation(libs.onnxruntime.android.qnn) {
        exclude(group = "com.qualcomm.qti", module = "qnn-runtime")
    }
    // On-device SLM backing SlmFormExtractor (llama_cpp runtime over GenieX).
    implementation("com.qualcomm.qti:geniex-android:0.3.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}