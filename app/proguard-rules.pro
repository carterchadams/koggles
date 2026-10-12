# Copied verbatim from third_party/cardboard/hellocardboard-android/proguard-rules.pro (Cardboard SDK v1.35.0).
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Koggles (Blueprint Task 21) -------------------------------------------------------------
# Native code calls NativeBridge's static upcalls by name (jni/upcalls.cpp) and binds its external
# methods; R8 sees no Kotlin callers for the upcalls and would remove them ("upcalls ready 0/3").
-keep class dev.koggles.NativeBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep interface dev.koggles.NativeListener { *; }
