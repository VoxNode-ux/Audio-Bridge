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
    
    // FORCE-UPGRADE TRANSITIVE EXPOSURES GLOBALLY ACROSS ALL MODULES NATIVELY
    versionCatalogs {
        create("libs") {
            // Self-contained declarations can go here if needed
        }
    }
    
    forcedModules {
        // 1. Patches CVE-2026-5588 Cryptographic Provider Timing Attack Bypasses
        force("org.bouncycastle:bcprov-jdk18on:1.85")
        force("org.bouncycastle:bcpkix-jdk18on:1.85")
        
        // 2. Patches CVE-2025-48924 StackOverflow Uncontrolled Recursion DoS
        force("org.apache.commons:commons-lang3:3.18.0")
        
        // 3. Patches CWE-772 Infrastructure Connection Pool Memory Socket Leaks
        force("org.apache.httpcomponents:httpclient:4.5.14")
        
        // 4. Patches CVE-2024-29371 JSON Web Encryption Token Compression Bombs
        force("org.bitbucket.b_c:jose4j:0.9.6")
    }
}

rootProject.name = "Audio-Stream"
include(":app")
 