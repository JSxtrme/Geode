plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.js.geode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.js.geode"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.1.19"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Versions match the set already cached from the Anipull build, so the
    // build can run offline on this VM (Java proxy tunnelling is unreliable).
    // Compose is pinned to 1.10.0 explicitly, exactly like Anipull: the
    // BOM 2024.12.01 would resolve to 1.7.6, whose -android artifacts are
    // NOT in the local cache, so the BOM alone fails offline.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui:1.10.0")
    implementation("androidx.compose.ui:ui-graphics:1.10.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.0")
    // EncryptedSharedPreferences for the BYOK Gemini key.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // OkHttp 5.3.2 (cached with its okio 3.17.0) for Gemini API calls +
    // best-effort video fetch. okio is forced to 3.17.0: okhttp-android
    // 5.3.2 wants 3.16.4, which is not in the cache.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okio:okio:3.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.0")
}
