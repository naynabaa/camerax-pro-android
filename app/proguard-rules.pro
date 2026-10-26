# CameraX Pro R8 / ProGuard Configuration
-keep class com.google.mediapipe.** { *; }
-keep class jp.co.cyberagent.android.gpuimage.** { *; }
-keep class androidx.camera.** { *; }
-keepattributes *Annotation*
-dontwarn com.google.mediapipe.**
