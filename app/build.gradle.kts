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
        debug {
            // Distinct id/label so this installs side-by-side with other builds on a test device.
            applicationIdSuffix = ".voicefill"
            resValue("string", "app_name", "Whisper Voice Fill (dev)")
        }
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
        resValues = true
    }

    // GenieX's native loader resolves its own .so (and plugin .so) paths with std::filesystem,
    // which can't canonicalize a path inside the APK zip ("...base.apk!/lib/arm64-v8a/..."). It
    // needs them physically extracted to the on-disk nativeLibraryDir, which AGP only does when
    // this is on (geniex_chat_android's build.gradle sets the same thing for the same reason).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    // The .onnx models are read from the asset file (copyAsset), keep them uncompressed.
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    implementation(project(":whisper"))
    // Piper TTS (reads the still-empty form fields aloud): decoder on the Hexagon NPU via ORT's QNN
    // provider, CPU fallback. Models live in assets/tts.
    // GenieX already bundles a newer QNN runtime (2.45, same .so names), so drop ORT's own copy
    // (2.33) rather than ship two; ORT's QNN provider dlopens libQnnHtp.so from nativeLibraryDir.
    implementation(libs.onnxruntime.android.qnn) {
        exclude(group = "com.qualcomm.qti", module = "qnn-runtime")
    }
    // On-device SLM backing SlmFormExtractor (Qwen3-0.6B-GGUF over GenieX's llama_cpp runtime).
    implementation("com.qualcomm.qti:geniex-android:0.3.5")
    implementation(libs.kotlinx.coroutines.android)
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