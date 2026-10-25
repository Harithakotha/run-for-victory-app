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
