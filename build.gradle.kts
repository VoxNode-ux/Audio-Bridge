buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        constraints {
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("Fixes CVE-2021-33813 High Severity XXE Vulnerability")
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
 