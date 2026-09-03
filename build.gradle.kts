// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // 8.10.0 is the earliest 8.x AGP release supporting compileSdk 36 (Android 16) —
    // 8.7-8.9 cap out at API 35. Staying on the 8.x line (not jumping to AGP 9.x)
    // avoids that major version's DSL/build-model breaking changes, which aren't
    // worth the risk for a project built and maintained via Termux CLI.
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}