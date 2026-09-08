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
    
    // FIXED: Uses the globally supported allProjects block to safely inject resolution rules
    allProjects {
        configurations.all {
            resolutionStrategy.eachDependency {
                when (requested.group) {
                    "org.bouncycastle" -> {
                        useVersion("1.85")
                        because("Fixes cryptographic vulnerabilities in both bcprov and bcpkix")
                    }
                    "org.apache.commons" -> {
                        if (requested.name == "commons-lang3") {
                            useVersion("3.18.0")
                            because("Fixes uncontrolled recursion denial of service vectors")
                        }
                    }
                    "org.apache.httpcomponents" -> {
                        if (requested.name == "httpclient") {
                            useVersion("4.5.14")
                            because("Fixes memory exhaustion flaws in older network clients")
                        }
                    }
                    "org.bitbucket.b_c" -> {
                        if (requested.name == "jose4j") {
                            useVersion("0.9.6")
                            because("Fixes JSON Web Encryption decompression bomb vulnerabilities")
                        }
                    }
                }
            }
        }
    }
}

rootProject.name = "Audio-Stream"
include(":app")
 