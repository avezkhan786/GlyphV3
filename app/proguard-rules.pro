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

# ─── Gson ────────────────────────────────────────────────────────────────────
# Gson reads generic signatures reflectively at runtime
# (TypeToken.getGenericSuperclass). Without these rules a release build crashes
# with "TypeToken must be created with a type argument ... make sure that
# generic signatures are preserved" (observed on the first chat-list bind,
# which parses persisted snapshots via Gson). Rules are the ones recommended
# by the Gson project for R8 (full mode):
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeVisibleTypeAnnotations
-keepattributes AnnotationDefault
-keep,allowobfuscation,allowshrinking interface com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Gson instantiates the app's own data models reflectively via their no-arg
# constructor (WalkieTalkieSession, MessageCache entries, backup models, ...).
# R8 strips a Kotlin data class's all-defaults constructor once it appears
# unused → "Class X does not define a no-argument constructor. If you are using
# ProGuard, make sure these constructors are not stripped." Keep the no-arg
# constructors of app models (class obfuscation is still allowed).
-keepclassmembers class com.glyph.glyph_v3.** { <init>(); }