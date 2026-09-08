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

            // 2. Patches CVE-2026-5588 Cryptographic Bypasses inside Bouncy Castle
            // FIXED: Paired exactly to the official 1.85 release values
            classpath("org.bouncycastle:bcprov-jdk18on:1.85") {
                because("Overrides legacy cryptographic provider layers used by lint engines")
            }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85") {
                because("Overrides legacy public key infrastructure utilities inside toolchains")
            }

            // 3. Patches CVE-2025-48924 Denial of Service StackOverflowError Uncontrolled Recursion
            // FIXED: Locked onto the target 3.17.0 release track to maintain compatibility
            classpath("org.apache.commons:commons-lang3:3.17.0") {
                because("Overrides vulnerable string formatting libraries bundled by build scripts")
            }

            // 4. Patches Connection Pool Leak Exhaustion Flaws (CWE-772)
            // FIXED: Uses stable legacy line fixes since 5.x uses a completely different architecture
            classpath("org.apache.httpcomponents:httpclient:4.5.14") {
                because("Forces a secure legacy network runtime for fallback resource fetches")
            }

            // 5. Patches Token Parsing Vulnerabilities (Decompression Bounds Flaws)
            // FIXED: Pinning to stable 0.9.6 coordinate structures
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
 