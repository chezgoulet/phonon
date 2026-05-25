# Phonon Worker ProGuard rules

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep JSON
-keep class org.json.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep our model classes (serialized to/from JSON)
-keep class com.chezgoulet.phonon.models.** { *; }
