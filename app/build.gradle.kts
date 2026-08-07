plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.tv.coverscreen"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tv.coverscreen"
        minSdk = 31
        targetSdk = 37
        versionCode = 11
        versionName = "0.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // libspark.so is a stripped arm64 build lifted out of the original
        // app. There is no other ABI, so do not let the packager look for one.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Ship the .so uncompressed and page aligned so the loader maps it straight
    // out of the apk. Pairs with extractNativeLibs="false" in the manifest.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)

    // Optional privileged path, all of it behind Privileged.kt. The app builds,
    // installs and runs the same on a phone with no Shizuku on it.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
