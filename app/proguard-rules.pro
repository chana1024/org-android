# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
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

# Keep org-java parser classes
-keep class org.orgzly.org.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.AndroidEntryPoint
-keepclassmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <methods>;
}

# JGit: uses reflection for translation bundles and FS detection
-keep class org.eclipse.jgit.** { *; }
-keepclassmembers class org.eclipse.jgit.internal.JGitText { public static *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.beans.**
-keep class org.slf4j.** { *; }