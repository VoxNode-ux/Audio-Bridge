buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        constraints {
            // 1. Patches CVE-2021-33813 High Severity XXE Injection inside tool buildscripts
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("Fixes high severity XXE parsing issues inside build tools")
            }

            // 2. Patches Cryptographic Padding Timing Vulnerabilities inside build tool processes
            classpath("org.bouncycastle:bcprov-jdk18on:1.85") {
                because("Overrides legacy cryptographic provider layers used by lint engines")
            }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85") {
                because("Overrides legacy public key infrastructure utilities inside toolchains")
            }

            // 3. Patches CVE-2025-48924 Uncontrolled Recursion Denial of Service Flaw in tools
            classpath("org.apache.commons:commons-lang3:3.20.0") {
                because("Overrides vulnerable string formatting libraries bundled by build scripts")
            }

            // 4. Patches Connection Pool Socket Leak Exhaustion parameters in tools
            classpath("org.apache.httpcomponents:httpclient:4.5.14") {
                because("Forces a secure legacy network runtime for fallback resource fetches")
            }

            // 5. Patches JSON Web Encryption Decompression Memory Exhaustion Loops in plugins
            classpath("org.bitbucket.b_c:jose4j:0.9.6") {
                because("Overrides vulnerable token parsing libraries embedded in AGP metadata integrations")
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

// SAFE TRANSLATION HOOK: Intercepts all sub-module runtime trees safely (fixes the last 4 alerts)
subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "org.bouncycastle" -> {
                    useVersion("1.85")
                    because("Fixes cryptographic vulnerabilities inside sub-module dependencies")
                }
                "org.apache.commons" -> {
                    if (requested.name == "commons-lang3") {
                        useVersion("3.20.0")
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

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
 