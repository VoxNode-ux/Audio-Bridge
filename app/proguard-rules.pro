# Keep line numbers for crash reports, strip everything else aggressively.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin coroutines / serialization internals sometimes need these on aggressive shrink.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# NSD / mDNS callback classes accessed via reflection by the platform.
-keep class com.audiobridge.app.discovery.** { *; }