plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.whispercpp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
        }

        ndk {
            // arm64 for real phones, x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ggml's own CMake build produces these generic shared libs alongside libwhisper*.so. The
    // GenieX AAR (app's dependency, for SlmFormExtractor's llama_cpp plugin) bundles its own
    // same-named copies that its other plugins must stay consistent with, so defer to those
    // instead of letting :app's packaging hit a "2 files found" merge conflict over this module's
    // copies.
    packaging {
        jniLibs {
            excludes += setOf(
                "**/libggml.so",
                "**/libggml-base.so",
                "**/libggml-cpu.so",
                "**/libomp.so",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
