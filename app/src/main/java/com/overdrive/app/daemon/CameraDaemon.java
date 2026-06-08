package com.overdrive.app.daemon;

import android.os.Handler;
import android.os.Looper;

import com.overdrive.app.abrp.AbrpConfig;
import com.overdrive.app.abrp.AbrpTelemetryService;
import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.server.HttpServer;
import com.overdrive.app.server.SurveillanceIpcServer;
import com.overdrive.app.server.TcpCommandServer;

import com.overdrive.app.daemon.proxy.Safe;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main Camera Daemon - orchestrates all camera operations.
 * 
 * Runs as a standalone process via app_process:
 *   adb shell "CLASSPATH=/data/app/.../base.apk app_process / \
 *       com.overdrive.app.daemon.CameraDaemon [outputDir] [nativeLibDir]"
 * 
 * Components:
 * - TcpCommandServer: JSON commands on port 19876
 * - HttpServer: Web UI and H.264 streaming on port 8080
 * - PanoramicCamera: BYD panoramic camera access
 * - VirtualView: Per-camera view cropping and encoding
 * - AccMonitor: Sentry mode when ACC goes off
 */
public class CameraDaemon {

    private static final String TAG = "CameraDaemon";
    
    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** com.overdrive.app */
    private static String APP_PACKAGE_NAME() { return Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8="); }
    /** /data/local/tmp/cam_stream */
    private static String PATH_CAMERA_STREAM_DIR() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzxuq9ag7mKGoQaOvzuwMDqM="); }
    /** /sdcard/DCIM/BYDCam */
    private static String PATH_CAMERA_OUTPUT_DIR() { return Safe.s("C6E+8XkzSNnhdgOIKBfVSXGyuhqY7qDiNp4pBP/hRuY="); }
    /** /data/local/tmp/stream_mode.txt */
    private static String PATH_STREAM_MODE_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz4A79W/sQd0NkqiGs/MIZWo="); }
    /** /data/local/tmp/.byd_device_id */
    private static String PATH_DEVICE_ID_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz8mvs/gQENVv3FEZ6OVKD54="); }
    
    // ==================== CONFIGURATION ====================
    public static final int TCP_PORT = 19876;
    public static final int HTTP_PORT = 8080;
    public static String STREAM_DIR() { return PATH_CAMERA_STREAM_DIR(); }
    public static final String APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream";
    
    // Recording config defaults. Runtime panoramic geometry comes from
    // CameraConfigResolver; these stay as legacy-profile fallbacks for code
    // paths that still read the daemon constants directly.
    public static final int PANO_WIDTH = com.overdrive.app.camera.CameraProfiles
        .getLegacyDefault().getPanoWidth();
    public static final int PANO_HEIGHT = com.overdrive.app.camera.CameraProfiles
        .getLegacyDefault().getPanoHeight();
    public static final int VIEW_WIDTH = PANO_WIDTH / 4;
    public static final int VIEW_HEIGHT = PANO_HEIGHT;
    public static final int FRAME_RATE = 25;
    public static final int BITRATE = 4_000_000;
    public static final int KEYFRAME_INTERVAL = 2;
    public static final long SEGMENT_DURATION_MS = 2 * 60 * 1000;
    
    // Streaming config (SIM-optimized)
    public static final int STREAM_WIDTH = 640;
    public static final int STREAM_HEIGHT = 480;
    public static final int STREAM_JPEG_QUALITY = 70;  // Increased from 40 for better quality
    public static final long STREAM_INTERVAL_MS = 100;
    
    // ==================== STATE ====================
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Handler mainHandler;
    private static String outputDir = null; // Initialized in main()
    private static String nativeLibDir = null; // Initialized in parseArguments()
    
    // ==================== LOGGING ====================
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // ==================== SERVERS ====================
    private static TcpCommandServer tcpServer;
    private static HttpServer httpServer;
    private static SurveillanceIpcServer ipcServer;
    private static com.overdrive.app.server.AacIngestServer aacIngestServer;
    private static AccMonitor accMonitor;
    
    // ==================== SURVEILLANCE ====================
    // Volatile because static onAccStateChanged / onGearChanged / IPC
    // handlers + pendingAccOff drain read this from arbitrary threads
    // (GearMonitor poll, AccSentry IPC, HTTP, ADAS callbacks). Publication
    // via Thread.start() happens-before is fragile against future refactors
    // that read the field before the constructing thread starts a worker;
    // volatile gives a hard guarantee on ARM weak-memory cores.
    private static volatile com.overdrive.app.surveillance.GpuSurveillancePipeline gpuPipeline;
    // Volatile: written from ACC handlers (multiple threads), read by HTTP
    // handlers, accOnDisarmWatchdog poll thread, lock-gate watchdog, and
    // status JSON readers. Without volatile, the ARM weak-memory model lets
    // a writer's update sit unseen by peer threads for milliseconds —
    // acceptable for cosmetic chip flicker but risky for the watchdog's
    // force-disarm decision.
    private static volatile boolean surveillanceEnabled = false;
    // OEM Dashcam pipeline — separate forward sensor, distinct from pano AVM.
    // Lazily allocated when the user enables OEM Dashcam recording or the
    // streaming view mode 5 is requested. Both pipelines may run
    // concurrently when camera.concurrentAvmSupported == 1; otherwise they
    // share the single AVMCamera client via the priorityWhenContended UCM
    // policy.
    //
    // VOLATILE: writers go through OemDashcamApiHandler.LIFECYCLE_LOCK, but
    // readers (surveillance event handler, ACC dispatch, stream router,
    // /api/oem-dashcam/config GET) deliberately don't hold the lock — the
    // volatile barrier is what makes a freshly-published reference visible
    // across CPU cores without a lock acquisition on every read.
    private static volatile com.overdrive.app.camera.OemDashcamPipeline oemDashcamPipeline;
    private static volatile boolean safeZoneSuppressed = false;
    // Pending ACC OFF state: if ACC goes off before GPU pipeline is ready,
    // queue the request and apply it once the pipeline initializes
    private static volatile boolean pendingAccOff = false;
    // Pending ACC ON state: symmetric counterpart so an ACC ON IPC that
    // arrives before the GPU pipeline is ready isn't silently dropped. The
    // drain at end of initSurveillance fires onAccStateChanged(false) once
    // the pipeline is non-null, seeding RecordingModeManager so pano
    // recording starts cleanly on cold-boot when ACC is already ON.
    // Mutually exclusive with pendingAccOff — setting one always clears the
    // other so the drain order is unambiguous.
    private static volatile boolean pendingAccOn = false;

    // DiLink 4 post-ACC-OFF camera-open grace duration. esco hardcodes 60 s
    // (FlameoutService p111dh/C4995i.java:407 m22726w(60_000L)). Earlier
    // AVMCamera.open races the MCU/ISP power-down and yields all-zero
    // frames forever. The actual gate lives in PanoramicCameraGpu and
    // covers ALL open paths (sentry, streaming, OEM, recording-mode).
    // Legacy fleet never arms it.
    private static final long DILINK4_SENTRY_DEFER_MS = 60_000L;
    
    // ==================== DOOR LOCK GATE (surveillance arm/disarm) ====================
    // Lock detection runs in CameraDaemon's process where cloud MQTT is active.
    // Surveillance is only armed after doors are locked (reduces false triggers from owner exiting).
    private static volatile boolean doorLockListenerArmed = false;

    // Three parallel lock-event sources, all active simultaneously while the
    // gate is open. Cloud is fragile in the field (rarely fires lock events
    // even when MQTT is healthy), so device-SDK and polling exist as
    // independent backups rather than as a fallback chain.
    private static com.overdrive.app.byd.cloud.BydCloudDataProvider.CloudLockStateListener cloudLockListener = null;
    private static Thread unlockPollThread = null;
    // Reverse watchdog: periodically queries hardware ACC state and force-
    // disables surveillance if ACC went ON without an event reaching us.
    // Symmetric counterpart to the ACC-OFF DoorLockTimeout that force-arms.
    private static Thread accOnDisarmWatchdog = null;
    private static final long ACC_ON_DISARM_POLL_INTERVAL_MS = 5_000;
    private static final long DOOR_LOCK_ARM_TIMEOUT_MS = 60_000;  // 60s grace period
    private static final long UNLOCK_POLL_INTERVAL_MS = 5_000;
    private static final int DOOR_STATE_INVALID = 0;
    private static final int DOOR_STATE_UNLOCK = 1;
    private static final int DOOR_STATE_LOCK = 2;
    
    // ==================== RECORDING MODE MANAGER ====================
    // Volatile: read by static onGearChanged/onAccStateChanged/onSafeZoneEnter
    // from arbitrary threads. See gpuPipeline volatile rationale.
    private static volatile com.overdrive.app.recording.RecordingModeManager recordingModeManager;
    
    // ==================== AVC HAL KEEP-ALIVE ====================
    // Keeps com.byd.avc alive while ACC is ON and pipeline is running.
    // Prevents BYD system from killing the camera app, which destabilizes
    // the HAL and causes "no video signal" on the native DVR.
    // Volatile + lazy-init guarded by AVC_WARMUP_INIT_LOCK. Without these,
    // two worker threads (RMM activate paths, resync retry, IPC) can both
    // observe `avcHalWarmup == null`, both `new AvcHalWarmup()`, both call
    // startKeepAlive() — leaking a duplicate keep-alive thread that never
    // gets stopped (the field holds only the second instance).
    private static volatile com.overdrive.app.camera.AvcHalWarmup avcHalWarmup;
    private static final Object AVC_WARMUP_INIT_LOCK = new Object();
    
    // ==================== STREAM MODE ====================
    public static final String STREAM_MODE_PRIVATE = "private";  // Local H.264 only
    public static final String STREAM_MODE_PUBLIC = "public";    // Tunnel access
    // Volatile: read/written from HTTP threads + boot init thread.
    private static volatile String streamMode = STREAM_MODE_PRIVATE;
    
    // ==================== DEVICE ID ====================
    // Volatile: written once at boot, read from many threads. Volatile
    // documents the contract and protects future refactors that might read
    // it before the writing thread starts a worker.
    private static volatile String deviceId = "unknown";
    
    // ==================== ABRP TELEMETRY ====================
    // All four below are volatile for the same reason as gpuPipeline /
    // recordingModeManager: cross-thread reads from IPC + HTTP + monitor
    // poll threads, hard memory guarantee instead of relying on
    // Thread.start() happens-before.
    private static volatile AbrpTelemetryService abrpTelemetryService;
    private static volatile com.overdrive.app.abrp.SohEstimator sohEstimator;

    // ==================== MQTT CONNECTIONS ====================
    private static volatile com.overdrive.app.mqtt.MqttConnectionManager mqttConnectionManager;

    // ==================== TRIP ANALYTICS ====================
    private static volatile com.overdrive.app.trips.TripAnalyticsManager tripAnalyticsManager;
    private static volatile java.util.concurrent.CompletableFuture<Void> tripAnalyticsInitFuture;

    // ==================== DATA LAYER (RecordingsIndex parallel kick) ====================
    // Completes when the parallel RecordingsIndex.init() + warmupAsync kick
    // returns. shutdown() and any caller that needs a guaranteed-open index
    // joins on this so we don't tear down a half-initialised DB.
    private static volatile java.util.concurrent.CompletableFuture<Void> dataLayerInitFuture;

    // ==================== TELEMETRY DATA COLLECTOR ====================
    private static volatile com.overdrive.app.telemetry.TelemetryDataCollector telemetryDataCollector;

    // ==================== ROADSENSE ====================
    // Daemon-side road-hazard detection brain (D-019/D-023). Reuses the already-
    // initialized BydDataCollector + GpsMonitor singletons; the app-side IMU
    // sidecar feeds it via the IMU_BATCH IPC command (see handleCommand). Driven
    // by the daemon housekeeping tick (onVehicleStatePoll + onWarningTick).
    private static volatile com.overdrive.app.roadsense.RoadSenseController roadSense;

    /** Accessor for the IPC server's IMU_BATCH case. */
    public static com.overdrive.app.roadsense.RoadSenseController getRoadSense() { return roadSense; }

    // ==================== SHARED APP CONTEXT ====================
    // Volatile: written at boot AND re-published on ACC ON via
    // reinitContextDependentComponents (different thread). Without volatile,
    // the re-publication has no happens-before guarantee for arbitrary
    // readers (HTTP, monitors, IPC).
    private static volatile android.content.Context sharedAppContext = null;

    // ==================== INIT-SURVEILLANCE RETRY (audit R2) ====================
    // Cold-boot transients (AssetManager cookie=0, GpuSurveillancePipeline
    // init throwing on a HAL race, DaemonBootstrap.getContext() crash during
    // framework warm-up) can leave gpuPipeline=null with no retry path —
    // pano recording stays dead until manual daemon kill / reboot. Schedule
    // a bounded exponential-backoff retry from the catch block; gated by a
    // CAS so concurrent callers don't queue duplicate retries.
    private static final java.util.concurrent.atomic.AtomicBoolean initSurveillanceRetryInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger initSurveillanceRetryAttempts =
        new java.util.concurrent.atomic.AtomicInteger(0);
    // FIX (audit R8, finding "initSurveillance retry budget exhaustion"):
    // dropped MAX_RETRIES cap. A bounded cap leaves pano permanently dead
    // when transient HAL flakes outlive the 155s budget — same principle as
    // the user memory rule "no retry cap on any watchdog". Backoff steps
    // 5s/30s/120s/300s and then stays at 300s (5 min) forever; success
    // short-circuits the retry, so unbounded retry is safe here.
    private static final long[] INIT_SURVEILLANCE_RETRY_DELAYS_MS =
        { 5_000L, 30_000L, 120_000L, 300_000L };
    private static final long INIT_SURVEILLANCE_RETRY_MAX_DELAY_MS = 300_000L;

    // ==================== SHARED-CONTEXT WATCHDOG (audit R2) ====================
    // When initSurveillance() succeeds in constructing gpuPipeline but
    // sharedAppContext was null, RecordingModeManager is never created and
    // the boot probe queues pendingAccOn/Off without firing them. Spawn a
    // one-shot poll thread that watches for sharedAppContext to become
    // valid (e.g. system_server warm-up completes), then drives
    // reinitContextDependentComponents() to construct rmm and drain the
    // queue. CAS-guarded so we never spawn two of these.
    private static final java.util.concurrent.atomic.AtomicBoolean contextWatchdogInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long CONTEXT_WATCHDOG_POLL_INTERVAL_MS = 2_000L;
    private static final long CONTEXT_WATCHDOG_MAX_DURATION_MS = 60_000L;
    
    /** Get the shared app context (for use by other components in this process). */
    public static android.content.Context getAppContext() { return sharedAppContext; }
    
    /** Check if the shared context is a broken fallback (null base). */
    private static boolean isContextBroken() {
        if (sharedAppContext == null) return true;
        return isContextBrokenFor(sharedAppContext);
    }
    
    /** Check if a given context is a broken fallback (null base). */
    private static boolean isContextBrokenFor(android.content.Context ctx) {
        if (ctx == null) return true;
        if (ctx instanceof PermissionBypassContext) {
            try {
                ctx.getMainLooper();
                return false;
            } catch (NullPointerException e) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Re-initialize components that depend on a valid app context.
     * Called on ACC ON after successfully recreating a broken context.
     */
    private static void reinitContextDependentComponents() {
        // Re-init BydDataCollector (was 0/17 devices with broken context)
        try {
            com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
            collector.init(sharedAppContext);
            collector.logSummary();
            log("ACC ON: BydDataCollector re-initialized (" + collector.getData().availableDevices.length + " devices)");
        } catch (Exception e) {
            log("ACC ON: BydDataCollector re-init failed: " + e.getMessage());
        }

        // Start BYD Cloud MQTT subscriber (if credentials configured)
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().startSubscriberIfConfigured();
        } catch (Exception e) {
            log("Cloud subscriber start failed: " + e.getMessage());
        }
        
        // Re-init GearMonitor with valid context
        try {
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            gearMonitor.init(sharedAppContext);
            if (telemetryDataCollector != null) {
                gearMonitor.setTelemetrySource(telemetryDataCollector);
            }
            log("ACC ON: GearMonitor re-initialized with valid context");
        } catch (Exception e) {
            log("ACC ON: GearMonitor re-init failed: " + e.getMessage());
        }
        
        // Re-init TelemetryDataCollector (BYD speed/gear/light devices were unavailable)
        try {
            if (telemetryDataCollector != null) {
                telemetryDataCollector.init(sharedAppContext);
                log("ACC ON: TelemetryDataCollector re-initialized");
            }
        } catch (Exception e) {
            log("ACC ON: TelemetryDataCollector re-init failed: " + e.getMessage());
        }
        
        // Re-init RecordingModeManager if it wasn't created (sharedAppContext was null at init time)
        if (recordingModeManager == null && gpuPipeline != null) {
            try {
                recordingModeManager = new com.overdrive.app.recording.RecordingModeManager(
                    sharedAppContext, gpuPipeline);
                log("ACC ON: RecordingModeManager created with valid context");

                // FIX (audit R1, finding "Boot probe vs initSurveillance early-return
                // when sharedAppContext null"): drain queued pendingAccOn/Off into the
                // freshly-created rmm. Without this, the boot-time queue (drains at
                // initSurveillance + boot-probe at :898) sits stagnant until the
                // user toggles ACC again. Mirrors the dispatch shape used by the
                // initSurveillance drains; gpuPipeline is non-null in this branch.
                try {
                    if (pendingAccOn) {
                        log("ACC ON: replaying pending ACC ON to newly-created rmm");
                        pendingAccOn = false;
                        recordingModeManager.onAccStateChanged(true);
                        // FIX (audit R7, finding "reinitContextDependentComponents
                        // drain bypasses daemon-level ACC-ON side-effects"):
                        // the daemon-level onAccStateChanged(true) path also
                        // starts GearMonitor and forwards current gear so
                        // DRIVE_MODE recording can fire on gear changes. The
                        // direct rmm replay above skips that, so DRIVE_MODE
                        // would be wedged until the next AccSentry 30 s
                        // heartbeat re-runs the full daemon chain. Start
                        // GearMonitor and replay current gear here so
                        // DRIVE_MODE recording is live immediately.
                        try {
                            com.overdrive.app.monitor.GearMonitor gm =
                                com.overdrive.app.monitor.GearMonitor.getInstance();
                            if (!gm.isRunning()) {
                                try {
                                    gm.start();
                                    log("ACC ON drain: GearMonitor started (was not running)");
                                } catch (Exception e) {
                                    log("ACC ON drain: GearMonitor start failed: " + e.getMessage());
                                }
                            }
                            if (gm.isRunning()) {
                                int curGear = gm.getCurrentGear();
                                log("ACC ON drain: replaying current gear=" + curGear + " to rmm");
                                recordingModeManager.onGearChanged(curGear);
                            }
                        } catch (Throwable gt) {
                            log("WARN: GearMonitor drain on context-recreate failed: " + gt.getMessage());
                        }
                    } else if (pendingAccOff) {
                        log("ACC ON: replaying pending ACC OFF to newly-created rmm");
                        pendingAccOff = false;
                        recordingModeManager.onAccStateChanged(false);
                    }
                } catch (Throwable t) {
                    log("WARN: rmm drain on context-recreate failed: " + t.getMessage());
                }
            } catch (Exception e) {
                log("ACC ON: RecordingModeManager creation failed: " + e.getMessage());
            }
        }
        
        // Re-init VehicleDataMonitor
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            vehicleMonitor.init(sharedAppContext);
            if (!vehicleMonitor.isRunning()) {
                vehicleMonitor.start();
            }
            log("ACC ON: VehicleDataMonitor re-initialized");
        } catch (Exception e) {
            log("ACC ON: VehicleDataMonitor re-init failed: " + e.getMessage());
        }
    }
    
    // Build stamp printed at startup so logs identify the running build.
    // BUMP THIS on every code change you intend to deploy + verify.
    private static final String BUILD_TAG = "20260603-coldstart-recfix-1";

    // Lock file for singleton enforcement
    private static final String LOCK_FILE = "/data/local/tmp/camera_daemon.lock";
    private static java.io.RandomAccessFile lockFile;
    private static java.nio.channels.FileLock fileLock;

    public static void main(String[] args) {
        initFileLogging();

        // CRITICAL: Acquire singleton lock FIRST - exit if another instance is running
        if (!acquireSingletonLock()) {
            log("ERROR: Another CameraDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }

        // Clear any stale screen-deterrent flags left from a previous unclean
        // exit (SIGKILL bypasses our shutdown hook). Without this, AccSentry
        // could see a future screenDeterrentActiveUntilMs and skip backlight
        // off forever, draining the 12V battery until the next ACC cycle.
        try {
            java.util.Map<String, Object> reset = new java.util.HashMap<>();
            reset.put("screenDeterrentActiveUntilMs", 0L);
            reset.put("screenDeterrentForceStop", false);
            com.overdrive.app.config.UnifiedConfigManager.updateValues(
                    "surveillance", reset);
        } catch (Exception ignored) {}
        
        // Enable daemon logging for StorageManager (uses DaemonLogger instead of android.util.Log).
        // The StorageManager singleton itself is constructed later, after the HTTP/TCP/IPC
        // server threads are already running — so a flaky external volume can't wedge the
        // daemon's recovery UI. See "RECOVERY-FIRST STARTUP" comment further down.
        com.overdrive.app.storage.StorageManager.enableDaemonLogging();

        log("=== CAMERA DAEMON STARTING ===");
        // Build stamp — bump BUILD_TAG on every change so the field log
        // unambiguously identifies which build is actually running. (Deploys
        // via `adb install -r` do NOT restart the in-memory daemon; this line
        // makes it trivial to confirm a restart actually loaded new code.)
        log("BUILD_TAG: " + BUILD_TAG);
        log("PID: " + android.os.Process.myPid() + ", UID: " + android.os.Process.myUid());

        // Grant all manifest permissions via shell (supplements PermissionBypassContext)
        PermissionGranter.grantAllPermissions(APP_PACKAGE_NAME());

        // Global exception handler - NEVER let the daemon die from uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (!(throwable instanceof ThreadDeath)) {
                log("FATAL: Uncaught exception in " + thread.getName() + ": " + throwable.getMessage());
                if (throwable.getCause() != null) {
                    log("  Cause: " + throwable.getCause().getMessage());
                }
                // Log stack trace
                for (StackTraceElement element : throwable.getStackTrace()) {
                    log("    at " + element.toString());
                }
                // DO NOT kill the daemon - just log and continue
                // The daemon should stay alive even if individual operations fail
            }
        });

        if (Looper.myLooper() == null) Looper.prepare();
        mainHandler = new Handler(Looper.myLooper());

        // Parse arguments (sets outputDir if provided)
        parseArguments(args);
        
        // Initialize outputDir if not set by arguments
        if (outputDir == null) {
            outputDir = PATH_CAMERA_OUTPUT_DIR();
        }
        
        // Load native libraries
        loadNativeLibraries();
        
        // Create directories
        new File(outputDir).mkdirs();
        new File(STREAM_DIR()).mkdirs();
        new File(APP_STREAM_DIR).mkdirs();
        
        // Generate device ID
        generateDeviceId();
        
        log("Output dir: " + outputDir);
        log("Device ID: " + deviceId);
        
        // Camera scan disabled — opening/closing all camera IDs can briefly
        // disrupt the BYD dashcam. Camera ID is auto-detected in GpuSurveillancePipeline.init()
        // scanCameras();

        // === RECOVERY-FIRST STARTUP ===
        // Construct + spawn the HTTP/TCP/IPC servers BEFORE any subsystem that
        // can block on external state (StorageManager mount probes,
        // GPU pipeline init, BYD HAL reflection). Reasoning:
        //
        //   The HTTP API is the user's only recovery surface — if the daemon
        //   wedges during init (e.g. a configured-but-missing SD/USB volume
        //   makes `sm list-volumes` / `sm mount` hang on certain ROMs), the
        //   user has no way to clear the bad config from the web UI because
        //   the web UI never came up. Pre-v18 the daemon's startup was small
        //   enough that this never bit us; v18.1's USB-storage support added
        //   shell-process calls without timeouts inside the StorageManager
        //   constructor, surfacing the latent fragility.
        //
        //   Handlers null-check gpuPipeline / storageManager and degrade
        //   gracefully when called before those subsystems are ready, so it
        //   is safe to expose the API early. A request that needs a
        //   subsystem returns 503 / a structured "not ready" payload until
        //   it's wired up — the user can still hit /api/storage/config to
        //   force surveillanceStorageType=INTERNAL and unblock the rest.
        tcpServer = new TcpCommandServer(TCP_PORT);
        httpServer = new HttpServer(HTTP_PORT);
        ipcServer = new SurveillanceIpcServer(19877);
        aacIngestServer = new com.overdrive.app.server.AacIngestServer();
        accMonitor = new AccMonitor();

        new Thread(tcpServer::start, "TcpServer").start();
        new Thread(httpServer::start, "HttpServer").start();
        new Thread(ipcServer, "SurveillanceIPC").start();
        new Thread(aacIngestServer, "AacIngest").start();

        // Init app context. This will break the app if run in a thread
        if (sharedAppContext == null) {
            try {
                sharedAppContext = createAppContext();
            } catch (Throwable ignored) {}
        }

        // Notifications subsystem — registry, push subscriptions, sinks.
        // Lives in this process because HttpServer (where the API routes bind)
        // runs here, and every v1 emit source (surveillance, proximity, tyre)
        // lives here too. Init on a background thread because reading APK
        // assets can take a moment and we don't want to delay HTTP startup.
        new Thread(() -> {
            try {
                initNotifications();
            } catch (Exception e) {
                log("Notifications init failed: " + e.getMessage());
            }
        }, "NotificationsInit").start();

        // SOTA: Initialize unified config manager (handles migration from legacy configs)
        com.overdrive.app.config.UnifiedConfigManager.init();

        // OTA-survives stickiness for the "Disable Native DVR" toggle.
        // If the user previously disabled com.byd.cdr but a factory reset /
        // OTA / external `pm enable` resurrected it, re-apply pm disable-user
        // here. Cheap (two `pm list packages` calls); no-op when the user
        // never opted in or the package isn't on this trim. We're already
        // running as UID shell so pm calls succeed directly.
        com.overdrive.app.server.OemDashcamApiHandler.enforceStickyDisableIfRequested();
        // OEM Dashcam pipeline: sticky enable is INTENTIONALLY deferred until
        // after the ACC hardware probe at line ~794. The two-axis resolver
        // gates each axis on AccMonitor.isAccOn(), and AccMonitor defaults to
        // accOn=false at boot. If we run the resolver here, a daemon respawn
        // mid-drive (ACC actually ON) would be misclassified as parked and
        // would arm the surveillance axis on top of an actively-driving car.
        // Move sticky-enable below probeAccState so the resolver sees the
        // real ACC state on its first run.
        // Concurrent-AVM probe: write camera.concurrentAvmSupported once
        // when both pano and OEM ids are known. The probe opens both
        // AVMCameras for ~2-5s to verify HAL allows simultaneous clients.
        // Async on a background thread so daemon boot isn't blocked. The
        // result feeds OemDashcamPipeline.applyBitrateBudgetCap so the
        // OEM pipeline's bitrate is correctly capped when concurrent
        // operation is supported. Without this call the probe is dead
        // code and concurrentAvmSupported stays at -1 forever.
        new Thread(() -> {
            try {
                Thread.sleep(15_000);   // wait for pano probe to settle
                com.overdrive.app.camera.ConcurrentAvmProbe.runIfNeeded();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log("ConcurrentAvmProbe boot run failed: " + t.getMessage());
            }
        }, "ConcurrentAvmProbeBoot").start();

        // Load persisted quality settings BEFORE initializing surveillance
        // This ensures the encoder is created with the correct settings
        HttpServer.loadPersistedSettings();

        // Construct StorageManager AFTER the servers are already accepting
        // connections. The constructor reads the unified config's `storage`
        // section and may attempt to mount an SD/USB volume that isn't
        // present — those calls are time-bounded (see ensureVolumeMounted)
        // but on a pathological ROM they can still take seconds. Doing this
        // here means the user's web UI is already alive even on slow paths,
        // and the watchdogs below kick in once the singleton exists.
        com.overdrive.app.storage.StorageManager storageManager =
            com.overdrive.app.storage.StorageManager.getInstance();
        storageManager.fixAllPermissions();

        // Start the SD-card mount watchdog at daemon boot (instead of only on
        // ACC OFF). The watchdog no-ops when no storage type is set to SD, so
        // it's safe to start unconditionally — but it must run continuously
        // because BYD/Android can unmount the SD card at any time, including
        // while ACC is ON. Stopping it on ACC ON (the previous behavior) left
        // a hole where the HTTP server returned empty recordings until the
        // user cycled ACC OFF→ON.
        storageManager.startSdCardWatchdog();

        // Touch the OEM-dashcam cleaner singleton so its constructor runs
        // and (if enabled in saved config) auto-starts the periodic monitor.
        // Without this the cleaner is lazy-initialized on first UI/API hit,
        // meaning a fresh boot with `enabled=true` in config never actually
        // begins reserving SD space until the user opens a settings screen.
        com.overdrive.app.storage.ExternalStorageCleaner.getInstance();

        // Periodic cleanup of our own recordings/surveillance dirs — runs
        // continuously instead of only while a recording is active. This
        // catches the case where the daemon crashed mid-recording leaving
        // the dir at 95%, or the user lowered the size limit while nothing
        // was recording. Cost: one directory walk every 30s; the threshold
        // check exits early if usage is below 90%.
        storageManager.startPeriodicCleanup();

        // Data-layer kickoff. RecordingsIndex's H2 open + warmup walk is
        // independent of GPU / ABRP / MQTT / TripDB and dominated user-
        // visible "Recordings page takes 5+ min" because it used to run
        // last in the serial init chain. Move it to a dedicated thread
        // that races initSurveillance() — the index is ready before the
        // GPU pipeline is armed in 99% of cases. File watchers wired in
        // the same block so they observe writes that begin during
        // initSurveillance().
        //
        // dataLayerInitFuture lets shutdown() join cleanly without
        // serializing the GPU init behind it. start() wrapped in try/
        // catch so an OOM/Security exception at thread spawn doesn't
        // leave the future forever pending.
        dataLayerInitFuture = new java.util.concurrent.CompletableFuture<>();
        try {
            Thread dataLayerThread = new Thread(() -> {
                try {
                    log("Initializing RecordingsIndex (parallel)...");
                    long t0 = System.currentTimeMillis();
                    boolean idxOk = com.overdrive.app.server.RecordingsIndex.getInstance().init();
                    if (idxOk) {
                        log("RecordingsIndex initialized in "
                                + (System.currentTimeMillis() - t0) + "ms — kicking off async warmup");
                        com.overdrive.app.server.RecordingsIndex.getInstance().warmupAsync();
                        try {
                            RecordingsIndexFileWatcher.getInstance().start();
                        } catch (Throwable t) {
                            log("RecordingsIndexFileWatcher start error: "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                    } else {
                        log("RecordingsIndex init returned false — API will fall back to direct-FS");
                    }
                } catch (Exception e) {
                    log("RecordingsIndex init error: " + e.getMessage());
                } finally {
                    dataLayerInitFuture.complete(null);
                }
            }, "RecordingsIndexInit");
            dataLayerThread.setDaemon(true);
            dataLayerThread.start();
        } catch (Throwable t) {
            log("RecordingsIndex thread spawn failed: " + t.getMessage());
            dataLayerInitFuture.complete(null);
        }

        // Note: we deliberately don't seed the version file here. The
        // updater writes it after a successful install with the actual
        // GitHub release string (e.g. "alpha-v15.6"). Until the user has
        // run a check-for-update the file is absent and
        // AppUpdater.getDisplayVersionFromFile() returns the
        // DISPLAY_VERSION_FALLBACK ("Manually Installed") which is more
        // accurate than seeding gradle's BuildConfig.VERSION_NAME stub
        // ("11.0") that has no relationship to the release the user
        // actually installed.


        // ImageReader FPS probe sentinel: when /data/local/tmp/run_imagereader_probe
        // exists, run AvmImageReaderFpsProbe BEFORE initSurveillance so the probe
        // has exclusive HAL access. Verifies whether replacing the live pipeline's
        // SurfaceTexture consumer with an ImageReader unblocks the ~8.5 fps panoramic
        // throttle (see CAMERA_FPS_INVESTIGATION.md). Sentinel is consumed (deleted)
        // so the probe runs once per `touch` invocation.
        try {
            File irProbeSentinel = new File("/data/local/tmp/run_imagereader_probe");
            if (irProbeSentinel.exists()) {
                log("=== ImageReader probe sentinel detected — running probe ===");
                File irProbeDir = new File("/data/local/tmp/imagereader_probe");
                new com.overdrive.app.camera.AvmImageReaderFpsProbe(irProbeDir).run();
                if (!irProbeSentinel.delete()) {
                    log("WARN: Could not delete ImageReader probe sentinel " + irProbeSentinel);
                }
                log("=== ImageReader probe finished — continuing with normal startup ===");
            }
        } catch (Throwable t) {
            log("ImageReader probe invocation failed: " + t.getMessage());
        }

        // Initialize surveillance module (will use loaded settings)
        initSurveillance();

        // Apply persisted settings to GPU pipeline (for runtime changes)
        // Note: Codec/bitrate are already applied during init, but this ensures
        // the config object is in sync and handles any settings that need runtime application
        applyPersistedSettings();

        // FIX (audit R1): drains were reading AccMonitor.isAccOn() which
        // defaults to false BEFORE the AccMonitor::start IPC seed and BEFORE
        // the recovery HW probe at :883. On daemon restart with ACC actually
        // ON, the race-guard branch silently discarded pendingAccOn. Probe
        // hardware directly here so the guard reflects ground truth, not the
        // uninitialised AccMonitor cache. probeAccState returns accIsOff
        // (true == OFF); invert for accIsOn semantics used below.
        //
        // FIX (audit R4): probeAccState's sentinel branch returns `!accOn`,
        // and at daemon boot accOn defaults to false → sentinel reading
        // (powerLevel=4 FAKE_OK or 255 INVALID) lies "ACC OFF" and the
        // pendingAccOn drain at :727 gets discarded falsely. Loop the probe
        // up to 3× 200ms so transient HAL bluffs settle to a real reading
        // before we treat probeAccState as authoritative.
        boolean hwAccIsOff_drain = probeAccStateWithBackoff("drain");
        boolean hwAccIsOn_drain = !hwAccIsOff_drain;

        // If ACC went OFF before pipeline was ready, apply it now
        // RACE CONDITION FIX: Also verify ACC is still OFF before applying.
        // If ACC turned ON during pipeline init, the pending state is stale.
        //
        // FIX (audit R1, finding "Boot probe vs initSurveillance early-return
        // when sharedAppContext null"): also gate the drain on
        // recordingModeManager != null. initSurveillance() can leave gpuPipeline
        // non-null while rmm is null when sharedAppContext could not be created
        // (system_server transient at daemon respawn). Without this gate the
        // drain would clear pendingAccOff/On and call onAccStateChanged on a
        // pipeline whose rmm is still null — the side-effects fire but no
        // recording gets started, and the flag is lost so the boot probe at
        // :898 can't recover it. Preserve the pending flag so the boot probe
        // (and any future rmm-init re-entry) can replay it.
        if (pendingAccOff && gpuPipeline != null && recordingModeManager != null) {
            if (!hwAccIsOn_drain) {
                log("Applying pending ACC OFF surveillance request (HW-probed)...");
                pendingAccOff = false;
                onAccStateChanged(true);
            } else {
                log("Pending ACC OFF discarded — HW probe shows ACC ON (race condition guard)");
                pendingAccOff = false;
            }
        } else if (pendingAccOff && gpuPipeline != null && recordingModeManager == null) {
            log("WARN: Pending ACC OFF preserved — recordingModeManager null "
                + "(initSurveillance early-return); deferring to boot probe / re-init");
        }

        // Symmetric drain: if an ACC ON IPC was queued while gpuPipeline was
        // null (cold-boot dispatch ordering), fire it now so RecordingModeManager
        // gets seeded and pano recording starts. Mirrors the ACC OFF drain
        // above with the inverse hardware-state guard. gpuPipeline is non-null
        // here, so onAccStateChanged takes the full dispatch path (no recursion
        // back into this branch).
        //
        // FIX (audit R1, same finding as ACC OFF drain): preserve pendingAccOn
        // when rmm is null. Otherwise dispatch's :3173 "recordingModeManager
        // null on ACC ON — recording disabled" warning fires and the queued ON
        // state is lost; the boot probe's `!pendingAccOn` gate at :903 then
        // evaluates true on stale data and the recovery seed never fires.
        if (pendingAccOn && gpuPipeline != null && recordingModeManager != null) {
            if (hwAccIsOn_drain) {
                log("Applying pending ACC ON request (HW-probed)...");
                pendingAccOn = false;
                onAccStateChanged(false);
            } else {
                log("Pending ACC ON discarded — HW probe shows ACC OFF (race condition guard)");
                pendingAccOn = false;
            }
        } else if (pendingAccOn && gpuPipeline != null && recordingModeManager == null) {
            log("WARN: Pending ACC ON preserved — recordingModeManager null "
                + "(initSurveillance early-return); deferring to boot probe / re-init");
        }
        
        // tcpServer / httpServer / ipcServer threads were spawned at the very top
        // of main() (recovery-first startup). The ACC monitor is started here
        // because the pendingAccOff check above must run first — otherwise an
        // ACC OFF that arrives during initSurveillance() can be missed.
        new Thread(accMonitor::start, "AccMonitor").start();

        // Initialize GPS monitor with app context for standard LocationManager access
        initGpsMonitor();

        // Initialize Safe Location Manager (geofence zones)
        com.overdrive.app.surveillance.SafeLocationManager.getInstance().init();

        // RoadSense: daemon-side road-hazard detection (D-019/D-023). BydDataCollector
        // + GpsMonitor are up by now (initGpsMonitor above; collector re-init on ACC ON),
        // which RoadSense reuses (D-020). Never let it block daemon boot.
        try {
            roadSense = new com.overdrive.app.roadsense.RoadSenseController(sharedAppContext);
            roadSense.start();
            log("RoadSense controller started");
        } catch (Throwable t) {
            log("RoadSense start failed: " + t.getMessage());
        }

        // Pre-warm the geocode cache so the first recording's place
        // resolution is a synchronous in-memory hit instead of a 2.5 MB
        // disk read on the recorder-stop path. Gated on at least one flow
        // having geocoding enabled — for the >95% of users who never
        // opt in, paying a 4 MB JSON read at every daemon boot is pure
        // waste. Users who enable the feature later trigger the natural
        // lazy-load path (first put / first get) at no perceptible cost.
        try {
            boolean recordingOn = com.overdrive.app.config.UnifiedConfigManager
                    .isGeocodingEnabledForFlow("recording");
            boolean surveillanceOn = com.overdrive.app.config.UnifiedConfigManager
                    .isGeocodingEnabledForFlow("surveillance");
            if (recordingOn || surveillanceOn) {
                com.overdrive.app.geo.GeoCache.getInstance().ensureLoaded();
            }
        } catch (Throwable t) {
            log("GeoCache prewarm failed: " + t.getMessage());
        }

        // Recordings index warmup. The very first /api/recordings call after
        // a daemon restart used to pay a directory walk + sidecar parse for
        // every recording (100-1000 typical) on the HTTP worker thread, so
        // the first events.html load showed a 1-3 s spinner. The H2-backed
        // RecordingsIndex now persists that work across daemon restarts;
        // warmupAsync() is a no-op once warmupComplete is set, and the first
        // run still happens off the user-visible path.
        //
        // Note: RecordingsIndex.init() + the FileObservers are wired in
        // alongside TripAnalyticsManager init below — they need
        // StorageManager/SohEstimator-adjacent state to be ready first.
        // The legacy RecordingsApiHandler.warmupCache() in-memory cache is
        // superseded by the index and will be removed in a follow-up.

        // Initialize SohEstimator (load persisted SOH — capacity detection deferred until collector is ready)
        try {
            sohEstimator = new SohEstimator();
            sohEstimator.init();
        } catch (Exception e) {
            log("SohEstimator init error: " + e.getMessage());
        }

        // Initialize Trip Analytics on its own thread, kicked HERE so it
        // races initVehicleDataMonitor() (which on long-running installs
        // pays a 100+ s background SOC migration) plus ABRP / MQTT init.
        // The H2 trip database open + orphan-trip cleanup + size_bytes
        // backfill takes ~3-5 s and writes nothing the UI depends on
        // until the user actually shifts out of P, by which point this
        // future is long completed.
        // GearMonitor.getCurrentGear() returns GEAR_P when not yet
        // started (initVehicleDataMonitor wires it later), so the
        // auto-start branch correctly no-ops at boot.
        // tripAnalyticsInitFuture lets shutdown() join cleanly.
        final SohEstimator sohEstSnapshot = sohEstimator;
        tripAnalyticsInitFuture = new java.util.concurrent.CompletableFuture<>();
        try {
            Thread tripAnalyticsThread = new Thread(() -> {
                try {
                    log("Initializing Trip Analytics (parallel)...");
                    long t0 = System.currentTimeMillis();
                    com.overdrive.app.trips.TripAnalyticsManager tam =
                            new com.overdrive.app.trips.TripAnalyticsManager();
                    tam.init(sharedAppContext, telemetryDataCollector, sohEstSnapshot);
                    tripAnalyticsManager = tam;
                    // initSurveillance() runs synchronously on the main
                    // thread BEFORE this thread is spawned, so
                    // telemetryDataCollector is already published. Re-read
                    // the volatile and forward defensively in case a future
                    // refactor moves the parallel kick earlier — the
                    // setTelemetryDataCollector call is idempotent.
                    com.overdrive.app.telemetry.TelemetryDataCollector tdc = telemetryDataCollector;
                    if (tdc != null) {
                        try { tam.setTelemetryDataCollector(tdc); }
                        catch (Throwable ignored) {}
                    }
                    log("Trip Analytics initialized in "
                            + (System.currentTimeMillis() - t0) + "ms (enabled="
                            + tam.isEnabled() + ")");

                    // ONE-TIME migration: clear poisoned consumption buckets
                    // if this is a PHEV and the migration hasn't been done.
                    java.io.File bucketMigrationMarker = new java.io.File("/data/local/tmp/overdrive_bucket_migration_done");
                    if (sohEstSnapshot != null && sohEstSnapshot.getNominalCapacityKwh() > 0
                            && sohEstSnapshot.getNominalCapacityKwh() < 30.0
                            && tam.getDatabase() != null
                            && !bucketMigrationMarker.exists()) {
                        tam.getDatabase().clearConsumptionBuckets();
                        log("One-time PHEV bucket migration: cleared poisoned consumption data");
                        try {
                            new java.io.FileWriter(bucketMigrationMarker).close();
                        } catch (Exception e) {
                            log("WARNING: Could not write bucket migration marker: " + e.getMessage());
                        }
                    }

                    // AUTO-START: if gear is non-P, start trip recording
                    // (handles mid-drive daemon restart).
                    if (tam.isEnabled()) {
                        try {
                            int currentGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                            if (currentGear != com.overdrive.app.monitor.GearMonitor.GEAR_P) {
                                log("Trip Analytics: non-P gear detected at startup (gear="
                                        + com.overdrive.app.monitor.GearMonitor.gearToString(currentGear)
                                        + ") — auto-starting trip recording");
                                tam.onGearChanged(currentGear);
                            }
                        } catch (Exception e) {
                            log("Trip Analytics gear probe error: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log("Trip Analytics init error: " + e.getMessage());
                } finally {
                    tripAnalyticsInitFuture.complete(null);
                }
            }, "TripAnalyticsInit");
            tripAnalyticsThread.setDaemon(true);
            tripAnalyticsThread.start();
        } catch (Throwable t) {
            log("Trip Analytics thread spawn failed: " + t.getMessage());
            tripAnalyticsInitFuture.complete(null);
        }

        // Initialize Vehicle Data Monitor + BydDataCollector
        initVehicleDataMonitor();

        // Now that BydDataCollector is ready, detect car model for accurate capacity
        try {
            if (sohEstimator != null) {
                sohEstimator.autoDetectCarModel(sharedAppContext);
                sohEstimator.seedInitialEstimate();
                log("SohEstimator: " + (sohEstimator.hasEstimate()
                        ? String.format("%.1f%%", sohEstimator.getCurrentSoh())
                        : "no estimate")
                    + " (capacity: " + String.format("%.2f kWh", sohEstimator.getNominalCapacityKwh()) + ")");
            }
        } catch (Exception e) {
            log("SohEstimator autoDetect error: " + e.getMessage());
        }

        // Initialize ABRP Telemetry Service
        try {
            log("Initializing ABRP telemetry...");
            AbrpConfig abrpConfig = new AbrpConfig();
            abrpConfig.load();
            
            // Auto-set car_model in ABRP config if not already set
            if (sohEstimator != null && (abrpConfig.getCarModel() == null || abrpConfig.getCarModel().isEmpty())) {
                double cap = sohEstimator.getNominalCapacityKwh();
                String model = capacityToModelName(cap);
                if (model != null) {
                    abrpConfig.setCarModel(model);
                    abrpConfig.save();
                    log("Auto-detected car model for ABRP: " + model + " (" + cap + " KWh)");
                }
            }
            
            abrpTelemetryService = new AbrpTelemetryService(abrpConfig, sohEstimator);
            abrpTelemetryService.init(sharedAppContext);
            
            // Set IPC references so SurveillanceIpcServer can access ABRP
            SurveillanceIpcServer.setAbrpReferences(abrpConfig, abrpTelemetryService);
            
            if (abrpConfig.isEnabled() && abrpConfig.isConfigured()) {
                abrpTelemetryService.start();
                log("ABRP telemetry started (token: " + abrpConfig.getMaskedToken() + ")");
            } else {
                log("ABRP telemetry not started (enabled=" + abrpConfig.isEnabled() + ", configured=" + abrpConfig.isConfigured() + ")");
            }
        } catch (Exception e) {
            log("ABRP init error: " + e.getMessage());
        }

        // Initialize MQTT Connection Manager
        try {
            log("Initializing MQTT connections...");
            mqttConnectionManager = new com.overdrive.app.mqtt.MqttConnectionManager();
            mqttConnectionManager.init(deviceId, sohEstimator);

            // Set IPC reference so SurveillanceIpcServer can access MQTT
            SurveillanceIpcServer.setMqttManager(mqttConnectionManager);

            // Start all enabled connections
            mqttConnectionManager.startAll();
            log("MQTT initialized (" + mqttConnectionManager.getActiveCount() + " active connections)");
        } catch (Exception e) {
            log("MQTT init error: " + e.getMessage());
        }

        // Start BYD Cloud MQTT subscriber for remote command results + push data
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().startSubscriberIfConfigured();
        } catch (Exception e) {
            log("Cloud MQTT subscriber start failed: " + e.getMessage());
        }

        // Trip Analytics + RecordingsIndex init were both kicked in parallel
        // earlier in main() — see dataLayerInitFuture (after StorageManager)
        // and tripAnalyticsInitFuture (after sohEstimator.init). By the time
        // execution reaches here, both inits are almost always already done;
        // the recordings-index warmup keeps running on its own thread and
        // clients see warming=true responses until it finishes.

        // Initialize OdometerReader for trip distance
        try {
            com.overdrive.app.trips.OdometerReader.getInstance().init(sharedAppContext);
        } catch (Exception e) {
            log("OdometerReader init error: " + e.getMessage());
        }

        // Restore stream mode from previous session
        loadStreamMode();

        // RECOVERY: Probe ACC state directly from hardware.
        // If CameraDaemon was restarted (e.g., EGL crash watchdog) while ACC was off,
        // AccSentryDaemon won't re-send the ACC OFF command. Reading the hardware
        // directly has zero dependency on AccSentryDaemon.
        try {
            // FIX (audit R4): use the same backoff probe as the drain path —
            // a single sentinel reading at boot can falsely report ACC OFF
            // because AccMonitor.accOn defaults to false and probeAccState
            // returns `!accOn` on sentinel power levels. Looping settles
            // transient HAL bluffs before we drop pano CONTINUOUS / DRIVE_MODE
            // mid-drive into a false sentry entry.
            boolean accIsOff = probeAccStateWithBackoff("recovery");
            if (accIsOff) {
                log("RECOVERY: Hardware probe shows ACC OFF — entering sentry mode");
                onAccStateChanged(true);  // true = accIsOff
            } else if (!pendingAccOn
                    && recordingModeManager != null
                    && !recordingModeManager.isAccOn()) {
                // Symmetric ACC ON recovery: daemon restarted while car is on.
                // RecordingModeManager hasn't been seeded by an ACC IPC, so the
                // recording-mode dispatcher won't start CONTINUOUS / DRIVE_MODE
                // pano recording until the user toggles ACC. Seed it directly.
                // pendingAccOn guard avoids fighting the initSurveillance drain
                // when the IPC arrived during init.
                //
                // FIX (audit R5): route through CameraDaemon.onAccStateChanged
                // (accIsOff=false) instead of seeding RMM directly. Direct seed
                // bypassed the dedup cache + full ACC ON side-effect chain
                // (AccMonitor.setAccState, surveillance disable, gear monitor
                // restart). lastDispatchedAccIsOff is null on cold boot, so the
                // dedup short-circuit can't fire and the full chain runs once.
                log("RECOVERY: Hardware probe shows ACC ON — dispatching full ACC ON chain");
                onAccStateChanged(false);
            } else if (!accIsOff && recordingModeManager == null) {
                // FIX (audit R1): initSurveillance early-returned with
                // sharedAppContext null, leaving rmm uncreated. The previous
                // boot probe had no branch for this — ACC ON went undelivered
                // until the user toggled ACC. Queue pendingAccOn so the next
                // ACC IPC handler (or the re-init path inside the ACC ON
                // hook) seeds the manager and dispatches recording start.
                // Also seed AccMonitor so downstream consumers don't read
                // the false default before any IPC arrives.
                log("RECOVERY: Hardware probe shows ACC ON but RMM null — "
                    + "queuing pendingAccOn for delayed seed");
                com.overdrive.app.monitor.AccMonitor.setAccState(true);
                pendingAccOn = true;
                pendingAccOff = false;
            }
        } catch (Exception e) {
            log("ACC hardware probe error: " + e.getMessage());
        }

        // Now that AccMonitor has been seeded by the hardware probe, the OEM
        // resolver can produce the right desired-state for the current ACC
        // phase. enforceStickyEnableIfRequested submits the recalc to the
        // dedicated lifecycle executor; it runs async so daemon boot isn't
        // blocked by AVC warmup + AVMCamera open inside the OEM pipeline.
        com.overdrive.app.server.OemDashcamApiHandler.enforceStickyEnableIfRequested();

        log("Daemon ready on TCP:" + TCP_PORT + " HTTP:" + HTTP_PORT);

        // Periodic memory monitor — mirrors AccSentryDaemon.logMemoryStatus().
        // Without this, post-mortem on a 1-2hr park silently dying tells us
        // nothing about whether the cause was OOM (RSS climbing toward limit)
        // or HAL-cascade (RSS flat, native FD count climbing, etc.). Cheap:
        // one ActivityManager.getMemoryInfo() + one Runtime.totalMemory()
        // every 5 minutes.
        startPeriodicMemoryLogging();

        // RESILIENT LOOPER: BYD framework listeners (gearbox, bodywork, etc.) can throw
        // uncaught exceptions from their internal processing (e.g., learningEPB → CarSettings
        // UID mismatch). These exceptions escape through Handler.dispatchMessage and kill
        // Looper.loop(). Wrapping in a retry loop keeps the daemon alive.
        while (running.get()) {
            try {
                Looper.loop();
                // Looper.loop() only returns if someone calls quit()
                break;
            } catch (Throwable t) {
                log("LOOPER CRASH (recovered): " + t.getClass().getSimpleName() + ": " + t.getMessage());
                if (t.getCause() != null) {
                    log("  Cause: " + t.getCause().getMessage());
                }
                // Log first 5 stack frames
                StackTraceElement[] stack = t.getStackTrace();
                for (int i = 0; i < Math.min(5, stack.length); i++) {
                    log("    at " + stack[i].toString());
                }
                // Continue looping — the Looper is still valid, just the current message failed
            }
        }
    }
    
    /**
     * Applies persisted settings to the GPU pipeline after initialization.
     */
    private static void applyPersistedSettings() {
        if (gpuPipeline == null) return;
        
        try {
            // Apply bitrate setting to config and encoder
            String bitrate = HttpServer.getRecordingBitrate();
            if (bitrate != null) {
                setRecordingBitrate(bitrate);
                log("Applied persisted bitrate: " + bitrate);
            }
            
            // Apply codec setting to config (encoder already created with this codec)
            String codec = HttpServer.getRecordingCodec();
            if (codec != null) {
                // Just update the config, don't reinitialize encoder
                com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec videoCodec;
                switch (codec.toUpperCase()) {
                    case "H265":
                    case "HEVC":
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265;
                        break;
                    case "H264":
                    case "AVC":
                    default:
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                        break;
                }
                gpuPipeline.getConfig().setVideoCodec(videoCodec);
                log("Applied persisted codec: " + codec);
            }
            
            // Apply quality settings
            String recQuality = HttpServer.getRecordingQuality();
            if (recQuality != null) {
                setRecordingQuality(recQuality);
                log("Applied persisted recording quality: " + recQuality);
            }
            
            String streamQuality = HttpServer.getStreamingQuality();
            if (streamQuality != null) {
                setStreamingQuality(streamQuality);
                log("Applied persisted streaming quality: " + streamQuality);
            }
        } catch (Exception e) {
            log("Error applying persisted settings: " + e.getMessage());
        }
    }

    // ==================== CAMERA MANAGEMENT ====================
    
    public static void startCamera(int viewId, boolean enableStreaming, boolean viewOnly) {
        if (viewId < 1 || viewId > 4) {
            log("ERROR: Invalid view ID: " + viewId);
            return;
        }
        
        log("Starting camera " + viewId + " (GPU mosaic recording, viewOnly=" + viewOnly + ")");
        
        // GPU pipeline handles all cameras together
        if (gpuPipeline != null && !gpuPipeline.isRunning()) {
            // If ACC is ON, warm up the camera HAL first on a background thread
            // to avoid blocking the HTTP/TCP handler thread for 4 seconds.
            if (AccMonitor.isAccOn() && avcHalWarmup != null) {
                final boolean fViewOnly = viewOnly;
                new Thread(() -> {
                    avcHalWarmup.warmupAndWait();
                    startPipelineInternal(viewId, fViewOnly);
                }, "CameraWarmup").start();
            } else {
                startPipelineInternal(viewId, viewOnly);
            }
        } else if (gpuPipeline != null && gpuPipeline.isRunning()) {
            // Pipeline already running - start recording if requested (stops surveillance)
            if (!viewOnly) {
                log("Pipeline already running - starting normal recording (stops surveillance if active)");
                gpuPipeline.startRecording();
            } else {
                log("Pipeline already running for camera " + viewId + " (view-only)");
            }
        }
    }
    
    /**
     * Internal: starts the GPU pipeline after any warmup delay.
     */
    private static void startPipelineInternal(int viewId, boolean viewOnly) {
        if (gpuPipeline == null || gpuPipeline.isRunning()) return;
        try {
            gpuPipeline.start(!viewOnly);
            log("GPU pipeline started for camera " + viewId);
            
            if (!viewOnly) {
                log("Auto-recording enabled (will start when recorder ready)");
            } else {
                log("View-only mode - recording NOT started");
            }
            
            // Start AVC keep-alive if ACC is ON
            startAvcKeepAliveIfNeeded();
            
        } catch (Exception e) {
            log("ERROR: Failed to start GPU pipeline: " + e.getMessage());
        }
    }

    public static void stopCamera(int viewId) {
        stopCamera(viewId, false);
    }
    
    /**
     * Stop a camera view.
     * @param viewId The view ID (1-4)
     * @param forceStop If true, stops even if recording. If false, only stops if not recording.
     */
    public static void stopCamera(int viewId, boolean forceStop) {
        try {
            log("Stopping camera " + viewId + " (GPU pipeline)");
            
            // GPU pipeline handles all cameras
            // Only stop if forcing
            if (forceStop && gpuPipeline != null) {
                gpuPipeline.stop();
                stopAvcKeepAlive();
                log("GPU pipeline stopped");
            }
        } catch (Exception e) {
            log("ERROR: Exception in stopCamera(" + viewId + "): " + e.getMessage());
        }
    }
    
    /**
     * Force stop a camera, even if recording.
     * Use this when user explicitly wants to stop everything.
     */
    public static void forceStopCamera(int viewId) {
        stopCamera(viewId, true);
    }

    public static void stopAllCameras() {
        stopAllCameras(true);
    }
    
    /**
     * Stop all cameras.
     * @param forceStop If true, stops all cameras. If false, only stops non-recording cameras.
     */
    public static void stopAllCameras(boolean forceStop) {
        log("Stopping all cameras (GPU pipeline, force=" + forceStop + ")");
        if (forceStop && gpuPipeline != null) {
            gpuPipeline.stop();
            stopAvcKeepAlive();
        }
    }
    
    
    // GPU pipeline handles camera internally - no separate camera management needed
    
    // ==================== AVC HAL KEEP-ALIVE ====================
    
    /**
     * Starts the AVC keep-alive watchdog.
     *
     * On legacy cars: gated on the GPU pipeline being live so we don't
     * waste am-start cycles when no consumer is using the camera.
     *
     * On dilink4 (byd_apa firmware): starts unconditionally. The AVM HAL
     * gates frame delivery on com.byd.avc being a co-consumer of
     * vendor.byd.avm; if BYD's reaper kills AVC at any point the next
     * camera open lands on a zombie HAL that returns all-zero buffers.
     * We keep AVC warm at all times — ACC ON or OFF — so a streaming-
     * client connect, surveillance arm, or recording start can never
     * race a fresh AVC reap.
     */
    public static void startAvcKeepAliveIfNeeded() {
        // Double-checked locking: cheap volatile read on the hot path,
        // synchronized init on the cold path. Prevents two concurrent
        // callers from each instantiating AvcHalWarmup and orphaning a
        // running keep-alive thread.
        com.overdrive.app.camera.AvcHalWarmup local = avcHalWarmup;
        if (local == null) {
            synchronized (AVC_WARMUP_INIT_LOCK) {
                local = avcHalWarmup;
                if (local == null) {
                    local = new com.overdrive.app.camera.AvcHalWarmup();
                    avcHalWarmup = local;
                }
            }
        }
        boolean dilink4 = isDilink4ModeActive();
        boolean pipelineLive = gpuPipeline != null && gpuPipeline.isRunning();
        if (dilink4 || pipelineLive) {
            if (!local.isActive()) {
                local.startKeepAlive();
                log("AVC keep-alive started (dilink4=" + dilink4
                    + ", pipelineLive=" + pipelineLive
                    + ", accOn=" + AccMonitor.isAccOn() + ")");
            }
            // Heartbeat is dilink4-only and self-gates inside; safe on legacy.
            startCameraActiveHeartbeatIfNeeded();
        }
    }

    /**
     * Stops the AVC keep-alive watchdog when the pipeline stops.
     *
     * On dilink4 this is a no-op — see {@link #startAvcKeepAliveIfNeeded}
     * for why AVC must stay alive across pipeline lifecycles. Use
     * {@link #stopAvcKeepAliveForShutdown} on daemon teardown.
     */
    public static void stopAvcKeepAlive() {
        if (isDilink4ModeActive()) {
            // Skip — dilink4 needs AVC alive for the next pipeline start
            // (streaming client connect, sentry arm, recording start).
            return;
        }
        stopAvcKeepAliveForShutdown();
    }

    /**
     * Force-stops the AVC keep-alive watchdog. Used on daemon shutdown
     * regardless of camera mode — at that point we're tearing everything
     * down and there will be no future camera consumer.
     */
    public static void stopAvcKeepAliveForShutdown() {
        if (avcHalWarmup != null && avcHalWarmup.isActive()) {
            avcHalWarmup.stopKeepAlive();
            log("AVC keep-alive stopped");
        }
        stopCameraActiveHeartbeat();
    }

    // ==================== CAMERA-ACTIVE HEARTBEAT ====================
    //
    // AccSentryDaemon (UID 2000, separate process) runs a 10s keepalive
    // that calls setBacklightState(false). On byd_apa firmware that tears
    // down the AVMCamera preview surface and emits HAL event=8. To stop
    // that, we publish surveillance.cameraActiveUntilMs ~5s ahead of now
    // every 4s while the GPU pipeline is consuming frames; AccSentryDaemon
    // reads the same key cross-process and skips its backlight-off tick
    // while we're hot. Gated on cameraMode=dilink4 — legacy cars don't
    // have the HAL-display coupling and shouldn't suppress power-save.
    private static volatile Thread cameraActiveHeartbeatThread = null;
    private static volatile boolean cameraActiveHeartbeatRunning = false;
    private static final long CAMERA_ACTIVE_TICK_MS = 4_000L;
    private static final long CAMERA_ACTIVE_LEASE_MS = 8_000L;

    public static void startCameraActiveHeartbeatIfNeeded() {
        if (gpuPipeline == null || !gpuPipeline.isRunning()) return;
        if (!isDilink4ModeActive()) return;
        if (cameraActiveHeartbeatRunning) return;
        cameraActiveHeartbeatRunning = true;
        cameraActiveHeartbeatThread = new Thread(() -> {
            log("Camera-active heartbeat started (dilink4, " +
                CAMERA_ACTIVE_LEASE_MS + "ms lease, refreshed every " +
                CAMERA_ACTIVE_TICK_MS + "ms)");
            while (cameraActiveHeartbeatRunning) {
                try {
                    if (gpuPipeline != null && gpuPipeline.isRunning()) {
                        publishCameraActiveLease();
                    }
                    Thread.sleep(CAMERA_ACTIVE_TICK_MS);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    log("Camera-active heartbeat error: " + t.getMessage());
                    try { Thread.sleep(1_000L); } catch (InterruptedException ie) { break; }
                }
            }
            // Best-effort lease clear so AccSentryDaemon stops suppressing
            // the backlight as soon as we tear the pipeline down. A stuck
            // future-dated lease would keep the screen on for up to 8s
            // after pipeline.stop() — recoverable but messy.
            try { clearCameraActiveLease(); } catch (Throwable ignored) {}
            log("Camera-active heartbeat stopped");
        }, "CamActiveHeartbeat");
        cameraActiveHeartbeatThread.setDaemon(true);
        cameraActiveHeartbeatThread.start();
    }

    public static void stopCameraActiveHeartbeat() {
        if (!cameraActiveHeartbeatRunning) return;
        cameraActiveHeartbeatRunning = false;
        Thread t = cameraActiveHeartbeatThread;
        if (t != null) t.interrupt();
        cameraActiveHeartbeatThread = null;
    }

    /**
     * FIX (audit R4): wrap AccMonitor.probeAccState in a 3× 200ms backoff
     * loop. probeAccState returns `!accOn` on sentinel HAL readings (powerLevel
     * = 4 FAKE_OK or 255 INVALID), and at daemon boot accOn defaults to false,
     * so a single sentinel read falsely reports "ACC OFF" — dropping pano
     * CONTINUOUS / DRIVE_MODE recording into sentry mid-drive.
     *
     * Loops up to 3 attempts, 200ms apart, treating the first non-sentinel
     * reading as authoritative. We can't see the raw power level from here
     * (probeAccState is a boolean API) so we use a stability heuristic: if
     * three successive readings agree, we trust the result. If they ever
     * disagree, we keep the last reading (HAL is converging) and warn.
     *
     * Returns the same boolean as probeAccState (true == ACC OFF). On
     * exception, falls back to the AccMonitor cache as before.
     */
    private static boolean probeAccStateWithBackoff(String tag) {
        boolean lastReading = false;
        boolean firstReadingSet = false;
        int agreementCount = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                boolean reading = com.overdrive.app.monitor.AccMonitor
                    .probeAccState(sharedAppContext);
                if (!firstReadingSet) {
                    lastReading = reading;
                    firstReadingSet = true;
                    agreementCount = 1;
                } else if (reading == lastReading) {
                    agreementCount++;
                } else {
                    log("WARN: ACC HW probe (" + tag + ") disagreed across attempts "
                        + "(was=" + lastReading + " now=" + reading + " attempt="
                        + attempt + ") — keeping latest");
                    lastReading = reading;
                    agreementCount = 1;
                }
                // Two agreeing reads is enough to short-circuit.
                if (agreementCount >= 2) {
                    if (attempt > 0) {
                        log("ACC HW probe (" + tag + ") settled to accIsOff="
                            + lastReading + " after " + (attempt + 1) + " attempts");
                    }
                    return lastReading;
                }
            } catch (Throwable t) {
                log("WARN: ACC HW probe (" + tag + ") attempt " + attempt
                    + " failed: " + t.getMessage());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!firstReadingSet) {
            // Every attempt threw — fall back to AccMonitor cache (same
            // behaviour as the prior single-attempt catch branch).
            boolean cacheFallback = !com.overdrive.app.monitor.AccMonitor.isAccOn();
            log("WARN: ACC HW probe (" + tag + ") all attempts failed — "
                + "falling back to AccMonitor cache accIsOff=" + cacheFallback);
            return cacheFallback;
        }
        log("WARN: ACC HW probe (" + tag + ") never reached agreement after 3 attempts; "
            + "using last reading accIsOff=" + lastReading);
        return lastReading;
    }

    private static boolean isDilink4ModeActive() {
        try {
            org.json.JSONObject c = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (c == null) return false;
            return "dilink4".equalsIgnoreCase(c.optString("cameraMode", "default"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Public alias for cross-class callers (RecordingModeManager,
     *  GpuSurveillancePipeline, SafeLocationManager). */
    public static boolean isDilink4ModeActiveStatic() {
        return isDilink4ModeActive();
    }

    private static void publishCameraActiveLease() {
        try {
            org.json.JSONObject patch = new org.json.JSONObject();
            patch.put("cameraActiveUntilMs",
                System.currentTimeMillis() + CAMERA_ACTIVE_LEASE_MS);
            com.overdrive.app.config.UnifiedConfigManager
                .updateSection("surveillance", patch);
        } catch (Throwable t) {
            // Throttle — log only on first failure per minute, otherwise
            // a stuck unified-config write would flood the daemon log.
            log("publishCameraActiveLease failed: " + t.getMessage());
        }
    }

    private static void clearCameraActiveLease() {
        try {
            org.json.JSONObject patch = new org.json.JSONObject();
            patch.put("cameraActiveUntilMs", 0L);
            com.overdrive.app.config.UnifiedConfigManager
                .updateSection("surveillance", patch);
        } catch (Throwable ignored) {}
    }
    
    // ==================== GETTERS ====================
    
    public static java.util.Map<Integer, Object> getVirtualViews() {
        // GPU pipeline doesn't use VirtualView - return empty map for compatibility
        return new java.util.HashMap<>();
    }
    
    public static boolean isRunning() {
        return running.get();
    }
    
    /**
     * Periodic memory monitor. Daemon-process equivalent of
     * {@code AccSentryDaemon.logMemoryStatus()}: emits ActivityManager
     * memory info plus our Java heap usage every 5 minutes. The daemon
     * runs for hours unattended during sentry mode; without this, a slow
     * heap leak (motion-event storm under no-AI, MediaCodec slot leak
     * across encoder reinits, etc.) is invisible in cam_daemon.log until
     * the LMK or SIGABRT kill lands.
     */
    private static java.util.concurrent.ScheduledExecutorService memoryLogScheduler;

    public static void startPeriodicMemoryLogging() {
        if (memoryLogScheduler != null) return;
        memoryLogScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryLog");
            t.setDaemon(true);
            return t;
        });
        memoryLogScheduler.scheduleAtFixedRate(
            CameraDaemon::logMemoryStatus,
            1, 5, java.util.concurrent.TimeUnit.MINUTES);
        // Piggy-back recording-cache prune onto the same scheduler. The
        // RecordingsApiHandler.RECORDING_CACHE is invalidated synchronously
        // when the daemon itself rotates an mp4 (HardwareEventRecorderGpu)
        // or storage cleanup deletes one (StorageManager). External SD
        // edits (eject + delete on a host PC, manual file-explorer delete
        // from the app) leave phantom entries that can only be reaped here.
        //
        // Wrapped in a try/catch: ScheduledExecutorService.scheduleAtFixedRate
        // permanently cancels a recurring task on the first uncaught throw.
        // A flapping SD mount could surface a transient IOException out of
        // File.exists(); without this guard one bad tick silently kills the
        // prune cadence for the rest of the daemon's life.
        memoryLogScheduler.scheduleAtFixedRate(() -> {
            try {
                com.overdrive.app.server.RecordingsApiHandler.pruneRecordingCache();
            } catch (Throwable t) {
                log("RECORDING_CACHE prune tick failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 60, 60, java.util.concurrent.TimeUnit.MINUTES);

        // RecordingsIndex reconcile — backstop for FileObserver event drops
        // on FUSE-mounted SD/USB volumes. Cheap when the index is in sync
        // (one stat() per row); patches missing rows + drops phantoms.
        // Same try/catch guard rationale as above: scheduleAtFixedRate
        // permanently cancels the recurring task on first uncaught throw,
        // and a flapping SD mount is exactly the case where one tick
        // failing must not silently kill all future ticks.
        memoryLogScheduler.scheduleAtFixedRate(() -> {
            try {
                com.overdrive.app.server.RecordingsIndex.getInstance().reconcile();
            } catch (Throwable t) {
                log("RecordingsIndex reconcile tick failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 60, 60, java.util.concurrent.TimeUnit.MINUTES);
        log("Periodic memory monitor started (5-minute cadence); recording cache prune + index reconcile armed (60-minute cadence)");
    }

    private static void logMemoryStatus() {
        try {
            Runtime rt = Runtime.getRuntime();
            long heapTotalMB = rt.totalMemory() / 1024 / 1024;
            long heapFreeMB = rt.freeMemory() / 1024 / 1024;
            long heapUsedMB = heapTotalMB - heapFreeMB;
            long heapMaxMB = rt.maxMemory() / 1024 / 1024;

            String sysLine = "";
            android.content.Context ctx = sharedAppContext;
            if (ctx != null) {
                try {
                    android.app.ActivityManager.MemoryInfo memInfo =
                        new android.app.ActivityManager.MemoryInfo();
                    android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.getMemoryInfo(memInfo);
                        long availMB = memInfo.availMem / 1024 / 1024;
                        long totalMB = memInfo.totalMem / 1024 / 1024;
                        sysLine = String.format(
                            ", sys.avail=%dMB / %dMB, lowMem=%s, threshold=%dMB",
                            availMB, totalMB, memInfo.lowMemory,
                            memInfo.threshold / 1024 / 1024);
                    }
                } catch (Exception ignored) {}
            }

            // Native heap (direct ByteBuffers, MediaCodec internal pools, GL).
            long nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024;
            long nativeSizeMB = android.os.Debug.getNativeHeapSize() / 1024 / 1024;

            log(String.format(
                "MEM: heap=%d/%dMB (max=%d), native=%d/%dMB%s",
                heapUsedMB, heapTotalMB, heapMaxMB,
                nativeHeapMB, nativeSizeMB, sysLine));
        } catch (Throwable t) {
            log("logMemoryStatus error: " + t.getMessage());
        }
    }

    /**
     * Sentinel file that signals the shell watchdog wrapper to NOT restart the daemon.
     * Written by shutdown() when the daemon is intentionally disabled (UI/Telegram).
     * The watchdog script checks for this file before each restart attempt.
     * To re-enable, delete this file and start the watchdog script again.
     */
    private static final String DISABLE_SENTINEL = "/data/local/tmp/camera_daemon.disabled";
    
    public static void shutdown() {
        log("Shutdown requested — writing disable sentinel and cleaning up...");
        running.set(false);

        // Stop AVC keep-alive immediately (force, daemon teardown)
        stopAvcKeepAliveForShutdown();

        // Stop periodic memory monitor
        if (memoryLogScheduler != null) {
            try { memoryLogScheduler.shutdownNow(); } catch (Exception ignored) {}
            memoryLogScheduler = null;
        }
        
        // Write disable sentinel FIRST — this tells the shell watchdog wrapper
        // to NOT restart the daemon after we exit. Without this, the wrapper
        // sees exit code 0 and respawns us immediately.
        writeDisableSentinel();
        
        // Cancel PermissionGranter to stop orphaned pm grant processes
        PermissionGranter.cancel();
        
        // Stop RoadSense (releases IMU sidecar, stores, warning audio). Early so
        // its warning-tick can't fire against tearing-down state.
        if (roadSense != null) {
            try { roadSense.stop(); } catch (Exception e) { log("RoadSense stop error: " + e.getMessage()); }
        }

        // Stop RecordingModeManager BEFORE the pipeline so its periodic
        // resync ticker can't fire one more activateMode() call against a
        // tearing-down pipeline. Idempotent w.r.t. modeActive bookkeeping;
        // safe even if the manager's pipeline state is already half-torn.
        if (recordingModeManager != null) {
            try { recordingModeManager.shutdown(); }
            catch (Exception e) { log("RecordingModeManager shutdown error: " + e.getMessage()); }
        }

        // Stop cameras and GPU pipeline
        stopAllCameras();
        if (gpuPipeline != null) {
            try { gpuPipeline.stop(); } catch (Exception e) { log("GPU pipeline stop error: " + e.getMessage()); }
        }
        
        // Stop all monitors
        try { com.overdrive.app.monitor.VehicleDataMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.GpsMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.GearMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.PerformanceMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.SocHistoryDatabase.getInstance().stop(); } catch (Exception ignored) {}
        
        // Stop services. Both the trip analytics + recordings index inits
        // run on parallel threads (see main()); join with a short timeout
        // before tearing down so we don't close a half-opened H2
        // connection. 5 s comfortably exceeds measured init time (~3-5 s
        // for trips, ~1 s for index open).
        try {
            if (tripAnalyticsInitFuture != null) {
                tripAnalyticsInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log("Trip analytics init join error: " + e.getMessage());
        }
        try {
            if (dataLayerInitFuture != null) {
                dataLayerInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log("Data layer init join error: " + e.getMessage());
        }
        if (tripAnalyticsManager != null) tripAnalyticsManager.shutdown();
        // Tear down RecordingsIndex AFTER trips so any in-flight upserts
        // from the recorder have already drained. stop() the watcher
        // first to silence inotify callbacks before close() yanks the
        // H2 connection (otherwise a late event would log a noisy
        // "upsert failed: connection closed" warning).
        try { RecordingsIndexFileWatcher.getInstance().stop(); }
        catch (Exception e) { log("RecordingsIndexFileWatcher stop error: " + e.getMessage()); }
        try { com.overdrive.app.server.RecordingsIndex.getInstance().close(); }
        catch (Exception e) { log("RecordingsIndex close error: " + e.getMessage()); }
        if (abrpTelemetryService != null) abrpTelemetryService.stop();
        if (mqttConnectionManager != null) mqttConnectionManager.stopAll();
        if (tcpServer != null) tcpServer.stop();
        if (httpServer != null) httpServer.stop();
        if (ipcServer != null) ipcServer.stop();
        if (aacIngestServer != null) aacIngestServer.stop();

        // Shutdown StorageManager (schedulers, executors)
        try { com.overdrive.app.storage.StorageManager.getInstance().shutdown(); } catch (Exception ignored) {}
        
        // Release singleton lock
        releaseSingletonLock();
        
        log("Daemon shutdown complete — killing self (watchdog will NOT restart)");
        
        // Also kill the shell watchdog wrapper process directly.
        // The sentinel file prevents restart, but killing the wrapper ensures
        // it doesn't linger as an idle process.
        killWatchdogWrapper();
        
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    
    /**
     * Write the disable sentinel file that tells the shell watchdog wrapper
     * to stop restarting the daemon.
     */
    private static void writeDisableSentinel() {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DISABLE_SENTINEL);
            fw.write("disabled at " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", 
                java.util.Locale.US).format(new java.util.Date()) + "\n");
            fw.write("pid=" + android.os.Process.myPid() + "\n");
            fw.close();
            log("Disable sentinel written: " + DISABLE_SENTINEL);
        } catch (Exception e) {
            log("WARNING: Failed to write disable sentinel: " + e.getMessage());
        }
    }
    
    /**
     * Kill the shell watchdog wrapper process (start_cam_daemon.sh).
     * Uses the PID file if available, falls back to pkill.
     */
    private static void killWatchdogWrapper() {
        try {
            // Try PID file first
            java.io.File pidFile = new java.io.File("/data/local/tmp/cam_watchdog.pid");
            if (pidFile.exists()) {
                String pid = new java.util.Scanner(pidFile).useDelimiter("\\A").next().trim();
                Runtime.getRuntime().exec(new String[]{"kill", "-9", pid});
                log("Killed watchdog wrapper via PID file (pid=" + pid + ")");
                pidFile.delete();
            }
            // Also pkill as fallback
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "start_cam_daemon"});
            // Delete the script so it can't be accidentally re-run
            new java.io.File("/data/local/tmp/start_cam_daemon.sh").delete();
        } catch (Exception e) {
            log("Watchdog wrapper kill error (non-fatal): " + e.getMessage());
        }
    }
    
    /**
     * Check if the daemon has been intentionally disabled.
     * Called by the shell watchdog wrapper before restarting.
     * Also callable from Java to check state.
     */
    public static boolean isDisabledBySentinel() {
        return new java.io.File(DISABLE_SENTINEL).exists();
    }
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     * Uses Java NIO FileLock which is process-safe.
     */
    private static boolean acquireSingletonLock() {
        try {
            File lockFileObj = new File(LOCK_FILE);
            lockFile = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFile.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();
            
            if (fileLock == null) {
                // Another process holds the lock — check if it's actually alive
                // AND that it's actually a CameraDaemon. We treat the following
                // as stale-lock cases, because each one means no live daemon
                // owns the lock:
                //   - empty lock file
                //   - corrupt/non-numeric PID
                //   - holder PID is our own PID (previous crash)
                //   - /proc/<pid> doesn't exist (dead PID)
                //   - /proc/<pid>/cmdline doesn't look like a CameraDaemon
                //     (PID was recycled to an unrelated process — the kernel
                //     flock should have been released, but if we got here the
                //     file content still points at a stale PID)
                boolean stale = false;
                String reason = null;
                try {
                    lockFile.seek(0);
                    String pidStr = lockFile.readLine();
                    int myPid = android.os.Process.myPid();
                    if (pidStr == null || pidStr.trim().isEmpty()) {
                        stale = true;
                        reason = "empty lock file";
                    } else {
                        int pid = Integer.parseInt(pidStr.trim());
                        if (pid == myPid) {
                            stale = true;
                            reason = "lock held by our own PID (previous crash)";
                        } else if (!new File("/proc/" + pid).exists()) {
                            stale = true;
                            reason = "dead PID " + pid;
                        } else {
                            // PID is alive — verify it's actually a CameraDaemon
                            // before declaring a real conflict. Without this an
                            // unrelated process that inherited the prior daemon's
                            // recycled PID would lock us out of starting forever.
                            //
                            // Three outcomes from readProcCmdline:
                            //   MATCH    → real conflict, refuse to start
                            //   NO_MATCH → PID is alive but not a daemon → stale
                            //   UNKNOWN  → cmdline unreadable (Android 10+
                            //              hidepid=2 blocks cross-UID reads).
                            //              We MUST NOT steal the lock in this
                            //              case — a legitimately-running daemon
                            //              under a different UID would be booted
                            //              out. Refuse to start; the watchdog's
                            //              backoff handles the retry.
                            CmdlineMatch match = classifyCmdline(pid);
                            if (match == CmdlineMatch.MATCH) {
                                log("Singleton: live daemon PID " + pid + " holds the lock"
                                    + " (cmdline=" + readProcCmdline(pid) + ")");
                                try { lockFile.close(); } catch (Exception ignored) {}
                                return false;
                            }
                            if (match == CmdlineMatch.UNKNOWN) {
                                log("Singleton: PID " + pid + " holds the lock but its "
                                    + "/proc/<pid>/cmdline is unreadable (different UID? "
                                    + "hidepid?) — assuming live daemon, refusing to start");
                                try { lockFile.close(); } catch (Exception ignored) {}
                                return false;
                            }
                            // NO_MATCH
                            stale = true;
                            reason = "PID " + pid + " is alive but not a CameraDaemon"
                                + " (cmdline=" + readProcCmdline(pid) + ")";
                        }
                    }
                } catch (NumberFormatException nfe) {
                    stale = true;
                    reason = "corrupt PID in lock file";
                } catch (Exception e) {
                    log("Singleton: lock-file inspection failed: " + e.getMessage());
                    try { lockFile.close(); } catch (Exception ignored) {}
                    return false;
                }
                
                if (stale) {
                    log("Singleton: stale lock (" + reason + ") — cleaning up");
                    try { lockFile.close(); } catch (Exception ignored) {}
                    lockFileObj.delete();
                    
                    // Small delay so the kernel releases the inode lock before retry
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    
                    // Retry lock acquisition on the new inode
                    lockFile = new java.io.RandomAccessFile(lockFileObj, "rw");
                    channel = lockFile.getChannel();
                    fileLock = channel.tryLock();
                    
                    if (fileLock == null) {
                        log("Singleton: retry after stale-lock cleanup still failed");
                        try { lockFile.close(); } catch (Exception ignored) {}
                        return false;
                    }
                    // Fall through to write PID and register shutdown hook
                }
            }
            
            // Write our PID to the lock file for debugging
            lockFile.seek(0);
            lockFile.setLength(0);
            lockFile.writeBytes(String.valueOf(android.os.Process.myPid()));
            
            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");
            
            // Register shutdown hook to release lock and clean up ALL resources on process termination.
            // CRITICAL: System.exit(0) from the GL watchdog skips normal cleanup.
            // Without this, the MediaCodec encoder, EGL context, camera HAL connection,
            // and TFLite GPU delegate leak across restarts. After 3-4 rapid restarts,
            // the Adreno 610 runs out of GPU contexts and the hardware encoder exhausts
            // its codec instance limit, causing system-level freezes.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log("Shutdown hook: cleaning up all resources...");

                // 0. Tear down any in-progress ScreenDeterrent FIRST. The
                //    deterrent owns SurfaceControl + UCM gate flags; if we
                //    skip this, AccSentryDaemon (separate process) reads
                //    a stuck screenDeterrentActiveUntilMs in the future and
                //    permanently skips its setBacklightState(false) — the
                //    panel stays lit until the next ACC transition.
                //    cancel() is non-blocking; the executor's finally block
                //    clears UCM and turns the backlight off.
                try {
                    com.overdrive.app.surveillance.ScreenDeterrent.getInstance().cancel();
                    // Defensive: clear cross-process flags directly in case
                    // the executor doesn't get a chance to finish (SIGKILL
                    // or VM dying mid-cleanup).
                    java.util.Map<String, Object> reset = new java.util.HashMap<>();
                    reset.put("screenDeterrentActiveUntilMs", 0L);
                    reset.put("screenDeterrentForceStop", false);
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", reset);
                    log("Shutdown hook: screen deterrent flags cleared");
                } catch (Exception e) {
                    log("Shutdown hook: screen deterrent cleanup error: " + e.getMessage());
                }

                // 1. Stop PermissionGranter — prevent orphaned pm grant processes
                //    from continuing to hammer PMS after we exit
                try {
                    PermissionGranter.cancel();
                } catch (Exception e) {
                    log("Shutdown hook: PermissionGranter cancel error: " + e.getMessage());
                }

                // 1.5. Flush the geocode cache. Puts are coalesced (30 s
                //      window) so a graceful shutdown that occurs inside
                //      the window would otherwise drop the latest reverse-
                //      geocode hits. Inline flush is bounded (≤ 4 MB JSON
                //      write) and finishes well within the shutdown budget.
                try {
                    com.overdrive.app.geo.GeoCache.getInstance().flushNow();
                } catch (Exception e) {
                    log("Shutdown hook: GeoCache flush error: " + e.getMessage());
                }
                
                // 1.6 Stop the OEM Dashcam pipeline outright. Pano's stop()
                //     also cascades to OEM (when pano is running), but if a
                //     user runs OEM standalone (recordingMode=continuous,
                //     no pano dashcam) gpuPipeline.stop() early-returns at
                //     !running and the cascade never fires — orphaning the
                //     OEM MediaCodec, drainer, and AVMCamera handle until
                //     daemon respawn. Tearing down here is unconditional.
                try {
                    com.overdrive.app.camera.OemDashcamPipeline oem =
                        getOemDashcamPipeline();
                    if (oem != null && oem.isRunning()) {
                        try { oem.stopRecording(); } catch (Throwable ignored) {}
                        oem.stop();
                        setOemDashcamPipeline(null);
                        log("Shutdown hook: OEM dashcam pipeline stopped");
                    }
                } catch (Exception e) {
                    log("Shutdown hook: OEM cleanup error: " + e.getMessage());
                }

                // 2. Stop the GPU pipeline (releases MediaCodec encoder slot, camera HAL, EGL).
                //    The encoder.release() and closeCamera() are synchronous.
                //    releaseGl() is posted to the GL thread which may be blocked — that's
                //    acceptable because EGL contexts are destroyed when the process exits.
                try {
                    if (gpuPipeline != null) {
                        gpuPipeline.stop();
                        log("Shutdown hook: GPU pipeline stopped");
                    }
                } catch (Exception e) {
                    log("Shutdown hook: GPU pipeline cleanup error: " + e.getMessage());
                }

                // 2.5 Drain the streaming-encoder release executor.
                //     gpuPipeline.stop()'s disableStreaming hands
                //     encoder.release() to STREAM_ENCODER_RELEASE_EXEC and
                //     returns immediately. Without an explicit drain, JVM
                //     exit kills the daemon thread mid-release and leaks
                //     the MediaCodec until the next process spawn.
                try {
                    boolean drained = com.overdrive.app.surveillance.GpuSurveillancePipeline
                        .shutdownStreamEncoderReleaseExec(4000);
                    if (drained) {
                        log("Shutdown hook: stream encoder release executor drained");
                    } else {
                        log("Shutdown hook: stream encoder release exec did NOT drain in 4s — "
                            + "shutdownNow used; queued releases dropped (MediaCodec may leak "
                            + "until next daemon spawn)");
                    }
                } catch (Exception e) {
                    log("Shutdown hook: stream encoder exec drain error: " + e.getMessage());
                }
                
                // 3. Stop all monitors (VehicleDataMonitor, GpsMonitor, GearMonitor,
                //    PerformanceMonitor) — these hold BYD device listeners and schedulers
                try {
                    com.overdrive.app.monitor.VehicleDataMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.GpsMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.GearMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.PerformanceMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                
                // 4. Close SOC History Database (H2 JDBC connection + scheduler)
                try {
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                
                // 5. Stop services (MQTT, ABRP, Trip Analytics).
                // Trip Analytics + RecordingsIndex were inited on parallel
                // threads — join briefly so we don't tear down a half-opened
                // H2 connection. 5 s exceeds measured init time.
                try {
                    if (tripAnalyticsInitFuture != null) {
                        tripAnalyticsInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception e) { /* ignore */ }
                try {
                    if (dataLayerInitFuture != null) {
                        dataLayerInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception e) { /* ignore */ }
                try {
                    if (mqttConnectionManager != null) mqttConnectionManager.stopAll();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (abrpTelemetryService != null) abrpTelemetryService.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (tripAnalyticsManager != null) tripAnalyticsManager.shutdown();
                } catch (Exception e) { /* ignore */ }
                // RecordingsIndex teardown — same ordering as shutdown():
                // unregister observers first so late inotify events don't
                // hit a closed JDBC connection.
                try { RecordingsIndexFileWatcher.getInstance().stop(); }
                catch (Exception e) { /* ignore */ }
                try { com.overdrive.app.server.RecordingsIndex.getInstance().close(); }
                catch (Exception e) { /* ignore */ }

                // 6. Stop servers (TCP, HTTP, IPC)
                try {
                    if (tcpServer != null) tcpServer.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (httpServer != null) httpServer.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (ipcServer != null) ipcServer.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (aacIngestServer != null) aacIngestServer.stop();
                } catch (Exception e) { /* ignore */ }

                // 7. Shutdown StorageManager (schedulers, executors, SD card watchdog)
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().shutdown();
                } catch (Exception e) { /* ignore */ }
                
                // 8. Release singleton lock (must be last)
                releaseSingletonLock();
                log("Shutdown hook: cleanup complete");
            }, "DaemonShutdown"));
            
            return true;
            
        } catch (java.nio.channels.OverlappingFileLockException e) {
            // Lock already held by this JVM (shouldn't happen but handle it)
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            // Don't fall back to port checks — TCP sockets linger in TIME_WAIT
            // long after the daemon dies and would cause spurious "already
            // running" decisions during a fast retry loop. If we can't take
            // the lock, admit defeat and let the watchdog back off.
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }
    
    /** Result of inspecting /proc/<pid>/cmdline for singleton-lock validation. */
    private enum CmdlineMatch {
        /** cmdline matches a CameraDaemon process — real conflict. */
        MATCH,
        /** cmdline is readable AND clearly NOT us — stale lock, recycled PID. */
        NO_MATCH,
        /** cmdline is unreadable (EACCES, hidepid=2, race against PID exit).
         *  Caller must NOT steal the lock — a legitimately-running daemon
         *  under a different UID could be booted out. */
        UNKNOWN
    }

    /**
     * Classify a PID's cmdline. Distinguishes "definitely not us" from
     * "we can't tell" — the latter happens on Android 10+ when the holder
     * runs under a different UID and procfs is mounted with hidepid=2.
     */
    private static CmdlineMatch classifyCmdline(int pid) {
        java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return CmdlineMatch.NO_MATCH; // PID gone in our window
        if (!f.canRead()) return CmdlineMatch.UNKNOWN; // EACCES / hidepid
        String cmdline = readProcCmdline(pid);
        if (cmdline.isEmpty()) {
            // canRead() said yes but read produced nothing — could be a
            // kernel thread (whose /proc/.../cmdline is empty by design)
            // or a transient race. Either way it's not our daemon.
            // Treat as NO_MATCH so the next-step retry handles it.
            return CmdlineMatch.NO_MATCH;
        }
        return isCameraDaemonCmdline(cmdline) ? CmdlineMatch.MATCH : CmdlineMatch.NO_MATCH;
    }

    /**
     * Read /proc/<pid>/cmdline and return it with NUL bytes turned into
     * spaces. Returns "" if the file is unreadable (race against PID exit,
     * permission denied, etc.). NOT a sufficient check on its own — callers
     * doing security-critical decisions must use {@link #classifyCmdline}.
     *
     * /proc/<pid>/cmdline reports stat()-size=0 on most kernels even when
     * it has content, so Files.readAllBytes (size-hinted) can short-read.
     * Stream until EOF instead. Capped at 4096 because cmdlines longer
     * than that are pathological and we only need a substring match.
     */
    private static String readProcCmdline(int pid) {
        java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return "";
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while (total < buf.length && (n = fis.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            if (total == 0) return "";
            // /proc/.../cmdline is NUL-separated and trailing-NUL-terminated.
            StringBuilder sb = new StringBuilder(total);
            for (int i = 0; i < total; i++) {
                byte b = buf[i];
                sb.append(b == 0 ? ' ' : (char) (b & 0xff));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Tighter cmdline match: the only legitimate ways our daemon shows up in
     * /proc/<pid>/cmdline are:
     *   - argv[0] (after kernel applies nice-name): "byd_cam_daemon"
     *   - app_process invocation: "...--nice-name=byd_cam_daemon..."
     *   - some launchers append "com.overdrive.app.daemon.CameraDaemon"
     *     as the entry-point arg
     *
     * We anchor on the underscore-named token / FQCN to reduce collisions
     * with unrelated processes (e.g. `logcat -s CameraDaemon`, `grep
     * cam_daemon`, an ADB shell that has these strings in its argv). A
     * bare `cam_daemon` substring is too broad; require either the
     * "byd_" prefix or the FQCN.
     */
    private static boolean isCameraDaemonCmdline(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        return cmdline.contains("byd_cam_daemon")
            || cmdline.contains("com.overdrive.app.daemon.CameraDaemon");
    }

    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFile != null) {
                lockFile.close();
                lockFile = null;
            }
            // Delete lock file
            new File(LOCK_FILE).delete();
            log("Released singleton lock");
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }
    
    /**
     * Check if a port is already in use (fallback check).
     */
    private static boolean isPortInUse(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            socket.setReuseAddress(true);
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }
    
    public static Handler getMainHandler() {
        return mainHandler;
    }
    
    public static String getOutputDir() {
        return outputDir;
    }
    
    public static String getDeviceId() {
        return deviceId;
    }
    
    public static com.overdrive.app.trips.TripAnalyticsManager getTripAnalyticsManager() {
        return tripAnalyticsManager;
    }
    
    // ==================== STREAMING CONTROL (REMOVED - VPS functionality removed) ====================
    
    /**
     * Start streaming a camera (DISABLED - VPS streaming removed).
     */
    public static void startStreaming(int viewId) {
        log("startStreaming(" + viewId + ") - VPS streaming removed, use local HTTP streaming instead");
    }
    
    /**
     * Stop streaming a camera (DISABLED - VPS streaming removed).
     */
    public static void stopStreaming(int viewId) {
        log("stopStreaming(" + viewId + ") - VPS streaming removed");
    }
    
    /**
     * Start streaming all cameras (DISABLED - VPS streaming removed).
     */
    public static void startAllStreaming() {
        log("startAllStreaming() - VPS streaming removed, use local HTTP streaming instead");
    }
    
    /**
     * Stop all streaming (DISABLED - VPS streaming removed).
     */
    public static void stopAllStreaming() {
        log("stopAllStreaming() - VPS streaming removed");
    }
    
    /**
     * Check if streaming is enabled (always false - VPS streaming removed).
     */
    public static boolean isStreamingEnabled() {
        return false;
    }
    
    /**
     * Get list of cameras currently streaming (empty - VPS streaming removed).
     */
    public static java.util.List<Integer> getStreamingCameras() {
        return new java.util.ArrayList<>();
    }
    
    // ==================== SURVEILLANCE CONTROL ====================

    /**
     * Initialize surveillance with hardware encoding.
     * CPU usage: ~20% during recording
     */
    private static void initSurveillance() {
        try {
            log("Initializing GPU Surveillance Pipeline...");

            // Resolve camera profile (Seal vs Tang) so the pipeline gets
            // correct strip dimensions per vehicle. Falls back to legacy Seal
            // if ro.product.model is unrecognized — same behavior as before
            // for existing Seal/Atto installs.
            com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
                com.overdrive.app.camera.CameraConfigResolver.resolve();

            // SOTA: Use StorageManager for surveillance output directory
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            File eventDir = storageManager.getSurveillanceDir();

            // Create GPU pipeline with resolved profile dimensions
            gpuPipeline = new com.overdrive.app.surveillance.GpuSurveillancePipeline(
                resolvedCamera.getPanoWidth(), resolvedCamera.getPanoHeight(), eventDir);
            
            // Get AssetManager from the app's APK
            // Since we're running as app_process, load model from filesystem
            android.content.res.AssetManager assetManager = null;
            try {
                // Try to create AssetManager from APK path
                String classpath = System.getenv("CLASSPATH");
                log("CLASSPATH: " + classpath);
                
                // Extract the app APK path (not framework jars)
                String apkPath = null;
                if (classpath != null) {
                    String[] paths = classpath.split(":");
                    for (String path : paths) {
                        if (path.contains("com.overdrive.app") && path.endsWith(".apk")) {
                            apkPath = path;
                            break;
                        }
                    }
                }
                
                if (apkPath != null) {
                    android.content.res.AssetManager mgr = android.content.res.AssetManager.class.newInstance();
                    java.lang.reflect.Method addAssetPath = android.content.res.AssetManager.class
                        .getDeclaredMethod("addAssetPath", String.class);
                    int cookie = (Integer) addAssetPath.invoke(mgr, apkPath);
                    
                    if (cookie != 0) {
                        assetManager = mgr;
                        log("AssetManager created from APK: " + apkPath);
                        
                        // Extract web assets for HTTP server
                        HttpServer.extractWebAssets(assetManager);
                    } else {
                        log("Failed to add asset path (cookie=0)");
                    }
                } else {
                    log("Could not find app APK in CLASSPATH");
                }
            } catch (Exception e) {
                log("Could not create AssetManager: " + e.getMessage());
                e.printStackTrace();
            }
            
            // Apply persisted settings to config BEFORE init
            // IMPORTANT: Set codec FIRST, then bitrate (so bitrate is calculated for correct codec)
            String persistedCodec = HttpServer.getRecordingCodec();
            if (persistedCodec != null) {
                com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec videoCodec;
                switch (persistedCodec.toUpperCase()) {
                    case "H265":
                    case "HEVC":
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265;
                        break;
                    case "H264":
                    case "AVC":
                    default:
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                        break;
                }
                gpuPipeline.getConfig().setVideoCodec(videoCodec);
                log("Pre-init: Set codec to " + persistedCodec);
            }
            
            // Prefer the canonical recordingQuality tier (ECONOMY..MAX) over
            // the legacy recordingBitrate (LOW/MEDIUM/HIGH) — applyPersistedSettings
            // will later apply recordingQuality, and if pre-init used the
            // legacy preset (which maps to a smaller bitrate range) the
            // encoder gets reinitialized at boot. That reinit allocates a
            // larger pre-record pool against the daemon's already-warm heap
            // and can OOM (5s × 30fps × 10Mbps tries to grab 187 MB).
            String persistedQuality = HttpServer.getRecordingQuality();
            if (persistedQuality != null) {
                com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality tier =
                    com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.fromString(persistedQuality);
                gpuPipeline.getConfig().setRecordingQuality(tier);
                int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
                log("Pre-init: Set quality to " + tier + " (" + effectiveBitrate / 1_000_000 + " Mbps for " +
                    gpuPipeline.getConfig().getVideoCodec() + ")");
            } else {
                // Fall back to legacy bitrate preset for installs that haven't
                // migrated to the tier-based config yet.
                String persistedBitrate = HttpServer.getRecordingBitrate();
                if (persistedBitrate != null) {
                    com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset preset;
                    switch (persistedBitrate.toUpperCase()) {
                        case "LOW":
                            preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.LOW;
                            break;
                        case "HIGH":
                            preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.HIGH;
                            break;
                        case "MEDIUM":
                        default:
                            preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.MEDIUM;
                            break;
                    }
                    gpuPipeline.getConfig().setBitratePreset(preset);
                    int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
                    log("Pre-init: Set bitrate to " + persistedBitrate + " (" + effectiveBitrate / 1_000_000 + " Mbps for " +
                        gpuPipeline.getConfig().getVideoCodec() + ")");
                }
            }
            
            gpuPipeline.init(assetManager, com.overdrive.app.daemon.DaemonBootstrap.getContext());
            
            log("GPU Surveillance initialized: profile=" + resolvedCamera.getProfile().getDisplayName()
                + ", panoCam=" + resolvedCamera.getPanoCameraId()
                + ", size=" + resolvedCamera.getPanoWidth() + "x" + resolvedCamera.getPanoHeight()
                + " -> " + resolvedCamera.getProfile().getEncoderWidth()
                + "x" + resolvedCamera.getProfile().getEncoderHeight() + " (mosaic)");
            
            // Clean up orphaned .tmp files from previous crashed recordings
            try {
                com.overdrive.app.storage.StorageManager sm = com.overdrive.app.storage.StorageManager.getInstance();
                com.overdrive.app.surveillance.HardwareEventRecorderGpu.cleanupOrphanedTmpFiles(sm.getRecordingsDir());
                com.overdrive.app.surveillance.HardwareEventRecorderGpu.cleanupOrphanedTmpFiles(sm.getSurveillanceDir());
            } catch (Exception e) {
                log("Tmp cleanup error: " + e.getMessage());
            }
            
            // Initialize TelemetryDataCollector for overlay (needs app context)
            // Moved after RecordingModeManager init since sharedAppContext may not exist yet
            
            // Initialize RecordingModeManager
            if (sharedAppContext == null) {
                sharedAppContext = createAppContext();
            }
            if (sharedAppContext != null) {
                recordingModeManager = new com.overdrive.app.recording.RecordingModeManager(
                    sharedAppContext, gpuPipeline);
                log("RecordingModeManager initialized");

                // Create AVC HAL warmup instance (shared with RecordingModeManager)
                // under the same init lock as startAvcKeepAliveIfNeeded so
                // we don't race a worker thread that just observed null
                // and is about to instantiate its own.
                synchronized (AVC_WARMUP_INIT_LOCK) {
                    if (avcHalWarmup == null) {
                        avcHalWarmup = new com.overdrive.app.camera.AvcHalWarmup();
                    }
                }
                log("AvcHalWarmup initialized");

                // dilink4: kick AVC keep-alive at boot regardless of pipeline
                // state. The byd_apa AVM HAL gates frame delivery on
                // com.byd.avc being a co-consumer; we cannot afford to let it
                // get reaped between camera consumers (streaming-client
                // connect, sentry arm, recording start). startKeepAlive
                // re-launches AVC every 60 s; AccSentry's 10 s pidof tick
                // covers the gap during sentry mode.
                if (isDilink4ModeActive()) {
                    try {
                        com.overdrive.app.camera.AvcHalWarmup.ensureAvcAlive();
                    } catch (Throwable t) {
                        log("Boot-time AVC ensureAlive failed: " + t.getMessage());
                    }
                    if (!avcHalWarmup.isActive()) {
                        avcHalWarmup.startKeepAlive();
                        log("AVC keep-alive started at daemon boot (dilink4)");
                    }
                }
                
                // Now initialize TelemetryDataCollector (context is guaranteed available)
                try {
                    telemetryDataCollector =
                        new com.overdrive.app.telemetry.TelemetryDataCollector();
                    telemetryDataCollector.init(sharedAppContext);
                    gpuPipeline.setTelemetryCollector(telemetryDataCollector);
                    
                    // Apply persisted overlay enabled state. The resolver
                    // honours per-flow keys (panoEnabled / oemDashcamEnabled)
                    // and falls back to legacy `enabled` for pano so older
                    // configs continue to work.
                    boolean overlayEnabled = com.overdrive.app.config.UnifiedConfigManager
                        .isTelemetryOverlayEnabledFor("pano");
                    gpuPipeline.setOverlayEnabled(overlayEnabled);
                    log("TelemetryDataCollector initialized, pano overlay=" + overlayEnabled);

                    // Apply persisted recording layout (standard 360 mosaic / dashcam)
                    String recLayout = com.overdrive.app.config.UnifiedConfigManager
                        .getRecording().optString("recordingLayout", "standard");
                    gpuPipeline.setRecordingLayout("dashcam".equals(recLayout) ? 1 : 0);
                    boolean dashcamUseWindshield = com.overdrive.app.config.UnifiedConfigManager
                        .getRecording().optBoolean("dashcamUseWindshield", false);
                    gpuPipeline.setDashcamUseWindshield(dashcamUseWindshield);
                    log("Recording layout: " + recLayout);

                    // Late-bind TelemetryDataCollector to TripAnalyticsManager
                    // (it was null when TripAnalytics was initialized before the 45s GPU delay)
                    if (tripAnalyticsManager != null) {
                        tripAnalyticsManager.setTelemetryDataCollector(telemetryDataCollector);
                        log("TelemetryDataCollector bound to TripAnalyticsManager");
                    }
                } catch (Exception e) {
                    log("WARNING: TelemetryDataCollector init failed: " + e.getMessage());
                }
            } else {
                log("WARNING: Could not create app context for RecordingModeManager");
                // FIX (audit R2, finding "Boot recovery probe leaves
                // pendingAccOn dangling when sharedAppContext is null at
                // init"): without a watchdog, rmm stays null until the user
                // toggles ACC AND the OFF→ON IPCs are not deduped. Spawn a
                // bounded poll thread that watches for sharedAppContext to
                // become valid, then drives reinitContextDependentComponents
                // to create rmm and drain pendingAccOn/Off.
                scheduleSharedContextWatchdog();
            }
            // Successful init — reset retry counter so a future restart-style
            // re-entry starts from attempt 0.
            initSurveillanceRetryAttempts.set(0);

        } catch (Exception e) {
            log("ERROR: GPU Surveillance init failed: " + e.getMessage());
            log("ERROR: Exception type: " + e.getClass().getName());
            if (e.getCause() != null) {
                log("ERROR: Caused by: " + e.getCause().getMessage());
            }
            // Print stack trace to logcat
            e.printStackTrace();
            gpuPipeline = null;
            // FIX (audit R2, finding "initSurveillance() exception → permanent
            // gpuPipeline=null with no retry path"): kick a bounded
            // exponential-backoff retry on a background thread. Without this,
            // every subsequent IPC takes the gpuPipeline-null queue branch,
            // the post-init drain never fires, and pano recording stays dead
            // until manual daemon restart or reboot.
            scheduleInitSurveillanceRetry();
        }
    }

    /**
     * FIX (audit R2): bounded retry of initSurveillance() after a transient
     * cold-boot failure. CAS-guarded so concurrent callers don't queue
     * duplicates; surfaces final failure via log and the gpuPipeline-null
     * branch the existing /api/status / IPC paths already cope with.
     */
    private static void scheduleInitSurveillanceRetry() {
        if (!initSurveillanceRetryInFlight.compareAndSet(false, true)) {
            log("initSurveillance retry already in flight — skipping duplicate schedule");
            return;
        }
        final int attempt = initSurveillanceRetryAttempts.get();
        // FIX (audit R8): no budget cap. Use the explicit step table for the
        // first few attempts, then clamp to INIT_SURVEILLANCE_RETRY_MAX_DELAY_MS
        // forever. Success short-circuits via the gpuPipeline!=null check.
        final long delayMs;
        if (attempt < INIT_SURVEILLANCE_RETRY_DELAYS_MS.length) {
            delayMs = INIT_SURVEILLANCE_RETRY_DELAYS_MS[attempt];
        } else {
            delayMs = INIT_SURVEILLANCE_RETRY_MAX_DELAY_MS;
        }
        Thread t = new Thread(() -> {
            try {
                log("initSurveillance retry: attempt " + (attempt + 1)
                    + " scheduled in " + delayMs + "ms (uncapped)");
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (gpuPipeline != null) {
                    log("initSurveillance retry: gpuPipeline already non-null — skipping retry");
                    return;
                }
                initSurveillanceRetryAttempts.incrementAndGet();
                log("initSurveillance retry: invoking initSurveillance() (attempt "
                    + (attempt + 1) + ")");
                initSurveillance();
                if (gpuPipeline != null) {
                    log("initSurveillance retry: SUCCESS on attempt " + (attempt + 1)
                        + " — draining pending ACC state");
                    // Replay any queued ACC state through the post-init drain
                    // shape used at end of main(). Cannot call drain
                    // directly (it lives inline in main()), so re-enter the
                    // dispatch path: pendingAccOn/Off is read by the boot
                    // probe shape we mirror here.
                    //
                    // FIX (audit R8, finding "retry replay path lacks HW guard"):
                    // up to several minutes elapse during retries; HW state can
                    // flip without an IPC reaching us in that window. HW-probe
                    // before replay so we don't seed RMM with a stale flag.
                    // Mirrors the main() drain shape at lines 731-769.
                    try {
                        boolean hwAccIsOff_replay = probeAccStateWithBackoff("retry-replay");
                        boolean hwAccIsOn_replay = !hwAccIsOff_replay;
                        if (pendingAccOff && recordingModeManager != null) {
                            if (hwAccIsOff_replay) {
                                log("initSurveillance retry: replaying pending ACC OFF (HW-probed)");
                                pendingAccOff = false;
                                onAccStateChanged(true);
                            } else {
                                log("initSurveillance retry: pending ACC OFF discarded — HW probe shows ACC ON");
                                pendingAccOff = false;
                            }
                        } else if (pendingAccOn && recordingModeManager != null) {
                            if (hwAccIsOn_replay) {
                                log("initSurveillance retry: replaying pending ACC ON (HW-probed)");
                                pendingAccOn = false;
                                onAccStateChanged(false);
                            } else {
                                log("initSurveillance retry: pending ACC ON discarded — HW probe shows ACC OFF");
                                pendingAccOn = false;
                            }
                        }
                    } catch (Throwable th) {
                        log("WARN: initSurveillance retry replay failed: " + th.getMessage());
                    }
                }
            } finally {
                initSurveillanceRetryInFlight.set(false);
                // FIX (audit R8): re-arm forever as long as initSurveillance
                // hasn't succeeded. Eventual HAL recovery (hours later) can
                // still self-heal pano without daemon restart.
                if (gpuPipeline == null) {
                    log("initSurveillance retry: still null — re-arming next attempt (uncapped)");
                    scheduleInitSurveillanceRetry();
                }
            }
        }, "InitSurveillanceRetry");
        t.setDaemon(true);
        t.start();
    }

    /**
     * FIX (audit R2): one-shot bounded poll thread that waits for
     * sharedAppContext to become valid and then drives
     * reinitContextDependentComponents(), which itself drains
     * pendingAccOn/Off into a freshly-created RecordingModeManager.
     * CAS-guarded against duplicates. Bails after CONTEXT_WATCHDOG_MAX_DURATION_MS.
     */
    private static void scheduleSharedContextWatchdog() {
        if (!contextWatchdogInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            log("sharedAppContext watchdog: starting (poll="
                + CONTEXT_WATCHDOG_POLL_INTERVAL_MS + "ms, max="
                + CONTEXT_WATCHDOG_MAX_DURATION_MS + "ms)");
            long deadline = System.currentTimeMillis() + CONTEXT_WATCHDOG_MAX_DURATION_MS;
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (sharedAppContext == null) {
                        try {
                            android.content.Context ctx = createAppContext();
                            if (ctx != null && !isContextBrokenFor(ctx)) {
                                sharedAppContext = ctx;
                                log("sharedAppContext watchdog: context created — "
                                    + "invoking reinitContextDependentComponents to drain queue");
                                reinitContextDependentComponents();
                                return;
                            }
                        } catch (Throwable th) {
                            log("sharedAppContext watchdog: createAppContext threw: "
                                + th.getMessage());
                        }
                    } else if (recordingModeManager == null && gpuPipeline != null) {
                        // Context appeared via another path — finish the
                        // job by running the rmm-creation drain.
                        log("sharedAppContext watchdog: context now non-null but rmm null — "
                            + "invoking reinitContextDependentComponents");
                        reinitContextDependentComponents();
                        return;
                    } else {
                        // rmm already exists — nothing left to do.
                        return;
                    }
                    try { Thread.sleep(CONTEXT_WATCHDOG_POLL_INTERVAL_MS); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                log("WARN: sharedAppContext watchdog: timed out after "
                    + CONTEXT_WATCHDOG_MAX_DURATION_MS + "ms — rmm still null, "
                    + "next ACC IPC will retry via existing isContextBroken path");
            } finally {
                contextWatchdogInFlight.set(false);
            }
        }, "SharedContextWatchdog");
        t.setDaemon(true);
        t.start();
    }
    
    /**
     * Enable surveillance mode.
     */
    public static void enableSurveillance() {
        // RACE CONDITION FIX: Reject surveillance enable if ACC is ON.
        // This is the primary guard against the race where AccSentryDaemon's
        // enableSurveillance() retry loop or the 45-second fallback timer fires
        // AFTER ACC has already turned ON. AccMonitor is the source of truth
        // because it's updated synchronously by onAccStateChanged() on the IPC thread.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            log("enableSurveillance() REJECTED — ACC is ON (race condition guard)");
            return;  // No recalc — resolver will fire from the ACC transition.
        }

        // OEM Dashcam: every non-ACC-rejected exit path of this method must
        // fire a recalc, because the user-facing surveillance state may have
        // changed (suppression cleared, schedule window opened, lock-gate
        // armed, etc.) and the resolver re-evaluates survSuppressed +
        // keepWarmSurv from the latest UCM/safe-zone/schedule state. The
        // try/finally guarantees the recalc fires even when an exception
        // propagates out of the surveillance start path.
        try {
            if (gpuPipeline == null) {
                log("GPU pipeline not ready — queuing surveillance enable for when pipeline initializes");
                // FIX (audit R5): mirror dispatch-path discipline at :3124-3130.
                // pendingAccOff and pendingAccOn are mutually exclusive; setting
                // one MUST clear the other or a stale ACC ON queued from an
                // earlier path can survive into the post-init drain and fire
                // an unwanted ACC ON after we just queued ACC OFF here.
                pendingAccOff = true;
                pendingAccOn = false;
                return;
            }

            // SOTA: Safe Location check — don't start camera if parked at safe zone
            com.overdrive.app.surveillance.SafeLocationManager safeMgr =
                com.overdrive.app.surveillance.SafeLocationManager.getInstance();
            if (safeMgr.isInSafeZone()) {
                log("SAFE ZONE: Surveillance suppressed — " + safeMgr.getCurrentZoneName()
                    + " (dist=" + Math.round(safeMgr.getDistanceToNearestZone()) + "m)");
                surveillanceEnabled = true;   // Mark intent so it auto-starts when leaving zone
                safeZoneSuppressed = true;
                return;  // Camera never opens. Zero resources.
            }

            log("Enabling GPU surveillance (pipeline=" + (gpuPipeline != null) +
                ", running=" + (gpuPipeline != null && gpuPipeline.isRunning()) +
                ", sentry=" + (gpuPipeline != null && gpuPipeline.getSentry() != null) + ")");
            surveillanceEnabled = true;
            safeZoneSuppressed = false;

            try {
                if (!gpuPipeline.isRunning()) {
                    log("Pipeline not running — starting...");
                    gpuPipeline.start();
                }
                // Enable surveillance mode (motion detection)
                gpuPipeline.enableSurveillance();
                // AVC keep-alive: same 60s `am start com.byd.avc` poke we use on
                // the ACC-ON / streaming / recording-mode flows. Without it, BYD
                // reaps com.byd.avc during a multi-hour park, the AVM HAL goes
                // cold, frames stall, and the GL watchdog drops into the restart
                // cascade that eventually trips MAX_RETRIES on the wrapper.
                startAvcKeepAliveIfNeeded();
                log("Surveillance mode activated successfully (AVC keep-alive on)");
            } catch (Exception e) {
                log("ERROR: Failed to enable surveillance: " + e.getMessage());
            }
        } finally {
            try {
                com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            } catch (Throwable ignored) {}
        }
    }
    
    /**
     * Ensure camera is running for surveillance (called by SurveillanceEngine when it becomes active).
     * This avoids circular calls between CameraDaemon and SurveillanceEngine.
     */
    public static void ensureCameraForSurveillance() {
        log("ensureCameraForSurveillance called");
        surveillanceEnabled = true;
        enableSurveillance();
    }
    
    /**
     * Disable surveillance mode.
     */
    public static void disableSurveillance() {
        log("Disabling surveillance mode");
        surveillanceEnabled = false;

        if (gpuPipeline != null) {
            gpuPipeline.disableSurveillance();
            // Keep pipeline running for potential streaming
        }
        // OEM Dashcam: surv-axis state changed (schedule window closed, master
        // toggle off, etc.). Recalc so surv=continuous tears down and surv=smart
        // unwarms the pipeline if no other consumer keeps it alive.
        try {
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
        } catch (Throwable ignored) {}
    }
    
    // ==================== DOOR LOCK GATE ====================
    // Surveillance is only armed after doors are locked. This prevents false motion
    // events from the owner exiting the car. Cloud lock detection is primary (MQTT
    // subscriber runs in this process), device SDK is fallback, 60s timeout is last resort.
    
    /**
     * Register door lock listener and arm surveillance when doors lock.
     * Called from ACC OFF path after all other gates (user enabled, safe zone, schedule) pass.
     * 
     * RACE CONDITION SAFETY: Every callback and timeout checks AccMonitor.isAccOn()
     * before arming. If ACC turns ON during the lock wait, surveillance is NOT armed.
     */
    private static void registerDoorLockListenerAndArmOnLock() {
        doorLockListenerArmed = false;

        // Two parallel lock-event sources, in priority order:
        //
        //   1. OTA polling (BYDAutoOtaDevice.getLFDoorLockState) — primary.
        //      Verified live ACC=OFF on DiLink 3.0 with ~1.5s latency. The
        //      legacy BYDAutoDoorLockDevice path (typed listener +
        //      getDoorLockStatus) returned INVALID on every firmware in the
        //      field, so it was removed.
        //
        //   2. Cloud MQTT (BydCloudDataProvider) — secondary. Lags 1-2s
        //      vs OTA on this trim but is the only source for the RF/LR/RR
        //      doors (OTA exposes only LF on DiLink 3.0). When the trim
        //      doesn't expose getLFDoorLockState (older firmware?), cloud
        //      is the sole signal.
        //
        // Both converge through applyLockEvent() which is idempotent —
        // multiple sources reporting the same transition cause exactly one
        // arm or disarm. attachDeviceLockSource() is now a no-op stub kept
        // for symmetry with attachCloudLockSource and the poll thread.

        attachCloudLockSource();
        attachDeviceLockSource();
        startUnlockPollThread();

        // Initial state probe — priority order: device (OTA-fast-path) BEFORE
        // cloud. The OTA device exposes LF state ACC=OFF with sub-second
        // latency; cloud MQTT can lag 1-2s and may not have a fresh
        // snapshot at gate-entry on cold boot. If device is INVALID
        // (older trim without OTA LF support), cloud initial fills the gap.
        // Both calls are gate-idempotent so order only matters for the
        // log line that reports which source decided.
        Boolean deviceInitial = currentDeviceLockState();
        if (deviceInitial != null) applyLockEvent(deviceInitial, "device-initial");
        Boolean cloudInitial = currentCloudLockState();
        if (cloudInitial != null) applyLockEvent(cloudInitial, "cloud-initial");

        // Force-arm timeout: if no source reports a lock within 60s, arm
        // anyway. Owner may have walked away without locking, or every event
        // source failed to deliver. This is the final safety net for arming.
        // Gate ONLY on doorLockListenerArmed — `surveillanceEnabled` is a
        // sticky static that's set to true by safe-zone / schedule
        // suppression paths without an actual arm, and is never reset on
        // ACC ON. Including it here silently disables the safety net for
        // every cycle after the first suppression.
        new Thread(() -> {
            try {
                Thread.sleep(DOOR_LOCK_ARM_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                log("LOCK GATE TIMEOUT: thread interrupted before deadline — not arming");
                return;
            }
            if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                log("LOCK GATE TIMEOUT: ACC is ON — not arming");
                return;
            }
            if (doorLockListenerArmed) {
                log("LOCK GATE TIMEOUT: already armed via lock event — no-op");
                return;
            }
            log("LOCK GATE TIMEOUT: No lock detected within "
                + (DOOR_LOCK_ARM_TIMEOUT_MS / 1000) + "s — force-arming surveillance");
            applyLockEvent(true, "timeout");
        }, "DoorLockTimeout").start();

        // Reverse fallback: ACC-ON disarm watchdog. Periodically queries
        // hardware ACC state directly. If ACC turned ON without any IPC
        // event reaching us (rare but seen during AccSentryDaemon restart
        // races), this thread force-disables surveillance.
        startAccOnDisarmWatchdog();
    }

    /**
     * Single arm/disarm path. Idempotent: redundant calls in the same state
     * are no-ops. Every lock-event source flows through here.
     */
    private static synchronized void applyLockEvent(boolean locked, String source) {
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            log("LOCK GATE [" + source + "]: " + (locked ? "LOCKED" : "UNLOCKED")
                + " but ACC is ON — ignoring");
            return;
        }
        if (locked) {
            if (doorLockListenerArmed) return;
            log("LOCK GATE [" + source + "]: LOCKED — arming surveillance");
            doorLockListenerArmed = true;
            enableSurveillance();
        } else {
            if (!doorLockListenerArmed) return;
            log("LOCK GATE [" + source + "]: UNLOCKED — disarming surveillance (owner returning)");
            disableSurveillance();
            doorLockListenerArmed = false;
        }
    }

    /** Cloud (MQTT) lock-event source. Always attached — runs in parallel
     *  with the device-SDK source. No primary/fallback toggle. */
    private static void attachCloudLockSource() {
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider cloudProvider =
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (cloudLockListener != null) {
                cloudProvider.removeLockStateListener(cloudLockListener);
            }
            cloudLockListener = (locked, timestampMs) -> applyLockEvent(locked, "cloud");
            cloudProvider.addLockStateListener(cloudLockListener);
            log("LOCK GATE: Cloud lock listener attached");
        } catch (Exception e) {
            log("LOCK GATE: Cloud listener attach failed: " + e.getMessage());
        }
    }

    /** Device-SDK lock-event source via BydDataCollector's typed listener.
     *  Always attached — runs in parallel with the cloud source. */
    /**
     * The legacy {@code BYDAutoDoorLockDevice} listener path was removed —
     * its {@code onDoorLockStatusChanged} callback never fired on any
     * firmware in the field, and the polled {@code getDoorLockStatus(area)}
     * returned INVALID. The OTA device fast-path (5s poll via
     * {@link #readDoorLockStatus}) replaces it. This stub is kept for
     * symmetry with the cloud / poll attachers — call sites unchanged.
     */
    private static void attachDeviceLockSource() {
        // Intentional no-op. See javadoc above.
    }

    /** @return true=locked, false=unlocked, null=unknown/cloud unavailable. */
    private static Boolean currentCloudLockState() {
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider cloudProvider =
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (!cloudProvider.isLockStateFresh()) return null;
            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = cloudProvider.getSnapshot();
            if (cs == null) return null;
            if (cs.isAllLocked()) return true;
            if (cs.isAnyUnlocked()) return false;
        } catch (Exception ignored) {}
        return null;
    }

    /** @return true=locked, false=unlocked, null=unknown/OTA unavailable. */
    private static Boolean currentDeviceLockState() {
        int s = readDoorLockStatus();
        if (s == DOOR_STATE_LOCK) return true;
        if (s == DOOR_STATE_UNLOCK) return false;
        return null;
    }

    /**
     * ACC-ON disarm watchdog. While surveillance is active during ACC OFF,
     * polls hardware ACC state every few seconds. If hardware says ACC ON
     * but AccMonitor still says OFF (IPC missed, AccSentryDaemon restarting),
     * force-disables surveillance directly. Symmetric counterpart to the
     * ACC-OFF arm timeout.
     */
    private static void startAccOnDisarmWatchdog() {
        if (accOnDisarmWatchdog != null && accOnDisarmWatchdog.isAlive()) return;
        accOnDisarmWatchdog = new Thread(() -> {
            log("ACC-ON disarm watchdog started");
            while (true) {
                try {
                    Thread.sleep(ACC_ON_DISARM_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                    log("ACC-ON disarm watchdog exiting (AccMonitor=ON)");
                    return;
                }
                if (sharedAppContext == null) continue;
                try {
                    // probeAccState: returns true if ACC is OFF, false if ON
                    // or unknown. As a side effect updates AccMonitor.
                    boolean hwSaysAccOff = com.overdrive.app.monitor.AccMonitor
                        .probeAccState(sharedAppContext);
                    if (!hwSaysAccOff && surveillanceEnabled) {
                        log("ACC-ON DISARM WATCHDOG: hardware says ACC ON but "
                            + "surveillance still active — force-disabling");
                        disableSurveillance();
                        doorLockListenerArmed = false;
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }, "AccOnDisarmWatchdog");
        accOnDisarmWatchdog.setDaemon(true);
        accOnDisarmWatchdog.start();
    }

    private static void stopAccOnDisarmWatchdog() {
        if (accOnDisarmWatchdog != null && accOnDisarmWatchdog.isAlive()) {
            accOnDisarmWatchdog.interrupt();
            accOnDisarmWatchdog = null;
        }
    }
    
    
    /**
     * Read driver-door (LF) lock status from {@code BYDAutoOtaDevice}.
     *
     * <p>Uses {@code BYDAutoOtaDevice.getLFDoorLockState()} — the OTA device
     * caches the LF lock signal even when the BCM is asleep (ACC=OFF).
     * Empirically verified live-tracking on DiLink 3.0 with ~1.5s
     * transition latency.
     *
     * <p>The legacy {@code BYDAutoDoorLockDevice.getDoorLockStatus(area)} +
     * {@code getDoorLockState()} paths were removed: every BYD firmware in
     * the field returns INVALID for them to user UID — no observed
     * firmware actually delivered a working signal. Cloud is the fallback
     * (full 4-door state via {@code BydCloudDataProvider}); see
     * {@link #currentCloudLockState}.
     *
     * <p>For all 4 doors (RF/LR/RR), the OTA device exposes only LF on
     * DiLink 3.0 (verified 2026-06-03). Use the cloud snapshot path for
     * full per-door state.
     *
     * @return {@link #DOOR_STATE_INVALID}(0), {@link #DOOR_STATE_UNLOCK}(1),
     *         or {@link #DOOR_STATE_LOCK}(2).
     */
    private static int readDoorLockStatus() {
        if (sharedAppContext == null) return DOOR_STATE_INVALID;
        try {
            Object otaDevice = com.overdrive.app.byd.BydDeviceHelper.getDevice(
                "android.hardware.bydauto.ota.BYDAutoOtaDevice", sharedAppContext);
            if (otaDevice == null) return DOOR_STATE_INVALID;
            Object v = com.overdrive.app.byd.BydDeviceHelper.callGetter(
                otaDevice, "getLFDoorLockState");
            if (v instanceof Number) {
                int state = ((Number) v).intValue();
                if (state == DOOR_STATE_UNLOCK || state == DOOR_STATE_LOCK) {
                    return state;
                }
                // 0=INVALID or anything out-of-range falls through.
            }
        } catch (Throwable t) {
            // Trim doesn't expose getLFDoorLockState. Returned INVALID;
            // caller relies on cloud as the secondary source.
        }
        return DOOR_STATE_INVALID;
    }
    
    // BYDAutoDoorLockDevice listener path removed — it never fired on any
    // firmware in the field. OTA polling (readDoorLockStatus) is the
    // primary lock signal now; cloud is the secondary.


    /**
     * Continuous unlock polling thread — detects door lock/unlock transitions.
     * Uses getDoorLockStatus(1) for the driver's door.
     * Polls every 5s while ACC is off.
     */
    private static void startUnlockPollThread() {
        stopUnlockPollThread();

        unlockPollThread = new Thread(() -> {
            log("Unlock poll thread started (5s polling getDoorLockStatus + REST fallback)");

            int restPollCounter = 0;

            while (!com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                try {
                    Thread.sleep(UNLOCK_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (com.overdrive.app.monitor.AccMonitor.isAccOn()) return;

                // Source 1: OTA device poll (BYDAutoOtaDevice.getLFDoorLockState).
                // Works ACC=OFF with sub-second latency for the LF (driver) door —
                // verified live on DiLink 3.0. The legacy BYDAutoDoorLockDevice
                // path returned INVALID on every firmware we observed and was
                // removed.
                try {
                    int state = readDoorLockStatus();
                    if (state == DOOR_STATE_LOCK) {
                        applyLockEvent(true, "ota-poll");
                    } else if (state == DOOR_STATE_UNLOCK) {
                        applyLockEvent(false, "ota-poll");
                    }
                } catch (Exception e) {
                    // Silently continue — OTA device may be unreachable
                }

                // Source 2: REST realtime poll fallback. Fires only when the
                // cached cloud lock state has gone stale (5 min default), and
                // is internally rate-limited at 30s. So this loop calls it
                // every UNLOCK_POLL_INTERVAL_MS but the actual REST hit only
                // happens when we genuinely need fresh data.
                // The 12-iteration gate avoids hitting refreshLockStateIfStale()
                // every 5s — that's still a no-op call but cheap to skip.
                restPollCounter++;
                if (restPollCounter >= 12) { // ~ once per minute at 5s interval
                    restPollCounter = 0;
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance()
                                .refreshLockStateIfStale();
                        // The data provider fires its CloudLockStateListener
                        // automatically when the fetch reveals a transition,
                        // which we attached via attachCloudLockSource().
                    } catch (Exception e) {
                        // Silently continue — cloud may be down
                    }
                }
            }
            log("Unlock poll thread exiting (ACC ON)");
        }, "UnlockPoll");
        unlockPollThread.setDaemon(true);
        unlockPollThread.start();
    }
    
    private static void stopUnlockPollThread() {
        if (unlockPollThread != null && unlockPollThread.isAlive()) {
            unlockPollThread.interrupt();
            unlockPollThread = null;
        }
    }
    
    /**
     * Clean up all door lock gate resources. Called on ACC ON.
     */
    private static void cleanupDoorLockGate() {
        doorLockListenerArmed = false;

        // Detach all three lock-event sources
        if (cloudLockListener != null) {
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance()
                    .removeLockStateListener(cloudLockListener);
            } catch (Exception ignored) {}
            cloudLockListener = null;
        }
        // (legacy device-SDK lock listener removed — see attachDeviceLockSource javadoc)
        stopUnlockPollThread();

        // Stop the reverse-fallback ACC-ON disarm watchdog
        stopAccOnDisarmWatchdog();
    }
    
    /**
     * Notify surveillance of ACC state change.
     * 
     * ACC OFF (sentry mode): Start pipeline with surveillance enabled
     * ACC ON (normal mode): Stop pipeline completely to save power
     */
    // FIX (audit R1): equality short-circuit. AccSentry heartbeat publishes
    // the cached ACC state every 30 s; without dedup each tick re-runs the
    // full side-effect chain (cleanupDoorLockGate, surveillanceEnabled reset,
    // OEM recalc, trip analytics, etc.). Track the last dispatched ACC state
    // so duplicate IPCs no-op. Boxed Boolean (-1/null sentinel) so the very
    // first call is never elided. Volatile for cross-thread reads from IPC +
    // heartbeat threads.
    private static volatile Boolean lastDispatchedAccIsOff = null;

    public static void onAccStateChanged(boolean accIsOff) {
        // FIX (audit R1): drop redundant heartbeat re-dispatches. We still let
        // the very first dispatch through (lastDispatchedAccIsOff == null) and
        // any state change (Boolean.equals false). Down-stream consumers
        // (RecordingModeManager, OEM resolver) already short-circuit on their
        // own caches but the work to GET there (DB writes, snapshot capture,
        // GearMonitor restart, etc.) is non-trivial and not all idempotent.
        if (lastDispatchedAccIsOff != null && lastDispatchedAccIsOff.booleanValue() == accIsOff) {
            log("onAccStateChanged: no-op (already " + (accIsOff ? "OFF" : "ON")
                + ", duplicate IPC / heartbeat)");
            // Still refresh AccMonitor so any consumer reading the cache sees
            // the asserted value — this is cheap and idempotent.
            com.overdrive.app.monitor.AccMonitor.setAccState(!accIsOff);
            return;
        }
        // FIX (audit R2, finding "Dedup short-circuit eats drain dispatch when
        // IPC queued during init"): do NOT mark this state as "dispatched"
        // yet. If gpuPipeline is null, we'll only enqueue pendingAccOn/Off and
        // bail; the post-init drain at :673/:699 then re-enters this method
        // and would otherwise hit the dedup guard above and short-circuit
        // before fully running side-effects (RMM seed, OEM recalc, context
        // recreate, sentry segment finalize). Move the cache update to AFTER
        // the queuing branch so a queued IPC + later drain runs the full
        // dispatch chain exactly once.

        // Update AccMonitor state for HTTP API responses
        com.overdrive.app.monitor.AccMonitor.setAccState(!accIsOff);

        // CRITICAL: Capture the BydVehicleData snapshot and record the ACC
        // transition BEFORE any pipeline/teardown work. The OFF event must
        // be persisted before BydDataCollector.setAccState(false) (further
        // down) zeroes out polling — otherwise the OFF row would have stale
        // or null telemetry. For ON, the collector is being resumed, not
        // torn down; the snapshot may be a few seconds stale, which is
        // fine (a 3s skew is negligible vs a 12-hour park, and any latency
        // biases the displayed delta toward zero — conservative).
        //
        // Wrapped in try/catch — must NEVER throw out of onAccStateChanged
        // because that would break the daemon's state machine.
        try {
            com.overdrive.app.byd.BydVehicleData accSnapshot = null;
            try {
                com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
                if (collector != null && collector.isInitialized()) {
                    accSnapshot = collector.getData();
                }
            } catch (Throwable t) {
                // Collector not initialized yet on cold boot, etc. — pass
                // null snapshot, the row will still be recorded with the
                // event type so future correlation is possible.
            }
            com.overdrive.app.monitor.SocHistoryDatabase.getInstance()
                .recordAccEvent(accIsOff ? "OFF" : "ON", accSnapshot);
        } catch (Throwable t) {
            log("recordAccEvent failed (non-fatal): " + t.getMessage());
        }

        // ALWAYS notify TripAnalyticsManager regardless of GPU pipeline state.
        // Trip detection depends on ACC events and must not be blocked by pipeline readiness.
        if (tripAnalyticsManager != null) {
            try {
                if (accIsOff) {
                    tripAnalyticsManager.onAccOff();
                } else {
                    tripAnalyticsManager.onAccOn();
                }
            } catch (Exception e) {
                log("Trip Analytics ACC " + (accIsOff ? "OFF" : "ON") + " error: " + e.getMessage());
            }
        }
        
        if (gpuPipeline == null) {
            if (accIsOff) {
                log("ACC OFF but GPU pipeline not ready — queuing for when pipeline initializes");
                pendingAccOff = true;
                pendingAccOn = false;
            } else {
                log("ACC ON but GPU pipeline not ready — queuing for when pipeline initializes");
                pendingAccOn = true;
                pendingAccOff = false;
            }
            // NOTE: leave lastDispatchedAccIsOff unset so the post-init drain
            // can re-enter this method and run the full side-effect chain.
            return;
        }

        // Mark this state as fully dispatched only AFTER passing the
        // gpuPipeline-null queuing branch. See dedup comment above.
        lastDispatchedAccIsOff = Boolean.valueOf(accIsOff);

        log("ACC state changed: " + (accIsOff ? "OFF (entering sentry)" : "ON (exiting sentry)"));
        
        if (accIsOff) {
            // ACC OFF - Start pipeline for sentry mode
            try {
                // CRITICAL: Notify RecordingModeManager FIRST so it can finalize any
                // active continuous/drive-mode recording segment before we transition
                // to surveillance. Without this, the last recording segment is lost
                // when surveillance is disabled or suppressed by safe zone (early returns
                // below skip enableSurveillance which was the only path that stopped recording).
                if (recordingModeManager != null) {
                    log("ACC OFF - notifying RecordingModeManager to finalize active recording...");
                    recordingModeManager.onAccStateChanged(false);
                }
                // OEM Dashcam ACC-off behaviour. accOffMode='off' (default)
                // tears down the pipeline so the encoder + camera handle
                // release at ACC-off; 'continuous' lets it run via the
                // existing AccSentry peripheral keep-alive lease so the
                // user gets parked-recording without separate plumbing.
                //
                // SURVEILLANCE INTEGRATION: when oem.surveillance.enabled is
                // true the user has opted into OEM clips on motion events.
                // If we tear down at ACC OFF, the surveillance event-trigger
                // path (SurveillanceEngineGpu.startEventRecording) finds
                // pipeline=null and silently skips OEM. Treat surveillance
                // intent as an implicit "keep alive" — same path as
                // accOffMode=continuous — so motion events can fire OEM
                // recordings during sentry without the user needing to
                // toggle continuous mode separately.
                try {
                    // Post-migration mode-based dispatch (R9 regression #1).
                    // Pre-fix this read oem.enabled / oem.accOffMode — both
                    // are nulled out by migrateOemDashcamModes, so this
                    // branch silently no-op'd on every install after first
                    // boot. Now read the mode-tier accessors directly.
                    String recMode = com.overdrive.app.config.UnifiedConfigManager
                        .getOemRecordingMode();
                    String survMode = com.overdrive.app.config.UnifiedConfigManager
                        .getOemSurveillanceMode();
                    boolean anyTriggerOn = com.overdrive.app.config.UnifiedConfigManager
                        .isAnyOemDashcamTriggerEnabled();
                    // Determine whether surveillance suppression also suppresses OEM keep-alive.
                    // User explicitly opted into safe-zone privacy / schedule windows; we honor
                    // that for the OEM dashcam too.  Surveillance-side OEM modes
                    // (continuous/smart on the surv axis) record / wake during the parked
                    // window — same window the user told us to be silent in. The
                    // recording-side continuous (rec=continuous) is the user's "record across
                    // ACC OFF too" intent and intentionally bypasses surveillance suppression,
                    // mirroring how pano dashcam recording continues across ACC OFF when
                    // oem.recordingMode=continuous.
                    boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                    boolean inSafeZone = com.overdrive.app.surveillance.SafeLocationManager.getInstance().isInSafeZone();
                    boolean outsideSchedule = false;
                    try {
                        com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                            com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                        outsideSchedule = (schedule != null && schedule.isEnabled() && !schedule.isActiveNow());
                    } catch (Exception ignored) {}
                    boolean surveillanceSuppressed = !userEnabled || inSafeZone || outsideSchedule;
                    // Two-axis policy: recording-side modes describe drive-time
                    // intent (ACC ON only); surveillance-side modes describe
                    // parked-time intent (ACC OFF only). At ACC OFF the recording
                    // axis is dormant by design — only the surveillance axis can
                    // keep the pipeline alive. ALWAYS schedule a recalc so the
                    // resolver in OemDashcamApiHandler.applyTriggerLifecycleFromUcm
                    // is the single source of truth: the ACC boundary itself is a
                    // state change (e.g. rec=continuous,surv=smart must stop
                    // recording AND keep the pipeline warm — only the resolver
                    // can express that without duplicating the gating logic here).
                    if (anyTriggerOn) {
                        log("OEM Dashcam: ACC OFF — recalc (rec=" + recMode
                            + ", surv=" + survMode
                            + (surveillanceSuppressed ? ", survSuppressed" : "") + ")");
                        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                    }
                } catch (Throwable t) {
                    log("OEM Dashcam ACC OFF dispatch failed: " + t.getMessage());
                }
                
                // CRITICAL: Force-stop TelemetryDataCollector when ACC goes off.
                // No consumer needs it when the car is off (no overlay, no trip recording).
                // This prevents refcount leaks from keeping the poller alive during sentry mode.
                if (telemetryDataCollector != null) {
                    telemetryDataCollector.setOverlayRecordingActive(false);
                    telemetryDataCollector.forceStopPolling();
                    log("TelemetryDataCollector force-stopped (ACC OFF)");
                }
                
                // Stop GearMonitor polling — gear is always P when ACC is off.
                // It will be restarted on ACC ON.
                com.overdrive.app.monitor.GearMonitor.getInstance().stop();
                log("GearMonitor stopped (ACC OFF)");
                
                // Tell BydDataCollector to skip speed/engine/gearbox polling (always 0 when parked)
                com.overdrive.app.byd.BydDataCollector.getInstance().setAccState(false);
                
                // CRITICAL: FORCE remount SD card when ACC goes off — BEFORE any early returns.
                // Even if surveillance is disabled or suppressed by safe zone, the SD card must stay
                // mounted so the HTTP server can serve existing recordings/events/trips.
                // Android/BYD system unmounts SD card when ACC is off, so we MUST force remount.
                com.overdrive.app.storage.StorageManager storage = 
                    com.overdrive.app.storage.StorageManager.getInstance();
                boolean anyStorageOnSd = 
                    storage.getSurveillanceStorageType() == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD ||
                    storage.getRecordingsStorageType() == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD ||
                    storage.getTripsStorageType() == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD;
                if (anyStorageOnSd) {
                    log("FORCE mounting SD card (ACC OFF, SD card configured for storage)...");
                    if (storage.ensureSdCardMounted(true)) {
                        log("SD card force mounted");
                    } else {
                        log("WARNING: SD card mount failed - using internal storage");
                    }
                    // Watchdog already started at daemon boot in main(); calling
                    // startSdCardWatchdog() again is idempotent (it stops any
                    // existing watchdog before starting). Kept here as a
                    // defensive re-arm in case the previous instance died.
                    storage.startSdCardWatchdog();
                }
                
                // Check if user has enabled surveillance in config
                boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                if (!userEnabled) {
                    log("Surveillance NOT enabled in config — skipping auto-start on ACC OFF");
                    return;  // SD card is mounted + watchdog running
                }
                
                // Safe zone check — don't start surveillance if parked at home/work
                com.overdrive.app.surveillance.SafeLocationManager safeMgr =
                    com.overdrive.app.surveillance.SafeLocationManager.getInstance();
                if (safeMgr.isInSafeZone()) {
                    log("SAFE ZONE: Surveillance suppressed on ACC OFF — " + safeMgr.getCurrentZoneName()
                        + " (dist=" + Math.round(safeMgr.getDistanceToNearestZone()) + "m)");
                    surveillanceEnabled = true;   // Mark intent so it auto-starts when leaving zone
                    safeZoneSuppressed = true;
                    return;  // SD card is mounted + watchdog running, just skip surveillance
                }
                
                // Schedule check — don't start surveillance outside configured time windows
                try {
                    com.overdrive.app.surveillance.SurveillanceSchedule schedule = 
                        com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                    if (schedule != null && schedule.isEnabled() && !schedule.isActiveNow()) {
                        log("SCHEDULE: Surveillance suppressed on ACC OFF — outside time window (" +
                            schedule.getSummary() + ")");
                        surveillanceEnabled = true;  // Mark intent so periodic checker can start it later
                        return;  // SD card is mounted + watchdog running, just skip surveillance
                    }
                } catch (Exception e) {
                    log("Schedule check error (proceeding with surveillance): " + e.getMessage());
                }
                
                Runnable startSentryPipeline = () -> {
                    if (!gpuPipeline.isRunning()) {
                        log("Starting pipeline for sentry mode...");
                        try { gpuPipeline.start(); } catch (Exception e) {
                            log("Pipeline start failed: " + e.getMessage());
                            return;
                        }
                    }
                    gpuPipeline.setRecordingMode(
                        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.SENTRY);
                    // AVC keep-alive for sentry — same 60s poke we use during ACC-ON
                    // and streaming/recording-mode. See enableSurveillance() for why.
                    startAvcKeepAliveIfNeeded();
                    // Door lock gate: surveillance is armed only after doors are locked.
                    // This prevents false motion events from the owner exiting the car.
                    // Three parallel sources fire concurrently (cloud MQTT, device-SDK
                    // typed listener, 5s polling); arm timeout at 60s; ACC-ON disarm
                    // watchdog runs in parallel as reverse fallback.
                    log("Pipeline started in sentry mode — waiting for door lock to arm surveillance");
                    registerDoorLockListenerAndArmOnLock();

                    // SOTA: Periodic schedule checker — monitors time window transitions
                    // during active sentry. If the schedule window ends, surveillance stops.
                    // If the window starts (e.g., user parked before the window), surveillance starts.
                    // Runs every 5 minutes. Only active when ACC is off.
                    startScheduleChecker();

                    log("Pipeline started in sentry mode");
                };

                if (isDilink4ModeActive()) {
                    // ESCO-PARITY: no 60s gate. esco opens AVMCamera
                    // immediately on PanoCameraRecordService start. The
                    // FlameoutService 60s timer schedules a SECONDARY
                    // sentry consumer, not a delay before camera open.
                    // ensureAvcAlive() (pidof on dilink4 — no am start)
                    // probes AVC presence without launching it.
                    try {
                        com.overdrive.app.camera.AvcHalWarmup.ensureAvcAlive();
                    } catch (Throwable th) {
                        log("AVC initial probe failed: " + th.getMessage());
                    }
                }
                startSentryPipeline.run();
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null) {
                    errorMsg = e.getClass().getSimpleName();
                }
                log("ERROR: Failed to start pipeline for sentry: " + errorMsg);
                e.printStackTrace();
            }
        } else {
            // ACC ON. We intentionally leave the SD-card watchdog running here:
            // BYD/Android can unmount the SD even with ACC on, and stopping the
            // watchdog created a window where the HTTP server returned empty
            // recordings until the user cycled ACC OFF→ON. The watchdog is
            // started at daemon boot in main() and runs for the daemon's
            // lifetime as long as any storage type is set to SD.

            // Stop schedule checker (only runs during ACC OFF sentry mode)
            stopScheduleChecker();

            // Stop door lock gate: detach cloud + device-SDK listeners, stop
            // unlock poll, stop ACC-ON disarm watchdog.
            cleanupDoorLockGate();

            // Reset surveillance intent flag. The safe-zone-suppressed and
            // schedule-suppressed branches in the ACC OFF handler set
            // surveillanceEnabled=true *without* actually arming, as an
            // "intent" marker for later re-evaluation. Once ACC turns ON
            // those branches no longer apply, and leaving the flag set
            // misleads the next cycle's force-arm timeout (which gates on it)
            // and the schedule checker. enableSurveillance() will set it
            // again next time the lock gate or schedule fires.
            if (surveillanceEnabled) {
                log("ACC ON: clearing sticky surveillanceEnabled flag");
                surveillanceEnabled = false;
            }

            // Clear safe-zone suppression flag. It was set during the prior
            // ACC OFF in a safe zone to record "would have armed surveillance,
            // but suppressed by geofence." Once the user has turned ACC back
            // ON the suppression no longer applies — recording modes
            // (CONTINUOUS / DRIVE_MODE / PROXIMITY_GUARD) handle their own
            // activation independent of surveillance state. Without this
            // clear, the daemon status JSON keeps reporting safeZoneSuppressed=true
            // until the GPS poller eventually notices the boundary crossing,
            // which can be minutes after driving away.
            if (safeZoneSuppressed) {
                log("Clearing safeZoneSuppressed flag on ACC ON (was set during last sentry suppression)");
                safeZoneSuppressed = false;
            }
            
            // Recreate app context if it was broken (system server was dead during init).
            // ACC ON means the head unit is awake and binder services should be available.
            // Run on a background thread because createAppContext() can block up to 10s
            // (systemMain timeout) — must not freeze the ACC ON handler.
            if (isContextBroken()) {
                new Thread(() -> {
                    log("ACC ON: sharedAppContext is broken — attempting recreation...");
                    android.content.Context newContext = createAppContext();
                    if (newContext != null && !isContextBrokenFor(newContext)) {
                        sharedAppContext = newContext;
                        log("ACC ON: App context recreated successfully");
                        
                        // Re-init components that failed with the broken context
                        reinitContextDependentComponents();
                        
                        // Now start GearMonitor if it still isn't running
                        com.overdrive.app.monitor.GearMonitor gm = com.overdrive.app.monitor.GearMonitor.getInstance();
                        if (!gm.isRunning()) {
                            try {
                                gm.start();
                                log("ACC ON: GearMonitor started after context recreation");
                            } catch (Exception e) {
                                log("ACC ON: GearMonitor start failed after recreation: " + e.getMessage());
                            }
                        }
                        
                        // Notify RecordingModeManager of current gear now that GearMonitor works
                        if (recordingModeManager != null && gm.isRunning()) {
                            recordingModeManager.onGearChanged(gm.getCurrentGear());
                        }
                    } else {
                        log("ACC ON: Context recreation failed — system services may still be starting");
                    }
                }, "ContextRecreate").start();
            }
            
            // Restart GearMonitor (stopped on ACC OFF)
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            if (!gearMonitor.isRunning()) {
                try {
                    gearMonitor.start();
                    log("GearMonitor restarted (ACC ON)");
                } catch (Exception e) {
                    log("GearMonitor restart failed (ACC ON): " + e.getMessage());
                }
            }
            
            // Tell BydDataCollector to resume full polling (speed/engine/gearbox)
            com.overdrive.app.byd.BydDataCollector.getInstance().setAccState(true);
            
            // If pipeline is currently in SURVEILLANCE mode, gracefully exit it:
            // finalize any in-progress sentry recording, flush the encoder, drop
            // out of SURVEILLANCE, and reopen the camera so BYD's native AVM app
            // can grab the primary slot. Skipped when not in surveillance —
            // calling onAccOn() in steady-state NORMAL_RECORDING would stop the
            // active recording and reopen the camera, which is exactly the
            // regression we're avoiding for duplicate ACC ON IPCs.
            if (gpuPipeline != null && gpuPipeline.isSurveillanceMode()) {
                try {
                    gpuPipeline.onAccOn();
                } catch (Exception e) {
                    log("gpuPipeline.onAccOn() error: " + e.getMessage()
                        + " — forcing pipeline.stop() to clear wedge state");
                    // Compensating teardown: a half-failed surveillance->normal
                    // transition leaves running=true with a dead camera handle,
                    // and the next pipeline.start() short-circuits because
                    // isRunning() returns true. Force stop() so the next
                    // recordingModeManager.onAccStateChanged(true) below sees a
                    // cleanly-stopped pipeline and runs a fresh start(false).
                    try {
                        gpuPipeline.stop();
                    } catch (Throwable t) {
                        log("Compensating pipeline.stop() also failed: " + t.getMessage());
                    }
                    // FIX (audit R7, finding "Dedup cache set before side-effects
                    // complete"): we set lastDispatchedAccIsOff=ACC_ON above
                    // BEFORE running this side-effect chain. If onAccOn() threw
                    // and the compensating stop ran, the pipeline is now down
                    // but the dedup cache says "ACC ON fully dispatched". The
                    // next AccSentry 30s heartbeat would no-op via the dedup
                    // guard at the top of this method and pano CONTINUOUS /
                    // DRIVE_MODE recording would stay dead until a manual ACC
                    // cycle. Null the cache so the next heartbeat re-enters
                    // and reruns the full chain (including
                    // recordingModeManager.onAccStateChanged(true) / RMM
                    // wedge-resync) against the now-cleanly-stopped pipeline.
                    log("WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs full ACC ON dispatch");
                    lastDispatchedAccIsOff = null;
                }
            }

            // Notify RecordingModeManager — it handles starting recording mode
            log("ACC ON - notifying RecordingModeManager...");
            if (recordingModeManager != null) {
                // FIX (audit R8, finding "notifyAccState treats {success:false}
                // IPC reply as success → seeds dedup against a partial-apply"):
                // recordingModeManager.onAccStateChanged(true) can throw under
                // pipeline wedge / HAL flake. The outer ACC-ON else branch
                // here lacks an outer try/catch, so the IPC reply path
                // (SurveillanceIpcServer outer try) returns success:false and
                // AccSentry's heartbeat dedup seeds against a partial apply.
                // Mirror the gpuPipeline.onAccOn() compensating teardown:
                // null lastDispatchedAccIsOff so the next AccSentry heartbeat
                // re-runs the full chain instead of being suppressed by the
                // dedup guard at the top of this method.
                try {
                    recordingModeManager.onAccStateChanged(true);
                } catch (Throwable t) {
                    log("WARN: recordingModeManager.onAccStateChanged(true) threw: "
                        + t.getMessage()
                        + " — clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs");
                    lastDispatchedAccIsOff = null;
                }
            }
            // OEM Dashcam ACC-on hook. The ACC boundary itself is a state
            // transition for the two-axis resolver — recording-axis modes only
            // arm during ACC ON, surveillance-axis modes only arm during ACC
            // OFF. Always recalc when any trigger is on, regardless of whether
            // the pipeline is currently live (it may have been kept warm by
            // surv=smart during sentry, in which case rec=continuous still
            // needs to flip recording on now).
            try {
                if (com.overdrive.app.config.UnifiedConfigManager
                        .isAnyOemDashcamTriggerEnabled()) {
                    log("OEM Dashcam: ACC ON — recalc");
                    com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                }
            } catch (Throwable t) {
                log("OEM Dashcam ACC ON dispatch failed: " + t.getMessage());
            }
            if (recordingModeManager == null) {
                // Previously this branch tore down the pipeline as "legacy
                // power-save fallback." That races initSurveillance(): if the
                // ACC ON IPC arrives before the manager is constructed, we'd
                // stop the pipeline that init was about to wire up, and the
                // pendingAccOn drain would then fire onAccStateChanged on a
                // pipeline we just killed. Leave the pipeline alone — a future
                // component (manager init drain, OEM resolver, surveillance
                // event) will start/stop it as needed.
                log("WARNING: recordingModeManager null on ACC ON — "
                    + "recording disabled until daemon restart or init completes");
                // FIX (audit R8, finding "ACC ON dispatch sets lastDispatchedAccIsOff
                // before checking rmm==null branch"): we set
                // lastDispatchedAccIsOff=ACC_ON above before reaching this
                // branch. RMM was never notified, so the next AccSentry
                // heartbeat IPC must re-run the full chain. Null the cache
                // (mirrors the R7 pattern at the gpuPipeline.onAccOn() catch
                // and the new R8 RMM-throw pattern above) so the dedup guard
                // doesn't suppress the heartbeat after watchdog/ContextRecreate
                // eventually creates rmm.
                log("WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs full ACC ON dispatch");
                lastDispatchedAccIsOff = null;
            }
        }
    }
    
    /**
     * Notify of gear state change.
     * 
     * Used by PROXIMITY_GUARD mode to activate/deactivate based on gear position.
     * When gear != P, proximity guard starts monitoring.
     * When gear = P, proximity guard stops (ADAS sensors go to ABNORMAL which is expected).
     * 
     * @param gear The new gear position (1=P, 2=R, 3=N, 4=D, 5=M, 6=S)
     */
    private static volatile int lastNotifiedGear = Integer.MIN_VALUE;

    public static void onGearChanged(int gear) {
        String gearName = com.overdrive.app.recording.RecordingModeManager.gearToString(gear);

        // GearMonitor primes the system with one initial notification on
        // start(); subsequent rapid duplicates can also slip through during
        // ACC ON re-init. Skip logging when the gear value is unchanged from
        // the last notification — downstream listeners already short-circuit
        // duplicate gears, but the daemon log shouldn't keep restating it.
        boolean redundant = (gear == lastNotifiedGear);
        lastNotifiedGear = gear;
        if (!redundant) {
            log("Gear changed to: " + gearName);
        }

        if (recordingModeManager != null) {
            recordingModeManager.onGearChanged(gear);
        } else if (!redundant) {
            log("RecordingModeManager not initialized - gear change ignored");
        }

        if (tripAnalyticsManager != null) tripAnalyticsManager.onGearChanged(gear);
    }
    
    /**
     * Check if surveillance is enabled.
     */
    public static boolean isSurveillanceEnabled() {
        return surveillanceEnabled;
    }
    
    /** True if surveillance was requested but suppressed because car is in a safe zone. */
    public static boolean isSafeZoneSuppressed() {
        return safeZoneSuppressed;
    }

    /**
     * True while the door-lock gate has fired and surveillance is genuinely
     * armed (owner has stepped away). Cleared on unlock or ACC ON. Used by
     * the mode-switch restart path to distinguish "user wants surveillance
     * generally" (UnifiedConfig.isSurveillanceEnabled) from "surveillance is
     * actually live right now" — the latter must be true to safely re-arm
     * after a stop+restart, otherwise an unlock-during-restart would re-arm
     * a session the owner just walked back into.
     */
    public static boolean isDoorLockArmed() {
        return doorLockListenerArmed;
    }
    
    public static void setSafeZoneSuppressed(boolean suppressed) {
        safeZoneSuppressed = suppressed;
    }
    
    // ==================== SCHEDULE CHECKER ====================
    
    private static Thread scheduleCheckerThread = null;
    
    /**
     * Starts the periodic schedule checker that monitors time window transitions.
     * Runs every 5 minutes while ACC is off. Stops when ACC turns on.
     */
    private static void startScheduleChecker() {
        stopScheduleChecker();
        scheduleCheckerThread = new Thread(new Runnable() {
            public void run() {
                log("Schedule checker started (5-min interval)");
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(5 * 60 * 1000);  // 5 minutes
                    } catch (InterruptedException e) {
                        break;
                    }
                    
                    // Only check when ACC is off
                    if (com.overdrive.app.monitor.AccMonitor.isAccOn()) continue;
                    
                    try {
                        com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                            com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                        
                        // Schedule disabled = always active, nothing to check
                        if (schedule == null || !schedule.isEnabled()) continue;
                        
                        boolean withinWindow = schedule.isActiveNow();
                        boolean currentlyActive = surveillanceEnabled && gpuPipeline != null 
                                && gpuPipeline.isSurveillanceMode();
                        
                        if (!withinWindow && currentlyActive) {
                            // Schedule window ended — stop surveillance
                            log("SCHEDULE: Time window ended (" + schedule.getSummary() + 
                                ") — stopping surveillance");
                            disableSurveillance();
                        } else if (withinWindow && !currentlyActive && !safeZoneSuppressed) {
                            // Schedule window started — enable surveillance if other conditions met
                            boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager
                                .isSurveillanceEnabled();
                            if (userEnabled) {
                                log("SCHEDULE: Time window started (" + schedule.getSummary() + 
                                    ") — enabling surveillance");
                                enableSurveillance();
                            }
                        }
                    } catch (Exception e) {
                        log("Schedule checker error: " + e.getMessage());
                    }
                }
                log("Schedule checker stopped");
            }
        }, "ScheduleChecker");
        scheduleCheckerThread.setDaemon(true);
        scheduleCheckerThread.start();
    }
    
    /**
     * Stops the periodic schedule checker.
     */
    private static void stopScheduleChecker() {
        if (scheduleCheckerThread != null) {
            scheduleCheckerThread.interrupt();
            scheduleCheckerThread = null;
        }
    }
    
    /**
     * Check if surveillance is actively processing.
     */
    public static boolean isSurveillanceActive() {
        return gpuPipeline != null && gpuPipeline.isRunning();
    }
    
    /**
     * Set recording quality tier — single user-facing knob that bundles
     * bitrate + perceptual quality. Accepts the new tier names
     * (ECONOMY/STANDARD/HIGH/PREMIUM/MAX). Anything else falls back to
     * STANDARD per the migration policy.
     */
    public static void setRecordingQuality(String quality) {
        if (gpuPipeline == null) return;
        if (gpuPipeline.getConfig() == null) {
            log("setRecordingQuality: config is null, skipping");
            return;
        }

        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality tier =
            com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.fromString(quality);

        gpuPipeline.getConfig().setRecordingQuality(tier);
        int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
        gpuPipeline.applyBitrateChange(effectiveBitrate);
        log("Recording quality set to: " + tier
            + " (" + effectiveBitrate / 1_000_000 + " Mbps for "
            + gpuPipeline.getConfig().getVideoCodec() + ")");
    }
    
    /**
     * Set streaming quality.
     */
    public static void setStreamingQuality(String quality) {
        if (gpuPipeline == null) return;
        
        com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality streamQuality =
            com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality.fromString(quality);
        
        gpuPipeline.setStreamingQuality(streamQuality);
        log("Streaming quality set to: " + streamQuality.displayName);
    }
    
    /**
     * @deprecated use {@link #setRecordingQuality(String)} with one of
     *             ECONOMY / STANDARD / HIGH / PREMIUM / MAX. Old LOW/MEDIUM/
     *             HIGH bitrate strings are mapped to the closest tier.
     */
    @Deprecated
    public static void setRecordingBitrate(String bitrate) {
        if (bitrate == null) return;
        String tier;
        switch (bitrate.toUpperCase()) {
            case "LOW":    tier = "ECONOMY"; break;
            case "MEDIUM": tier = "STANDARD"; break;
            case "HIGH":   tier = "HIGH"; break;
            default:       tier = "STANDARD"; break;
        }
        log("setRecordingBitrate(" + bitrate + ") → mapping to recordingQuality=" + tier);
        setRecordingQuality(tier);
    }
    
    /**
     * Set recording codec (H.264 or H.265).
     * Note: Codec change requires encoder restart.
     */
    public static void setRecordingCodec(String codec) {
        if (gpuPipeline == null) {
            log("setRecordingCodec: gpuPipeline is null, skipping");
            return;
        }
        
        try {
            com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec videoCodec;
            switch (codec.toUpperCase()) {
                case "H265":
                case "HEVC":
                    videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265;
                    break;
                case "H264":
                case "AVC":
                default:
                    videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                    break;
            }
            
            if (gpuPipeline.getConfig() == null) {
                log("setRecordingCodec: config is null, skipping");
                return;
            }
            
            gpuPipeline.getConfig().setVideoCodec(videoCodec);
            gpuPipeline.applyCodecChange(videoCodec);
            log("Recording codec set to: " + codec + " (" + videoCodec.displayName + ") - restart recording to apply");
        } catch (Exception e) {
            log("setRecordingCodec error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get current recording quality tier (ECONOMY..MAX).
     * Canonical accessor — prefer this over the deprecated bitrate alias.
     */
    public static String getRecordingQuality() {
        if (gpuPipeline == null || gpuPipeline.getConfig() == null) return "STANDARD";
        return gpuPipeline.getConfig().getRecordingQuality().name();
    }

    /**
     * Get current recording bitrate setting.
     * @deprecated Use {@link #getRecordingQuality()} for the canonical tier.
     */
    @Deprecated
    public static String getRecordingBitrate() {
        if (gpuPipeline == null) return "MEDIUM";
        return gpuPipeline.getConfig().getBitratePreset().name();
    }

    /**
     * Get current recording codec setting.
     */
    public static String getRecordingCodec() {
        if (gpuPipeline == null) return "H264";
        return gpuPipeline.getConfig().getVideoCodec() == 
            com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265 ? "H265" : "H264";
    }
    
    /**
     * Get GPU pipeline instance.
     */
    public static com.overdrive.app.surveillance.GpuSurveillancePipeline getGpuPipeline() {
        return gpuPipeline;
    }

    /**
     * Get the OEM Dashcam pipeline instance (or null if not started).
     */
    public static com.overdrive.app.camera.OemDashcamPipeline getOemDashcamPipeline() {
        return oemDashcamPipeline;
    }

    /**
     * Get the shared TelemetryDataCollector. Null until pano pipeline
     * initialises it (line 1856-1858). OEM lifecycle injects this so its
     * overlay refcount discipline can hold polling like pano does.
     */
    public static com.overdrive.app.telemetry.TelemetryDataCollector getTelemetryDataCollector() {
        return telemetryDataCollector;
    }

    /**
     * Generation counter for OEM dashcam pipeline instances. Bumped on
     * every {@link #setOemDashcamPipeline} call. Surveillance captures
     * the value at event-trigger time and compares on event-end so a
     * stop call only fires against the SAME pipeline instance that
     * issued tryStartIfIdle. Without this, a quality-mirror restart
     * (which tears down and rebuilds the pipeline) would let
     * surveillance's event-end stop the user's NEW continuous recording.
     */
    private static final java.util.concurrent.atomic.AtomicInteger
        oemDashcamPipelineGeneration = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Set the OEM Dashcam pipeline reference. Called by RecordingModeManager
     * after it constructs / starts the pipeline. Setting null indicates the
     * pipeline has been torn down.
     */
    public static void setOemDashcamPipeline(com.overdrive.app.camera.OemDashcamPipeline p) {
        oemDashcamPipeline = p;
        oemDashcamPipelineGeneration.incrementAndGet();
    }

    /** Read the current pipeline generation. Surveillance compares this
     *  on event-end against what it captured at event-start. */
    public static int getOemDashcamPipelineGeneration() {
        return oemDashcamPipelineGeneration.get();
    }

    /**
     * Switch the WebSocket stream sink to the OEM Dashcam encoder. Called by
     * StreamingApiHandler when view mode 6 is selected. Returns true if the
     * routing actually attached, false otherwise — caller surfaces an
     * honest error to the client when the underlying gates refuse.
     */
    public static boolean routeStreamToOemDashcam() {
        if (oemDashcamPipeline == null) {
            log("routeStreamToOemDashcam: pipeline null; ignoring");
            return false;
        }
        if (gpuPipeline == null) {
            log("routeStreamToOemDashcam: gpuPipeline null; ignoring");
            return false;
        }
        try {
            return gpuPipeline.attachExternalStreamCallback(oemDashcamPipeline);
        } catch (Throwable t) {
            log("routeStreamToOemDashcam: attach failed: " + t.getMessage());
            return false;
        }
    }
    
    // ==================== RECORDING MODE CONTROL ====================
    
    /**
     * Set recording mode (NONE, CONTINUOUS, DRIVE_MODE, PROXIMITY_GUARD).
     */
    public static void setRecordingMode(String mode) {
        if (recordingModeManager == null) {
            log("ERROR: RecordingModeManager not initialized");
            return;
        }
        
        try {
            com.overdrive.app.recording.RecordingModeManager.Mode modeEnum =
                com.overdrive.app.recording.RecordingModeManager.Mode.valueOf(mode.toUpperCase());
            recordingModeManager.setMode(modeEnum);
            log("Recording mode set to: " + mode);
        } catch (IllegalArgumentException e) {
            log("ERROR: Invalid recording mode: " + mode);
        }
    }
    
    /**
     * Get current recording mode.
     */
    public static String getRecordingMode() {
        if (recordingModeManager == null) {
            return "NONE";
        }
        return recordingModeManager.getCurrentMode().name();
    }
    
    /**
     * Get recording mode manager instance.
     */
    public static com.overdrive.app.recording.RecordingModeManager getRecordingModeManager() {
        return recordingModeManager;
    }
    
    /**
     * Get surveillance status for API.
     */
    public static java.util.Map<String, Object> getSurveillanceStatus() {
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        
        if (gpuPipeline != null) {
            status.put("initialized", gpuPipeline.isInitialized());
            status.put("enabled", surveillanceEnabled);
            status.put("active", gpuPipeline.isRunning());
            status.put("recording", gpuPipeline.getSentry() != null && gpuPipeline.getSentry().isRecording());
            status.put("frameCount", gpuPipeline.getCamera() != null ? gpuPipeline.getCamera().getFrameCount() : 0);
            status.put("encoderType", "gpu-zero-copy");
            
            // Grid motion stats (for UI display)
            if (gpuPipeline.getSentry() != null) {
                status.put("activeBlocks", gpuPipeline.getSentry().getLastActiveBlocksCount());
                status.put("totalBlocks", gpuPipeline.getSentry().getTotalBlocks());
                status.put("baselineBlocks", gpuPipeline.getSentry().getBaselineNoiseBlocks());
                status.put("blockSensitivity", gpuPipeline.getSentry().getBlockSensitivity());
                status.put("requiredBlocks", gpuPipeline.getSentry().getRequiredActiveBlocks());
                
                // SOTA: Enhanced motion detection stats
                status.put("temporalBlocks", gpuPipeline.getSentry().getLastTemporalBlocksCount());
                status.put("estimatedDistance", gpuPipeline.getSentry().getLastEstimatedDistance());
                int[] bounds = gpuPipeline.getSentry().getLastMotionBounds();
                if (bounds != null) {
                    status.put("motionMinY", bounds[0]);
                    status.put("motionMaxY", bounds[1]);
                }
            }
            
            // Get today's events with details
            java.util.List<java.util.Map<String, Object>> events = getTodaysEvents();
            status.put("totalEventsToday", events.size());
            status.put("events", events);
        } else {
            status.put("initialized", false);
            status.put("enabled", false);
            status.put("active", false);
            status.put("encoderType", "none");
            status.put("totalEventsToday", 0);
            status.put("events", new java.util.ArrayList<>());
        }
        
        // SOTA: Safe Location status
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        status.put("safeZoneSuppressed", safeZoneSuppressed);
        status.put("inSafeZone", safeMgr.isInSafeZone());
        status.put("safeZoneName", safeMgr.getCurrentZoneName());
        
        // SOTA: BYD camera coordinator status
        if (gpuPipeline != null && gpuPipeline.getCamera() != null) {
            com.overdrive.app.camera.BydCameraCoordinator coordinator = 
                gpuPipeline.getCamera().getCameraCoordinator();
            if (coordinator != null) {
                status.put("cameraServiceRegistered", coordinator.isRegistered());
                // cameraUserRegistered intentionally omitted — registerCameraUser is
                // permanently DISABLED, the value is always false. Polling fallback
                // is the only live path. See BydCameraCoordinator.register().
                status.put("cameraYielded", coordinator.isYielded());
                status.put("nativeAppActive", coordinator.isNativeAppActive());
                status.put("cameraEventCallback", coordinator.isEventCallbackActive());
            }
            
            // SOTA: Camera probe status
            com.overdrive.app.camera.PanoramicCameraGpu cam = gpuPipeline.getCamera();
            status.put("probeComplete", cam.isProbeComplete());
            status.put("activeCameraId", cam.getCameraId());
            status.put("activeSurfaceMode", cam.getCameraSurfaceMode());
        }
        
        return status;
    }
    
    /**
     * Count event recordings from today.
     * Looks for files matching pattern: event_YYYYMMDD_*.mp4 in sentry_events directory
     */
    private static int countTodaysEvents() {
        return getTodaysEvents().size();
    }
    
    /** Map battery capacity to ABRP car model name */
    private static String capacityToModelName(double capacityKwh) {
        if (capacityKwh >= 105) return "byd:seal:23:108";     // Tang EV
        if (capacityKwh >= 84) return "byd:han:21:85";        // Han EV
        if (capacityKwh >= 80) return "byd:seal:23:82";       // Seal
        if (capacityKwh >= 70) return "byd:seal_u:24:72";     // Seal U
        if (capacityKwh >= 59) return "byd:atto3:22:60";      // Atto 3
        if (capacityKwh >= 55) return "byd:qin_plus:21:56";   // Qin Plus
        if (capacityKwh >= 43) return "byd:dolphin:22:45";    // Dolphin
        if (capacityKwh >= 36) return "byd:seagull:23:38";    // Seagull
        return null;
    }
    
    /**
     * Get list of today's events with timestamps.
     * Returns list of event info maps with filename, time, and size.
     */
    public static java.util.List<java.util.Map<String, Object>> getTodaysEvents() {
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        
        try {
            // Get today's date prefix (e.g., "event_20260111_")
            String todayPrefix = "event_" + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date()) + "_";
            
            // SOTA: Use StorageManager for surveillance directory
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            java.io.File sentryDir = storageManager.getSurveillanceDir();
            java.io.File[] files = null;
            
            if (sentryDir.exists() && sentryDir.isDirectory()) {
                files = sentryDir.listFiles((dir, name) -> 
                    name.startsWith(todayPrefix) && name.endsWith(".mp4"));
            }
            
            // Fallback to legacy locations for backward compatibility
            if (files == null || files.length == 0) {
                sentryDir = new java.io.File(outputDir, "sentry_events");
                if (sentryDir.exists() && sentryDir.isDirectory()) {
                    files = sentryDir.listFiles((dir, name) -> 
                        name.startsWith(todayPrefix) && name.endsWith(".mp4"));
                }
            }
            
            if (files == null || files.length == 0) {
                sentryDir = new java.io.File("/storage/emulated/0/Android/data/com.overdrive.app/files/sentry_events");
                if (sentryDir.exists() && sentryDir.isDirectory()) {
                    files = sentryDir.listFiles((dir, name) -> 
                        name.startsWith(todayPrefix) && name.endsWith(".mp4"));
                }
            }
            
            if (files != null) {
                // Sort by filename (which includes timestamp) descending (newest first)
                java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                
                for (java.io.File file : files) {
                    java.util.Map<String, Object> event = new java.util.HashMap<>();
                    event.put("filename", file.getName());
                    event.put("size", file.length() / 1024); // KB
                    
                    // Extract time from filename: event_YYYYMMDD_HHMMSS.mp4
                    String name = file.getName();
                    if (name.length() >= 22) {
                        String timeStr = name.substring(15, 21); // HHMMSS
                        String formatted = timeStr.substring(0, 2) + ":" + timeStr.substring(2, 4) + ":" + timeStr.substring(4, 6);
                        event.put("time", formatted);
                    } else {
                        event.put("time", "--:--:--");
                    }
                    
                    events.add(event);
                }
            }
        } catch (Exception e) {
            log("Error getting today's events: " + e.getMessage());
        }
        
        return events;
    }
    
    /**
     * Get comprehensive streaming status (VPS streaming removed).
     * Returns a map with streaming state info for API responses.
     */
    public static Map<String, Object> getStreamingStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("enabled", false);
        status.put("deviceId", deviceId);
        status.put("activeCameras", new java.util.ArrayList<>());
        status.put("publisherCount", 0);
        status.put("mode", streamMode);
        status.put("note", "VPS streaming removed - use local HTTP streaming");
        
        // Per-camera status (all false)
        Map<Integer, Boolean> cameraStatus = new java.util.HashMap<>();
        for (int i = 1; i <= 4; i++) {
            cameraStatus.put(i, false);
        }
        status.put("cameras", cameraStatus);
        
        return status;
    }

    // ==================== STREAM MODE CONTROL ====================
    
    /**
     * Set stream mode: "private" (local only) or "public" (tunnel access).
     * Both modes now use tunnel URLs for remote access.
     */
    public static void setStreamMode(String mode) {
        if (!STREAM_MODE_PRIVATE.equals(mode) && !STREAM_MODE_PUBLIC.equals(mode)) {
            log("ERROR: Invalid stream mode: " + mode);
            return;
        }
        
        String oldMode = streamMode;
        streamMode = mode;
        
        // Persist to file
        saveStreamMode(mode);
        
        log("Stream mode changed: " + oldMode + " -> " + mode);
        // VPS heartbeat removed - both modes use tunnel URLs now
    }
    
    /**
     * Save stream mode to file for persistence.
     */
    private static void saveStreamMode(String mode) {
        try {
            java.io.FileWriter writer = new java.io.FileWriter(PATH_STREAM_MODE_FILE());
            writer.write(mode);
            writer.close();
        } catch (Exception e) {
            log("Failed to save stream mode: " + e.getMessage());
        }
    }
    
    /**
     * Load stream mode from file.
     */
    private static void loadStreamMode() {
        try {
            File file = new File(PATH_STREAM_MODE_FILE());
            if (file.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
                String mode = reader.readLine();
                reader.close();
                
                if (STREAM_MODE_PUBLIC.equals(mode)) {
                    log("Restored stream mode: PUBLIC");
                    setStreamMode(STREAM_MODE_PUBLIC);
                } else {
                    log("Restored stream mode: PRIVATE");
                    streamMode = STREAM_MODE_PRIVATE;
                }
            }
        } catch (Exception e) {
            log("Failed to load stream mode: " + e.getMessage());
        }
    }
    
    /**
     * Get current stream mode.
     */
    public static String getStreamMode() {
        return streamMode;
    }
    
    /**
     * Check if public streaming is enabled.
     */
    public static boolean isPublicMode() {
        return STREAM_MODE_PUBLIC.equals(streamMode);
    }
    
    /**
     * Get list of recording cameras (helper for status).
     */
    private static java.util.List<Integer> getRecordingCameras() {
        java.util.List<Integer> recording = new java.util.ArrayList<>();
        // GPU pipeline records all 4 cameras in mosaic
        if (gpuPipeline != null && gpuPipeline.isRunning()) {
            recording.add(1);
            recording.add(2);
            recording.add(3);
            recording.add(4);
        }
        return recording;
    }

    // ==================== INITIALIZATION ====================
    
    private static void generateDeviceId() {
        // FIRST: Try to read from shared file (written by app with context)
        // This ensures daemon uses the same ID as the app
        try {
            File idFile = new File(PATH_DEVICE_ID_FILE());
            if (idFile.exists()) {
                // Self-heal for older installs: the legacy saveDeviceId()
                // didn't chmod the file, leaving it at the shell-UID-only
                // mode 0600 default. The app UID couldn't read it, fell
                // back to the "overdrive-default-device" sentinel, derived
                // a different AES key, and silently failed to decrypt every
                // stored credential. setReadable(true, false) is idempotent
                // — no-op if it's already world-readable from a recent
                // install. Apply on every daemon start so a re-deploy
                // repairs older devices automatically.
                try {
                    idFile.setReadable(true, false);
                    idFile.setWritable(true, false);
                } catch (Exception ignored) {}
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(idFile));
                String fileId = reader.readLine();
                reader.close();
                if (fileId != null && !fileId.isEmpty() && fileId.startsWith("byd-")) {
                    deviceId = fileId;
                    log("Device ID loaded from file: " + deviceId);
                    return;
                }
            }
        } catch (Exception e) {
            log("WARN: Could not read device ID from file: " + e.getMessage());
        }
        
        // Fallback: use serial number hash
        try {
            String serial = android.os.Build.SERIAL;
            if (serial != null && !serial.equals("unknown")) {
                deviceId = "byd-" + Integer.toHexString(serial.hashCode()).substring(0, 8);
                saveDeviceId(deviceId);
                log("Device ID generated from serial: " + deviceId);
                return;
            }
        } catch (Exception e) {
            log("WARN: Could not get serial: " + e.getMessage());
        }
        
        // Fallback: use build fingerprint hash
        try {
            String fingerprint = android.os.Build.FINGERPRINT;
            if (fingerprint != null && !fingerprint.isEmpty()) {
                deviceId = "byd-" + Integer.toHexString(fingerprint.hashCode()).substring(0, 8);
                saveDeviceId(deviceId);
                log("Device ID generated from fingerprint: " + deviceId);
                return;
            }
        } catch (Exception e) {
            log("WARN: Could not get fingerprint: " + e.getMessage());
        }
        
        // Last resort: generate random ID
        deviceId = "byd-" + Long.toHexString(System.currentTimeMillis()).substring(4);
        saveDeviceId(deviceId);
        log("Device ID generated randomly: " + deviceId);
    }
    
    private static void saveDeviceId(String id) {
        try {
            File idFile = new File(PATH_DEVICE_ID_FILE());
            java.io.FileWriter writer = new java.io.FileWriter(idFile);
            writer.write(id);
            writer.close();
            // Files created in /data/local/tmp by the shell-UID daemon land
            // at mode 0600 owned by shell. The app UID can't read them at
            // that mode, so CredentialCipher.readDid() falls through to the
            // "overdrive-default-device" sentinel and derives a different
            // AES key — every encrypted credential (telegram bot token,
            // BYD-cloud password) decodes to "" in the app process.
            // Set world-readable so both UIDs read the same DID and derive
            // the same key. setWritable too so the app can update the DID
            // if a future migration ever needs to.
            idFile.setReadable(true, false);
            idFile.setWritable(true, false);
        } catch (Exception e) {
            log("WARN: Could not save device ID to file: " + e.getMessage());
        }
    }
    
    private static void parseArguments(String[] args) {
        if (args.length > 0) {
            outputDir = args[0];
            log("Arg[0] outputDir: " + outputDir);
        }
        
        if (args.length > 1) {
            nativeLibDir = args[1];  // Use class field
            log("Arg[1] nativeLibDir: " + nativeLibDir);
        }
    }
    
    private static void loadNativeLibraries() {
        try {
            try { System.loadLibrary("nativehelper"); } catch (Throwable t) {}
            System.loadLibrary("cutils");
            System.loadLibrary("utils");
            System.loadLibrary("binder");
            System.loadLibrary("gui");
            System.loadLibrary("bmmcamera");
        } catch (Throwable e) {
            log("WARN: System lib warning: " + e.getMessage());
        }
        
        // Load surveillance library - try default path first
        if (!com.overdrive.app.surveillance.NativeMotion.isLibraryLoaded()) {
            // Try explicit path using nativeLibDir
            if (nativeLibDir != null) {
                if (com.overdrive.app.surveillance.NativeMotion.tryLoadLibrary(nativeLibDir)) {
                    log("Surveillance library loaded from: " + nativeLibDir);
                } else {
                    // Try alternate paths
                    loadSurveillanceFromPath(nativeLibDir);
                }
            }
            
            // Final check
            if (com.overdrive.app.surveillance.NativeMotion.isLibraryLoaded()) {
                log("Surveillance library loaded successfully");
            } else {
                log("WARN: Surveillance library NOT available: " +
                    com.overdrive.app.surveillance.NativeMotion.getLoadError());
            }
        } else {
            log("Surveillance library already loaded");
        }

        // Load libod.so via explicit path too — System.loadLibrary("od") can't
        // resolve by name in the app_process daemon (same reason as surveillance).
        // Without this, Od.resolve() returns zeros in the daemon and the view-7/8
        // stitch shader gets all-zero coefficients → black blind-spot stream.
        if (nativeLibDir != null) {
            boolean odLoaded = com.overdrive.app.od.Od.tryLoadLibrary(nativeLibDir);
            log("od native lib loaded (daemon): " + odLoaded);
        }
    }
    
    private static void loadSurveillanceFromPath(String nativeLibDir) {
        // Load surveillance library
        String[] surveillancePaths = {
            nativeLibDir + "/libsurveillance.so",
            nativeLibDir.replace("/arm64", "/arm64-v8a") + "/libsurveillance.so",
            nativeLibDir + "-v8a/libsurveillance.so"
        };
        
        for (String libPath : surveillancePaths) {
            if (new File(libPath).exists()) {
                try {
                    System.load(libPath);
                    log("SUCCESS: Surveillance library loaded from: " + libPath);
                    return;
                } catch (Throwable e) {
                    log("ERROR: FAILED to load " + libPath + ": " + e.getMessage());
                }
            }
        }
    }
    
    private static void scanCameras() {
        log("--- CAMERA SCAN ---");
        try {
            Class<?> infoClass = Class.forName("android.hardware.BmmCameraInfo");
            java.lang.reflect.Method mGetTags = infoClass.getDeclaredMethod("getValidCameraTag");
            mGetTags.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<String> tags = (java.util.List<String>) mGetTags.invoke(null);

            java.lang.reflect.Method mGetId = infoClass.getDeclaredMethod("getCameraId", String.class);
            mGetId.setAccessible(true);

            if (tags != null) {
                for (String tag : tags) {
                    int id = (int) mGetId.invoke(null, tag);
                    log("FOUND: [" + tag.toUpperCase() + "] -> ID: " + id);
                }
            }
        } catch (Exception e) {
            log("WARN: BmmCamera scan failed: " + e.getMessage());
        }
        
        // Probe AVMCamera IDs 0-5 to find which cameras exist on this device
        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            java.lang.reflect.Constructor<?> ctor = avmClass.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            java.lang.reflect.Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            java.lang.reflect.Method mClose = avmClass.getDeclaredMethod("close");
            mClose.setAccessible(true);
            
            for (int id = 0; id <= 5; id++) {
                try {
                    Object cam = ctor.newInstance(id);
                    boolean opened = (boolean) mOpen.invoke(cam);
                    if (opened) {
                        log("AVMCamera ID " + id + ": AVAILABLE");
                        mClose.invoke(cam);
                    } else {
                        log("AVMCamera ID " + id + ": open() returned false");
                    }
                } catch (Exception e) {
                    // Camera ID doesn't exist or can't be opened
                }
            }
        } catch (ClassNotFoundException e) {
            log("WARN: AVMCamera not available on this device");
        } catch (Exception e) {
            log("WARN: AVMCamera probe failed: " + e.getMessage());
        }
        
        log("--- END SCAN ---");
    }

    // ==================== LOGGING ====================
    
    private static void initFileLogging() {
        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)  // Enable stdout for daemon processes
            .withFileLog(true)
            .withConsoleLog(true));
        log("=== CameraDaemon Log Started ===");
    }
    
    public static void log(String message) {
        logger.info(message);
    }

    // ==================== NOTIFICATIONS ====================

    /**
     * Idempotency guard. Once the registry + sinks are wired, repeat calls
     * are no-ops.
     */
    private static volatile boolean notificationsInitialized = false;

    /**
     * Initialize the Web Push notification subsystem. Loads the category
     * registry from APK assets, opens persistent stores under
     * {@code /data/local/tmp/.push/}, registers PushSink + LogSink with
     * NotificationBus, and wires NotificationApiHandler so HTTP routes can
     * resolve.
     */
    public static synchronized void initNotifications() throws Exception {
        if (notificationsInitialized) return;

        com.overdrive.app.notifications.CategoryRegistry registry = null;

        // The registry JSON ships in the APK assets. Use the cached
        // sharedAppContext if already populated; Do not create one
        // as it breaks in a thread
        android.content.Context appContext = getAppContext();
        if (appContext != null) {
            try {
                registry = com.overdrive.app.notifications.CategoryRegistry.loadFromAssets(appContext);
            } catch (Exception e) {
                log("Failed to load notifications-categories.json: " + e.getMessage());
            }
        }
        if (registry == null) {
            log("Notification registry unavailable; subsystem will boot in degraded mode.");
            return;
        }

        java.io.File pushDir = new java.io.File("/data/local/tmp/.push");
        if (!pushDir.exists()) pushDir.mkdirs();

        com.overdrive.app.notifications.push.VapidKeyStore keyStore =
                new com.overdrive.app.notifications.push.VapidKeyStore(
                        new java.io.File(pushDir, "vapid.json"));
        // Touch the keystore so we generate / cache the keypair eagerly.
        keyStore.publicKeyB64Url();

        com.overdrive.app.notifications.push.SubscriptionStore subStore =
                new com.overdrive.app.notifications.push.SubscriptionStore(
                        new java.io.File(pushDir, "subscriptions.json"));
        subStore.load();

        com.overdrive.app.notifications.push.VapidSigner signer =
                new com.overdrive.app.notifications.push.VapidSigner(keyStore, "");

        com.overdrive.app.notifications.NotificationBus.get()
                .subscribe(new com.overdrive.app.notifications.sinks.LogSink());
        com.overdrive.app.notifications.NotificationBus.get()
                .subscribe(new com.overdrive.app.notifications.sinks.PushSink(
                        subStore, registry, keyStore, signer));

        com.overdrive.app.server.NotificationApiHandler.init(registry, subStore, keyStore);

        notificationsInitialized = true;
        log("Notifications initialized: " + registry.all().size() + " categories, "
                + subStore.size() + " subscriptions");
    }

    // ==================== GPS MONITOR ====================
    
    /**
     * Initialize GPS Monitor with app context for standard LocationManager access.
     * Uses PermissionBypassContext to access location services without runtime permission prompts.
     */
    private static void initGpsMonitor() {
        try {
            log("Initializing GPS Monitor with app context...");
            
            // Location permissions are already granted by PermissionGranter on its
            // background thread. No need to duplicate those 3 synchronous pm grant
            // calls here — they were blocking initGpsMonitor for several seconds
            // and adding redundant load to PackageManagerService.
            
            // Try to get or create shared app context
            if (sharedAppContext == null) {
                sharedAppContext = createAppContext();
            }
            
            if (sharedAppContext == null) {
                log("WARNING: Could not create app context for GpsMonitor, falling back to daemon mode");
                com.overdrive.app.monitor.GpsMonitor.getInstance().init(null);
                return;
            }
            
            log("Got app context: " + sharedAppContext.getClass().getName());
            
            // Verify LocationManager is accessible
            Object locMgr = sharedAppContext.getSystemService(android.content.Context.LOCATION_SERVICE);
            if (locMgr == null) {
                log("WARNING: LocationManager not available, falling back to daemon mode");
                com.overdrive.app.monitor.GpsMonitor.getInstance().init(null);
                return;
            }
            log("LocationManager available: " + locMgr.getClass().getName());
            
            com.overdrive.app.monitor.GpsMonitor gpsMonitor =
                com.overdrive.app.monitor.GpsMonitor.getInstance();
            
            gpsMonitor.init(sharedAppContext);
            gpsMonitor.start();  // Start GPS tracking immediately
            
            log("GPS Monitor initialized with Context mode");
            
            // Initialize NetworkMonitor for WiFi/Mobile Data status in sidebar
            com.overdrive.app.monitor.NetworkMonitor.init(sharedAppContext);
            log("Network Monitor initialized");
            
        } catch (Exception e) {
            log("Failed to initialize GPS Monitor with context: " + e.getMessage());
            log("Falling back to daemon mode (shell commands)");
            com.overdrive.app.monitor.GpsMonitor.getInstance().init(null);
        }
    }
    
    /**
     * Grant location permissions to the app via shell commands.
     * The daemon runs with elevated privileges so it can grant permissions.
     */
    private static void grantLocationPermissions() {
        String[] permissions = {
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        };
        
        log("Granting location permissions...");
        
        for (String perm : permissions) {
            try {
                Process process = Runtime.getRuntime().exec(
                    "pm grant com.overdrive.app " + perm);
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    log("Granted: " + perm);
                } else {
                    log("Failed to grant: " + perm + " (exit=" + exitCode + ")");
                }
            } catch (Exception e) {
                log("Error granting " + perm + ": " + e.getMessage());
            }
        }
    }
    
    // ==================== VEHICLE DATA MONITOR ====================
    
    /**
     * Initialize Vehicle Data Monitor for EV battery and charging data.
     * Reuses shared app context with PermissionBypassContext for BYD hardware access.
     */
    private static void initVehicleDataMonitor() {
        try {
            log("Initializing Vehicle Data Monitor...");
            
            // Reuse shared context if available, otherwise create new
            if (sharedAppContext == null) {
                sharedAppContext = createAppContext();
            }
            
            if (sharedAppContext == null) {
                log("WARNING: Could not create app context for VehicleDataMonitor");
                return;
            }
            
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            
            vehicleMonitor.init(sharedAppContext);
            vehicleMonitor.start();
            
            log("Vehicle Data Monitor initialized successfully");
            
            // Initialize Universal BYD Data Collector (runs alongside existing monitors)
            try {
                com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
                collector.init(sharedAppContext);
                collector.logSummary();
                log("BYD Data Collector initialized (" + collector.getData().availableDevices.length + " devices)");
            } catch (Exception e) {
                log("BYD Data Collector init error (non-fatal): " + e.getMessage());
            }
            
            // Initialize Gear Monitor for PROXIMITY_GUARD mode
            com.overdrive.app.monitor.GearMonitor gearMonitor =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            gearMonitor.init(sharedAppContext);
            // Wire GearMonitor to read gear from TelemetryDataCollector's cached snapshot
            // when the overlay poller is running, avoiding duplicate CAN bus reads
            if (telemetryDataCollector != null) {
                gearMonitor.setTelemetrySource(telemetryDataCollector);
            }
            try {
                gearMonitor.start();
            } catch (Exception e) {
                log("GearMonitor start failed (will retry on ACC ON): " + e.getMessage());
            }
            
            log("Gear Monitor initialized successfully");
            
            // Initialize Performance Monitor for system instrumentation
            com.overdrive.app.monitor.PerformanceMonitor perfMonitor =
                com.overdrive.app.monitor.PerformanceMonitor.getInstance();
            perfMonitor.init(sharedAppContext);
            perfMonitor.start();
            
            log("Performance Monitor initialized successfully");
            
            // Initialize SOC History Database for persistent battery tracking
            com.overdrive.app.monitor.SocHistoryDatabase socDb =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            socDb.setSohEstimator(sohEstimator);
            socDb.init();
            socDb.start();

            log("SOC History Database initialized successfully");

            // Fix stale kWh records from before PHEV capacity was correctly
            // detected. Runs on a background thread — this is a one-shot data
            // migration over the soc_history table that has been observed to
            // take 100+ seconds on a long-running install (full table scan
            // with per-row arithmetic). Blocking the main init thread here
            // delayed ABRP / MQTT / TripAnalytics by the same 100+ s, which
            // is exactly the "trips loading 3-4 min" symptom users hit.
            //
            // The migration is idempotent (rows that already match the
            // formula are no-ops); SocHistoryDatabase's periodic recorder
            // tolerates concurrent UPDATE on the same connection (H2
            // serializes internally) and the migration runs once per
            // daemon lifetime.
            final com.overdrive.app.abrp.SohEstimator sohEstSnapshotForMigration = sohEstimator;
            if (sohEstSnapshotForMigration != null
                    && sohEstSnapshotForMigration.getNominalCapacityKwh() > 0
                    && sohEstSnapshotForMigration.getNominalCapacityKwh() < 30.0) {
                Thread migration = new Thread(() -> {
                    try {
                        long t0 = System.currentTimeMillis();
                        log("Fixing stale kWh records for PHEV (nominal="
                                + sohEstSnapshotForMigration.getNominalCapacityKwh()
                                + " kWh) — async");
                        socDb.fixStaleRemainingKwh(sohEstSnapshotForMigration.getNominalCapacityKwh());
                        log("Stale kWh migration done in "
                                + (System.currentTimeMillis() - t0) + "ms");
                    } catch (Throwable t) {
                        log("Stale kWh migration failed: " + t.getMessage());
                    }
                }, "SocHistoryMigration");
                migration.setDaemon(true);
                migration.setPriority(Thread.MIN_PRIORITY);
                migration.start();
            }
            
        } catch (Exception e) {
            log("Failed to initialize Vehicle Data Monitor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Create app context with permission bypass for BYD hardware access.
     */
    private static android.content.Context createAppContext() {
        try {
            log("createAppContext: Starting...");
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread;

            // Strategy 1: Get existing ActivityThread (works if app process is running)
            try {
                java.lang.reflect.Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                activityThread = currentActivityThread.invoke(null);
                log("createAppContext: currentActivityThread = " + activityThread);
            } catch (Exception e) {
                log("createAppContext: currentActivityThread failed: " + e.getMessage());
                activityThread = null;
            }

            // Strategy 2: systemMain() with timeout — this can deadlock on some firmware
            if (activityThread == null) {
                log("createAppContext: Trying systemMain with 10s timeout...");
                final Object[] result = new Object[1];
                final Exception[] error = new Exception[1];
                Thread systemMainThread = new Thread(() -> {
                    try {
                        java.lang.reflect.Method systemMain = activityThreadClass.getMethod("systemMain");
                        result[0] = systemMain.invoke(null);
                    } catch (Exception e) {
                        error[0] = e;
                    }
                }, "SystemMainInit");
                systemMainThread.setDaemon(true);
                systemMainThread.start();
                systemMainThread.join(10_000); // 10 second timeout
                
                if (systemMainThread.isAlive()) {
                    log("createAppContext: systemMain TIMED OUT (10s)");
                    systemMainThread.interrupt();
                    try {
                        java.lang.reflect.Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                        activityThread = currentActivityThread.invoke(null);
                        log("createAppContext: post-timeout currentActivityThread = " + activityThread);
                    } catch (Exception e2) {
                        log("createAppContext: post-timeout currentActivityThread also failed");
                    }
                } else if (error[0] != null) {
                    log("createAppContext: systemMain failed: " + error[0].getMessage());
                } else {
                    activityThread = result[0];
                    log("createAppContext: systemMain = " + activityThread);
                }
            }
            
            // Strategy 3: Prepare looper manually + create ActivityThread via constructor
            if (activityThread == null) {
                log("createAppContext: Trying manual ActivityThread creation...");
                try {
                    // Ensure main looper exists (idempotent if already prepared)
                    try { android.os.Looper.prepareMainLooper(); } catch (Exception ignored) {}
                    
                    // Create ActivityThread via default constructor
                    java.lang.reflect.Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    activityThread = ctor.newInstance();
                    
                    // Set as the current thread via sCurrentActivityThread field
                    try {
                        java.lang.reflect.Field sField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                        sField.setAccessible(true);
                        sField.set(null, activityThread);
                    } catch (NoSuchFieldException e) {
                        // Some Android versions use different field name
                        try {
                            java.lang.reflect.Field sField = activityThreadClass.getDeclaredField("sMainThreadHandler");
                            // If we got here, the field layout is different — just proceed
                        } catch (Exception ignored) {}
                    }
                    
                    log("createAppContext: manual ActivityThread = " + activityThread);
                } catch (Exception e) {
                    log("createAppContext: manual creation failed: " + e.getMessage());
                }
            }

            if (activityThread == null) {
                // Strategy 4: Last resort — get system context directly via ContextImpl
                log("createAppContext: All ActivityThread strategies failed, trying ContextImpl...");
                return createFallbackContext();
            }

            java.lang.reflect.Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            android.content.Context systemContext = (android.content.Context) getSystemContext.invoke(activityThread);
            log("createAppContext: systemContext = " + systemContext);
            
            if (systemContext == null) {
                log("createAppContext: systemContext is null, trying fallback...");
                return createFallbackContext();
            }

            String packageName = APP_PACKAGE_NAME();
            log("createAppContext: Creating package context for " + packageName);
            android.content.Context appContext = systemContext.createPackageContext(packageName,
                    android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
            log("createAppContext: appContext = " + appContext);
            
            if (appContext == null) {
                log("createAppContext: appContext is null, trying fallback...");
                return createFallbackContext();
            }
            
            PermissionBypassContext wrapped = new PermissionBypassContext(appContext);
            log("createAppContext: Success, returning PermissionBypassContext");
            return wrapped;

        } catch (Exception e) {
            log("createAppContext failed: " + e.getMessage() + ", trying fallback...");
            return createFallbackContext();
        }
    }
    
    /**
     * Fallback context creation when ActivityThread is completely unavailable.
     * Creates a minimal context via ContextImpl reflection that's enough for
     * BYD device getInstance() calls (they just need enforceCallingOrSelfPermission to not NPE).
     */
    private static android.content.Context createFallbackContext() {
        try {
            // Try to create ContextImpl directly
            Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
            
            // Try createSystemContext() — available on most Android versions
            try {
                java.lang.reflect.Method createSystemContext = contextImplClass.getDeclaredMethod("createSystemContext", 
                    Class.forName("android.app.ActivityThread"));
                createSystemContext.setAccessible(true);
                // Pass null ActivityThread — some versions tolerate this
                android.content.Context ctx = (android.content.Context) createSystemContext.invoke(null, (Object) null);
                if (ctx != null) {
                    log("createFallbackContext: ContextImpl.createSystemContext succeeded");
                    return new PermissionBypassContext(ctx);
                }
            } catch (Exception e) {
                log("createFallbackContext: createSystemContext failed: " + e.getMessage());
            }
            
            // Try createAppContext with minimal params
            try {
                java.lang.reflect.Method[] methods = contextImplClass.getDeclaredMethods();
                for (java.lang.reflect.Method m : methods) {
                    if (m.getName().equals("createAppContext") && m.getParameterTypes().length == 2) {
                        m.setAccessible(true);
                        // Can't call without valid params, skip
                        break;
                    }
                }
            } catch (Exception ignored) {}
            
            // Last resort: use a bare PermissionBypassContext with a dummy base
            // This creates a context that returns PERMISSION_GRANTED for all checks
            // and delegates everything else to the system
            log("createFallbackContext: Using null-safe PermissionBypassContext as last resort");
            return new PermissionBypassContext(null);
            
        } catch (Exception e) {
            log("createFallbackContext failed completely: " + e.getMessage());
            return new PermissionBypassContext(null);
        }
    }
    
    /**
     * Context wrapper that bypasses permission checks and handles null base context.
     * Required for accessing BYD hardware services without signature permissions.
     * When base is null (fallback mode), provides safe defaults for methods BYD devices call.
     */
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(android.content.Context base) { super(base); }
        
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        
        // Null-safe overrides for when base context is null (fallback mode).
        // CRITICAL: getMainLooper() must be overridden — BYDAutoDeviceManager calls
        // context.getMainLooper() in its constructor, and ContextWrapper delegates
        // to the base context which is null in fallback mode, causing NPE that
        // makes all 18 BYD device monitors null.
        @Override public android.os.Looper getMainLooper() {
            try { return super.getMainLooper(); } catch (NullPointerException e) {
                // Return the process main looper — BYD devices use it to register
                // Handler callbacks for CAN bus data change listeners.
                android.os.Looper looper = android.os.Looper.getMainLooper();
                return looper != null ? looper : android.os.Looper.myLooper();
            }
        }
        @Override public android.content.Context getApplicationContext() {
            try { return super.getApplicationContext(); } catch (NullPointerException e) { return this; }
        }
        @Override public String getPackageName() {
            try { return super.getPackageName(); } catch (NullPointerException e) { return APP_PACKAGE_NAME(); }
        }
        @Override public Object getSystemService(String name) {
            try { return super.getSystemService(name); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            try { return super.getApplicationInfo(); } catch (NullPointerException e) { return new android.content.pm.ApplicationInfo(); }
        }
        @Override public android.content.ContentResolver getContentResolver() {
            try { return super.getContentResolver(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.res.Resources getResources() {
            try { return super.getResources(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.Context createPackageContext(String packageName, int flags) {
            try { return super.createPackageContext(packageName, flags); } catch (Exception e) { return this; }
        }
    }
}
