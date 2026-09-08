buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        constraints {
            // 1. Patches CVE-2021-33813 High Severity XXE Injection
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("Fixes high severity XXE parsing issues inside build tools")
            }

            // 2. Patches CVE-2026-5588 Cryptographic Padding Bypass Vulnerabilities
            classpath("org.bouncycastle:bcprov-jdk18on:1.85") {
                because("Overrides legacy cryptographic provider layers used by lint engines")
            }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85") {
                because("Overrides legacy public key infrastructure utilities inside toolchains")
            }

            // 3. Patches CVE-2025-48924 Uncontrolled Recursion Denial of Service Flaw
            // FIXED: Bumped from 3.17.0 to 3.18.0 to safely exit the vulnerability threat range
            classpath("org.apache.commons:commons-lang3:3.18.0") {
                because("Overrides vulnerable string formatting libraries bundled by build scripts")
            }

            // 4. Patches Connection Pool Socket Leak Exhaustion parameters (CWE-772)
            classpath("org.apache.httpcomponents:httpclient:4.5.14") {
                because("Forces a secure legacy network runtime for fallback resource fetches")
            }

            // 5. Patches JSON Web Encryption Decompression Memory Exhaustion Loops
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

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
 