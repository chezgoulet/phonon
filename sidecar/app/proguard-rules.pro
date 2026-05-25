# phonon-agent ProGuard rules

# Keep OkHttp WebSocket / HTTP internals
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep our model classes
-keep class com.chezgoulet.phonon.models.** { *; }

# Keep JDK HTTP server
-keep class com.sun.net.httpserver.** { *; }
