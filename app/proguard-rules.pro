# Run for Victory Proguard Rules
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep data classes and domain models
-keep class com.runforvictory.tuition.domain.** { *; }
-keep class com.runforvictory.tuition.data.** { *; }

# Keep Compose runtime annotations
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
    @androidx.compose.runtime.ReadOnlyComposable *;
}

# Strip logging calls in release builds for privacy and performance
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

