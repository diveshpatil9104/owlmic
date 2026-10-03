plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.owlmic.media"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // Oboe ships with the shared C++ runtime, so the JNI library uses it too.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        prefab = true
    }

    // libopus, Oboe and the JNI layer. Needs CMake and the opus submodule (git submodule update --init).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // Any CMake from here up, whether from the SDK manager or the PATH.
            version = "3.22.1+"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.oboe)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.lifecycle.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
