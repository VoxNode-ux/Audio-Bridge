plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.audiobridge.app"
    // Bumped to 37 (from 36) alongside the AGP 9.4.0 upgrade — androidx.core:core-ktx
    // 1.19.0 and the Compose 1.12.0 artifacts both hard-require compileSdk 37+ and
    // AGP 9.1.0+ to compile at all (confirmed via a real CI failure: CheckAarMetadata
    // rejected the build with "requires libraries and applications that depend on it
    // to compile against version 37 or later"). AGP 8.10.0's own max recommended
    // compileSdk was 36, so this bump only becomes safe/necessary together with the
    // AGP 9 upgrade, not on its own.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.audiobridge.app"
        // minSdk 29 covers system-audio-capture (AudioPlaybackCaptureConfiguration, API 29+)
        // and is the actual floor: your Lenovo Tab 9 is permanently on Android 13 (API 33),
        // comfortably above this, and won't receive further OS updates — so 29 has margin
        // without being so low it drags in behavior for OS versions neither device runs.
        minSdk = 29
        targetSdk = 37
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Replaces the old `kotlinOptions { jvmTarget = "21" }` DSL, which built-in Kotlin (AGP 9+)
// no longer exposes on the `android {}` block — this is the new equivalent, applied via the
// Kotlin plugin's own extension instead of AGP's.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Replaces the old composeOptions.kotlinCompilerExtensionVersion — that setting only ever
// understood Kotlin-1.9-era Compose compiler artifact versions (like the previous "1.5.14")
// and is ignored/invalid now that the Compose compiler ships version-matched to Kotlin
// itself via the org.jetbrains.kotlin.plugin.compose plugin applied above.
composeCompiler {
    // No extra options needed — default settings are correct for this project.
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    
    // Updated to stable 2.11.0
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    
    // Updated to stable 1.13.0
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose BOM keeps all Compose artifact versions in sync
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Updated to stable 1.2.1
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Coroutines for async audio/network work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
 