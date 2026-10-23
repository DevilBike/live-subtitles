plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CPU features whisper.cpp is compiled for. The default (dot product + fp16)
// covers phones from about 2018 on; pass -Pwhisper.armArch=armv8-a for older ones.
val whisperArmArch = (findProperty("whisper.armArch") as String?) ?: "armv8.2-a+dotprod+fp16"

android {
    namespace = "dev.bike.livesubs"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.bike.livesubs"
        // Android 10: the first version with AudioPlaybackCapture.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // whisper.cpp is built for 64-bit ARM only, which every Android 10+ phone has.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DLIVESUBS_ARM_ARCH=$whisperArmArch" }
        }
        buildConfigField("String", "WHISPER_ARM_ARCH", "\"$whisperArmArch\"")
    }

    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            // Unoptimized whisper.cpp is around 10x slower, so the debug APK gets an optimized native build too.
            externalNativeBuild {
                cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" }
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // On-device Japanese/Korean -> English translation (models downloaded once by the app).
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
