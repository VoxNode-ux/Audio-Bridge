pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // Force the safe version across all build configurations
    versionCatalogs {
        create("libs") {
            // Optional: catalog configurations can go here
        }
    }
}

// Intercept buildscript / plugin environments directly
// This blocks the transitive leak inside com.android.tools.build:gradle
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
 