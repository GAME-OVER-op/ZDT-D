# Keep JS interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# libsu is used for root shell operations. Keep its public/internal API names stable
# for maximum compatibility with reflection-based fallbacks and OEM/root-manager quirks.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
