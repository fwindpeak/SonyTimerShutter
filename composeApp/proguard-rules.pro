# Keep entry point activity
-keep class com.sonytimer.shutter.MainActivity { *; }

# Strip verbose/debug Android logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
