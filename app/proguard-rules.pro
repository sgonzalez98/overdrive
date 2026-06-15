# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== BYD SDK Stubs (reflection) ====================
# These are compile-time stubs - real classes come from system at runtime
-keep class android.hardware.bydauto.** { *; }
-keep class android.hardware.BmmCamera** { *; }

# ==================== Daemon Entry Points (app_process) ====================
# ONLY keep class names and main() - everything else gets obfuscated
# This hides internal method names like whitelistViaBruteForce -> a()

-keep class com.overdrive.app.daemon.CameraDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.SentryDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.AccSentryDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.TelegramBotDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.daemon.GlobalProxyDaemon {
    public static void main(java.lang.String[]);
}
-keep class com.overdrive.app.byd.BydEventDaemon {
    public static void main(java.lang.String[]);
}

# Keep listener classes that extend BYD SDK (method names must match parent)
-keep class com.overdrive.app.daemon.AccSentryDaemon$AccListener {
    <methods>;
}

# ==================== Static Watchdog Builders (cross-process callers) ====================
# These companion-object static helpers are called from daemon processes
# (AccSentryDaemon, TelegramBotDaemon) to build the SAME shell-watchdog
# script the UI deploys. Without `-keep` here, R8 may rename
# `buildTelegramWatchdogScript` etc., the runtime call from
# AccSentryDaemon.launchTelegramDaemon would NoSuchMethodError, and the
# code falls back to the bare-nohup unsupervised launch — silently
# regressing the H2 watchdog work.
-keep class com.overdrive.app.launcher.DaemonLauncher$Companion {
    public *;
}
-keep class com.overdrive.app.launcher.ZrokLauncher$Companion {
    public *;
}

# ==================== Daemon Support Classes ====================
# These are used by daemons but don't need full preservation

# Safe - Pure Java AES decryption (replaced native NativeSecrets)
-keep class com.overdrive.app.daemon.proxy.Safe {
    public static java.lang.String s(java.lang.String);
}

# S - Short alias for string decryption (used throughout daemon code)
-keep class com.overdrive.app.daemon.proxy.S {
    public static java.lang.String d(java.lang.String);
}

# Enc - holds decrypted constants accessed via reflection from
# DaemonBootstrap.verifySafeWorking() (Class.forName + getDeclaredField).
# Without this, R8 renames APP_PACKAGE to a single-letter name and the
# bootstrap aborts before the daemon ever loads.
-keep class com.overdrive.app.daemon.proxy.Enc {
    public static java.lang.String APP_PACKAGE;
    *;
}

# CameraDaemon.getAppContext() — called reflectively by ScreenDeterrent.resolveContext()
# (surveillance package can't compile-time depend on daemon package). The
# main()-only -keep above doesn't preserve this, so R8 renames it to a()
# and ScreenDeterrent silently falls through to DaemonBootstrap.
-keepclassmembers class com.overdrive.app.daemon.CameraDaemon {
    public static android.content.Context getAppContext();
}

# DaemonBootstrap.getContext() — fallback path for the same reflection above.
-keepclassmembers class com.overdrive.app.daemon.DaemonBootstrap {
    public static android.content.Context getContext();
}

# Messages.get(String, String) and Messages.get(String) — called
# reflectively by SrtWriter.lookupCatalog() so subtitle generation can
# pull localized strings without a compile-time dependency from
# surveillance → server.
-keepclassmembers class com.overdrive.app.server.Messages {
    public static java.lang.String get(java.lang.String, java.lang.String);
    public static java.lang.String get(java.lang.String);
}

# LocaleManager.get() — called reflectively by SrtWriter.resolveCurrentLocale()
-keepclassmembers class com.overdrive.app.server.LocaleManager {
    public static java.lang.String get();
}

# Keep class names for daemon subpackages (for Class.forName if used internally)
# but allow method/field renaming
-keepnames class com.overdrive.app.daemon.** { }
-keepnames class com.overdrive.app.byd.** { }
-keepnames class com.overdrive.app.camera.** { }
-keepnames class com.overdrive.app.server.** { }
-keepnames class com.overdrive.app.encoding.** { }
-keepnames class com.overdrive.app.stream.** { }
-keepnames class com.overdrive.app.monitor.** { }

# ==================== Logging (keep class names + DaemonLogConfig for runtime control) ====================
-keepnames class com.overdrive.app.logging.** { }
-keep class com.overdrive.app.logging.DaemonLogConfig { *; }

# ==================== Native Methods (all classes) ====================
# JNI method names must match native function signatures exactly
-keepclasseswithmembernames class * {
    native <methods>;
}

# ==================== Dadb (ADB client) ====================
-keep class dadb.** { *; }
-dontwarn dadb.**

# ==================== ZXing (QR codes) ====================
-keep class com.google.zxing.** { *; }

# ==================== Eclipse Paho MQTT ====================
# Paho uses java.util.logging internally and loads logging resource bundles
# by class name via reflection. ProGuard strips these, causing
# MissingResourceException at connect time.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep class org.eclipse.paho.client.mqttv3.logging.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
# v5 client used by BydCloudMqttSubscriber for BYD's EMQ broker — same
# reflection-based logging-bundle loader as v3. The package roots differ
# from v3 (mqttv5 sits directly under org.eclipse.paho, no .client. prefix
# in the package after that), so the v3 rules above don't cover it.
-keep class org.eclipse.paho.mqttv5.** { *; }
-keep class org.eclipse.paho.mqttv5.client.logging.** { *; }
-dontwarn org.eclipse.paho.mqttv5.**

# ==================== RTMP client ====================
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# ==================== TensorFlow Lite ====================
# CPU-only (XNNPACK). The GPU delegate dependency was removed because on
# Adreno 610 (unified-memory SoC) concurrent OpenCL inference and the H.265
# encoder share one DDR bus, producing visible eglSwap stalls during
# recording. See YoloDetector.kt class doc.
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.auto.value.**

# AI detection classes - keep class names but allow method obfuscation
# Detection data class needs field names for any serialization
-keep class com.overdrive.app.ai.Detection { *; }
-keepnames class com.overdrive.app.ai.** { }

# ==================== Kotlin & AndroidX ====================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
# AndroidX - only keep what's needed, not everything
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.work.** { *; }
-keep class androidx.navigation.** { *; }
-keepnames class androidx.** { }
-dontwarn androidx.**

# ==================== App Components (declared in AndroidManifest) ====================
# R8 auto-keeps these, but explicit rules for safety
-keep class com.overdrive.app.OverdriveApplication { *; }
-keep class com.overdrive.app.ui.MainActivity { *; }
-keep class com.overdrive.app.ui.LocationStarterActivity { *; }
-keep class com.overdrive.app.BlockerActivity { *; }
-keep class com.overdrive.app.receiver.BootReceiver { *; }
-keep class com.overdrive.app.receiver.LocationBootReceiver { *; }
-keep class com.overdrive.app.services.LocationSidecarService { *; }
# RoadSense IMU sidecar — launched by the daemon via a STRING-LITERAL `am
# start-foreground-service -n .../RoadSenseImuSidecarService` (R8 can't see that
# reference), so its class name must not be renamed/stripped. Same rationale as
# LocationSidecarService above. The daemon→app bridge (CameraDaemon.getRoadSense,
# RoadSenseController, RoadSenseApiHandler) is reached by typed calls so R8 tracks
# those automatically; only the am-launched component needs an explicit keep.
-keep class com.overdrive.app.roadsense.sidecar.RoadSenseImuSidecarService { *; }
-keep class com.overdrive.app.roadsense.overlay.RoadSenseOverlayService { *; }

# ==================== App Packages (allow obfuscation) ====================
# Keep class names for debugging but obfuscate methods/fields
-keepnames class com.overdrive.app.auth.** { }
-keepnames class com.overdrive.app.bridge.** { }
-keepnames class com.overdrive.app.byd.** { }
-keepnames class com.overdrive.app.client.** { }
-keepnames class com.overdrive.app.config.** { }
-keepnames class com.overdrive.app.launcher.** { }
-keepnames class com.overdrive.app.manager.** { }
-keepnames class com.overdrive.app.proximity.** { }
-keepnames class com.overdrive.app.recording.** { }
-keepnames class com.overdrive.app.service.** { }
-keepnames class com.overdrive.app.shell.** { }
-keepnames class com.overdrive.app.storage.** { }
-keepnames class com.overdrive.app.streaming.** { }
-keepnames class com.overdrive.app.surveillance.** { }
-keepnames class com.overdrive.app.telemetry.** { }
# TelemetrySnapshot fields accessed by overlay renderer — keep from renaming
-keepclassmembers class com.overdrive.app.telemetry.TelemetrySnapshot { public *; }
-keepnames class com.overdrive.app.abrp.** { }
-keepnames class com.overdrive.app.telegram.** { }
-keepnames class com.overdrive.app.ui.** { }
-keepnames class com.overdrive.app.util.** { }
-keepnames class com.overdrive.app.webrtc.** { }

# ==================== Serialization & Parcelable ====================
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ==================== H2 Database & JDBC Fixes (COMPLETE) ====================

# 1. Ignore Desktop UI (AWT/Swing)
-dontwarn java.awt.**
-dontwarn java.beans.**

# 2. Ignore Java Management Extensions (JMX)
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# 3. Ignore Servlets (Web Server features) - MISSING IN PREVIOUS
-dontwarn javax.servlet.**
-dontwarn jakarta.servlet.**

# 4. Ignore OSGi (Module System) - MISSING IN PREVIOUS
-dontwarn org.osgi.**

# 5. Ignore Advanced SQL/Transaction APIs (XA/JDBCType)
-dontwarn java.sql.**
-dontwarn javax.sql.**
-dontwarn javax.transaction.**
-dontwarn javax.naming.**
-dontwarn javax.security.**

# 6. Ignore GIS/Geometry Support (JTS)
-dontwarn org.locationtech.jts.**

# 7. Ignore Lucene (Full Text Search)
-dontwarn org.apache.lucene.**

# 8. Ignore other missing standard Java extensions
-dontwarn javax.xml.stream.**
-dontwarn javax.xml.transform.**
-dontwarn javax.tools.**
-dontwarn javax.script.**

# 9. Keep H2 functional
-keep class org.h2.** { *; }

# ==================== Log Stripping (Release Builds) ====================
#
# CONTROLLED BY: com.overdrive.app.logging.DaemonLogConfig
#
# DaemonLogger stripping is in a SEPARATE file: proguard-rules-strip-logs.pro
# build.gradle.kts auto-detects DaemonLogConfig flags:
#   - All flags false (default) → includes strip-logs → DaemonLogger calls stripped
#   - Any flag true            → excludes strip-logs → DaemonLogger calls kept
#
# Console/stdout stripping below is ALWAYS active (even in debug-logging builds).
# ====================================================================

# Always strip android.util.Log (logcat) — security: no log strings in production
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
    public static String getStackTraceString(...);
    public static int println(...);
}

# Always strip System.out/err (daemon stdout)
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
    public void printf(...);
}

# Keep DaemonLogConfig (R8 needs it for runtime checks when logging is enabled)
-keep class com.overdrive.app.logging.DaemonLogConfig { *; }

# Keep DaemonLogger class structure
-keep class com.overdrive.app.logging.DaemonLogger { *; }
-keep class com.overdrive.app.logging.DaemonLogger$Config { *; }
-keep class com.overdrive.app.logging.DaemonLogger$Level { *; }

# Strip Kotlin null checks (minor optimization — always safe to strip)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
}

# ==================== General Safety ====================
-dontwarn sun.misc.Unsafe
-dontwarn java.nio.file.**
-dontwarn java.util.spi.ToolProvider
