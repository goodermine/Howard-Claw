# ── Howard ProGuard Rules ───────────────────────────────────────────────────

# Keep JNI methods (called from howard_jni.cpp)
-keepclasseswithmembernames class au.howardagent.engine.LocalEngine {
    native <methods>;
}

# Keep Room entities and DAOs
-keep class au.howardagent.data.** { *; }

# Keep all Kotlin data classes used for serialisation
-keep class au.howardagent.download.ModelInfo { *; }
-keep class au.howardagent.connectors.TelegramMessage { *; }

# OkHttp + Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Kotlin metadata
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Security crypto
-keep class androidx.security.crypto.** { *; }

# Suppress missing class warnings for unused modules
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
