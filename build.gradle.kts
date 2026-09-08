// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// AGP 9+ has Kotlin support built in — org.jetbrains.kotlin.android is no longer applied
// (applying both throws "Cannot add extension with name 'kotlin'"). The Compose compiler,
// merged into the Kotlin repo since Kotlin 2.0, now needs its own version-matched plugin
// (org.jetbrains.kotlin.plugin.compose) instead of the old composeOptions.kotlinCompilerExtensionVersion,
// which only understood Kotlin-1.9-era compiler artifacts.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}