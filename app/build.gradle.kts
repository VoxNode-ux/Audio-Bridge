plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.audiobridge.app"
    // 36 = Android 16, matching the Moto Edge 70 sender's actual OS. When it updates to
    // Android 17 in ~2 months, bump this again — targeting the platform version your
    // primary device actually runs avoids the compatibility-behavior shims Android
    // applies when targetSdk trails the running OS. The Lenovo Tab 9 (Android 13 /
    // API 33, receiving no further OS updates) is unaffected by this number — it's
    // covered by minSdk below regardless of how high targetSdk goes.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.audiobridge.app"
        // minSdk 29 covers system-audio-capture (AudioPlaybackCaptureConfiguration, API 29+)
        // and is the actual floor: your Lenovo Tab 9 is permanently on Android 13 (API 33),
        // comfortably above this, and won't receive further OS updates — so 29 has margin
        // without being so low it drags in behavior for OS versions neither device runs.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 64-bit only — no armeabi-v7a/x86 legacy junk, single clean APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    // Single universal APK, no ABI splits — keeps "one file, no invalid package" promise.
    splits {
        abi {
            isEnable = false
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose BOM keeps all Compose artifact versions in sync
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lifecycle-aware ViewModel for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // DataStore for storing last-used connection (replaces SharedPreferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines for async audio/network work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

