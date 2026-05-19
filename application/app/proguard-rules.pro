# Keep JS interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# libsu is used for root shell operations. Keep its public/internal API names stable
# for maximum compatibility with reflection-based fallbacks and OEM/root-manager quirks.
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# MaxMind DB reader is used by the offline DB-IP Lite City MMDB resolver.
# Keep it stable under R8 because the reader uses typed decoding/reflection paths internally.
-keep class com.maxmind.db.** { *; }
-dontwarn com.maxmind.db.**
