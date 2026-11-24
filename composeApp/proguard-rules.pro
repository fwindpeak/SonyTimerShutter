# Keep entry point activity
-keep class com.sonytimer.shutter.MainActivity { *; }

# Strip verbose/debug Android logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Keep JSch classes for reflection-based cipher/kex/userauth loading
-keep class com.jcraft.jsch.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.**
-dontwarn org.slf4j.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
