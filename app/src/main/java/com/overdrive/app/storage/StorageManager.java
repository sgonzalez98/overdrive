package com.overdrive.app.storage;

import android.os.StatFs;
import android.util.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StorageManager - SOTA Storage Management for Overdrive
 * 
 * Manages recording and surveillance storage with:
 * - Dedicated directories under /storage/emulated/0/Overdrive/ (internal) or SD card
 * - Storage type selection: INTERNAL or SD_CARD for both recordings and surveillance
 * - Configurable size limits (100MB - 10000MB for SD card)
 * - Automatic cleanup of oldest files when limit is reached
 * - Event-driven cleanup (after each file save)
 * - Periodic background cleanup during long recordings
 * - Thread-safe operations
 * - SD card detection and availability monitoring
 * 
 * SOTA Cleanup Strategy:
 * 1. Pre-recording check - Reserve space before starting
 * 2. Post-file cleanup - Run after each file is closed/saved
 * 3. Periodic cleanup - Background task every 30 seconds during active recording
 * 
 * Storage Selection:
 * - Each storage type (recordings, surveillance) can independently use internal or SD card
 * - SD card paths are auto-discovered via BYD system properties or known mount points
 * - Graceful fallback to internal storage if SD card becomes unavailable
 */
public class StorageManager {
    private static final String TAG = "StorageManager";
    
    // Storage type enum
    public enum StorageType {
        INTERNAL,
        SD_CARD,
        USB
    }
    
    // Hybrid logger - uses DaemonLogger when running as daemon, android.util.Log otherwise
    private static boolean useDaemonLogger = false;
    private static com.overdrive.app.logging.DaemonLogger daemonLogger = null;
    
    /**
     * Enable daemon logging mode (call from daemon process).
     */
    public static void enableDaemonLogging() {
        useDaemonLogger = true;
        daemonLogger = com.overdrive.app.logging.DaemonLogger.getInstance(TAG);
    }
    
    private static void logInfo(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.info(msg);
        } else {
            Log.i(TAG, msg);
        }
    }
    
    private static void logWarn(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.warn(msg);
        } else {
            Log.w(TAG, msg);
        }
    }
    
    private static void logError(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.error(msg);
        } else {
            Log.e(TAG, msg);
        }
    }

    /**
     * Bounded {@link Process#waitFor()} — kills the child if it doesn't exit
     * within {@code timeoutMs}. Returns the exit code on clean exit, or
     * {@code -1} on timeout / interrupt. The vendored {@code sm} binary on
     * BYD ROMs has been observed to hang indefinitely when an SD/USB volume
     * is in a bad state (post-update with the slot empty, or with stale
     * mount table state after a SIGKILL'd vold helper). Without a timeout
     * here, the daemon's startup path blocked forever — see the
     * recovery-first comment in CameraDaemon.main().
     */
    private static int waitForBounded(Process p, long timeoutMs, String label) {
        try {
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return p.exitValue();
            }
            logWarn(label + ": timed out after " + timeoutMs + "ms — killing child");
            p.destroyForcibly();
            // Give the kernel a moment to reap, but bound this too.
            try { p.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            return -1;
        }
    }
    
    private static void logDebug(String msg) {
        if (useDaemonLogger && daemonLogger != null) {
            daemonLogger.debug(msg);
        } else {
            Log.d(TAG, msg);
        }
    }
    
    // Base directories for Overdrive files
    private static final String INTERNAL_BASE_DIR = "/storage/emulated/0/Overdrive";

    // Legacy paths from older app versions. Files here aren't written anymore
    // but they still count toward the user's configured limit and must be
    // reaped — otherwise a 500 MB limit can show 800 MB used in the UI.
    private static final String LEGACY_APP_FILES_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files";
    private static final String LEGACY_SURVEILLANCE_DIR = LEGACY_APP_FILES_DIR + "/sentry_events";

    // Subdirectories
    public static final String RECORDINGS_SUBDIR = "recordings";
    public static final String SURVEILLANCE_SUBDIR = "surveillance";
    public static final String PROXIMITY_SUBDIR = "proximity";
    public static final String TRIPS_SUBDIR = "trips";
    
    // Config file location
    private static final String CONFIG_FILE = "/data/local/tmp/overdrive_config.json";

    // Persisted UUID of whichever public volume we've previously confirmed as
    // the SD card. Used as the first-class signal in classifyPublicVolume()
    // when the BYD vendor prop (sys.byd.mSdcardUuid) is empty. The vendor
    // prop is only populated WHILE the card is mounted on this firmware, so
    // during the unmount window between ACC OFF and our remount attempt the
    // prop returns "" and the major-number fallback was misclassifying the
    // bridged-SD (major 8, DEVNAME=sd*) as USB. Learning the FAT volume
    // serial from a previous successful cycle bridges the gap. File is
    // tiny (~10 bytes), atomic-write semantics not required because a stale
    // value still resolves to the same physical card.
    private static final String LEARNED_SD_UUID_FILE = "/data/local/tmp/overdrive_sd_uuid";
    
    // Default limits (in bytes)
    private static final long DEFAULT_RECORDINGS_LIMIT_MB = 500;
    private static final long DEFAULT_SURVEILLANCE_LIMIT_MB = 500;
    private static final long DEFAULT_PROXIMITY_LIMIT_MB = 500;
    private static final long DEFAULT_TRIPS_LIMIT_MB = 500;
    private static final long MIN_LIMIT_MB = 100;

    // Hard ceiling fallback used only when StatFs reports 0 (volume unmounted
    // at the moment of the read). Keeps the slider usable while we wait for a
    // refresh. Real cap comes from getEffectiveMaxLimitMb(type) below, which
    // pulls the live filesystem total minus a safety reserve.
    private static final long MAX_LIMIT_MB_FALLBACK = 100000;  // 100GB

    // Per-category share of the volume — recordings, surveillance, trips,
    // proximity all live on the same FS, so giving each one 100% of the disk
    // overcommits by 4x. 40% per category leaves headroom for the OS, the
    // muxer flush queue, and the other Overdrive categories competing for
    // the same pool.
    private static final double PER_CATEGORY_SHARE = 0.40;

    // Reserve a small fraction of the volume so the encoder can never hit
    // ENOSPC mid-file from the user setting "max" on a near-empty disk.
    private static final long VOLUME_HEADROOM_MB = 256;
    
    // Periodic cleanup interval (30 seconds)
    private static final long CLEANUP_INTERVAL_SECONDS = 30;
    
    // Current limits
    private static long recordingsLimitMb = DEFAULT_RECORDINGS_LIMIT_MB;
    private static long surveillanceLimitMb = DEFAULT_SURVEILLANCE_LIMIT_MB;
    private static long proximityLimitMb = DEFAULT_PROXIMITY_LIMIT_MB;
    private long tripsLimitMb = DEFAULT_TRIPS_LIMIT_MB;
    
    // Storage type selection (SOTA: independent selection for recordings and surveillance)
    // volatile: read cross-thread (recorder pre-flight reconcile, size queries)
    // and written on settings/HTTP threads.
    private volatile StorageType recordingsStorageType = StorageType.INTERNAL;
    private volatile StorageType surveillanceStorageType = StorageType.INTERNAL;
    private volatile StorageType tripsStorageType = StorageType.INTERNAL;

    // SD card state.
    // volatile: (re)assigned by discoverVolumes/ensureVolumeMounted on the
    // VolumeWatchdog + mount threads and read by unrelated threads (resolveActive
    // via reconcileRecordingOverride, isSdCardLikelyMounted). The volatile fence
    // gives those readers a happens-before edge to the mount-path writes; without
    // it a remount-completed write could stay invisible to the recorder thread.
    private volatile String sdCardPath = null;
    private volatile boolean sdCardAvailable = false;

    // USB state — flash drives mounted via OTG. Treated as a separate volume
    // class from SD because of how head-units enumerate them: SD sits behind
    // an mmc driver (Linux major 179), USB behind sd/SCSI (major 8/65/66/...).
    // Without this distinction discoverSdCard() will happily latch onto a USB
    // stick when both are present. volatile for the same cross-thread reason
    // as the SD state fields above.
    private volatile String usbPath = null;
    private volatile boolean usbAvailable = false;
    
    // Singleton instance
    private static StorageManager instance;
    
    // Internal storage directories (always available)
    private File internalRecordingsDir;
    private File internalSurveillanceDir;
    private File internalProximityDir;
    private File internalTripsDir;
    
    // SD card directories (may be null if SD card not available).
    // volatile: initSdCardDirectories() (re)assigns / null-clears these on the
    // remount paths (watchdog + mount threads); resolveActive reads them from
    // other threads. NOT immutable-after-init — the volatile fence is what makes
    // a freshly-assigned dir visible to the recorder-side reader.
    private volatile File sdCardRecordingsDir;
    private volatile File sdCardSurveillanceDir;
    private volatile File sdCardProximityDir;
    private volatile File sdCardTripsDir;

    // USB directories (may be null if USB drive not available). volatile for the
    // same cross-thread remount-visibility reason as the SD directories above.
    private volatile File usbRecordingsDir;
    private volatile File usbSurveillanceDir;
    private volatile File usbProximityDir;
    private volatile File usbTripsDir;
    
    // Active directories (based on storage type selection).
    // Volatile because they're written by setters/watchdog threads (which
    // hold per-category cleanup locks during the assignment) and read by
    // unrelated readers (size queries, file-saved handlers, recorder
    // pre-flight) that may not hold the same lock. The previous design
    // shared a single cleanupLock that ordered all reads/writes; with
    // per-category locks the reads can land on a stale value without the
    // volatile fence.
    private volatile File recordingsDir;
    private volatile File surveillanceDir;
    private volatile File proximityDir;
    private volatile File tripsDir;
    
    // Background cleanup scheduler
    private ScheduledExecutorService cleanupScheduler;
    private final AtomicBoolean recordingActive = new AtomicBoolean(false);
    private final AtomicBoolean surveillanceActive = new AtomicBoolean(false);

    // Absolute path of the currently-recording trip telemetry file (.jsonl.gz)
    // or null when no trip is active. Path-based instead of a boolean so a
    // limit-change cleanup mid-trip can still reap older trip files; only the
    // in-flight file is protected. Read by ensureSpace before each delete.
    private volatile String activeTripFilePath = null;

    // SOTA: Authoritative "encoder is mid-write" probe.
    //
    // The setRecordingActive / setSurveillanceActive booleans above track the
    // *user-facing* recording state, set by GpuMosaicRecorder.startRecording /
    // stopRecording. They are NOT a reliable signal for "is the disk writer
    // currently flushing packets to the SD card", because there's a real lag:
    //   - User starts recording → recordingActive=true. Encoder hasn't yet
    //     produced its first packet. Cleanup CAN safely run for ~100 ms.
    //   - User stops recording → recordingActive=false. Disk writer is still
    //     draining the muxer queue + finalising the moov atom (~50-200ms).
    //     A cleanup burst here corrupts the still-open file's footer write.
    //
    // The probe below points at HardwareEventRecorderGpu.isWritingToFile() —
    // the volatile flag set under startStopLock that goes true the moment the
    // muxer is constructed and false ONLY after closeEventRecording has
    // released it. Cleanup uses this to gate destructive deletes and avoid
    // contending with the realtime SD-card writes during an active recording.
    //
    // Default probe returns false so a stale binding never blocks cleanup
    // forever. PipelineDaemon installs the real probe after the encoder
    // exists; if the encoder is later released, the probe returns false
    // gracefully (HardwareEventRecorderGpu.isWritingToFile reads a volatile
    // field that's false when the recorder isn't holding a muxer).
    private volatile java.util.function.BooleanSupplier encoderWritingProbe = () -> false;
    /**
     * Set true the first time setEncoderWritingProbe wires a real probe. The
     * periodic cleanup loop early-returns until this flips, so the first
     * 30-second tick after daemon boot can't run un-gated against a default
     * fail-open probe (audit P1).
     */
    private final java.util.concurrent.atomic.AtomicBoolean probeWired =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // Async cleanup executor (single thread to avoid concurrent cleanup)
    private final java.util.concurrent.ExecutorService asyncCleanupExecutor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                // SOTA: Linux nice +10 (THREAD_PRIORITY_BACKGROUND). The Java
                // MIN_PRIORITY below is advisory; this is what actually keeps
                // file deletes from preempting the disk writer's muxer writes
                // under SD card I/O contention.
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "StorageCleanupAsync");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

    // Deferred-cleanup queue: when a save event fires while encoder is mid-
    // write, instead of running the delete burst we mark the directory as
    // "needs cleanup later". A polling pass on the same asyncCleanupExecutor
    // drains this set the next time encoderWritingProbe returns false. Without
    // this, a back-to-back recording/cleanup pattern would skip cleanup
    // forever and storage would grow past the limit.
    private final java.util.Set<String> deferredCleanupDirs =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String DEFERRED_RECORDINGS = "recordings";
    private static final String DEFERRED_SURVEILLANCE = "surveillance";
    private static final String DEFERRED_PROXIMITY = "proximity";
    private static final String DEFERRED_TRIPS = "trips";

    // Per-category cleanup locks. The previous design used a single shared
    // monitor for every ensureXxxSpace / sweep / wipe call across all four
    // categories. That serialised unrelated work — most catastrophically,
    // the boot startup reap (which sweeps all four categories under the same
    // lock) blocked recorder.startRecording's pre-flight ensureRecordingsSpace
    // for the entire duration of the reap. On a USB drive with 1k+ recordings,
    // that's minutes of starvation, during which the user's drive is silently
    // not recorded (RecordingModeManager.activateMode calls pipeline.startRecording
    // synchronously and pins the manager monitor across the whole stall).
    //
    // Each category now owns its own monitor. Cross-category orchestrators
    // (runCleanup, the periodic ticker, the boot reap) take the locks one
    // category at a time so the windows of contention with per-recording calls
    // are bounded to a single category's walk.
    private final Object recordingsCleanupLock = new Object();
    private final Object surveillanceCleanupLock = new Object();
    private final Object proximityCleanupLock = new Object();
    private final Object tripsCleanupLock = new Object();
    // FIX (audit R8, LOW): serialize peer setStorageType calls so concurrent
    // HTTP threads don't interleave field writes / setOutputDir push /
    // stopRecording / saveConfig. setRecordingsStorageType, setSurveillanceStorageType,
    // and setTripsStorageType all take this lock.
    private final Object configChangeLock = new Object();

    /** Resolve the per-category lock by category key — used by helpers that
     *  receive the category as a string (drainDeferredCleanup, sweep helpers).
     *  Unknown keys map to {@code recordingsCleanupLock} as a safe default;
     *  callers should still validate the category. */
    private Object lockForCategory(String category) {
        switch (category) {
            case "recordings":  return recordingsCleanupLock;
            case "surveillance": return surveillanceCleanupLock;
            case "proximity":   return proximityCleanupLock;
            case "trips":       return tripsCleanupLock;
            default:            return recordingsCleanupLock;
        }
    }
    
    // SD card / USB mount watchdog (keeps the configured external volume
    // mounted during sentry mode). Single scheduler covers both classes —
    // each volume gets its own consecutive-failure counter so quiet-log
    // throttling is independent.
    private ScheduledExecutorService sdCardWatchdog;
    private static final long SD_WATCHDOG_INTERVAL_SECONDS = 15;
    private int sdWatchdogConsecutiveFailures = 0;
    private int usbWatchdogConsecutiveFailures = 0;
    private static final int SD_WATCHDOG_MAX_VERBOSE_FAILURES = 5;  // Log verbosely for first 5 failures
    private static final int SD_WATCHDOG_QUIET_LOG_INTERVAL = 20;   // Then log every 20th attempt (~5 min)

    // Rate-limit for the raw `sm list-volumes` diagnostic dump. The fingerprint
    // line (publicRows=N matchedRows=M) is cheap and stays at logInfo on every
    // failure; the multi-line raw output only re-prints when 5 minutes have
    // elapsed since the last dump. Without this, a multi-hour park with the
    // SD genuinely missing produces ~240 raw dumps/hour which floods cam_daemon.log.
    private long lastSmRawDumpAtMs = 0;
    private static final long SM_RAW_DUMP_INTERVAL_MS = 5 * 60_000;
    
    /**
     * Parse a storage-type string from persisted config. Anything that
     * doesn't match SD_CARD/USB falls back to INTERNAL — that includes
     * legacy configs and accidentally-truncated writes.
     */
    private static StorageType parseStorageType(String s) {
        if ("SD_CARD".equals(s)) return StorageType.SD_CARD;
        if ("USB".equals(s))     return StorageType.USB;
        return StorageType.INTERNAL;
    }

    /**
     * Coerce a configured storage type to {@code INTERNAL} when the
     * underlying volume isn't currently present. Per project spec, when the
     * user-selected external volume isn't available the runtime default is
     * always INTERNAL — never a "phantom" SD_CARD/USB whose path silently
     * resolves to internal at every read.
     *
     * <p>Does NOT overwrite the persisted preference (the persisted value
     * stays untouched in {@code overdrive_config.json}). On the next boot
     * with the volume re-attached, parseStorageType + normalizeStorageType
     * will return the user's original choice.
     */
    private StorageType normalizeStorageType(StorageType configured) {
        if (configured == StorageType.SD_CARD && !sdCardAvailable) return StorageType.INTERNAL;
        if (configured == StorageType.USB     && !usbAvailable)    return StorageType.INTERNAL;
        return configured;
    }

    private StorageManager() {
        discoverVolumes();
        initDirectories();
        loadConfig();

        // If config says SD/USB but it's not available, try to mount it on a
        // background thread. Even with the per-call timeouts in
        // ensureVolumeMounted, the worst-case is `sm list-volumes` (2s) +
        // `sm mount` (8s) + 10×500ms accessibility-poll = up to ~15s. Doing
        // that synchronously here used to wedge daemon startup whenever a
        // configured external volume was missing or in a bad state — which
        // is exactly the post-update scenario users hit (the updater's
        // pkill-9 of vold helpers can leave the volume marked-unmounted in
        // the kernel until the next ACC cycle).
        //
        // The startSdCardWatchdog() loop already retries failed mounts on a
        // schedule, so there's no value in blocking startup on a one-shot
        // attempt. Same logic for USB. updateActiveDirectories() is called
        // here AND inside ensureVolumeMounted on success, so consumers see
        // INTERNAL until/if the mount lands, then transparently switch.
        Runnable mountAttempt = () -> {
            try {
                if (!sdCardAvailable &&
                    (surveillanceStorageType == StorageType.SD_CARD ||
                     recordingsStorageType == StorageType.SD_CARD ||
                     tripsStorageType == StorageType.SD_CARD)) {
                    logInfo("SD card configured but not available - attempting mount (async)...");
                    ensureSdCardMounted(true);
                }
                if (!usbAvailable &&
                    (surveillanceStorageType == StorageType.USB ||
                     recordingsStorageType == StorageType.USB ||
                     tripsStorageType == StorageType.USB)) {
                    logInfo("USB configured but not available - attempting mount (async)...");
                    ensureUsbMounted(true);
                }
            } catch (Exception e) {
                logWarn("Async mount attempt failed: " + e.getMessage());
            }
        };
        new Thread(mountAttempt, "StorageMountInit").start();

        updateActiveDirectories();

        // One-shot startup reap. If the user lowered the limit, switched
        // storage type, or upgraded from a legacy build, the inactive +
        // legacy locations may be holding orphan files that count toward
        // the limit. Reap them once at boot so the UI total agrees with
        // the configured limit before any new event fires the per-save
        // cleanup. Async — don't block daemon startup.
        //
        // Each ensureXxxSpace call self-acquires its own per-category lock,
        // so this loop holds no lock between categories. That matters: on a
        // first-recording-after-boot path the recorder calls
        // ensureRecordingsSpace(100MB) under recordingsCleanupLock; if we
        // held a single shared lock across the entire reap, that pre-flight
        // would block until the boot reap finished walking every category.
        // On a USB volume with thousands of clips, that's the difference
        // between recording the user's drive and silently losing it.
        asyncCleanupExecutor.execute(() -> {
            try {
                sweepOrphanTempFiles();
                ensureRecordingsSpace(0);
                ensureSurveillanceSpace(0);
                ensureProximitySpace(0);
                ensureTripsSpace(0);
            } catch (Exception e) {
                logWarn("Startup reap failed: " + e.getMessage());
            }
        });
    }

    /**
     * Delete orphan {@code .mp4.tmp} and {@code .broken} files left behind by
     * abnormal daemon exits (SIGKILL, OOM, ACC-cycle kill mid-recording).
     *
     * <p>Only the close path renames {@code .mp4.tmp → .mp4}; if the daemon
     * dies before the rename, the {@code .tmp} sits forever — counted by the
     * filesystem but invisible to the events UI. On BYD head-units where
     * the daemon is regularly killed by ACC cycles, these accumulate until
     * the SD card fills.
     *
     * <p>Conservative policy: only delete files older than {@code TEMP_FILE_GRACE_MS}
     * (10 minutes) so we never race a recording in flight. Anything younger
     * is assumed to belong to a live recording on a sibling daemon process.
     *
     * <p>Sweeps surveillance, proximity, and recordings dirs (current + legacy
     * locations).
     */
    private void sweepOrphanTempFiles() {
        final long graceMs = 10 * 60 * 1000L;
        final long cutoff = System.currentTimeMillis() - graceMs;
        int deleted = 0;
        long bytesFreed = 0;

        java.util.HashSet<String> seenPaths = new java.util.HashSet<>();
        for (String category : new String[]{"recordings", "surveillance", "proximity", "trips"}) {
            String[] partials = partialExtensionsForCategory(category);
            if (partials.length == 0) continue;
            // Take this category's lock for its iteration only — releases
            // between categories so a slow USB walk in "recordings" doesn't
            // block ensureSurveillanceSpace fired by a concurrent event save.
            synchronized (lockForCategory(category)) {
                for (File dir : getReapableDirs(category)) {
                    if (dir == null) continue;
                    String path = dir.getAbsolutePath();
                    if (!seenPaths.add(path)) continue;
                    if (!dir.isDirectory()) continue;
                    File[] files = dir.listFiles((d, name) -> {
                        for (String ext : partials) {
                            if (name.endsWith(ext)) return true;
                        }
                        return false;
                    });
                    if (files == null) continue;
                    for (File f : files) {
                        // Don't unlink a still-being-written trip file in case
                        // the recorder uses an atomic ".jsonl.gz.tmp → .jsonl.gz"
                        // rename and the in-flight file is the .tmp.
                        if (activeTripFilePath != null
                                && (activeTripFilePath.equals(f.getAbsolutePath())
                                    || activeTripFilePath.equals(f.getAbsolutePath() + ".tmp"))) {
                            continue;
                        }
                        if (f.lastModified() > cutoff) continue;  // grace window
                        long size = f.length();
                        boolean ok = f.delete();
                        if (!ok) ok = deleteFileViaShell(f);
                        if (ok) {
                            deleted++;
                            bytesFreed += size;
                        } else {
                            logWarn("Orphan tmp delete failed: " + f.getAbsolutePath());
                        }
                    }
                }
            }
        }
        if (deleted > 0) {
            logInfo("Orphan tmp sweep: deleted " + deleted + " files, "
                    + (bytesFreed / 1024) + " KB freed");
        }
    }
    
    public static synchronized StorageManager getInstance() {
        if (instance == null) {
            instance = new StorageManager();
        }
        return instance;
    }
    
    // ==================== SD Card Discovery ====================
    
    /**
     * SOTA: Mount SD card if unmounted.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     * 
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted() {
        return ensureSdCardMounted(false);
    }
    
    /**
     * SOTA: Mount SD card, optionally forcing a remount.
     * Uses Android's StorageManager (sm) command to mount public volumes.
     *
     * @param force If true, always attempt to mount even if already mounted
     * @return true if SD card is now mounted, false otherwise
     */
    public boolean ensureSdCardMounted(boolean force) {
        return ensureVolumeMounted("SD", force);
    }

    /**
     * Mount USB drive (or remount if stale). Mirror of ensureSdCardMounted
     * for the USB volume class.
     */
    public boolean ensureUsbMounted() {
        return ensureUsbMounted(false);
    }

    public boolean ensureUsbMounted(boolean force) {
        return ensureVolumeMounted("USB", force);
    }

    /**
     * Generic mount-or-remount for a specific volume class (SD or USB).
     * Walks {@code sm list-volumes all}, classifies each public volume by
     * underlying block-device major number (see classifyPublicVolume), and
     * mounts the first one matching the requested class. Updates the
     * corresponding {@code <class>Path} / {@code <class>Available} fields
     * + initializes per-class directories on success.
     *
     * @param targetClass "SD" or "USB"
     * @param force       attempt even if already mounted (for remount cases)
     */
    private boolean ensureVolumeMounted(String targetClass, boolean force) {
        boolean isSd = "SD".equals(targetClass);
        String currentPath = isSd ? sdCardPath : usbPath;
        boolean currentAvailable = isSd ? sdCardAvailable : usbAvailable;

        // Quick check: if path is already accessible, no work needed.
        // Use the cheap StatFs+canWrite probe — the touch+rm shell exec
        // (isMountWritable) blocks up to 2s under FUSE binder contention
        // from concurrent dir-walks, falsely reporting unmounted and
        // forcing the slow `sm mount` path even though the volume is fine.
        // This was the root of the "trips storage selection silently fails"
        // bug: setTripsStorageType → ensureExternalAvailable → here, and
        // the 2s timeout returned false → setTripsStorageType returned false.
        if (!force && currentAvailable && currentPath != null) {
            if (isPathLikelyMounted(currentPath)) {
                logDebug(targetClass + " already mounted at: " + currentPath);
                return true;
            }
        }

        logDebug("Mounting " + targetClass + "...");

        // Pre-mount discovery via /proc/mounts. On some BYD ROMs the system
        // remounts the SD slot itself a few seconds after ACC OFF, but `sm
        // list-volumes` lags behind the kernel's actual mount table — so the
        // SD is live at /storage/<uuid> but `sm` either omits the row or
        // reports it as `unmounted`. Without this probe, ensureVolumeMounted
        // would proceed to `sm mount <id>` (which fails because the kernel
        // already owns the mount) and fall back to internal storage despite
        // the card being writable the whole time.
        //
        // discoverVolumes()'s /proc/mounts pass already does the right thing,
        // but the mount path didn't share that knowledge. Run a discovery
        // pass FIRST; if it commits the field for our class, we're done.
        try {
            discoverVolumes();
            if (isSd && sdCardAvailable && sdCardPath != null
                    && isPathLikelyMounted(sdCardPath)) {
                logInfo(targetClass + " already mounted via /proc/mounts: " + sdCardPath);
                if (isSd) initSdCardDirectories(); else initUsbDirectories();
                updateActiveDirectories();
                return true;
            }
            if (!isSd && usbAvailable && usbPath != null
                    && isPathLikelyMounted(usbPath)) {
                logInfo(targetClass + " already mounted via /proc/mounts: " + usbPath);
                initUsbDirectories();
                updateActiveDirectories();
                return true;
            }
        } catch (Throwable t) {
            // Discovery is best-effort here — never let it block the
            // sm-driven mount path below.
            logDebug("Pre-mount discovery threw: " + t.getMessage());
        }

        // Raw sm output captured here so we can dump it on failure. Some BYD
        // ROMs at ACC OFF emit no `public:` row for the SD slot at all —
        // distinguishing that from the "row exists but in unmounted state"
        // case is critical for diagnosis. Without the dump, the daemon log
        // shows "SD card mount failed" with no clue WHICH failure mode.
        StringBuilder rawSmOutput = new StringBuilder();
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;
            String volumeId = null;
            String volumeUuid = null;
            int volMajor = -1, volMinor = -1;
            int publicRowCount = 0;
            int matchedRowCount = 0;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                rawSmOutput.append(line).append('\n');
                logDebug("sm list-volumes: " + line);
                if (!line.startsWith("public:")) continue;
                publicRowCount++;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                String[] dev = parts[0].substring("public:".length()).split(",");
                int major, minor;
                try {
                    major = Integer.parseInt(dev[0]);
                    minor = Integer.parseInt(dev[1]);
                } catch (Exception e) {
                    continue;
                }
                String state = parts[1];
                String thisUuid = parts[2];
                String klass = classifyPublicVolume(major, minor, thisUuid);
                if (!targetClass.equals(klass)) continue;  // wrong volume class
                matchedRowCount++;

                if ("mounted".equals(state)) {
                    String mountPath = "/storage/" + thisUuid;
                    // Cheap check (no shell fork). See note at the
                    // already-accessible branch above for why touch+rm is
                    // unsafe under contention.
                    if (isPathLikelyMounted(mountPath)) {
                        if (isSd) {
                            sdCardPath = mountPath;
                            sdCardAvailable = true;
                            learnSdUuid(thisUuid);  // remember for next unmount window
                        } else {
                            usbPath = mountPath;
                            usbAvailable = true;
                        }
                        logInfo(targetClass + " already mounted at: " + mountPath);
                        reader.close();
                        waitForBounded(listProcess, 2_000, "sm list-volumes (already-mounted)");
                        if (isSd) initSdCardDirectories(); else initUsbDirectories();
                        updateActiveDirectories();
                        return true;
                    }
                    logWarn(targetClass + " volume " + parts[0] + " reports mounted but path " +
                        mountPath + " not accessible — will force remount");
                }

                volumeId = parts[0];
                volumeUuid = thisUuid;
                volMajor = major;
                volMinor = minor;
                break;
            }
            reader.close();
            waitForBounded(listProcess, 2_000, "sm list-volumes (ensureVolumeMounted)");

            // Diagnostic: capture the WHY of an `sm list-volumes` miss. On
            // affected BYD models at ACC OFF this often shows publicRows>0
            // but matchedRows==0 (slot present but classifier rejected it
            // because sys.byd.mSdcardUuid is empty during the transition).
            // The mismatch fingerprint tells us whether mitigation B (kernel
            // fallback) or mitigation C (path-based discovery) needs to fire.
            if (volumeId == null) {
                logInfo("sm list-volumes: no " + targetClass + " match (publicRows="
                    + publicRowCount + ", matchedRows=" + matchedRowCount
                    + ") — falling back to kernel-level retry");
                long nowMs = System.currentTimeMillis();
                if (rawSmOutput.length() > 0
                        && (nowMs - lastSmRawDumpAtMs) >= SM_RAW_DUMP_INTERVAL_MS) {
                    logInfo("sm list-volumes raw output:\n" + rawSmOutput.toString().trim());
                    lastSmRawDumpAtMs = nowMs;
                }
            }

            if (volumeId != null) {
                Process mountProcess = Runtime.getRuntime().exec(new String[]{"sm", "mount", volumeId});
                BufferedReader outReader = new BufferedReader(new InputStreamReader(mountProcess.getInputStream()));
                BufferedReader errReader = new BufferedReader(new InputStreamReader(mountProcess.getErrorStream()));
                StringBuilder output = new StringBuilder();
                String outLine;
                while ((outLine = outReader.readLine()) != null) output.append(outLine).append("\n");
                while ((outLine = errReader.readLine()) != null) output.append("ERR: ").append(outLine).append("\n");
                outReader.close();
                errReader.close();

                // 8s ceiling for the actual mount. Healthy SD/USB mounts on
                // BYD finish in <1s; anything past 8s is a stuck vold and
                // we'd rather fall back to internal than wedge the daemon.
                int exitCode = waitForBounded(mountProcess, 8_000, "sm mount " + volumeId);
                logInfo("sm mount " + volumeId + " exit code: " + exitCode +
                    (output.length() > 0 ? ", output: " + output.toString().trim() : ""));

                if (exitCode == 0 && volumeUuid != null) {
                    String mountPath = "/storage/" + volumeUuid;
                    // Lengthened from 10 to 20 iterations (5s → 10s budget).
                    // On affected BYD models the FUSE bridge is published
                    // ~3-6s after `sm mount` returns 0 — the prior 5s budget
                    // raced the publication and falsely concluded the mount
                    // failed. Per-iteration cost is microseconds (StatFs +
                    // canWrite, no shell fork), so the longer poll is free
                    // when the mount is healthy.
                    boolean interrupted = false;
                    for (int i = 0; i < 20 && !interrupted; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            // Restore the flag and break — the watchdog (or
                            // shutdown path) is asking us to stop. Without
                            // this, the prior code swallowed the interrupt
                            // via the outer Exception catch and silently
                            // turned a shutdown request into 10s of polling.
                            Thread.currentThread().interrupt();
                            interrupted = true;
                            break;
                        }
                        if (isPathLikelyMounted(mountPath)) {
                            if (isSd) {
                                sdCardPath = mountPath;
                                sdCardAvailable = true;
                                learnSdUuid(volumeUuid);
                            } else {
                                usbPath = mountPath;
                                usbAvailable = true;
                            }
                            logInfo(targetClass + " mounted successfully at: " + mountPath
                                + " (poll attempt " + (i + 1) + "/20)");
                            if (isSd) initSdCardDirectories(); else initUsbDirectories();
                            updateActiveDirectories();
                            return true;
                        }
                        logDebug("Waiting for " + targetClass + " mount... attempt " + (i+1) + "/20");
                    }
                    logWarn(targetClass + " mount path not accessible after mount: " + mountPath);
                } else {
                    logWarn("sm mount " + volumeId + " failed with exit code: " + exitCode);
                }
            } else {
                logDebug("No public " + targetClass + " volume found in sm output");
            }

        } catch (Exception e) {
            logError("Error mounting " + targetClass + ": " + e.getMessage());
        }

        // TODO: USB has no analogous kernel-level fallback. /sys/class/mmc_host
        // is mmc-only, so an equivalent USB probe would walk /sys/bus/usb/devices.
        // The reported symptom (ACC OFF → external storage invisible) is SD-only
        // so far; revisit if USB-only configs report the same pattern.
        // Kernel-level fallback for the "sm doesn't see the slot at all" case.
        // On affected BYD ROMs, ACC OFF transiently deregisters the volume from
        // vold's VolumeRecord — but the underlying mmcblk* device is still
        // alive and the kernel mount table either still holds it or is about
        // to. Two recovery routes:
        //   (a) The card may already be mounted by the system at /mnt/media_rw/
        //       or /storage/<uuid> with no `sm` row — discoverVolumes() picks
        //       this up via /proc/mounts. Run a fresh discovery and re-check.
        //   (b) The card is physically present (visible under /sys/class/mmc_host)
        //       but vold hasn't surfaced it yet — wait + re-discover up to 5
        //       times (5s total) for vold to catch up. We don't try `sm forget`
        //       or partition rescans here; those are too invasive and can leave
        //       a wedged volume worse off.
        // If neither route catches the card, fall through to internal storage —
        // the caller's existing fallback path handles that gracefully.
        if (isSd && !sdCardAvailable && isSdCardPhysicallyPresent()) {
            logInfo("Kernel fallback: SD slot reports a card present but sm/proc-mounts didn't find it — polling for vold catch-up");
            for (int i = 0; i < 5; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                discoverVolumes();
                if (sdCardAvailable && sdCardPath != null
                        && isPathLikelyMounted(sdCardPath)) {
                    logInfo("Kernel fallback: SD picked up after " + (i + 1) + "s at " + sdCardPath);
                    initSdCardDirectories();
                    updateActiveDirectories();
                    return true;
                }
            }
            logWarn("Kernel fallback: SD slot card present but vold never surfaced it within 5s");
        }

        // Re-run discovery in case mount succeeded but we missed it
        discoverVolumes();
        return isSd ? sdCardAvailable : usbAvailable;
    }

    /**
     * Probe whether the SD slot has a physical card inserted, independent of
     * vold/sm state. Used by the kernel-level mount fallback to distinguish
     * "card pulled" (give up cleanly) from "vold hasn't caught up yet" (worth
     * waiting on).
     *
     * <p><b>Bus-agnostic by design.</b> The SD reader on some BYD trims is
     * wired through a SCSI/USB-storage bridge, so the card enumerates under
     * block major 8 / {@code DEVNAME=sd*} and NEVER appears in
     * {@code /sys/class/mmc_host}. The historical mmc_host-only probe returned
     * false on those vehicles, so this fallback never armed: an ACC-OFF
     * vold-drop then fell back to internal storage for the whole (~tens of
     * seconds) republish window even though the card was physically seated the
     * entire time (see the SCSI-bridge ACC-OFF unmount report, 2026-06).
     *
     * <p>Two SD-specific signals, in order. Neither false-positives on the
     * always-present internal eMMC or on a real USB stick — both of which a
     * raw block-device / major-8 scan would wrongly match, which is exactly
     * why we do NOT scan {@code /sys/block} or {@code /sys/class/scsi_disk}
     * here:
     * <ol>
     *   <li><b>{@code sys.byd.isSDExist}</b> — BYD vendor prop. EXPECTED to
     *       report SD-slot occupancy bus-agnostically (independent of how the
     *       kernel surfaces the device), which is what would cover the
     *       SCSI-bridged case. {@code ExternalStorageCleaner} already reads
     *       this prop. <b>Unverified on-car for major-8/sd* bridged readers as
     *       of 2026-06</b> — the raw value is logged on every probe (see below)
     *       so a single ACC-OFF device session (card seated, then pulled)
     *       resolves both (a) whether it is populated for the bridged reader
     *       and (b) whether it tracks live occupancy or can latch stale. NOTE
     *       we intentionally do NOT corroborate with {@code sys.byd.mSdcardUuid}
     *       (as ExternalStorageCleaner does) here: that prop is empty during
     *       the very unmount window this probe recovers from, so requiring it
     *       would defeat the fallback.</li>
     *   <li><b>{@code /sys/class/mmc_host/mmcN/mmcN:*}</b> — the original
     *       MMC-bus probe, retained for non-BYD hosts and for the case where
     *       the prop is unavailable. The kernel publishes this entry as soon
     *       as a card is electrically detected on the native MMC subsystem.</li>
     * </ol>
     *
     * <p>This probe is consulted only inside the SD kernel-fallback branch,
     * which is reached only after {@code sm}/{@code /proc/mounts} already failed
     * to find the SD — i.e. not on any hot path during normal operation — so
     * logging the raw prop value on each call is a ~per-watchdog-tick cadence
     * during an outage, not a flood.
     *
     * @return true if a card is physically present in the SD slot
     */
    private boolean isSdCardPhysicallyPresent() {
        // Signal 1 (vendor prop, EXPECTED bus-agnostic + SD-specific): the BYD
        // firmware's own SD-slot occupancy flag. Intended to cover SCSI/USB-
        // bridged SD readers (major 8 / sd*) that never surface under
        // /sys/class/mmc_host. Log the RAW value so on-car behavior (populated?
        // live vs sticky?) is resolvable from the daemon log in one session.
        try {
            String sdExist = getSystemProperty("sys.byd.isSDExist");
            logInfo("isSdCardPhysicallyPresent: sys.byd.isSDExist='" + sdExist + "'");
            if ("true".equalsIgnoreCase(sdExist)) {
                return true;
            }
        } catch (Throwable t) {
            logDebug("isSdCardPhysicallyPresent: sys.byd.isSDExist read failed: " + t.getMessage());
        }

        // Signal 2 (MMC bus): /sys/class/mmc_host/mmcN/mmcN:* card-present entry.
        try {
            File mmcHostDir = new File("/sys/class/mmc_host");
            if (mmcHostDir.exists() && mmcHostDir.isDirectory()) {
                File[] hosts = mmcHostDir.listFiles();
                if (hosts != null) {
                    for (File host : hosts) {
                        File[] children = host.listFiles();
                        if (children == null) continue;
                        for (File child : children) {
                            // mmcN:NNNN entries appear when a card is attached.
                            // Internal eMMC also creates such entries (mmc0:0001 typically),
                            // so the presence check alone isn't SD-specific — but the
                            // caller already gated on classifyPublicVolume not finding
                            // an SD via sm, so any mmc_host child here that ISN'T the
                            // eMMC indicates an inserted external card. We don't need
                            // to disambiguate further: false-positives just trigger a
                            // 5s vold-catchup poll that no-ops and falls through.
                            if (child.getName().matches("mmc\\d+:[0-9a-fA-F]+")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logDebug("isSdCardPhysicallyPresent probe failed: " + t.getMessage());
        }
        // Neither signal found a card. Log so the silent-give-up path is
        // visible in the daemon log (the kernel fallback will not arm).
        logInfo("isSdCardPhysicallyPresent: no card detected (isSDExist not true, no mmc_host card entry)");
        return false;
    }

    /**
     * Cheap probe: is any USB mass-storage device attached?
     *
     * <p>Used by the single-volume tiebreaker in {@link #discoverVolumes}
     * to distinguish "lone SCSI-bridged SD card" (no USB device → treat as
     * SD) from "real USB stick is the only thing inserted" (USB device
     * present → keep classifying as USB). We walk {@code /sys/bus/usb/devices}
     * for entries with {@code bInterfaceClass=08} (Mass Storage); pure-host
     * USB ports have no children matching that. Returns false on probe error
     * so the tiebreaker stays conservative — better to miss the SD promotion
     * than to misclassify a real USB stick as SD.
     */
    private boolean isUsbDeviceAttached() {
        try {
            File usbDir = new File("/sys/bus/usb/devices");
            if (!usbDir.exists() || !usbDir.isDirectory()) return false;
            File[] devices = usbDir.listFiles();
            if (devices == null) return false;
            for (File dev : devices) {
                // Skip root hubs (usb1, usb2, …) — those are always present
                // even when nothing is plugged in. Real attached devices show
                // up as e.g. 1-1, 1-1.2 (port path notation).
                String name = dev.getName();
                if (name.startsWith("usb") || !name.contains("-")) continue;
                File[] children = dev.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    File classFile = new File(child, "bInterfaceClass");
                    if (!classFile.isFile() || !classFile.canRead()) continue;
                    try (BufferedReader r = new BufferedReader(new FileReader(classFile))) {
                        String cls = r.readLine();
                        // 08 = USB Mass Storage. 06 = Image (cameras), other
                        // classes (HID etc.) won't surface as a public:
                        // volume in sm list-volumes anyway.
                        if (cls != null && "08".equalsIgnoreCase(cls.trim())) return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable t) {
            logDebug("isUsbDeviceAttached probe failed: " + t.getMessage());
        }
        return false;
    }
    
    /**
     * Check if SD card is currently mounted (without attempting to mount).
     * Simply checks if the path exists and is writable.
     *
     * @return true if SD card is mounted
     */
    public boolean isSdCardMounted() {
        if (sdCardPath == null) {
            return false;
        }
        return isMountWritable(sdCardPath);
    }

    /**
     * Cheap liveness check for the SD card mount, suitable for the watchdog
     * tick (called every 15s). Avoids forking a `touch+rm` shell — that
     * probe blocks for up to 2s under FUSE binder contention from concurrent
     * dir-walks (recordings/stats, storage/external, etc.) and falsely
     * reports "unmounted", triggering a remount cascade that itself runs
     * more shell forks and amplifies the contention.
     *
     * <p>Layered check, fail-fast:
     * <ol>
     *   <li>Path resolved? Directory exists? — Java {@code File} API, no fork.</li>
     *   <li>{@code StatFs.getTotalBytes()} — single binder call, ~200µs.</li>
     *   <li>{@code File.canWrite()} — Java permission check, no fork.</li>
     * </ol>
     * Three signals all green = mount is live. The expensive write probe is
     * reserved for {@link #isMountWritable} which callers invoke when they
     * are about to actually write.
     */
    public boolean isSdCardLikelyMounted() {
        if (sdCardPath == null) return false;
        File d = new File(sdCardPath);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(sdCardPath);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }

    /**
     * Check if USB drive is currently mounted (without attempting to mount).
     */
    public boolean isUsbMounted() {
        if (usbPath == null) {
            return false;
        }
        return isMountWritable(usbPath);
    }

    /**
     * Cheap, fork-free USB-mount probe — mirrors {@link #isSdCardLikelyMounted}
     * for the USB volume. Used by the per-minute watchdog tick so we don't
     * spawn a shell ({@code touch} via {@link #isMountWritable}) every cycle.
     *
     * <p>FIX (audit R5): a {@code touch+rm} fork on every tick (1/min) under
     * FUSE binder contention can itself amplify the contention that the
     * watchdog is supposed to recover from, plus the false-positive "USB
     * unmounted" reading after every UI settings save (when the page reflexively
     * walks USB via /api/storage/external + /api/recordings/stats). The cheap
     * StatFs+canWrite path has none of those side effects.
     *
     * <p>The expensive write-probe is reserved for {@link #isMountWritable} —
     * callers that are about to actually write call it before mid-segment
     * fsync points, where a 2s probe stall is preferable to a silently-vanished
     * mount.
     */
    public boolean isUsbLikelyMounted() {
        if (usbPath == null) return false;
        File d = new File(usbPath);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(usbPath);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }

    /**
     * Ensure storage is ready for use.
     * If SD/USB storage is selected but not mounted, attempts to mount it.
     * If mount fails, falls back to internal storage.
     *
     * @param forSurveillance true if checking for surveillance, false for recordings
     * @return true if storage is ready (either SD/USB mounted or fallback to internal)
     */
    public boolean ensureStorageReady(boolean forSurveillance) {
        StorageType selectedType = forSurveillance ? surveillanceStorageType : recordingsStorageType;

        if (selectedType == StorageType.INTERNAL) {
            // Internal storage is always ready
            return true;
        }

        // CRITICAL: Don't switch storage location while recording is active
        // This prevents files from being split across volumes
        if (!forSurveillance && recordingActive.get()) {
            logDebug("Recording active - not switching storage location");
            return true;
        }
        if (forSurveillance && surveillanceActive.get()) {
            logDebug("Surveillance active - not switching storage location");
            return true;
        }

        if (selectedType == StorageType.SD_CARD) {
            if (!isSdCardMounted()) {
                logInfo("SD card not mounted, attempting to mount for " +
                    (forSurveillance ? "surveillance" : "recordings"));
                if (!ensureSdCardMounted()) {
                    logWarn("Failed to mount SD card, falling back to internal storage");
                    // Let updateActiveDirectories handle the fallback via
                    // resolveActive — single source of truth for "configured
                    // external missing → use internal." Avoids the partial
                    // assignment trap (writing only surveillanceDir+proximityDir
                    // while leaving recordingsDir/tripsDir at stale values).
                    updateActiveDirectories();
                    return true;
                }
            }
            initSdCardDirectories();
            updateActiveDirectories();

            // Pre-reserve space on SD card by cleaning BYD dashcam files if needed
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("Pre-recording CDR cleanup failed: " + e.getMessage());
            }
            return true;
        }

        if (selectedType == StorageType.USB) {
            if (!isUsbMounted()) {
                logInfo("USB not mounted, attempting to mount for " +
                    (forSurveillance ? "surveillance" : "recordings"));
                if (!ensureUsbMounted()) {
                    logWarn("Failed to mount USB, falling back to internal storage");
                    updateActiveDirectories();
                    return true;
                }
            }
            initUsbDirectories();
            updateActiveDirectories();
            return true;
        }

        return true;
    }
    
    /**
     * Backwards-compatible alias for {@link #discoverVolumes()} — public
     * callers (refreshSdCard, watchdog) keep working unchanged.
     */
    public void discoverSdCard() {
        discoverVolumes();
    }

    /**
     * Classify a public volume as SD or USB.
     *
     * Three signals, in order of authority:
     *   1. {@code sys.byd.mSdcardUuid} — vendor-set prop carrying the UUID of
     *      the SD card slot's volume. Present on BYD head-units; the most
     *      reliable signal because the firmware itself decides what the
     *      slot is. We compare against the volume's UUID (parts[2] from sm
     *      list-volumes), so this works even when the kernel exposes the
     *      SD reader through a USB/SCSI bridge (which surfaces the device
     *      under major 8 / DEVNAME=sd*, otherwise indistinguishable from
     *      a real USB stick — see Seal 2026-05 firmware).
     *   2. {@code /sys/dev/block/M:N/uevent} DEVNAME — kernel-level. Reliable
     *      when the SD goes through the standard mmc subsystem (major 179),
     *      misleading when SD is bridged through SCSI (sda*). Used as the
     *      first fallback when the BYD prop didn't match.
     *   3. Linux major-number table — last resort.
     *      - 179         → mmcblk* (SD slot)                → SD
     *      - 8, 65..71,  → sd* (SCSI; USB-OTG flash drives) → USB
     *        128..135
     *
     * @return "SD", "USB", or null if classification failed (treat as
     *         "don't claim it for either" — better than misclassifying).
     */
    private String classifyPublicVolume(int major, int minor, String volumeUuid) {
        // Signal 1 (vendor-authoritative, live-only): does this volume's UUID
        // match the BYD SD-slot UUID prop? Most reliable WHEN populated, but
        // BYD only writes the prop while the card is mounted, so this misses
        // during the unmount window between ACC OFF and our remount attempt.
        if (volumeUuid != null && !volumeUuid.isEmpty()) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            if (sdUuid != null && !sdUuid.isEmpty() && sdUuid.equalsIgnoreCase(volumeUuid)) {
                return "SD";
            }
        }

        // Signal 1b (vendor-authoritative, persistent): UUID we previously
        // confirmed as SD via a successful mount. Survives the unmount
        // window where the BYD vendor prop returns empty. The FAT volume
        // serial in `volumeUuid` is stable across remount cycles for the
        // same physical card, so a match here is conclusive.
        if (volumeUuid != null && !volumeUuid.isEmpty()) {
            String learned = readLearnedSdUuid();
            if (!learned.isEmpty() && learned.equalsIgnoreCase(volumeUuid)) {
                return "SD";
            }
        }

        // Signal 2: DEVNAME from the kernel uevent.
        try {
            File ueventFile = new File("/sys/dev/block/" + major + ":" + minor + "/uevent");
            if (ueventFile.exists() && ueventFile.canRead()) {
                BufferedReader r = new BufferedReader(new FileReader(ueventFile));
                String l;
                String devname = null;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("DEVNAME=")) {
                        devname = l.substring("DEVNAME=".length()).trim();
                        break;
                    }
                }
                r.close();
                if (devname != null) {
                    if (devname.startsWith("mmcblk")) return "SD";
                    if (devname.startsWith("sd"))     return "USB";
                }
            }
        } catch (Exception e) {
            logDebug("classifyPublicVolume read failed for " + major + ":" + minor + ": " + e.getMessage());
        }

        // Signal 3: major-number fallback.
        if (major == 179) return "SD";
        if (major == 8 || (major >= 65 && major <= 71) || (major >= 128 && major <= 135)) return "USB";
        return null;
    }

    /**
     * Probe whether the given mount point is writable from app/daemon UID.
     * Java's File.canWrite() returns false on FUSE-bridged mounts that are
     * actually writable via shell, so we fall back to a touch+rm probe.
     */
    private boolean isMountWritable(String mountPath) {
        File dir = new File(mountPath);
        if (!dir.exists() || !dir.isDirectory()) return false;
        if (dir.canWrite()) return true;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "sh", "-c", "touch " + mountPath + "/.overdrive_probe && rm " + mountPath + "/.overdrive_probe"
            });
            // 2s ceiling — touch/rm against a healthy FUSE mount returns in
            // single-digit ms; anything slower is a stuck filesystem and
            // should be treated as not-writable so we don't latch onto it.
            return waitForBounded(p, 2_000, "isMountWritable(" + mountPath + ")") == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Discover both SD card and USB drive paths in a single pass so they
     * can never alias each other. Replaces the old SD-only discoverSdCard
     * which would happily latch onto a USB stick when both were inserted
     * (the type-blind methods accepted any writable {@code public:} volume).
     *
     * Strategy:
     *   1. {@code sm list-volumes all} — walk every mounted public volume
     *      and classify by underlying block-device major number.
     *   2. BYD UUID prop ({@code sys.byd.mSdcardUuid}) as a tie-breaker
     *      for SD when sm didn't help.
     *   3. /proc/mounts vfat/exfat as final fallback, with the same
     *      major-number classifier applied to the source device.
     *
     * The legacy /storage/ blind scan and SD_CARD_PATHS catch-all are
     * removed — they were the source of the SD/USB confusion.
     */
    public void discoverVolumes() {
        // Stage detection in local vars — only commit to fields on success.
        // Previously we nulled sdCardPath / sdCardAvailable at the top, which
        // meant any transient failure mid-detect (sm timeout, isMountWritable
        // false-positive under FUSE contention, /proc/mounts read error)
        // permanently wiped known-good state until the next watchdog tick.
        // Combined with B5 in the audit, that's the "finds it but can't
        // mount" failure mode the user reported: sm list-volumes correctly
        // returned the volume id, but isMountWritable's `touch+rm` probe
        // timed out under contention so the field was never assigned, and
        // the daemon ran the rest of the session thinking the SD was gone.
        String foundSdPath = null;
        boolean foundSdAvail = false;
        String foundUsbPath = null;
        boolean foundUsbAvail = false;

        // Track every mounted public volume we observed, even ones the
        // classifier couldn't bucket. Used by the single-volume tiebreaker
        // below to promote an ambiguously-typed lone volume to SD when no
        // physical USB stick is present (the v17-and-earlier "any writable
        // public volume → SD" behaviour, scoped to safe conditions).
        java.util.List<String[]> ambiguousMounts = new java.util.ArrayList<>();

        // Method 1: sm list-volumes all
        try {
            Process listProcess = Runtime.getRuntime().exec(new String[]{"sm", "list-volumes", "all"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                // Parse lines like: "public:8,97 mounted 3661-3064"
                line = line.trim();
                if (!line.startsWith("public:") || !line.contains("mounted")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;

                // parts[0] = "public:8,97" → major=8, minor=97
                String[] dev = parts[0].substring("public:".length()).split(",");
                int major, minor;
                try {
                    major = Integer.parseInt(dev[0]);
                    minor = Integer.parseInt(dev[1]);
                } catch (Exception e) {
                    continue;
                }
                String volumeUuid = parts[2];
                String mountPath = "/storage/" + volumeUuid;
                // Use the cheap layered check — the expensive touch+rm probe
                // here was the source of the false-negative cascade. If a
                // public:* volume is in `mounted` state per `sm` AND the
                // path exists with positive StatFs, trust it.
                if (!isPathLikelyMounted(mountPath)) continue;

                String klass = classifyPublicVolume(major, minor, volumeUuid);
                ambiguousMounts.add(new String[]{mountPath, volumeUuid,
                        klass == null ? "" : klass,
                        String.valueOf(major), String.valueOf(minor)});
                if ("SD".equals(klass) && !foundSdAvail) {
                    foundSdPath = mountPath;
                    foundSdAvail = true;
                    learnSdUuid(volumeUuid);
                    logInfo("Found SD card via sm list-volumes (" + major + ":" + minor + "): " + mountPath);
                } else if ("USB".equals(klass) && !foundUsbAvail) {
                    foundUsbPath = mountPath;
                    foundUsbAvail = true;
                    logInfo("Found USB drive via sm list-volumes (" + major + ":" + minor + "): " + mountPath);
                }
                // Keep iterating — both kinds may be present.
            }
            reader.close();
            waitForBounded(listProcess, 2_000, "sm list-volumes (discoverVolumes)");
        } catch (Exception e) {
            logDebug("Could not check sm list-volumes: " + e.getMessage());
        }

        // Method 2: BYD UUID prop is SD-specific. Only use if Method 1 missed SD.
        if (!foundSdAvail) {
            String sdUuid = getSystemProperty("sys.byd.mSdcardUuid");
            if (sdUuid != null && !sdUuid.isEmpty()) {
                String uuidPath = "/storage/" + sdUuid;
                if (isPathLikelyMounted(uuidPath) && !uuidPath.equals(foundUsbPath)) {
                    foundSdPath = uuidPath;
                    foundSdAvail = true;
                    learnSdUuid(sdUuid);
                    logInfo("Found SD card via BYD UUID: " + uuidPath);
                }
            }
        }

        // Method 3: /proc/mounts for vfat/exfat — classify the source device
        // by its base name (mmcblk* → SD, sd* → USB) before claiming it.
        if (!foundSdAvail || !foundUsbAvail) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("vfat") && !line.contains("exfat")) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) continue;
                    String source = parts[0];      // e.g., /dev/block/mmcblk1p1 or /dev/block/sda1
                    String mountPoint = parts[1];
                    if (mountPoint.startsWith("/mnt/vendor") || mountPoint.startsWith("/firmware") ||
                        mountPoint.equals("/boot") || mountPoint.startsWith("/cache")) {
                        continue;
                    }
                    if (!isPathLikelyMounted(mountPoint)) continue;

                    // Strip /dev/block/ prefix and trailing partition number.
                    String base = source;
                    int slash = base.lastIndexOf('/');
                    if (slash >= 0) base = base.substring(slash + 1);
                    // base now like "mmcblk1p1" or "sda1"
                    String klass = null;
                    if (base.startsWith("mmcblk")) klass = "SD";
                    else if (base.startsWith("sd")) klass = "USB";

                    if ("SD".equals(klass) && !foundSdAvail && !mountPoint.equals(foundUsbPath)) {
                        foundSdPath = mountPoint;
                        foundSdAvail = true;
                        logInfo("Found SD card via /proc/mounts (" + source + "): " + mountPoint);
                    } else if ("USB".equals(klass) && !foundUsbAvail && !mountPoint.equals(foundSdPath)) {
                        foundUsbPath = mountPoint;
                        foundUsbAvail = true;
                        logInfo("Found USB drive via /proc/mounts (" + source + "): " + mountPoint);
                    }
                }
                reader.close();
            } catch (Exception e) {
                logDebug("Could not parse /proc/mounts: " + e.getMessage());
            }
        }

        // Method 4: single-volume tiebreaker (v17 behaviour, scoped).
        // Affected firmwares route the SD slot through a SCSI/USB bridge so
        // the kernel surfaces it as DEVNAME=sd*, major 8 — indistinguishable
        // from a real USB stick to classifyPublicVolume(). On those vehicles
        // the BYD prop is also empty, so Method 2 can't help, and the v17
        // permissive "any writable public volume → SD" code path was lost in
        // the type-discriminating rewrite. Recover for the unambiguous case:
        //   - SD still not found
        //   - exactly one mounted public volume observed by Method 1
        //   - no physical USB device attached (per /sys/bus/usb/devices)
        // Then the lone volume is, by elimination, the SD slot.
        if (!foundSdAvail && ambiguousMounts.size() == 1
                && !isUsbDeviceAttached()) {
            String[] only = ambiguousMounts.get(0);
            String mountPath = only[0];
            String volumeUuid = only[1];
            // If we already classed this as USB above, demote it — we now
            // know it's the only volume and there's no real USB to call it.
            if (mountPath.equals(foundUsbPath)) {
                foundUsbPath = null;
                foundUsbAvail = false;
            }
            foundSdPath = mountPath;
            foundSdAvail = true;
            learnSdUuid(volumeUuid);
            logInfo("Found SD card via single-volume tiebreaker (no USB attached): "
                    + mountPath + " [" + only[3] + ":" + only[4]
                    + ", classifier=" + (only[2].isEmpty() ? "ambiguous" : only[2]) + "]");
        }

        // Commit results atomically. Volumes that disappeared since the last
        // detection do go from non-null → null here; that's correct behavior
        // (the card was actually pulled). What we avoid is the transient-
        // failure case where Method 1 found the card via sm list-volumes
        // but Method 1's writability probe timed out — without staging, that
        // would have nulled state mid-walk and Method 2/3 wouldn't recover
        // because they branch on `!sdCardAvailable` (now `!foundSdAvail`,
        // which preserved the success).
        sdCardPath = foundSdPath;
        sdCardAvailable = foundSdAvail;
        usbPath = foundUsbPath;
        usbAvailable = foundUsbAvail;

        if (!sdCardAvailable) logDebug("No writable SD card found");
        if (!usbAvailable) logDebug("No writable USB drive found");
    }

    /** Cheap mount-liveness check for any path. Same layered logic as
     * {@link #isSdCardLikelyMounted} but for arbitrary mount points.
     * StatFs + canWrite, no shell fork. */
    private boolean isPathLikelyMounted(String path) {
        if (path == null) return false;
        File d = new File(path);
        if (!d.exists() || !d.isDirectory()) return false;
        try {
            android.os.StatFs s = new android.os.StatFs(path);
            if (s.getTotalBytes() <= 0) return false;
        } catch (Throwable t) {
            return false;
        }
        return d.canWrite();
    }
    
    /**
     * Get Android system property via reflection or shell.
     */
    private String getSystemProperty(String key) {
        try {
            // Try reflection first
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "");
        } catch (Exception e) {
            // Fall back to shell
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                reader.close();
                waitForBounded(p, 1_000, "getprop " + key);
                return line != null ? line.trim() : "";
            } catch (Exception e2) {
                return "";
            }
        }
    }

    /**
     * Read the persisted UUID of the volume previously confirmed as SD. See
     * {@link #LEARNED_SD_UUID_FILE} for why this exists. Returns empty string
     * if no learned value (first boot, or file missing).
     */
    private String readLearnedSdUuid() {
        File f = new File(LEARNED_SD_UUID_FILE);
        if (!f.exists() || !f.canRead()) return "";
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Persist the UUID of a volume just confirmed as SD. Idempotent — re-writes
     * are cheap and harmless. We only record on a successful mount, so this
     * value only ever describes a real, working SD card.
     */
    private void learnSdUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return;
        if (uuid.equalsIgnoreCase(readLearnedSdUuid())) return;  // unchanged, skip write
        try (FileWriter w = new FileWriter(LEARNED_SD_UUID_FILE, false)) {
            w.write(uuid);
            // 0644 — daemon (UID 2000) writes, app process needs to read on
            // the rare path where it walks classifyPublicVolume itself.
            try { new File(LEARNED_SD_UUID_FILE).setReadable(true, false); } catch (Exception ignored) {}
            logInfo("Learned SD UUID for future classification: " + uuid);
        } catch (Exception e) {
            logDebug("learnSdUuid write failed: " + e.getMessage());
        }
    }


    /**
     * Initialize storage directories.
     * IMPORTANT: Sets world-readable permissions so the UI app can access recordings.
     */
    private void initDirectories() {
        // Initialize internal storage directories (always available)
        File internalBaseDir = new File(INTERNAL_BASE_DIR);
        if (!internalBaseDir.exists()) {
            boolean created = internalBaseDir.mkdirs();
            logInfo("Created internal base directory: " + INTERNAL_BASE_DIR + " (success=" + created + ")");
        }
        internalBaseDir.setReadable(true, false);
        internalBaseDir.setExecutable(true, false);
        
        internalRecordingsDir = new File(internalBaseDir, RECORDINGS_SUBDIR);
        if (!internalRecordingsDir.exists()) {
            boolean created = internalRecordingsDir.mkdirs();
            logInfo("Created internal recordings directory: " + internalRecordingsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalRecordingsDir.setReadable(true, false);
        internalRecordingsDir.setExecutable(true, false);
        
        internalSurveillanceDir = new File(internalBaseDir, SURVEILLANCE_SUBDIR);
        if (!internalSurveillanceDir.exists()) {
            boolean created = internalSurveillanceDir.mkdirs();
            logInfo("Created internal surveillance directory: " + internalSurveillanceDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalSurveillanceDir.setReadable(true, false);
        internalSurveillanceDir.setExecutable(true, false);
        
        internalProximityDir = new File(internalBaseDir, PROXIMITY_SUBDIR);
        if (!internalProximityDir.exists()) {
            boolean created = internalProximityDir.mkdirs();
            logInfo("Created internal proximity directory: " + internalProximityDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalProximityDir.setReadable(true, false);
        internalProximityDir.setExecutable(true, false);
        
        internalTripsDir = new File(internalBaseDir, TRIPS_SUBDIR);
        if (!internalTripsDir.exists()) {
            boolean created = internalTripsDir.mkdirs();
            logInfo("Created internal trips directory: " + internalTripsDir.getAbsolutePath() + " (success=" + created + ")");
        }
        internalTripsDir.setReadable(true, false);
        internalTripsDir.setExecutable(true, false);
        
        // Initialize SD card and USB directories if available
        initSdCardDirectories();
        initUsbDirectories();
    }

    /**
     * Initialize SD card directories if SD card is available.
     */
    private void initSdCardDirectories() {
        if (!sdCardAvailable || sdCardPath == null) {
            sdCardRecordingsDir = null;
            sdCardSurveillanceDir = null;
            sdCardProximityDir = null;
            sdCardTripsDir = null;
            return;
        }
        File[] dirs = initVolumeDirectories(sdCardPath, "SD card");
        if (dirs != null) {
            sdCardRecordingsDir   = dirs[0];
            sdCardSurveillanceDir = dirs[1];
            sdCardProximityDir    = dirs[2];
            sdCardTripsDir        = dirs[3];
        }
    }

    /**
     * Initialize USB directories if USB drive is available.
     */
    private void initUsbDirectories() {
        if (!usbAvailable || usbPath == null) {
            usbRecordingsDir = null;
            usbSurveillanceDir = null;
            usbProximityDir = null;
            usbTripsDir = null;
            return;
        }
        File[] dirs = initVolumeDirectories(usbPath, "USB");
        if (dirs != null) {
            usbRecordingsDir   = dirs[0];
            usbSurveillanceDir = dirs[1];
            usbProximityDir    = dirs[2];
            usbTripsDir        = dirs[3];
        }
    }

    /**
     * Build {@code <volumePath>/Overdrive/{recordings,surveillance,proximity,trips}}
     * with world rwx so the app UID can read them. Returns the four dirs in
     * order, or null if the base couldn't be created.
     */
    private File[] initVolumeDirectories(String volumePath, String label) {
        File base = new File(volumePath, "Overdrive");
        boolean baseCreated = base.mkdirs();
        if (!base.exists()) {
            logError("Failed to create " + label + " base directory: " + base.getAbsolutePath());
            return null;
        }
        if (baseCreated) {
            logInfo("Created " + label + " base directory: " + base.getAbsolutePath());
        }
        base.setReadable(true, false);
        base.setWritable(true, false);
        base.setExecutable(true, false);

        File rec = makeChildDir(base, RECORDINGS_SUBDIR, label + " recordings");
        File surv = makeChildDir(base, SURVEILLANCE_SUBDIR, label + " surveillance");
        File prox = makeChildDir(base, PROXIMITY_SUBDIR, label + " proximity");
        File trips = makeChildDir(base, TRIPS_SUBDIR, label + " trips");

        if (surv != null && surv.exists() && !surv.canWrite()) {
            logError(label + " surveillance directory exists but is not writable: " + surv.getAbsolutePath());
        }
        return new File[]{rec, surv, prox, trips};
    }

    private File makeChildDir(File parent, String name, String label) {
        File dir = new File(parent, name);
        boolean created = dir.mkdirs();
        if (!dir.exists()) {
            logError("Failed to create " + label + " directory: " + dir.getAbsolutePath());
            return dir;
        }
        if (created) {
            logInfo("Created " + label + " directory: " + dir.getAbsolutePath());
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
        return dir;
    }
    
    /**
     * Resolve the active directory for one (category, type) pair, falling
     * back to internal when the requested external volume isn't ready.
     * Logs the fallback only when we actually downgraded (else the boot path
     * spams "fell back" lines for users who never selected SD/USB).
     *
     * <p>Returns a small holder so the caller can log both the configured
     * type AND the resolved type. The previous API returned only the File,
     * forcing the caller to log the configured enum even when we'd
     * downgraded — the resulting log line ("Trips using SD_CARD:
     * /storage/emulated/0/...") was actively misleading on Seal trims with
     * no SD slot.
     */
    private static final class ResolvedDir {
        final File dir;
        final StorageType resolved;
        ResolvedDir(File dir, StorageType resolved) {
            this.dir = dir;
            this.resolved = resolved;
        }
    }

    private ResolvedDir resolveActive(StorageType type,
                                      File internalDir, File sdDir, File usbDir,
                                      String label) {
        if (type == StorageType.SD_CARD) {
            if (sdCardAvailable && sdDir != null) {
                return new ResolvedDir(sdDir, StorageType.SD_CARD);
            }
            logWarn("SD card not available for " + label + ", falling back to internal storage");
            return new ResolvedDir(internalDir, StorageType.INTERNAL);
        }
        if (type == StorageType.USB) {
            if (usbAvailable && usbDir != null) {
                return new ResolvedDir(usbDir, StorageType.USB);
            }
            logWarn("USB not available for " + label + ", falling back to internal storage");
            return new ResolvedDir(internalDir, StorageType.INTERNAL);
        }
        return new ResolvedDir(internalDir, StorageType.INTERNAL);
    }

    /**
     * Reconcile a recorder's latched recordings output-dir override against
     * the CURRENT resolved storage state, at the instant the recorder is about
     * to consume it for a fresh recording.
     *
     * <p><b>Why this exists.</b> The volume watchdog pushes a one-shot
     * {@code setOutputDir} override onto the pano recorder: the SUCCESS branch
     * pushes the live SD/USB path, the FAILURE branch pushes the INTERNAL
     * fallback so segments keep landing somewhere while the external volume is
     * gone. That override outranks {@link #getRecordingsDir()} at consumption
     * time. But when an SD card comes back via the recording-start path's own
     * mount attempt (not a watchdog SUCCESS tick), nothing refreshes the
     * latched INTERNAL override — so the very next recording lands on internal
     * even though the configured SD remounted moments earlier (observed
     * 2026-06: SD back at 09:38:20.435, recording opened on internal 0.3s
     * later). This reconciles that stale latch.
     *
     * <p><b>Conservative by construction — no regression to the override
     * mechanism.</b> The override is redirected ONLY in the exact stale case:
     * the configured recordings volume is external AND it now resolves as
     * available again (the watchdog-FAILURE internal latch is obsolete). In
     * every other case the override is returned unchanged:
     * <ul>
     *   <li>override already equals the live target → unchanged;</li>
     *   <li>configured volume still resolves to internal (genuinely gone) →
     *       unchanged, so we never redirect a recording onto a volume we just
     *       resolved as unavailable;</li>
     *   <li>configured storage is INTERNAL → unchanged (no external latch is
     *       ever pushed in that mode).</li>
     * </ul>
     * Any failure falls back to returning the override as-is.
     *
     * <p>Safe to call at start-of-recording only (no in-flight segment to
     * split): the recorder guards on {@code recording==false} before this.
     *
     * <p><b>Thread-safety.</b> This runs on the recording-start thread while the
     * volume watchdog (a separate thread) and HTTP storage-setter threads mutate
     * the volume state. We (1) snapshot the configured type, per-class dirs and
     * availability flags under {@code recordingsCleanupLock} — the same lock the
     * recordings-category writers ({@code updateActiveDirectories}) take, which
     * gives this reader a happens-before edge to those writes and reads the
     * {availability, dir} pair as a consistent unit rather than a torn mix; and
     * (2) before actually redirecting, re-confirm the external volume is live
     * RIGHT NOW with the cheap fork-free liveness probe. Step (2) is the real
     * safety net: even if the snapshot's availability flag is momentarily stale
     * (card re-pulled since the watchdog last ran), the StatFs+canWrite probe
     * fails on the vanished mount and we keep the protective internal override
     * — so a stale read can never redirect a recording onto a dead SD/USB path.
     *
     * @param override the recorder's currently latched override (may be null)
     * @return the directory the recorder should actually use; {@code override}
     *         unchanged unless it is a confirmed-stale internal fallback AND the
     *         configured external volume is verified live at this instant
     */
    public File reconcileRecordingOverride(File override) {
        if (override == null) return null;
        try {
            // (1) Consistent snapshot under the recordings-category lock.
            final StorageType type;
            final File sdDir, usbDir;
            final boolean sdAvail, usbAvail;
            synchronized (recordingsCleanupLock) {
                type = recordingsStorageType;
                sdDir = sdCardRecordingsDir;
                usbDir = usbRecordingsDir;
                sdAvail = sdCardAvailable;
                usbAvail = usbAvailable;
            }

            // Internal mode never carries an external latch worth reconciling.
            if (type == StorageType.INTERNAL) return override;

            // Resolve the live external dir for the configured type, but only
            // if it currently resolves as available. If it doesn't, the volume
            // is genuinely gone — leave the protective override untouched.
            final File live;
            final boolean liveMounted;
            if (type == StorageType.SD_CARD) {
                if (!sdAvail || sdDir == null) return override;
                live = sdDir;
                liveMounted = isSdCardLikelyMounted();
            } else { // USB
                if (!usbAvail || usbDir == null) return override;
                live = usbDir;
                liveMounted = isUsbLikelyMounted();
            }

            // Already pointed at what we'd pick right now — nothing to do.
            if (live.getAbsolutePath().equals(override.getAbsolutePath())) {
                return override;
            }

            // (2) Ground-truth re-confirm: only redirect onto a volume that is
            // verifiably live at this instant. Neutralizes a stale availability
            // read — a re-pulled card fails this probe and we keep the override.
            if (!liveMounted) return override;

            logInfo("Recorder override reconciled: configured " + type
                + " is available again — redirecting stale override "
                + override.getAbsolutePath() + " → " + live.getAbsolutePath());
            return live;
        } catch (Throwable t) {
            logWarn("reconcileRecordingOverride failed, using override as-is: " + t.getMessage());
        }
        return override;
    }

    /**
     * Emit the canonical "<Category> using <type>: <path>" line. When the
     * resolved type doesn't match what the user configured (external volume
     * missing → fell back to internal), the line includes both so log
     * readers don't have to cross-reference the path against the directory
     * constants to detect a fallback.
     */
    private void logResolvedDir(String label, StorageType configured, ResolvedDir r) {
        if (r.resolved != configured) {
            logInfo(label + " configured=" + configured + " active=" + r.resolved
                + " (fallback): " + r.dir.getAbsolutePath());
        } else {
            logInfo(label + " using " + r.resolved + ": " + r.dir.getAbsolutePath());
        }
    }

    /**
     * Update active directories based on storage type selection.
     * Falls back to internal storage if the selected external volume is not
     * available. Per-category recording-active guard prevents files from
     * being split across volumes when the user changes storage mid-recording.
     */
    private void updateActiveDirectories() {
        // Each per-category lock is taken briefly so a concurrent
        // ensureXxxSpace / sweep / wipe sees an atomic dir swap (not a
        // torn read where one volatile read returns the old dir and a
        // later read in the same call path returns the new one).
        // resolveActive reads internalXxxDir (immutable after init) plus
        // sdCardXxxDir / usbXxxDir / sdCardAvailable / usbAvailable. The
        // external dir + availability fields are NOT immutable — they are
        // (re)assigned/null-cleared by initSdCardDirectories/initUsbDirectories
        // and discoverVolumes on the remount paths. They are declared volatile
        // for cross-thread visibility; we hold the per-category lock here for
        // the assignment fence and so a reader taking the same lock (e.g.
        // reconcileRecordingOverride) sees the {availability, dir} pair
        // consistently rather than a torn mix.

        // Recordings directory
        synchronized (recordingsCleanupLock) {
            if (recordingActive.get()) {
                logDebug("Recording active - skipping recordings directory update");
            } else {
                ResolvedDir r = resolveActive(recordingsStorageType,
                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir, "recordings");
                recordingsDir = r.dir;
                logResolvedDir("Recordings", recordingsStorageType, r);
            }
        }

        // Surveillance directory
        synchronized (surveillanceCleanupLock) {
            if (surveillanceActive.get()) {
                logDebug("Surveillance active - skipping surveillance directory update");
            } else {
                ResolvedDir r = resolveActive(surveillanceStorageType,
                    internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir, "surveillance");
                surveillanceDir = r.dir;
                logResolvedDir("Surveillance", surveillanceStorageType, r);
            }
        }

        // Proximity always uses same storage as surveillance
        synchronized (proximityCleanupLock) {
            if (!surveillanceActive.get()) {
                ResolvedDir r = resolveActive(surveillanceStorageType,
                    internalProximityDir, sdCardProximityDir, usbProximityDir, "proximity");
                proximityDir = r.dir;
                // Proximity follows surveillance silently — no log here, the
                // surveillance line already conveyed the resolution.
            }
        }

        // Trips directory — trip telemetry files are small, no active guard
        synchronized (tripsCleanupLock) {
            ResolvedDir rTrips = resolveActive(tripsStorageType,
                internalTripsDir, sdCardTripsDir, usbTripsDir, "trips");
            tripsDir = rTrips.dir;
            logResolvedDir("Trips", tripsStorageType, rTrips);
        }
    }
    
    /**
     * Load storage limits and storage type from config file.
     */
    private void loadConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                JSONObject storage = config.optJSONObject("storage");
                if (storage != null) {
                    recordingsLimitMb = storage.optLong("recordingsLimitMb", DEFAULT_RECORDINGS_LIMIT_MB);
                    surveillanceLimitMb = storage.optLong("surveillanceLimitMb", DEFAULT_SURVEILLANCE_LIMIT_MB);
                    proximityLimitMb = storage.optLong("proximityLimitMb", DEFAULT_PROXIMITY_LIMIT_MB);
                    tripsLimitMb = storage.optLong("tripsLimitMb", DEFAULT_TRIPS_LIMIT_MB);
                    
                    // Load storage type selection. The configured values are
                    // kept as-is so the watchdog still tries to mount what
                    // the user originally asked for; the runtime "active"
                    // type is reported via getActive*StorageType() and
                    // resolves to INTERNAL when the configured external
                    // volume isn't currently available.
                    recordingsStorageType   = parseStorageType(storage.optString("recordingsStorageType", "INTERNAL"));
                    surveillanceStorageType = parseStorageType(storage.optString("surveillanceStorageType", "INTERNAL"));
                    tripsStorageType        = parseStorageType(storage.optString("tripsStorageType", "INTERNAL"));

                    // Clamp to dynamic max — limit may have been persisted against
                    // a different volume (e.g., user swapped a 128GB SD for a 32GB
                    // one), so re-check against the current effective ceiling.
                    recordingsLimitMb   = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(recordingsStorageType),   recordingsLimitMb));
                    surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), surveillanceLimitMb));
                    proximityLimitMb    = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), proximityLimitMb));
                    tripsLimitMb        = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(tripsStorageType),        tripsLimitMb));
                    
                    logInfo("Loaded storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType + 
                        "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType + 
                        "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
                }
            }
        } catch (Exception e) {
            logWarn("Could not load storage config: " + e.getMessage());
        }
    }

    /**
     * Save storage limits and storage type to config file.
     *
     * <p>Synchronized: the HTTP layer uses a 32-thread pool, so
     * concurrent setters (setRecordingsLimitMb, setSurveillanceStorageType,
     * etc.) can race the read-modify-write cycle below. Without this lock
     * two writers could each read the file, mutate disjoint fields in their
     * own copy, and the second writer's full-file write would clobber the
     * first writer's changes.
     */
    public synchronized void saveConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            JSONObject config;
            
            if (configFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(configFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                config = new JSONObject(sb.toString());
            } else {
                config = new JSONObject();
                config.put("version", 1);
            }
            
            JSONObject storage = config.optJSONObject("storage");
            if (storage == null) {
                storage = new JSONObject();
            }
            storage.put("recordingsLimitMb", recordingsLimitMb);
            storage.put("surveillanceLimitMb", surveillanceLimitMb);
            storage.put("proximityLimitMb", proximityLimitMb);
            storage.put("tripsLimitMb", tripsLimitMb);
            storage.put("recordingsStorageType", recordingsStorageType.name());
            storage.put("surveillanceStorageType", surveillanceStorageType.name());
            storage.put("tripsStorageType", tripsStorageType.name());
            config.put("storage", storage);
            config.put("lastModified", System.currentTimeMillis());
            
            FileWriter writer = new FileWriter(configFile);
            writer.write(config.toString(2));
            writer.close();

            configFile.setReadable(true, false);
            configFile.setWritable(true, false);

            // UnifiedConfigManager has its own in-memory cache of this same
            // file. Without this invalidation, the next updateSection() call
            // would merge into a stale cached config (still holding the OLD
            // storage section) and write it back, silently reverting the
            // SD_CARD/USB selection the user just made.
            try {
                com.overdrive.app.config.UnifiedConfigManager.forceReload();
            } catch (Throwable t) {
                logWarn("UnifiedConfigManager.forceReload() failed: " + t.getMessage());
            }

            logInfo("Saved storage config: recordings=" + recordingsLimitMb + "MB (" + recordingsStorageType +
                "), surveillance=" + surveillanceLimitMb + "MB (" + surveillanceStorageType +
                "), trips=" + tripsLimitMb + "MB (" + tripsStorageType + ")");
        } catch (Exception e) {
            logError("Could not save storage config: " + e.getMessage());
        }
    }
    
    // ==================== Directory Getters ====================
    
    public File getRecordingsDir() {
        return recordingsDir;
    }
    
    public File getSurveillanceDir() {
        return surveillanceDir;
    }
    
    public File getProximityDir() {
        return proximityDir;
    }
    
    public File getTripsDir() {
        return tripsDir;
    }
    
    public String getRecordingsPath() {
        return recordingsDir.getAbsolutePath();
    }
    
    public String getSurveillancePath() {
        return surveillanceDir.getAbsolutePath();
    }
    
    public String getProximityPath() {
        return proximityDir.getAbsolutePath();
    }
    
    public String getTripsPath() {
        return tripsDir.getAbsolutePath();
    }
    
    /**
     * Fix permissions on all storage directories and files.
     * Call this from daemon startup to ensure UI app can read recordings.
     * Note: chmod doesn't work on FUSE - rely on MediaScanner broadcast for cross-UID visibility.
     */
    public void fixAllPermissions() {
        // Fix directory permissions synchronously (fast, no I/O contention)
        File baseDir = new File(INTERNAL_BASE_DIR);
        if (baseDir.exists()) {
            baseDir.setReadable(true, false);
            baseDir.setExecutable(true, false);
        }
        fixDirectoryPermissions(recordingsDir);
        fixDirectoryPermissions(surveillanceDir);
        fixDirectoryPermissions(proximityDir);
        fixDirectoryPermissions(tripsDir);
        
        // Make all existing files world-readable (chmod 666).
        // Required for: (1) UI app (different UID) to read files directly,
        // (2) FUSE layer on BYD Android to allow File.listFiles() to see them.
        // This is fast (no shell processes) — just Java File.setReadable() calls.
        makeFilesReadable(recordingsDir);
        makeFilesReadable(surveillanceDir);
        makeFilesReadable(proximityDir);
        makeFilesReadable(tripsDir);
        
        // SOTA: Incremental MediaScanner broadcast — only broadcast files created
        // since the last successful broadcast. Uses a marker file to track the
        // timestamp of the last full scan. On first run (no marker), broadcasts
        // everything once, then subsequent startups only broadcast new files.
        //
        // Additionally, broadcasts are throttled (50ms between each shell exec)
        // to avoid saturating the I/O bus during camera pipeline startup.
        // The old approach spawned 2 shell processes per file × hundreds of files
        // = hundreds of concurrent process forks competing with the GPU pipeline.
        new Thread(() -> {
            long lastScanTimestamp = loadLastBroadcastTimestamp();
            long scanStartTime = System.currentTimeMillis();
            
            int count = 0;
            count += broadcastFilesSince(recordingsDir, lastScanTimestamp);
            count += broadcastFilesSince(surveillanceDir, lastScanTimestamp);
            count += broadcastFilesSince(proximityDir, lastScanTimestamp);
            
            saveLastBroadcastTimestamp(scanStartTime);
            
            if (count > 0) {
                logInfo("MediaScanner broadcast complete: " + count + " new files indexed");
            } else {
                logDebug("MediaScanner: no new files to broadcast since last scan");
            }
        }, "MediaScannerBroadcast").start();
    }
    
    /** Marker file that stores the epoch millis of the last successful broadcast scan. */
    private static final String BROADCAST_MARKER_FILE = "/data/local/tmp/overdrive_last_mediascan";
    
    /** Throttle delay between individual file broadcasts (ms). */
    private static final long BROADCAST_THROTTLE_MS = 50;
    
    /**
     * Load the timestamp of the last successful MediaScanner broadcast.
     * Returns 0 if no marker exists (first run — will broadcast everything).
     */
    private long loadLastBroadcastTimestamp() {
        try {
            File marker = new File(BROADCAST_MARKER_FILE);
            if (marker.exists()) {
                String content = new java.util.Scanner(marker).useDelimiter("\\A").next().trim();
                return Long.parseLong(content);
            }
        } catch (Exception e) {
            logDebug("No broadcast marker found, will do full scan");
        }
        return 0;
    }
    
    /**
     * Save the timestamp of the current broadcast scan.
     */
    private void saveLastBroadcastTimestamp(long timestamp) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(BROADCAST_MARKER_FILE);
            fw.write(String.valueOf(timestamp));
            fw.close();
        } catch (Exception e) {
            logWarn("Failed to save broadcast marker: " + e.getMessage());
        }
    }
    
    /**
     * Broadcast only files modified after the given timestamp.
     * Throttled to avoid I/O contention with the GPU pipeline.
     * @return number of files broadcast
     */
    private int broadcastFilesSince(File dir, long sinceTimestamp) {
        if (dir == null || !dir.exists()) return 0;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) return 0;
        
        int count = 0;
        for (File f : files) {
            if (f.lastModified() > sinceTimestamp) {
                broadcastFile(f);
                count++;
                
                // Throttle: yield between broadcasts to avoid saturating I/O
                if (count % 5 == 0) {
                    try { Thread.sleep(BROADCAST_THROTTLE_MS); } catch (InterruptedException e) { break; }
                }
            }
        }
        return count;
    }
    
    // ==================== Limit Getters/Setters ====================
    
    public long getRecordingsLimitMb() {
        return recordingsLimitMb;
    }
    
    public long getSurveillanceLimitMb() {
        return surveillanceLimitMb;
    }
    
    public long getProximityLimitMb() {
        return proximityLimitMb;
    }
    
    public long getTripsLimitMb() {
        return tripsLimitMb;
    }
    
    public void setRecordingsLimitMb(long limitMb) {
        recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(recordingsStorageType), limitMb));
        saveConfig();
    }

    public void setSurveillanceLimitMb(long limitMb) {
        surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), limitMb));
        saveConfig();
    }

    public void setProximityLimitMb(long limitMb) {
        proximityLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(surveillanceStorageType), limitMb));
        saveConfig();
    }

    public void setTripsLimitMb(long limitMb) {
        tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(tripsStorageType), limitMb));
        saveConfig();
    }
    
    // ==================== Storage Type Getters/Setters ====================

    /** The user's persisted choice. May not match where files are actually
     *  written if the external volume isn't currently available — see
     *  {@link #getActiveRecordingsStorageType}. */
    public StorageType getRecordingsStorageType() {
        return recordingsStorageType;
    }

    public StorageType getSurveillanceStorageType() {
        return surveillanceStorageType;
    }

    public StorageType getTripsStorageType() {
        return tripsStorageType;
    }

    /**
     * The storage type that recordings are actually being written to right
     * now. Returns INTERNAL if the configured external volume isn't
     * currently mounted. UI should show this (with the configured value as
     * a secondary "you wanted X, currently using Y" hint when they differ).
     */
    public StorageType getActiveRecordingsStorageType() {
        return normalizeStorageType(recordingsStorageType);
    }

    public StorageType getActiveSurveillanceStorageType() {
        return normalizeStorageType(surveillanceStorageType);
    }

    public StorageType getActiveTripsStorageType() {
        return normalizeStorageType(tripsStorageType);
    }
    
    /**
     * Set recordings storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setRecordingsStorageType(StorageType type) {
        // FIX (audit R8, LOW): serialize concurrent setters so a fast
        // double-fire from the web UI / HTTP pool can't interleave
        // recordingsStorageType writes, pendingOutputDirOverride pushes,
        // and saveConfig persistence. configChangeLock is shared with the
        // peer setSurveillance/setTrips methods so cross-category races
        // are also serialized (saveConfig is a single shared file write).
        synchronized (configChangeLock) {
        if (!ensureExternalAvailable(type, "recordings")) return false;

        recordingsStorageType = type;
        // Re-clamp the persisted limit against the new volume's effective max
        // (e.g., user switches from SD to USB, USB is smaller). Limit may
        // need to shrink before updateActiveDirectories runs cleanup.
        recordingsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), recordingsLimitMb));
        updateActiveDirectories();
        saveConfig();
        logInfo("Recordings storage type set to: " + type);

        // Re-arm the volume watchdog so a transition INTERNAL → SD/USB during
        // ACC=ON brings up the per-minute remount loop, and SD/USB → INTERNAL
        // tears it down when no longer needed. startSdCardWatchdog is
        // idempotent and self-gating (returns early when no category is on
        // an external volume), so we can call it unconditionally here.
        try {
            startSdCardWatchdog();
            logInfo("setRecordingsStorageType: volume watchdog re-armed for type=" + type);
        } catch (Throwable t) {
            logWarn("setRecordingsStorageType: could not re-arm volume watchdog: " + t.getMessage());
        }

        // Push the new recordings dir into the live pano recorder so an
        // in-progress CONTINUOUS / DRIVE_MODE session lands future segments
        // on the freshly selected volume. Without this, the recorder keeps
        // writing to the dir captured at startRecording time until the next
        // mode toggle or hot remount cycle (watchdog at line ~4229 only
        // covers unmount/remount, not user-initiated type swaps).
        //
        // Bypass getRecordingsDir() / the volatile field: when a recording is
        // active, updateActiveDirectories SKIPS the recordingsDir swap (per
        // the active-recording guard at line ~1658), so the field still
        // points at the OLD volume. Resolve the new dir directly from the
        // freshly-set type so the user's choice actually reaches the
        // recorder override even mid-session.
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.getRecorder() != null) {
                ResolvedDir r = resolveActive(type,
                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir, "recordings");
                java.io.File newRecordingsDir = r.dir;
                pipeline.getRecorder().setOutputDir(newRecordingsDir);
                logInfo("setRecordingsStorageType: pano recorder output dir updated to "
                    + (newRecordingsDir != null ? newRecordingsDir.getAbsolutePath() : "null")
                    + " (resolved=" + r.resolved
                    + "; in-flight segment keeps prior path; future segments use new dir)");
                // FIX (audit R4): if a CONTINUOUS / DRIVE_MODE session is
                // mid-flight, the encoder's segmentBasePath was latched at
                // startRecording time and segment rotations stay on the OLD
                // volume until the next mode toggle. Force a stopRecording on
                // the wrapper so the listener bridge clears recordingActive,
                // then RMM's wedge ticker (or the next activateMode call)
                // performs a fresh startRecording that picks up the new
                // pendingOutputDirOverride. We lose ≈ one segment-second but
                // the user's storage-type choice takes effect within seconds
                // instead of waiting for the next ACC cycle.
                try {
                    if (pipeline.getRecorder().isRecording()) {
                        logWarn("setRecordingsStorageType: recording active, "
                            + "forcing pipeline.stopRecording so override applies on next start");
                        // FIX (audit R7, HIGH): use pipeline.stopRecording() not
                        // recorder.stopRecording(). The wrapper-only stop leaves
                        // pipeline.currentMode=NORMAL_RECORDING + recordingMode=true,
                        // which makes RMM.runActivateGuarded short-circuit on
                        // pipeline.isNormalRecordingMode() and never re-issue
                        // pipeline.startRecording() — pendingOutputDirOverride
                        // is never consumed, recording is silently lost until
                        // next ACC cycle. pipeline.stopRecording() additionally
                        // clears recordingMode, currentMode=IDLE, and
                        // pendingRecordingDir/Prefix so the next activateMode
                        // is allowed and consumes the new override.
                        pipeline.stopRecording();
                        // FIX (audit R5, MEDIUM): kick RMM to re-evaluate mode
                        // immediately so the next startRecording fires within
                        // a tick instead of waiting for the wedge ticker / next
                        // ACC cycle. resyncFromHardware reads currentMode +
                        // accIsOn fresh and re-issues activateMode → which
                        // consumes the just-pushed setOutputDir override.
                        // Caller is HTTP / settings-save thread; resyncFromHardware
                        // dispatches to its own executor and returns quickly.
                        try {
                            com.overdrive.app.recording.RecordingModeManager rmm =
                                com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                            if (rmm != null) {
                                rmm.resyncFromHardware("storage-type-switch-recordings");
                                logInfo("setRecordingsStorageType: kicked RMM "
                                    + "resyncFromHardware to re-arm recording on new volume");
                            }
                        } catch (Throwable rt) {
                            logWarn("setRecordingsStorageType: RMM resync kick threw: "
                                + rt.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    logWarn("setRecordingsStorageType: stopRecording for re-segment threw: "
                        + t.getMessage());
                }
            } else {
                logInfo("setRecordingsStorageType: no live pano recorder, recorder dir push skipped");
            }
        } catch (Throwable t) {
            logWarn("setRecordingsStorageType: could not push recorder dir: " + t.getMessage());
        }

        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }

        // FIX (audit R8, MEDIUM): re-arm RecordingsIndex against the new
        // volume's recordings dir + reconcile so events.html / native
        // fragment lists stop showing the OLD volume's clips. Without
        // this, FileObservers continue watching the old dir set and new
        // cam_*.mp4 segments on the new volume don't fire events — UI
        // shows stale data until the 1-hour periodic reconcile. Mirrors
        // the existing notifyRecordingsIndexOfStorageChange calls in the
        // SD/USB watchdog success branches (lines 4454, 4677).
        try {
            notifyRecordingsIndexOfStorageChange("set-recordings-storage-type");
            logInfo("setRecordingsStorageType: re-armed RecordingsIndex for type=" + type);
        } catch (Throwable t) {
            logWarn("setRecordingsStorageType: RecordingsIndex re-arm failed: " + t.getMessage());
        }
        return true;
        } // end synchronized(configChangeLock) — FIX audit R8 LOW
    }

    /**
     * Set surveillance storage type (INTERNAL or SD_CARD).
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setSurveillanceStorageType(StorageType type) {
        // FIX (audit R8, LOW): peer setter — share configChangeLock with
        // setRecordingsStorageType / setTripsStorageType.
        synchronized (configChangeLock) {
        if (!ensureExternalAvailable(type, "surveillance")) return false;

        surveillanceStorageType = type;
        surveillanceLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), surveillanceLimitMb));
        proximityLimitMb    = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), proximityLimitMb));
        updateActiveDirectories();
        saveConfig();
        logInfo("Surveillance storage type set to: " + type);

        // Re-arm the volume watchdog: a transition INTERNAL → SD/USB during
        // ACC=ON must bring up the remount loop so a transient unmount during
        // the drive (kernel hiccup, FUSE bridge reset) is recovered before
        // the next ACC cycle. Idempotent + self-gating.
        try {
            startSdCardWatchdog();
            logInfo("setSurveillanceStorageType: volume watchdog re-armed for type=" + type);
        } catch (Throwable t) {
            logWarn("setSurveillanceStorageType: could not re-arm volume watchdog: " + t.getMessage());
        }

        if (type == StorageType.SD_CARD) {
            autoEnableCdrCleanup();
        }

        // FIX (audit R8, MEDIUM): symmetric with setRecordingsStorageType.
        // Recordings live alongside surveillance events on the same volume
        // family; a surveillance-side type swap may shift the recordings
        // dir indirectly (when both share a volume) or alter availability.
        // Re-arm the index defensively. Best-effort.
        try {
            notifyRecordingsIndexOfStorageChange("set-surveillance-storage-type");
            logInfo("setSurveillanceStorageType: re-armed RecordingsIndex for type=" + type);
        } catch (Throwable t) {
            logWarn("setSurveillanceStorageType: RecordingsIndex re-arm failed: " + t.getMessage());
        }
        return true;
        } // end synchronized(configChangeLock) — FIX audit R8 LOW
    }

    /**
     * Set trips storage type (INTERNAL or SD_CARD).
     * Does NOT call autoEnableCdrCleanup() — trip files are small and don't compete with BYD dashcam space.
     * @param type The storage type to use
     * @return true if successfully changed, false if SD card not available
     */
    public boolean setTripsStorageType(StorageType type) {
        // FIX (audit R8, LOW): peer setter — share configChangeLock with
        // setRecordingsStorageType / setSurveillanceStorageType.
        synchronized (configChangeLock) {
        if (!ensureExternalAvailable(type, "trips")) return false;

        tripsStorageType = type;
        tripsLimitMb = Math.max(MIN_LIMIT_MB, Math.min(getEffectiveMaxLimitMb(type), tripsLimitMb));
        updateActiveDirectories();
        saveConfig();
        logInfo("Trips storage type set to: " + type);

        // Re-arm the volume watchdog so a trips-only external choice still
        // gets remount coverage during ACC=ON. Idempotent + self-gating.
        try {
            startSdCardWatchdog();
            logInfo("setTripsStorageType: volume watchdog re-armed for type=" + type);
        } catch (Throwable t) {
            logWarn("setTripsStorageType: could not re-arm volume watchdog: " + t.getMessage());
        }

        // FIX (audit R8, MEDIUM): symmetric with setRecordingsStorageType.
        // Trips don't live in the recordings tree directly, but a type
        // swap that changes mount availability can affect peer dirs;
        // refresh the index so derived availability views stay coherent.
        try {
            notifyRecordingsIndexOfStorageChange("set-trips-storage-type");
            logInfo("setTripsStorageType: re-armed RecordingsIndex for type=" + type);
        } catch (Throwable t) {
            logWarn("setTripsStorageType: RecordingsIndex re-arm failed: " + t.getMessage());
        }
        return true;
        } // end synchronized(configChangeLock) — FIX audit R8 LOW
    }

    /**
     * Helper: ensure the requested external volume is available before we
     * accept a storage-type change. INTERNAL is always OK. SD/USB get a
     * mount attempt; refusing the change is preferable to silently writing
     * to internal under a label that says "SD card".
     */
    private boolean ensureExternalAvailable(StorageType type, String label) {
        if (type == StorageType.SD_CARD) {
            if (sdCardAvailable) return true;
            logInfo("SD card not available, attempting to mount for " + label + "...");
            if (!ensureSdCardMounted(true)) {
                logWarn("Cannot set " + label + " to SD card - mount failed");
                return false;
            }
            return true;
        }
        if (type == StorageType.USB) {
            if (usbAvailable) return true;
            logInfo("USB not available, attempting to mount for " + label + "...");
            if (!ensureUsbMounted(true)) {
                logWarn("Cannot set " + label + " to USB - mount failed");
                return false;
            }
            return true;
        }
        return true;  // INTERNAL
    }
    
    /**
     * SOTA: Auto-enable CDR (BYD dashcam) cleanup when Overdrive uses SD card.
     * This ensures Overdrive always has space by cleaning up old dashcam files.
     */
    private void autoEnableCdrCleanup() {
        try {
            ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
            if (!cleaner.isEnabled() && cleaner.isSdCardAvailable()) {
                // Calculate recommended reserved space based on our limits
                long totalNeeded = 0;
                if (recordingsStorageType == StorageType.SD_CARD) {
                    totalNeeded += recordingsLimitMb;
                }
                if (surveillanceStorageType == StorageType.SD_CARD) {
                    totalNeeded += surveillanceLimitMb;
                }
                // Add 20% buffer
                long reservedMb = Math.max(2048, (long)(totalNeeded * 1.2));
                
                cleaner.setReservedSpaceMb(reservedMb);
                cleaner.setEnabled(true);
                logInfo("Auto-enabled CDR cleanup with " + reservedMb + "MB reserved for Overdrive");
            }
        } catch (Exception e) {
            logWarn("Could not auto-enable CDR cleanup: " + e.getMessage());
        }
    }
    
    // ==================== Volume Info ====================

    public boolean isSdCardAvailable() {
        return sdCardAvailable;
    }

    public String getSdCardPath() {
        return sdCardPath;
    }

    public boolean isUsbAvailable() {
        return usbAvailable;
    }

    public String getUsbPath() {
        return usbPath;
    }

    /**
     * Re-detect both SD and USB. Public alias mostly used by polling
     * watchdogs / API handlers that want to refresh state on demand.
     */
    public void refreshUsb() {
        discoverVolumes();
        initSdCardDirectories();
        initUsbDirectories();
        updateActiveDirectories();
        logInfo("Volume refresh complete. SD=" + sdCardAvailable + ", USB=" + usbAvailable);
    }
    
    // ==================== All Storage Locations (for scanning) ====================
    
    /**
     * Get ALL directories that may contain recordings of a given type.
     * Returns the active (configured) directory first, then any alternate locations
     * where files may exist (e.g., internal when SD card is active, or vice versa).
     * 
     * This is the single source of truth for multi-location scanning.
     * Callers should iterate all returned directories to find all files.
     */
    public List<File> getAllRecordingsDirs() {
        return getAllDirsForType(recordingsDir, internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir);
    }

    public List<File> getAllSurveillanceDirs() {
        return getAllDirsForType(surveillanceDir, internalSurveillanceDir, sdCardSurveillanceDir, usbSurveillanceDir);
    }

    public List<File> getAllProximityDirs() {
        return getAllDirsForType(proximityDir, internalProximityDir, sdCardProximityDir, usbProximityDir);
    }

    public List<File> getAllTripsDirs() {
        return getAllDirsForType(tripsDir, internalTripsDir, sdCardTripsDir, usbTripsDir);
    }

    /**
     * Same as {@link #getAllSurveillanceDirs()} et al, but additionally
     * includes legacy app-files locations from older app versions where
     * stale media may still be living and counting toward the limit.
     *
     * Used by both the size accounting and the cleanup reaper so the two
     * agree about what "the surveillance pool" actually is — otherwise
     * the UI can show 800 MB used against a 500 MB limit while cleanup
     * (which only saw the active dir) thinks everything is fine.
     *
     * Includes the flat legacy base ({@link #LEGACY_APP_FILES_DIR}) when a
     * non-null filename prefix is supplied via {@link #namePrefixForCategory},
     * because the flat base is shared across categories and only files
     * matching the category's prefix should be touched.
     */
    private List<File> getReapableDirs(String category) {
        List<File> dirs;
        String legacyPath = null;
        boolean includeFlatBase = false;
        switch (category) {
            case "recordings":
                dirs = new ArrayList<>(getAllRecordingsDirs());
                legacyPath = LEGACY_APP_FILES_DIR + "/recordings";
                includeFlatBase = true;  // some old installs wrote cam_* into <base>
                break;
            case "surveillance":
                dirs = new ArrayList<>(getAllSurveillanceDirs());
                legacyPath = LEGACY_SURVEILLANCE_DIR;
                break;
            case "proximity":
                dirs = new ArrayList<>(getAllProximityDirs());
                legacyPath = LEGACY_APP_FILES_DIR + "/proximity_events";
                break;
            case "trips":
                dirs = new ArrayList<>(getAllTripsDirs());
                break;
            default:
                return new ArrayList<>();
        }
        if (legacyPath != null) {
            addDirIfMissing(dirs, new File(legacyPath));
        }
        if (includeFlatBase) {
            addDirIfMissing(dirs, new File(LEGACY_APP_FILES_DIR));
        }
        return dirs;
    }

    private static void addDirIfMissing(List<File> dirs, File candidate) {
        if (candidate == null || !candidate.exists() || !candidate.isDirectory()) return;
        String path = candidate.getAbsolutePath();
        for (File d : dirs) {
            if (d != null && d.getAbsolutePath().equals(path)) return;
        }
        dirs.add(candidate);
    }

    /**
     * Filename prefix that identifies media belonging to {@code category}.
     * When non-null, callers that scan multi-category directories (the
     * flat legacy base) should restrict to filenames starting with this
     * prefix so they don't reap a sibling category's files. Returns null
     * for categories whose dirs are all category-dedicated.
     */
    private static String namePrefixForCategory(String category) {
        switch (category) {
            case "recordings":  return "cam";        // cam_*, cam2_*, …
            case "surveillance": return "event_";
            case "proximity":   return "proximity_";
            default: return null;
        }
    }

    /**
     * Auxiliary filename prefixes that don't share a stem with the anchor but
     * still belong to {@code category} and must be reaped to keep the limit
     * honest. Used by the orphan-sidecar pass — these files are matched as
     * sidecars whose anchor stem is parsed by stripping the auxiliary prefix.
     *
     * <p>Surveillance: per-actor thumbnails are written as
     * {@code thumb_event_<base>_a<id>_<rel>.jpg} (SurveillanceEngineGpu:4823).
     * They don't start with {@code event_}, so the standard prefix gate
     * filters them out — without this auxiliary entry they accumulate
     * untracked.
     */
    private static String[] auxiliaryPrefixesForCategory(String category) {
        switch (category) {
            // OEM Dashcam clips share the recordings directory with cam_*.
            // The primary prefix gate is "cam" (line 2136), so dvr_*.mp4
            // would be invisible to size accounting and the reaper without
            // this auxiliary entry — the SD card would fill silently.
            case "recordings":   return new String[]{"dvr_"};
            // Per-actor JPGs are named `thumb_<anchorStem>_a<id>_<rel>.jpg`,
            // where anchorStem already includes the `event_` prefix
            // (SurveillanceEngineGpu:4815-4824 derives tmpBase from the
            // segment basename minus ".mp4"). The aux prefix must be just
            // `thumb_` — anything longer would double-count `event_` and
            // miss every actual file.
            case "surveillance": return new String[]{"thumb_"};
            default:             return new String[]{};
        }
    }

    /**
     * Returns true if {@code name} matches the category's primary prefix or
     * any auxiliary prefix. Centralizes the prefix gate so size accounting,
     * the anchor reaper, and the orphan-sidecar pass agree on which files
     * belong to a category.
     */
    private static boolean nameMatchesCategoryPrefix(String name, String primaryPrefix,
                                                     String[] auxPrefixes) {
        if (primaryPrefix != null && name.startsWith(primaryPrefix)) return true;
        for (String aux : auxPrefixes) {
            if (name.startsWith(aux)) return true;
        }
        return false;
    }

    /**
     * Sum primary + sidecar files across the given dirs, deduplicating by
     * filename (so a clip mirrored on internal + SD-card isn't counted twice).
     * Must match what {@link #ensureSpace} actually frees, otherwise the UI
     * reports usage past the limit while cleanup believes it's fine.
     *
     * @param category   Category key — recordings/surveillance/proximity/trips.
     * @param namePrefix If non-null, only files whose name starts with
     *                   this prefix are summed. Used when the dir set
     *                   includes the flat legacy base shared across
     *                   categories.
     */
    private long getDirectoriesTotalSize(String category, List<File> dirs, String namePrefix) {
        String primaryExt = primaryExtensionForCategory(category);
        String[] sidecarExts = sidecarExtensionsForCategory(category);
        String[] partialExts = partialExtensionsForCategory(category);
        String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        long size = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) {
                files = listFilesViaShell(dir);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null
                        && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                boolean isPrimary = name.endsWith(primaryExt);
                boolean isSidecar = false;
                boolean isPartial = false;
                if (!isPrimary) {
                    for (String ext : sidecarExts) {
                        if (name.endsWith(ext)) { isSidecar = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar) {
                    for (String ext : partialExts) {
                        if (name.endsWith(ext)) { isPartial = true; break; }
                    }
                }
                if (!isPrimary && !isSidecar && !isPartial) continue;
                if (!seen.add(name)) continue;
                size += f.length();
            }
        }
        return size;
    }
    
    /**
     * Build a deduplicated list of directories: active first, then alternates.
     * Skips null entries and directories that match the active one.
     */
    private List<File> getAllDirsForType(File activeDir, File internalDir, File sdCardDir, File usbDir) {
        List<File> dirs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (activeDir != null) {
            dirs.add(activeDir);
            seen.add(activeDir.getAbsolutePath());
        }
        if (internalDir != null && !seen.contains(internalDir.getAbsolutePath())) {
            dirs.add(internalDir);
            seen.add(internalDir.getAbsolutePath());
        }
        if (sdCardDir != null && !seen.contains(sdCardDir.getAbsolutePath())) {
            dirs.add(sdCardDir);
            seen.add(sdCardDir.getAbsolutePath());
        }
        if (usbDir != null && !seen.contains(usbDir.getAbsolutePath())) {
            dirs.add(usbDir);
            seen.add(usbDir.getAbsolutePath());
        }
        return dirs;
    }
    
    /**
     * Get available space on SD card in bytes.
     */
    public long getSdCardFreeSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                logDebug("SD card path not accessible: " + sdCardPath);
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get SD card free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on SD card in bytes.
     */
    public long getSdCardTotalSpace() {
        if (sdCardPath == null) return 0;
        try {
            // Verify path exists before using StatFs
            File sdDir = new File(sdCardPath);
            if (!sdDir.exists() || !sdDir.isDirectory()) {
                return 0;
            }
            StatFs stat = new StatFs(sdCardPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get available space on internal storage in bytes.
     */
    public long getInternalFreeSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get internal free space: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get total space on internal storage in bytes.
     */
    public long getInternalTotalSpace() {
        try {
            StatFs stat = new StatFs(INTERNAL_BASE_DIR);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Refresh SD card AND USB detection and update directories.
     * Call this when either volume may have been inserted/removed.
     * (Kept under the historical name for callers that still reference it.)
     */
    public void refreshSdCard() {
        discoverVolumes();
        initSdCardDirectories();
        initUsbDirectories();
        updateActiveDirectories();
        logInfo("Volume refresh complete. SD=" + sdCardAvailable + ", USB=" + usbAvailable);
    }

    /**
     * Get available space on USB drive in bytes.
     */
    public long getUsbFreeSpace() {
        if (usbPath == null) return 0;
        try {
            File d = new File(usbPath);
            if (!d.exists() || !d.isDirectory()) return 0;
            StatFs stat = new StatFs(usbPath);
            return stat.getAvailableBytes();
        } catch (Exception e) {
            logWarn("Could not get USB free space: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Get total space on USB drive in bytes.
     */
    public long getUsbTotalSpace() {
        if (usbPath == null) return 0;
        try {
            File d = new File(usbPath);
            if (!d.exists() || !d.isDirectory()) return 0;
            StatFs stat = new StatFs(usbPath);
            return stat.getTotalBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Effective max-limit ceiling in MB for the requested storage type.
     *
     * Pulled live from StatFs each call so card swaps and capacity changes
     * reflect immediately in the slider — but capped per-category at
     * PER_CATEGORY_SHARE of the volume so the four categories sharing a
     * single FS can't overcommit it 4x.
     *
     * When the requested SD/USB volume is unmounted, falls back to the
     * INTERNAL ceiling rather than the absurd MAX_LIMIT_MB_FALLBACK
     * sentinel — the runtime fall-back path lands writes on internal,
     * so capping at internal's true total stops the user from persisting
     * a 100GB limit against a missing 32GB stick. INTERNAL itself
     * returning <=0 (StatFs literally unreadable) keeps the sentinel.
     */
    public long getEffectiveMaxLimitMb(StorageType type) {
        long totalBytes;
        switch (type) {
            case SD_CARD: totalBytes = sdCardAvailable ? getSdCardTotalSpace() : 0; break;
            case USB:     totalBytes = usbAvailable    ? getUsbTotalSpace()    : 0; break;
            case INTERNAL:
            default:      totalBytes = getInternalTotalSpace(); break;
        }
        if (totalBytes <= 0) {
            if (type == StorageType.INTERNAL) return MAX_LIMIT_MB_FALLBACK;
            // Unmounted SD/USB: clamp to internal volume's ceiling so a save
            // while the volume is missing can't persist a value larger than
            // the fallback target can ever hold.
            long internalBytes = getInternalTotalSpace();
            if (internalBytes <= 0) return MAX_LIMIT_MB_FALLBACK;
            long internalUsableMb = (internalBytes / 1024L / 1024L) - VOLUME_HEADROOM_MB;
            if (internalUsableMb <= 0) return MIN_LIMIT_MB;
            return Math.max(MIN_LIMIT_MB, (long)(internalUsableMb * PER_CATEGORY_SHARE));
        }

        long usableMb = (totalBytes / 1024L / 1024L) - VOLUME_HEADROOM_MB;
        if (usableMb <= 0) return MIN_LIMIT_MB;
        long perCategoryMb = (long)(usableMb * PER_CATEGORY_SHARE);
        return Math.max(MIN_LIMIT_MB, perCategoryMb);
    }

    /**
     * Backwards-compatible: returns the dynamic max for the given type.
     * Old callers that passed a {@link StorageType} keep working.
     */
    public long getMaxLimitMb(StorageType type) {
        return getEffectiveMaxLimitMb(type);
    }
    
    // ==================== Storage Stats ====================
    
    /**
     * Get current size of recordings across all locations (active dir, the
     * inactive internal/SD-card mirror, and legacy app-files paths).
     *
     * Must match the dirs the cleanup actually reaps — otherwise the UI can
     * report 800 MB used while the limit is 500 MB and cleanup never fires.
     */
    public long getRecordingsSize() {
        return getDirectoriesTotalSize("recordings", getReapableDirs("recordings"), namePrefixForCategory("recordings"));
    }

    /**
     * Get current size of surveillance across all locations (active dir, the
     * inactive internal/SD-card mirror, and the legacy sentry_events path).
     */
    public long getSurveillanceSize() {
        return getDirectoriesTotalSize("surveillance", getReapableDirs("surveillance"), namePrefixForCategory("surveillance"));
    }

    /**
     * Get current size of proximity across all locations (active dir, the
     * inactive internal/SD-card mirror, and the legacy proximity_events path).
     */
    public long getProximitySize() {
        return getDirectoriesTotalSize("proximity", getReapableDirs("proximity"), namePrefixForCategory("proximity"));
    }
    
    /**
     * Get recordings file count across all locations (active + inactive
     * mirror + legacy). Matches the size accounting so per-file averages
     * line up with reported totals.
     */
    public int getRecordingsCount() {
        return getFileCountAcross("recordings", getReapableDirs("recordings"), namePrefixForCategory("recordings"));
    }

    /**
     * Get surveillance events file count across all locations.
     */
    public int getSurveillanceCount() {
        return getFileCountAcross("surveillance", getReapableDirs("surveillance"), namePrefixForCategory("surveillance"));
    }

    /**
     * Get proximity events file count across all locations.
     */
    public int getProximityCount() {
        return getFileCountAcross("proximity", getReapableDirs("proximity"), namePrefixForCategory("proximity"));
    }

    private int getFileCountAcross(String category, List<File> dirs, String namePrefix) {
        String primaryExt = primaryExtensionForCategory(category);
        int total = 0;
        Set<String> seen = new HashSet<>();
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
            if (files == null) {
                files = listFilesByExt(dir, primaryExt);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null && !name.startsWith(namePrefix)) continue;
                if (seen.add(name)) {
                    total++;
                }
            }
        }
        return total;
    }
    
    /**
     * Get current size of trips directory in bytes.
     *
     * <p>Prefers the DB-backed aggregate ({@code size_bytes + sidecar_size_bytes}
     * column sum) once the one-shot backfill has populated every legacy row.
     * On full-storage / FUSE-mounted SD cards the legacy filesystem walk took
     * 10-20 minutes — the storage card on trips.html would block until that
     * completed. The DB query is sub-millisecond.
     *
     * <p>While backfill is still running (or if the trip analytics manager
     * isn't initialised yet), falls back to the legacy walk wrapped in a 30s
     * in-memory cache so concurrent storage-card requests during the
     * backfill window don't pile up. Once {@code isBackfillComplete()} flips
     * to true, the DB path takes over and the cache path is unreachable.
     */
    public long getTripsSize() {
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                com.overdrive.app.trips.TripDatabase db = tam.getDatabase();
                if (db != null && db.isBackfillComplete()) {
                    return getTripsSizeFromDbCached(db);
                }
            }
        } catch (Throwable t) {
            // Fall through to direct walk
        }
        // Fallback: legacy walk + 30s in-memory cache so concurrent requests
        // during the backfill window don't pile up. Once backfill completes,
        // the DB path takes over and this is unreachable.
        return getTripsSizeWithCache();
    }

    private long cachedTripsSize = -1;
    private long cachedTripsSizeAt = 0;
    private static final long TRIPS_SIZE_CACHE_MS = 30_000;

    // Short TTL cache for the DB-backed SUM(size_bytes+sidecar_size_bytes)
    // aggregate. The query itself is sub-ms but it serializes on
    // TripDatabase's monitor; without this cache, repeated ensureTripsSpace
    // calls under tripsCleanupLock would each pay the round-trip while
    // holding the lock, deferring peer cleanup. 5s is short enough that
    // storage bookkeeping stays accurate but long enough to coalesce bursts.
    private long cachedTripsDbSize = -1;
    private long cachedTripsDbSizeAt = 0;
    private static final long TRIPS_DB_SIZE_CACHE_MS = 5_000;

    private synchronized long getTripsSizeFromDbCached(
            com.overdrive.app.trips.TripDatabase db) {
        long now = System.currentTimeMillis();
        if (cachedTripsDbSize >= 0 && (now - cachedTripsDbSizeAt) < TRIPS_DB_SIZE_CACHE_MS) {
            return cachedTripsDbSize;
        }
        long size = db.getTotalSizeBytes();
        cachedTripsDbSize = size;
        cachedTripsDbSizeAt = now;
        return size;
    }

    private synchronized long getTripsSizeWithCache() {
        long now = System.currentTimeMillis();
        if (cachedTripsSize >= 0 && (now - cachedTripsSizeAt) < TRIPS_SIZE_CACHE_MS) {
            return cachedTripsSize;
        }
        long size = getDirectoriesTotalSize("trips", getReapableDirs("trips"), namePrefixForCategory("trips"));
        cachedTripsSize = size;
        cachedTripsSizeAt = now;
        return size;
    }

    /**
     * Get trips file count.
     *
     * <p>Same DB-vs-walk pattern as {@link #getTripsSize()}: prefer
     * {@code TripDatabase.getTripCount()} once backfill is complete (a
     * one-row aggregate against the indexed trips table), fall back to the
     * filesystem walk wrapped in a 30s in-memory cache while backfill is
     * still running.
     */
    public int getTripsCount() {
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                com.overdrive.app.trips.TripDatabase db = tam.getDatabase();
                if (db != null && db.isBackfillComplete()) {
                    return db.getTripCount();
                }
            }
        } catch (Throwable t) {
            // Fall through to direct walk
        }
        return getTripsCountWithCache();
    }

    private int cachedTripsCount = -1;
    private long cachedTripsCountAt = 0;

    private synchronized int getTripsCountWithCache() {
        long now = System.currentTimeMillis();
        if (cachedTripsCount >= 0 && (now - cachedTripsCountAt) < TRIPS_SIZE_CACHE_MS) {
            return cachedTripsCount;
        }
        int count = getFileCountAcross("trips", getReapableDirs("trips"), namePrefixForCategory("trips"));
        cachedTripsCount = count;
        cachedTripsCountAt = now;
        return count;
    }

    /**
     * SOTA: List files via shell command when direct access fails.
     * This handles the case where UI app owns the directory but daemon needs to list files.
     * Returns every file in the directory regardless of extension.
     */
    private File[] listFilesViaShell(File dir) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"ls", dir.getAbsolutePath()});
            final Process proc = p;
            final java.util.List<File> files =
                java.util.Collections.synchronizedList(new java.util.ArrayList<File>());

            // Run the stdout drain on a daemon thread so we can bound the WHOLE
            // read, not just the post-EOF wait. A bare readLine() loop blocks
            // indefinitely if `ls` hangs mid-write on a bad FUSE-bridged volume
            // (a real fault on these head-units) — the previous waitForBounded
            // here only fired AFTER stdout EOF, so a mid-write stat hang was
            // still unbounded. We give the drain a hard deadline and
            // destroyForcibly() the child on timeout (which unblocks the
            // reader's readLine with EOF). Conservative on timeout: we return
            // whatever lines were drained before the deadline — under-counting
            // self-corrects on the next reap/ticker pass; we never delete a file
            // we didn't fully see.
            Thread drain = new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;
                        files.add(new File(dir, line));
                    }
                } catch (Exception ignored) {
                    // Stream closed by destroyForcibly on timeout, or read error
                    // — return whatever we have.
                }
            }, "listFilesViaShell-drain");
            drain.setDaemon(true);
            drain.start();
            drain.join(4_000);
            if (drain.isAlive()) {
                logWarn("listFilesViaShell(" + dir.getName() + "): `ls` drain exceeded 4s"
                    + " — killing child and returning partial list (" + files.size() + " so far)");
                p.destroyForcibly();
                // Give the kernel a moment to close the stream so the drain
                // thread unblocks and stops touching `files` before we snapshot.
                drain.join(500);
            } else {
                // Drain finished; reap the (now-exited or about-to-exit) child.
                waitForBounded(p, 1_000, "listFilesViaShell(" + dir.getName() + ")");
            }

            File[] snapshot;
            synchronized (files) {
                snapshot = files.toArray(new File[0]);
            }
            logDebug("listFilesViaShell: found " + snapshot.length + " files in " + dir.getName());
            return snapshot;
        } catch (Exception e) {
            logWarn("listFilesViaShell failed: " + e.getMessage());
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return new File[0];
        }
    }

    /**
     * Same as {@link #listFilesViaShell(File)} but filtered to a specific
     * extension. Used by the cleanup path so the anchor-collection step
     * picks up the right primary file type for each category.
     */
    private File[] listFilesByExt(File dir, String ext) {
        File[] all = listFilesViaShell(dir);
        if (all == null || all.length == 0) return all;
        java.util.List<File> matched = new java.util.ArrayList<>();
        for (File f : all) {
            if (f.getName().endsWith(ext)) matched.add(f);
        }
        return matched.toArray(new File[0]);
    }

    /**
     * Public mp4-aware listing with the FUSE shell fallback. Mirrors what
     * the legacy {@code RecordingsApiHandler.scanDirectory} did before the
     * SOTA index rewrite — direct {@code listFiles()} returns null on SD
     * card / USB FUSE mounts under daemon UID 2000, and silently dropping
     * that directory leaves the recordings index empty.
     *
     * <p>Used by {@link com.overdrive.app.server.RecordingsIndex} during
     * warmup + reconcile, and by the API handler's {@code hasAnyMp4OnDisk}
     * probe. Returns an empty array (never null) so callers don't need to
     * null-check.
     */
    public File[] listMp4Files(File dir) {
        return listFilesWithFallback(dir, ".mp4");
    }

    /**
     * Generic FUSE-fallback listing — same shell-ls fallback as
     * {@link #listMp4Files(File)} but for any single suffix or
     * prefix+suffix combination. Used by sidecar-cleanup paths that
     * sweep per-actor thumbs ({@code thumb_<base>_a*.jpg}) and by
     * {@code dir.listFiles(filter)} sites that would otherwise swallow
     * the SD-card / USB null-listing case.
     *
     * <p>Pass null for {@code prefix} to filter on suffix only.
     * Returns an empty array (never null).
     */
    public File[] listFilesWithFallback(File dir, String suffix) {
        return listFilesWithFallback(dir, null, suffix);
    }

    public File[] listFilesWithFallback(File dir, String prefix, String suffix) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || !dir.canRead()) {
            return new File[0];
        }
        java.io.FileFilter filter = f -> {
            String n = f.getName();
            if (suffix != null && !n.endsWith(suffix)) return false;
            if (prefix != null && !n.startsWith(prefix)) return false;
            return true;
        };
        File[] files = dir.listFiles(filter);
        if (files == null) {
            // FUSE returned null — shell ls then filter in-process. The
            // listFilesViaShell path doesn't take a filter so we apply it
            // ourselves on the returned array.
            File[] all = listFilesViaShell(dir);
            if (all == null || all.length == 0) return new File[0];
            java.util.List<File> matched = new java.util.ArrayList<>();
            for (File f : all) {
                if (filter.accept(f)) matched.add(f);
            }
            files = matched.toArray(new File[0]);
        }
        return files != null ? files : new File[0];
    }

    /**
     * Notify {@link com.overdrive.app.server.RecordingsIndex} that the
     * active recordings/surveillance/proximity dir set has changed —
     * either via user-driven storage-type switch (settings page) or via
     * volume hot-plug detected by the SD/USB watchdogs.
     *
     * <p>Two-step recovery:
     *  1. Re-arm FileObservers against the new dir set so future writes
     *     reach the index.
     *  2. Reconcile so existing files on the new volume populate the
     *     index immediately. Without this, hot-mounted SD/USB sticks
     *     stay invisible to events.html and the native fragment until
     *     the 1-hour periodic reconcile.
     *
     * <p>Step 2 runs on a background thread so we don't block the
     * caller (the SD/USB watchdog tick is on a single-thread executor;
     * blocking it would delay the next health probe).
     *
     * <p>Best-effort: any failure here is logged and swallowed. The
     * periodic reconcile is the absolute backstop.
     */
    private void notifyRecordingsIndexOfStorageChange(String reason) {
        try {
            com.overdrive.app.daemon.RecordingsIndexFileWatcher.getInstance().refresh();
        } catch (Throwable t) {
            logWarn(reason + ": RecordingsIndexFileWatcher refresh failed: " + t.getMessage());
        }
        new Thread(() -> {
            try {
                com.overdrive.app.server.RecordingsIndex.getInstance().reconcile();
            } catch (Throwable t) {
                logWarn(reason + ": RecordingsIndex reconcile failed: " + t.getMessage());
            }
        }, "RecordingsIndexHotplugReconcile").start();
    }

    // ==================== Cleanup Logic ====================
    
    /**
     * If a recording is in flight, defer the cleanup so it runs on the next
     * encoder-idle periodic tick. Returns true when deferral happened.
     *
     * <p>Three of the public ensure*Space callers can race the encoder:
     * user-initiated limit changes from the HTTP/IPC settings handlers
     * (QualitySettingsApiHandler, SurveillanceIpcServer, TcpCommandServer).
     * Without this gate, lowering the recordings limit while actively
     * recording triggers a delete burst that contends with the disk
     * writer and produces multi-second eglSwap stalls observed in field
     * logs. The post-save cleanup path has its own gate at
     * onRecordingFileSaved; this generalises the same guard to limit-
     * change paths so the cleanup contract is uniform.
     *
     * <p>Hard-overlimit escape: if usage already exceeds the limit by
     * &gt;5%, deferral is skipped — at that point the encoder will
     * backpressure on disk full anyway, so unblocking storage is the
     * lesser evil. Mirrors the periodic-loop policy.
     */
    private boolean deferIfEncoderBusy(String deferredKey, long currentSize, long limitBytes) {
        if (!isEncoderWriting()) return false;
        boolean hardOverLimit = limitBytes > 0 && currentSize > limitBytes * 21 / 20;
        if (hardOverLimit) {
            logWarn("Cleanup forced during recording: " + deferredKey + " at "
                + formatSize(currentSize) + "/" + formatSize(limitBytes) + " (HARD)");
            return false;
        }
        deferredCleanupDirs.add(deferredKey);
        logDebug("Cleanup deferred (encoder busy): " + deferredKey + " — will drain on next idle tick");
        return true;
    }

    /**
     * Ensure recordings storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * <p>Defers when the encoder is mid-write (see {@link #deferIfEncoderBusy})
     * unless usage is hard-over-limit, in which case cleanup runs anyway to
     * keep the disk writer from hitting ENOSPC.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available, or true
     *         when deferred (the deferred-cleanup drain will retry).
     */
    public boolean ensureRecordingsSpace(long reserveBytes) {
        return ensureRecordingsSpace(reserveBytes, null);
    }

    /**
     * Variant that takes an explicit {@code activeDir} so the caller can
     * snapshot it once and pass through. Without this, two volatile reads
     * of {@code recordingsDir} (one in the caller for filename construction,
     * one inside this method for the cleanup target) can disagree if a
     * concurrent storage-type switch swaps volumes between them — the
     * recorder writes the file into the OLD volume while the pre-flight
     * reserve targets the NEW volume.
     *
     * @param activeDir the directory the caller intends to write to. Pass
     *                  {@code null} to use the live {@code recordingsDir}.
     */
    public boolean ensureRecordingsSpace(long reserveBytes, File activeDir) {
        // recordingsCleanupLock serializes recordings-only work — post-save
        // async cleanups, periodic ticks, the reset/wipe path, and the
        // recorder's pre-flight reserve. It does NOT block on the surveillance
        // / proximity / trips locks, so a long boot reap of one category can't
        // starve the recorder's pre-flight in another.
        synchronized (recordingsCleanupLock) {
            File targetDir = (activeDir != null) ? activeDir : recordingsDir;
            if (deferIfEncoderBusy(DEFERRED_RECORDINGS, getRecordingsSize(),
                    recordingsLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("recordings", getReapableDirs("recordings"), targetDir,
                namePrefixForCategory("recordings"),
                recordingsLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Recorder-critical-path variant of {@link #ensureRecordingsSpace(long, File)}.
     *
     * <p>WHY THIS EXISTS — the ~9-10 min "recording doesn't start after ACC ON"
     * bug. The full {@code ensureRecordingsSpace} acquires
     * {@code recordingsCleanupLock} and walks/deletes the entire recordings pool
     * down to the configured size limit. On a cold boot that lock is held by the
     * startup reap (constructor → {@code ensureRecordingsSpace(0)}), whose
     * recordings leg can take many minutes on a FUSE-bridged SD/USB volume with
     * thousands of legacy clips (one {@code ls}/{@code rm} shell fork per file).
     * The recorder's pre-flight used to block on that same per-category lock for
     * the WHOLE walk, so {@code RecordingModeManager.activateMode} →
     * {@code pipeline.startRecording()} stalled and the user's drive was silently
     * not recorded until the reap finished. The historical per-category-lock
     * split fixed cross-category starvation but NOT recordings-vs-recordings:
     * boot reap and recorder pre-flight are the same category.
     *
     * <p>THE INSIGHT — the recorder pre-flight only needs ONE guarantee: enough
     * PHYSICAL free space that the next segment won't hit ENOSPC mid-file. That's
     * a {@link android.os.StatFs} question (single ~200µs binder call, no lock, no
     * shell fork), not a category-retention question. Keeping the folder under its
     * configured MB limit is housekeeping that the startup reap, the 30s periodic
     * ticker, AND the post-save cleanup all already enforce OFF the critical path.
     *
     * <p>So: if the target volume already has {@code reserveBytes} free, start
     * recording immediately and hand category-limit retention to the async
     * executor (it serialises behind any in-flight boot reap on the single-thread
     * {@link #asyncCleanupExecutor} — correct ordering, no double-reap contention;
     * and if the encoder is by then writing, {@code deferIfEncoderBusy} defers it
     * to the next idle drain, so it never competes with live muxer writes). Only
     * when the disk is GENUINELY short of physical space do we fall back to the
     * synchronous lock-held reap — the one case where blocking is correct, because
     * we truly cannot write the next segment until space frees.
     *
     * <p>This keeps every existing caller of {@link #ensureRecordingsSpace} (boot
     * reap, periodic ticker, post-save, TCP limit-change, reset/wipe) byte-for-byte
     * unchanged — only the recorder's hot path routes through here.
     *
     * @param reserveBytes physical bytes the next segment needs (recorder passes ~100MB)
     * @param targetDir    the directory the recorder is about to write into
     * @return true once it's safe to start recording (fast-path) or after a
     *         successful synchronous reap; false only if the synchronous reap
     *         could not free enough space.
     */
    public boolean ensureRecordingsSpaceForRecorder(long reserveBytes, File targetDir) {
        File dir = (targetDir != null) ? targetDir : recordingsDir;

        long availableBytes = -1L;
        try {
            // Probe the target dir itself when it exists; otherwise its parent
            // (the dir may not be mkdir'd yet on first boot). StatFs reads the
            // volume the path lives on either way.
            File probeDir = null;
            if (dir != null) {
                probeDir = dir.exists() ? dir : dir.getParentFile();
            }
            if (probeDir != null && probeDir.exists()) {
                android.os.StatFs stat = new android.os.StatFs(probeDir.getAbsolutePath());
                availableBytes = stat.getAvailableBytes();
            }
        } catch (Throwable t) {
            // StatFs throws IllegalArgumentException on an unmounted / half-
            // mounted volume. Treat as "unknown" → fall through to the
            // synchronous reap (which has its own dir-existence guards and is
            // the correct conservative choice when we can't measure free space).
            logWarn("Recorder pre-flight StatFs failed for "
                + (dir != null ? dir.getAbsolutePath() : "null") + ": " + t.getMessage()
                + " — falling back to synchronous reap");
            availableBytes = -1L;
        }

        if (availableBytes >= reserveBytes) {
            // Enough physical space — do NOT block on recordingsCleanupLock
            // (which the boot reap may hold for minutes). Start recording now;
            // hand category-limit retention to the async executor.
            logDebug("Recorder pre-flight fast-path: " + formatSize(availableBytes)
                + " free >= " + formatSize(reserveBytes) + " reserve — starting now, "
                + "deferring retention to async cleanup");
            final File asyncTarget = dir;
            try {
                asyncCleanupExecutor.execute(() -> {
                    try {
                        ensureRecordingsSpace(0, asyncTarget);
                    } catch (Throwable t) {
                        logWarn("Deferred recorder retention reap failed: " + t.getMessage());
                    }
                });
            } catch (Throwable t) {
                // Executor rejected (shutting down) — non-fatal; the 30s
                // periodic ticker and post-save cleanup still enforce the limit.
                logDebug("Could not enqueue deferred retention: " + t.getMessage());
            }
            return true;
        }

        // Genuine physical-space pressure (or StatFs unavailable): we MUST free
        // space before writing, so the synchronous lock-held reap is correct
        // here even if it means waiting on an in-flight reap. The bounded shell
        // forks (listFilesViaShell / deleteFileViaShell) keep each delete from
        // hanging indefinitely while we hold the recorder thread.
        // WARN (not INFO): this is the ONE path that can still block the
        // recorder behind the boot reap on recordingsCleanupLock — the exact
        // mechanism behind the historical "~9-10 min late recording" stall.
        // It is correct to block here (we genuinely cannot write a segment with
        // less than the reserve free), but if this fires repeatedly in the
        // field it means a volume is chronically near-full and recording start
        // is being delayed — so make it loud and timed for diagnosability.
        logWarn("Recorder pre-flight: only "
            + (availableBytes < 0 ? "unknown (StatFs unavailable)" : formatSize(availableBytes))
            + " free (< " + formatSize(reserveBytes) + " reserve) — running "
            + "SYNCHRONOUS lock-held reap before recording (may block behind an "
            + "in-flight boot/periodic reap; this is the near-full-volume fallback)");
        long syncReapStartNs = System.nanoTime();
        boolean ok = ensureRecordingsSpace(reserveBytes, targetDir);
        long syncReapMs = (System.nanoTime() - syncReapStartNs) / 1_000_000L;
        if (syncReapMs > 1_000) {
            logWarn("Recorder pre-flight synchronous reap took " + syncReapMs
                + "ms (freedEnough=" + ok + ") — recording start was delayed by storage cleanup");
        }
        return ok;
    }

    /**
     * Ensure surveillance storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureSurveillanceSpace(long reserveBytes) {
        synchronized (surveillanceCleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_SURVEILLANCE, getSurveillanceSize(),
                    surveillanceLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                namePrefixForCategory("surveillance"),
                surveillanceLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure proximity storage is within size limit.
     * Deletes oldest files (across active + inactive + legacy locations)
     * until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureProximitySpace(long reserveBytes) {
        synchronized (proximityCleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_PROXIMITY, getProximitySize(),
                    proximityLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                namePrefixForCategory("proximity"),
                proximityLimitMb * 1024 * 1024, reserveBytes);
        }
    }

    /**
     * Ensure trips storage is within size limit.
     * Deletes oldest files until the total falls under the limit.
     *
     * @param reserveBytes Additional bytes to reserve for new file
     * @return true if cleanup was successful and space is available
     */
    public boolean ensureTripsSpace(long reserveBytes) {
        synchronized (tripsCleanupLock) {
            if (deferIfEncoderBusy(DEFERRED_TRIPS, getTripsSize(),
                    tripsLimitMb * 1024 * 1024)) {
                return true;
            }
            return ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                namePrefixForCategory("trips"),
                tripsLimitMb * 1024 * 1024, reserveBytes);
        }
    }
    
    /**
     * Primary file extension for a category. Cleanup walks files matching
     * this extension as the "anchor" rows; sidecars are pulled in via
     * {@link #sidecarExtensionsForCategory(String)}.
     *
     * @return non-null lowercase extension including the leading dot.
     */
    private static String primaryExtensionForCategory(String category) {
        switch (category) {
            case "recordings":   return ".mp4";
            case "surveillance": return ".mp4";
            case "proximity":    return ".mp4";
            case "trips":        return ".jsonl.gz";
            default:             return ".mp4";
        }
    }

    /**
     * Partial / orphan extensions that aren't anchors but still consume disk
     * space the user expects to be subject to the limit. These are produced
     * by abnormal exits (SIGKILL between {@code .mp4.tmp} and {@code .mp4}
     * rename, encoder write that left a {@code .broken}, hero-image
     * extraction that left a {@code .jpg.tmp}). Counted by size accounting
     * and {@link #sweepOrphanTempFiles} reaps them with a 10-minute grace
     * window so live writers aren't disturbed.
     *
     * @return possibly empty array of lowercase suffixes (with leading dot
     *         or compound like {@code .mp4.tmp}).
     */
    private static String[] partialExtensionsForCategory(String category) {
        switch (category) {
            case "recordings":   return new String[]{".mp4.tmp", ".broken"};
            case "surveillance": return new String[]{".mp4.tmp", ".broken", ".jpg.tmp", ".json.tmp", ".srt.tmp"};
            case "proximity":    return new String[]{".mp4.tmp", ".broken"};
            case "trips":        return new String[]{".jsonl.gz.tmp"};
            default:             return new String[]{};
        }
    }

    /**
     * Sidecar extensions that share a stem with the primary file and should
     * be reaped together. Used both for size accounting (so the on-disk
     * footprint matches what the user sees in the UI) and for delete-time
     * orphan cleanup.
     *
     * @return possibly empty array of lowercase extensions (each starting
     *         with a dot), in addition to the primary extension.
     */
    private static String[] sidecarExtensionsForCategory(String category) {
        switch (category) {
            case "recordings":
                // cam_*.mp4 has no sidecars in the current build.
                return new String[]{};
            case "surveillance":
                // event_*: timeline JSON, hero JPG, overlay SRT.
                return new String[]{".json", ".jpg", ".srt"};
            case "proximity":
                return new String[]{".json"};
            case "trips":
                return new String[]{};
            default:
                return new String[]{};
        }
    }

    /**
     * Strip the primary extension from a file name, leaving the stem used
     * to match sidecars. Handles compound extensions like ".jsonl.gz".
     */
    private static String stemForName(String fileName, String primaryExt) {
        if (fileName.endsWith(primaryExt)) {
            return fileName.substring(0, fileName.length() - primaryExt.length());
        }
        return fileName;
    }

    /**
     * Generic cleanup method that operates across a set of directories.
     *
     * Pools all primary-extension files from every dir (active, inactive
     * mirror, legacy), sorts globally by mtime, and deletes oldest-first
     * (along with each anchor's sidecars) until the combined total is
     * under the limit. This guarantees the user-configured limit is honored
     * across orphan locations after a storage-type switch or after a legacy
     * install left behind clips.
     *
     * Size accounting includes sidecars so the limit reflects the on-disk
     * footprint the user sees in the UI, not just the anchor file's bytes.
     *
     * SOTA: Uses shell fallback for listing/deleting when directory is owned
     * by a different UID than the daemon.
     *
     * @param category    Category key — recordings/surveillance/proximity/trips.
     * @param dirs        Every directory whose files count toward this limit.
     *                    May contain a mix of active, inactive, and legacy
     *                    paths. Nulls/missing dirs are skipped.
     * @param activeDir   The dir new files will land in. Created if missing
     *                    so the next write doesn't fail.
     * @param limitBytes  Total bytes allowed across all dirs.
     * @param reserveBytes Additional bytes to keep free (subtracted from limit).
     * @return true if cleanup was successful and space is available
     */
    private boolean ensureSpace(String category, List<File> dirs, File activeDir,
                                String namePrefix,
                                long limitBytes, long reserveBytes) {
        if (activeDir != null && (!activeDir.exists() || !activeDir.isDirectory())) {
            activeDir.mkdirs();
        }

        long targetSize = limitBytes - reserveBytes;
        if (targetSize < 0) targetSize = 0;

        final String primaryExt = primaryExtensionForCategory(category);
        final String[] sidecarExts = sidecarExtensionsForCategory(category);
        final String[] auxPrefixes = auxiliaryPrefixesForCategory(category);

        // Collect every reapable anchor file, deduplicated by filename so a
        // clip that exists on both internal and SD card isn't accounted twice.
        // When namePrefix is non-null, restrict to files matching the category
        // (some dirs in the list — typically the flat legacy base — are shared
        // across categories).
        List<File> allFiles = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        long currentSize = 0;
        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(primaryExt));
            if (files == null) {
                files = listFilesByExt(dir, primaryExt);
            }
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (namePrefix != null && !name.startsWith(namePrefix)) continue;
                if (!seenNames.add(name)) continue;
                allFiles.add(f);
                currentSize += f.length();
                // Add sidecar bytes to the running total so we measure the
                // same on-disk footprint we'd actually free by deleting the
                // anchor + its sidecars below. Two sidecar shapes:
                //   - same-stem siblings: <stem><sidecarExt>  (json/srt/jpg)
                //   - aux-prefix siblings: <auxPrefix><stem>_*  (per-actor thumbs)
                if (sidecarExts.length > 0 || auxPrefixes.length > 0) {
                    String stem = stemForName(name, primaryExt);
                    for (String ext : sidecarExts) {
                        File sidecar = new File(f.getParentFile(), stem + ext);
                        if (sidecar.isFile()) currentSize += sidecar.length();
                    }
                    if (auxPrefixes.length > 0) {
                        for (File aux : findAuxiliarySiblings(f.getParentFile(), auxPrefixes, stem)) {
                            currentSize += aux.length();
                        }
                    }
                }
            }
        }

        if (currentSize <= targetSize) {
            return true;  // Already within limit
        }

        if (allFiles.isEmpty()) {
            return true;
        }

        // Oldest first (global ordering across all dirs).
        Collections.sort(allFiles, Comparator.comparingLong(File::lastModified));

        int deletedCount = 0;
        long deletedSize = 0;
        boolean reapedFromInactive = false;

        // Path of the in-flight trip telemetry file, if any. Only honored
        // when reaping the trips category; recordings/surveillance use the
        // encoder-writing probe further down.
        final String protectedTripPath = "trips".equals(category) ? activeTripFilePath : null;

        for (File file : allFiles) {
            if (currentSize <= targetSize) break;

            // Don't unlink a still-being-written trip file. The recorder
            // keeps the GZIPOutputStream open across SAMPLE_INTERVAL_MS ticks;
            // a delete here would orphan every byte buffered after this point.
            if (protectedTripPath != null
                    && protectedTripPath.equals(file.getAbsolutePath())) {
                logDebug("Skipping in-flight trip file: " + file.getName());
                continue;
            }

            long fileSize = file.length();
            boolean deleted = file.delete();
            if (!deleted) {
                deleted = deleteFileViaShell(file);
            }

            if (deleted) {
                currentSize -= fileSize;
                deletedCount++;
                deletedSize += fileSize;
                if (activeDir == null
                    || !file.getParentFile().getAbsolutePath().equals(activeDir.getAbsolutePath())) {
                    reapedFromInactive = true;
                }
                logInfo("Deleted old file: " + file.getAbsolutePath() + " (" + formatSize(fileSize) + ")");

                // Drop the H2 row immediately. Without this, a limit-driven
                // retention sweep would leave phantom entries until the
                // next 1-hour reconcile — chips and counts would lie for
                // up to an hour. Cheap (single indexed DELETE), no-op for
                // non-recording categories like trips because the index
                // only knows about .mp4 filenames.
                if (file.getName().endsWith(".mp4")) {
                    try {
                        com.overdrive.app.server.RecordingsIndex
                                .getInstance().remove(file.getName());
                    } catch (Throwable ignored) {}
                }

                // Sidecars share the anchor's stem and are dead weight once
                // the anchor is gone. Walk the registered set instead of the
                // recordings-only ".mp4 → .json" replace().
                if (sidecarExts.length > 0) {
                    String stem = stemForName(file.getName(), primaryExt);
                    for (String ext : sidecarExts) {
                        File sidecar = new File(file.getParentFile(), stem + ext);
                        if (sidecar.exists()) {
                            long sidecarSize = sidecar.length();
                            boolean sidecarDeleted = sidecar.delete();
                            if (!sidecarDeleted) sidecarDeleted = deleteFileViaShell(sidecar);
                            if (sidecarDeleted) {
                                currentSize -= sidecarSize;
                                deletedSize += sidecarSize;
                            }
                        }
                    }
                }

                // Aux-prefix siblings (per-actor thumbs `thumb_event_<base>_a*`).
                // These don't share a stem suffix with the anchor — they share
                // a prefix construction (auxPrefix + anchorStem + "_…").
                if (auxPrefixes.length > 0) {
                    String stem = stemForName(file.getName(), primaryExt);
                    for (File aux : findAuxiliarySiblings(file.getParentFile(), auxPrefixes, stem)) {
                        long auxSize = aux.length();
                        boolean auxDeleted = aux.delete();
                        if (!auxDeleted) auxDeleted = deleteFileViaShell(aux);
                        if (auxDeleted) {
                            currentSize -= auxSize;
                            deletedSize += auxSize;
                        }
                    }
                }

                // Drop any cached entry the recordings API might still hold.
                try {
                    com.overdrive.app.server.RecordingsApiHandler
                        .invalidateRecordingCache(file.getAbsolutePath());
                } catch (Throwable ignored) {
                    // RecordingsApiHandler may not be loaded in every process.
                }
            } else {
                logWarn("Failed to delete: " + file.getAbsolutePath());
            }
        }

        if (deletedCount > 0) {
            logInfo("Cleanup complete: deleted " + deletedCount + " files (" + formatSize(deletedSize) + ")"
                + (reapedFromInactive ? " — including orphan/legacy locations" : ""));
        }

        // Orphan-sidecar pass. The collection loop above accumulated every
        // anchor stem in `seenNames`; any sidecar (same-stem .json/.jpg/.srt
        // or aux-prefix `thumb_event_<stem>_a*`) whose stem isn't in that set
        // belongs to an anchor that was deleted in a prior cycle (or by an
        // app-side reset that didn't sweep sidecars). Reap them so per-actor
        // thumbnails / SRT / JSON fragments don't accumulate forever.
        // Skipped when the category has no sidecars or aux prefixes.
        if (sidecarExts.length > 0 || auxPrefixes.length > 0) {
            // Convert anchor-name set to stems for sidecar comparison.
            Set<String> liveStems = new HashSet<>();
            for (String anchorName : seenNames) {
                liveStems.add(stemForName(anchorName, primaryExt));
            }
            // Anchors we just deleted are still in seenNames but their
            // sidecars have already been wiped above; safe to leave them in.
            int orphansDeleted = 0;
            long orphansFreed = 0;
            Set<String> seenSidecarPaths = new HashSet<>();
            for (File dir : dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) files = listFilesViaShell(dir);
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    // Sidecar candidates may match the primary prefix
                    // (event_xxx.json) OR an aux prefix (thumb_event_xxx_a17.jpg);
                    // both must be considered, hence nameMatchesCategoryPrefix.
                    if (namePrefix != null
                            && !nameMatchesCategoryPrefix(name, namePrefix, auxPrefixes)) continue;

                    String stem = null;
                    // Aux-prefix sibling first, since a thumb_event_xxx_a*.jpg
                    // also matches the .jpg sidecar branch — without this
                    // ordering, we'd parse stem as the full minus-".jpg" name
                    // and incorrectly conclude it's an orphan on every tick.
                    String matchedAux = null;
                    for (String aux : auxPrefixes) {
                        if (name.startsWith(aux)) { matchedAux = aux; break; }
                    }
                    if (matchedAux != null) {
                        // Per-actor thumb shape: "<aux><anchorStem>_a<id>[_<rel>].<ext>".
                        // The anchor stem itself can contain underscores
                        // (event_<date>_<time>), so we can't parse with
                        // indexOf('_', auxLen). Find the LAST "_a<digit>" run
                        // and treat everything between aux and that as stem.
                        int auxLen = matchedAux.length();
                        int actorMarker = lastIndexOfActorMarker(name, auxLen);
                        if (actorMarker > auxLen) {
                            stem = name.substring(auxLen, actorMarker);
                        }
                    } else {
                        // Same-stem sidecar (event_xxx.json/.srt/.jpg).
                        for (String ext : sidecarExts) {
                            if (name.endsWith(ext)) {
                                stem = name.substring(0, name.length() - ext.length());
                                break;
                            }
                        }
                    }
                    if (stem == null) continue;
                    if (!seenSidecarPaths.add(f.getAbsolutePath())) continue;
                    if (liveStems.contains(stem)) continue;  // anchor still around
                    long sz = f.length();
                    boolean ok = f.delete();
                    if (!ok) ok = deleteFileViaShell(f);
                    if (ok) {
                        orphansDeleted++;
                        orphansFreed += sz;
                        currentSize -= sz;
                    }
                }
            }
            if (orphansDeleted > 0) {
                logInfo("Sidecar orphans reaped: " + orphansDeleted + " files (" + formatSize(orphansFreed) + ")");
            }
        }

        // If still over limit and the active dir lives on the SD card, fall
        // back to CDR cleanup to free up underlying SD-card space.
        if (currentSize > targetSize
            && sdCardAvailable
            && activeDir != null
            && sdCardPath != null
            && activeDir.getAbsolutePath().startsWith(sdCardPath)) {
            try {
                ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
                if (cleaner.isEnabled()) {
                    logInfo("Overdrive cleanup insufficient on SD card — triggering CDR cleanup");
                    cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                logWarn("CDR fallback cleanup failed: " + e.getMessage());
            }
        }

        return currentSize <= targetSize;
    }
    
    /**
     * Find the last "_a<digit>" actor-id marker in a thumb filename. Returns
     * the offset of the underscore in "_a", or -1 if none found within
     * {@code [from, name.length())}. The anchor stem (e.g.
     * {@code event_<date>_<time>}) can contain underscores, so we cannot
     * use the first underscore after the aux prefix.
     */
    private static int lastIndexOfActorMarker(String name, int from) {
        for (int i = name.length() - 2; i >= from; i--) {
            if (name.charAt(i) != '_') continue;
            if (i + 1 >= name.length() || name.charAt(i + 1) != 'a') continue;
            // Require a digit after "_a" so we don't match arbitrary text.
            if (i + 2 < name.length() && Character.isDigit(name.charAt(i + 2))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find every aux-prefix sibling of an anchor stem within a directory.
     * For surveillance, an anchor {@code event_<base>.mp4} in {@code dir}
     * has aux siblings matching {@code thumb_event_<base>_*} — per-actor
     * thumbnails written by {@code SurveillanceEngineGpu.dispatchSegmentMetadata}.
     *
     * <p>Returns an empty list when {@code auxPrefixes} is empty, the dir
     * doesn't exist, or no matches are found.
     */
    private List<File> findAuxiliarySiblings(File dir, String[] auxPrefixes, String stem) {
        if (dir == null || auxPrefixes == null || auxPrefixes.length == 0
                || !dir.isDirectory()) {
            return java.util.Collections.emptyList();
        }
        List<File> hits = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) files = listFilesViaShell(dir);
        if (files == null) return hits;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            for (String aux : auxPrefixes) {
                // Match `<aux><stem>_…` so we don't accidentally swallow
                // an unrelated stem that happens to share a prefix.
                String wanted = aux + stem + "_";
                if (name.startsWith(wanted)) {
                    hits.add(f);
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * SOTA: Delete file via shell command when Java delete fails.
     */
    private boolean deleteFileViaShell(File file) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rm", file.getAbsolutePath()});
            // Bounded wait — a stuck `rm` on a bad FUSE-bridged external volume
            // would otherwise pin the reap (and any caller holding a per-category
            // cleanup lock) indefinitely. This is the per-file fork the boot reap
            // runs hundreds of times; without a bound, a single hung delete stalls
            // the entire walk. Returns -1 on timeout (treated as "not deleted",
            // matching a non-zero exit) and force-kills the child.
            int exitCode = waitForBounded(p, 4_000, "deleteFileViaShell(" + file.getName() + ")");
            return exitCode == 0;
        } catch (Exception e) {
            logWarn("deleteFileViaShell failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Run cleanup across every category. Each ensureXxxSpace self-acquires
     * its own per-category lock; we no longer hold a single big lock across
     * the four calls. That used to be done so the four passes "appeared
     * atomic" to peers, but no caller relies on that atomicity — the four
     * categories are independent volumes/limits. Removing the big lock lets
     * a per-recording ensureRecordingsSpace overlap with a periodic
     * ensureSurveillanceSpace, which is the common case.
     */
    public void runCleanup() {
        ensureRecordingsSpace(0);
        ensureSurveillanceSpace(0);
        ensureProximitySpace(0);
        ensureTripsSpace(0);
    }
    
    // ==================== Utility ====================
    
    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
    
    public static long getMinLimitMb() {
        return MIN_LIMIT_MB;
    }
    
    /**
     * Static fallback ceiling. Use the instance methods
     * {@link #getEffectiveMaxLimitMb(StorageType)} / {@link #getMaxLimitMb(StorageType)}
     * for the live, volume-aware ceiling. These statics are kept only
     * for legacy callers that don't have an instance handy.
     */
    public static long getMaxLimitMb() {
        return MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbInternal() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.INTERNAL) : MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbSdCard() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.SD_CARD) : MAX_LIMIT_MB_FALLBACK;
    }

    public static long getMaxLimitMbUsb() {
        StorageManager sm = instance;
        return sm != null ? sm.getEffectiveMaxLimitMb(StorageType.USB) : MAX_LIMIT_MB_FALLBACK;
    }
    
    // ==================== Event-Driven Cleanup (SOTA) ====================
    
    /**
     * Called after a recording file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * This is the SOTA approach - cleanup after each file save rather than
     * only at recording start, preventing storage overflow during long sessions.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onRecordingFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(recordingsDir);

        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()

        // Gate destructive cleanup on encoder write state. If a recording is
        // mid-flight, deferring this delete burst is what keeps the SD card
        // available for the encoder's disk writer. The deferred queue is
        // drained the next time we observe encoder=idle, so nothing is lost.
        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_RECORDINGS);
            logDebug("Recording file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (recordingsCleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(recordingsDir);

                    long currentSize = getRecordingsSize();
                    long limitBytes = recordingsLimitMb * 1024 * 1024;

                    if (currentSize > limitBytes) {
                        logInfo("Recording file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        // Call ensureSpace directly: we already hold
                        // recordingsCleanupLock and already gated on
                        // isEncoderWriting at the top. Going through
                        // ensureRecordingsSpace would just re-take the
                        // (reentrant) lock and re-check the encoder gate.
                        ensureSpace("recordings", getReapableDirs("recordings"), recordingsDir,
                            namePrefixForCategory("recordings"),
                            limitBytes, 0);
                    } else {
                        logDebug("Recording file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async recording cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a surveillance event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onSurveillanceFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(surveillanceDir);

        // FIX: Removed broadcastRecentFiles() call that re-scanned ALL files modified
        // in the last 60 seconds. This caused duplicate MediaScanner broadcasts —
        // if two events saved 20 seconds apart, the second save re-broadcast the first.
        // Over days of parking, this list grows to hundreds of files, causing massive
        // CPU spikes on every new event. The specific file is already broadcast by
        // onFileSaved() → broadcastFile(file) before this method is called.

        // Defer destructive cleanup if encoder is mid-write. See onRecordingFileSaved
        // for rationale — SD card I/O contention against the encoder's disk writer
        // is what produces the freeze+skip artifact in the recorded MP4.
        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
            logDebug("Surveillance file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (surveillanceCleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(surveillanceDir);

                    long currentSize = getSurveillanceSize();
                    long limitBytes = surveillanceLimitMb * 1024 * 1024;

                    if (currentSize > limitBytes) {
                        logInfo("Surveillance file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                            namePrefixForCategory("surveillance"),
                            limitBytes, 0);
                    } else {
                        logDebug("Surveillance file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async surveillance cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a proximity event file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the video encoding thread.
     */
    public void onProximityFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(proximityDir);

        // FIX: Removed broadcastRecentFiles() — specific file already broadcast by onFileSaved()

        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_PROXIMITY);
            logDebug("Proximity file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (proximityCleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(proximityDir);

                    long currentSize = getProximitySize();
                    long limitBytes = proximityLimitMb * 1024 * 1024;

                    if (currentSize > limitBytes) {
                        logInfo("Proximity file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                            namePrefixForCategory("proximity"),
                            limitBytes, 0);
                    } else {
                        logDebug("Proximity file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async proximity cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Called after a trip telemetry file is saved/closed.
     * Triggers async cleanup to ensure we stay within limits.
     * Also sets file permissions so UI app can read it.
     * 
     * IMPORTANT: Runs async to avoid blocking the telemetry recording thread.
     */
    public void onTripFileSaved() {
        // Fix directory permissions in case they were reset
        fixDirectoryPermissions(tripsDir);

        if (isEncoderWriting()) {
            deferredCleanupDirs.add(DEFERRED_TRIPS);
            logDebug("Trip file saved during active write — deferring cleanup");
            return;
        }

        asyncCleanupExecutor.execute(() -> {
            synchronized (tripsCleanupLock) {
                try {
                    // Make all files in directory readable
                    makeFilesReadable(tripsDir);

                    long currentSize = getTripsSize();
                    long limitBytes = tripsLimitMb * 1024 * 1024;

                    if (currentSize > limitBytes) {
                        logInfo("Trip file saved - triggering cleanup (current=" +
                            formatSize(currentSize) + ", limit=" + formatSize(limitBytes) + ")");
                        ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                            namePrefixForCategory("trips"),
                            limitBytes, 0);
                    } else {
                        logDebug("Trip file saved - within limits (" +
                            formatSize(currentSize) + "/" + formatSize(limitBytes) + ")");
                    }
                } catch (Exception e) {
                    logWarn("Async trips cleanup error: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Fix directory permissions so UI app can read files.
     * Note: chmod doesn't work on Android FUSE filesystem, but we keep Java API calls.
     */
    private void fixDirectoryPermissions(File dir) {
        if (dir != null && dir.exists()) {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        }
    }
    
    /**
     * Make all .mp4 files in directory readable by all.
     * Note: chmod doesn't work on Android FUSE filesystem - rely on MediaStore instead.
     */
    private void makeFilesReadable(File dir) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) {
            files = listFilesViaShell(dir);
        }
        
        if (files != null) {
            for (File f : files) {
                f.setReadable(true, false);
            }
        }
    }
    
    /**
     * Make a single file readable by all users.
     * Note: chmod doesn't work on Android FUSE - rely on MediaStore for cross-UID access.
     */
    public void makeFileReadable(File file) {
        if (file == null || !file.exists()) return;
        file.setReadable(true, false);
    }
    
    /**
     * Force Android MediaScanner to index a file so it appears in MediaStore
     * and becomes visible to standard apps with READ_EXTERNAL_STORAGE.
     * 
     * CRITICAL: Both methods are required on BYD's Android 10:
     * - `am broadcast MEDIA_SCANNER_SCAN_FILE` refreshes the FUSE permission cache
     *   so that File.listFiles() on SD card paths can see the file. Without this,
     *   the RecordingsApiHandler's scanDirectory() gets incomplete file listings.
     * - `content insert` directly inserts into MediaStore for cross-UID visibility
     *   (needed for the UI app running as a different UID).
     */
    private void broadcastFile(File file) {
        if (file == null || !file.exists()) return;
        
        String path = file.getAbsolutePath();
        
        try {
            // Method 1: FUSE cache refresh via MediaScanner intent
            // Required for File.listFiles() to work on SD card FUSE paths
            Runtime.getRuntime().exec(new String[]{
                "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                "-d", "file://" + path
            });
            
            // Method 2: Direct MediaStore insert for cross-UID visibility
            Runtime.getRuntime().exec(new String[]{
                "content", "insert",
                "--uri", "content://media/external/video/media",
                "--bind", "_data:s:" + path
            });
            
            logDebug("Broadcast file to MediaScanner: " + file.getName());
        } catch (Exception e) {
            logWarn("Failed to broadcast file: " + e.getMessage());
        }
    }
    
    /**
     * SOTA: Fix permissions and broadcast a single file after it's saved.
     * Call this immediately after closing a video file.
     * @param file The video file that was just saved
     */
    public void onFileSaved(File file) {
        if (file == null || !file.exists()) {
            logWarn("onFileSaved: file is null or doesn't exist");
            return;
        }
        
        logInfo("Processing saved file: " + file.getName() + " (" + formatSize(file.length()) + ")");

        // 1. Make file readable by all (chmod 666)
        makeFileReadable(file);

        // 2. Broadcast to MediaScanner. This spawns shell processes
        // (am broadcast + content insert) which compete for I/O bandwidth.
        // While the encoder is mid-write, defer to the background cleanup
        // executor so the disk writer keeps priority. (Audit P2.)
        if (isEncoderWriting()) {
            final File f = file;
            asyncCleanupExecutor.execute(() -> {
                try { broadcastFile(f); } catch (Exception e) {
                    logWarn("Deferred broadcastFile error: " + e.getMessage());
                }
            });
        } else {
            broadcastFile(file);
        }

        // 3. Trigger appropriate cleanup based on directory
        String path = file.getAbsolutePath();
        if (path.contains(RECORDINGS_SUBDIR)) {
            onRecordingFileSaved();
        } else if (path.contains(SURVEILLANCE_SUBDIR)) {
            onSurveillanceFileSaved();
        } else if (path.contains(PROXIMITY_SUBDIR)) {
            onProximityFileSaved();
        } else if (path.contains(TRIPS_SUBDIR)) {
            onTripFileSaved();
        }
    }
    
    /**
     * Broadcast all recent files in a directory to MediaScanner.
     * @param dir Directory to scan
     * @param maxAgeMs Only broadcast files modified within this time (ms)
     */
    private void broadcastRecentFiles(File dir, long maxAgeMs) {
        if (dir == null || !dir.exists()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files != null) {
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (now - f.lastModified() < maxAgeMs) {
                    broadcastFile(f);
                }
            }
        }
    }
    
    // ==================== Periodic Background Cleanup ====================
    
    /**
     * Start periodic cleanup for long recording sessions.
     * Runs every 30 seconds while recording is active.
     */
    public void startPeriodicCleanup() {
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            return;  // Already running
        }
        
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                // Same low-priority strategy as asyncCleanupExecutor — the
                // periodic tick must never preempt the disk writer.
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "StorageCleanup");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                // Don't run un-gated cleanup before the encoder probe is wired.
                // Daemon-init ordering: startPeriodicCleanup() fires early
                // (before pipeline.init), so the first scheduled tick at
                // T+30s could land before the probe is bound and would treat
                // an active recording as "encoder idle" → run the destructive
                // cleanup right through the recording. (Audit P1.)
                if (!probeWired.get()) {
                    logDebug("Periodic cleanup tick skipped — encoder probe not wired yet");
                    return;
                }
                // SOTA: skip the entire pass while the encoder is writing. The
                // 19-files / 118 MB delete burst observed in field logs while
                // recording was active produced a 2.8 sec mosaic+swap stall on
                // the GL thread (encoder backpressured eglSwap because the disk
                // writer was starved by cleanup I/O). Deferring is safe: the
                // dir is added to deferredCleanupDirs so the next tick (or the
                // next save event after recording finishes) drains it.
                //
                // Edge case: a HARD storage situation (disk literally full, can't
                // even write the next muxer chunk) needs a way out. We honor
                // that by forcing a cleanup pass when current usage exceeds the
                // limit by >5% — at that point eglSwap will fail anyway, so
                // unblocking storage is the lesser evil. Below that threshold,
                // the encoder write wins.
                if (isEncoderWriting()) {
                    // Per-dir over-limit ratio. Old code used MAX(limits)/20 as
                    // the denominator, which let a small dir (e.g., 100 MB
                    // recordings) grow many tens of MB over its OWN limit
                    // before triggering. Per-dir ratio gives every dir an
                    // independent, fair escape (audit Finding "storage drift").
                    long recBytes = getRecordingsSize();
                    long survBytes = getSurveillanceSize();
                    long tripsBytes = getTripsSize();
                    long recLim = recordingsLimitMb * 1024 * 1024;
                    long survLim = surveillanceLimitMb * 1024 * 1024;
                    long tripsLim = tripsLimitMb * 1024 * 1024;
                    boolean recHard  = recLim   > 0 && recBytes   > recLim   * 21 / 20;  // >5% over OWN limit
                    boolean survHard = survLim  > 0 && survBytes  > survLim  * 21 / 20;
                    boolean tripsHard= tripsLim > 0 && tripsBytes > tripsLim * 21 / 20;

                    // Free-disk emergency: if ANY active volume is critically
                    // low, continuing to write is going to fail anyway. Force
                    // cleanup regardless of probe. The min across all
                    // categories' active volumes covers the case where
                    // surveillance is on USB while recordings are on internal —
                    // a starved surveillance volume must still trigger.
                    long minFree = Long.MAX_VALUE;
                    for (StorageType t : new StorageType[]{
                            recordingsStorageType, surveillanceStorageType, tripsStorageType}) {
                        long f;
                        switch (t) {
                            case SD_CARD: f = getSdCardFreeSpace(); break;
                            case USB:     f = getUsbFreeSpace();    break;
                            case INTERNAL:
                            default:      f = getInternalFreeSpace(); break;
                        }
                        if (f > 0 && f < minFree) minFree = f;
                    }
                    long sdFree = (minFree == Long.MAX_VALUE) ? 0 : minFree;
                    boolean diskCritical = sdFree > 0 && sdFree < 200L * 1024 * 1024;  // <200MB free

                    boolean hardOverlimit = recHard || survHard || tripsHard || diskCritical;
                    if (hardOverlimit) {
                        logWarn("Periodic cleanup forced during recording: "
                            + "rec=" + formatSize(recBytes) + "/" + formatSize(recLim) + (recHard ? " HARD" : "")
                            + " surv=" + formatSize(survBytes) + "/" + formatSize(survLim) + (survHard ? " HARD" : "")
                            + " trips=" + formatSize(tripsBytes) + "/" + formatSize(tripsLim) + (tripsHard ? " HARD" : "")
                            + " sdFree=" + formatSize(sdFree) + (diskCritical ? " CRITICAL" : ""));
                    } else {
                        // Mark all dirs that are at risk so we drain them later.
                        if (recBytes > recLim * 0.9) deferredCleanupDirs.add(DEFERRED_RECORDINGS);
                        if (survBytes > survLim * 0.9) deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
                        if (tripsBytes > tripsLim * 0.9) deferredCleanupDirs.add(DEFERRED_TRIPS);
                        return;
                    }
                }

                // Encoder idle: drain any deferred work first so storage limits
                // re-converge after a long recording.
                drainDeferredCleanupIfDue();

                // Sweep orphan .mp4.tmp / .broken / .jpg.tmp partials. The
                // helper itself takes per-category locks one at a time and
                // holds a 10-minute grace window so live writers are never
                // touched. Without this in the periodic loop, partials
                // accumulated only got reaped at daemon boot — a long-running
                // daemon hit by an OOM kill mid-recording could leave the
                // disk half-full of tmps that the size-based reaper never
                // frees because it only walks primary-extension files.
                sweepOrphanTempFiles();

                // Standard periodic pass (catches dirs that grew past the limit
                // while the daemon was offline, or after a manual limit change).
                // Each ensureXxxSpace self-acquires its category lock; the
                // size readouts under the same monitor stay consistent with
                // what ensureXxxSpace will operate on.
                synchronized (recordingsCleanupLock) {
                    long currentSize = getRecordingsSize();
                    long limitBytes = recordingsLimitMb * 1024 * 1024;
                    if (currentSize > limitBytes * 0.9) {  // 90% threshold
                        logInfo("Periodic cleanup: recordings at " +
                            formatSize(currentSize) + "/" + formatSize(limitBytes));
                        ensureRecordingsSpace(50 * 1024 * 1024);  // Reserve 50MB
                    }
                }

                synchronized (surveillanceCleanupLock) {
                    long currentSize = getSurveillanceSize();
                    long limitBytes = surveillanceLimitMb * 1024 * 1024;
                    if (currentSize > limitBytes * 0.9) {  // 90% threshold
                        logInfo("Periodic cleanup: surveillance at " +
                            formatSize(currentSize) + "/" + formatSize(limitBytes));
                        ensureSurveillanceSpace(50 * 1024 * 1024);  // Reserve 50MB
                    }
                }

                synchronized (tripsCleanupLock) {
                    long currentSize = getTripsSize();
                    long limitBytes = tripsLimitMb * 1024 * 1024;
                    if (currentSize > limitBytes * 0.9) {  // 90% threshold
                        logInfo("Periodic cleanup: trips at " +
                            formatSize(currentSize) + "/" + formatSize(limitBytes));
                        ensureTripsSpace(50 * 1024 * 1024);  // Reserve 50MB
                    }
                }
            } catch (Exception e) {
                logWarn("Periodic cleanup error: " + e.getMessage());
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        logInfo("Started periodic storage cleanup (interval=" + CLEANUP_INTERVAL_SECONDS + "s)");
    }
    
    /**
     * Drain any cleanup that was deferred because the encoder was mid-write.
     * Called from the periodic tick AND from each onXxxFileSaved path, so a
     * deferred backlog never sits indefinitely. Safe to call when the queue
     * is empty (early-exits on empty set).
     */
    private void drainDeferredCleanupIfDue() {
        if (deferredCleanupDirs.isEmpty()) return;
        if (isEncoderWriting()) return;  // still busy, try later
        // Snapshot+clear so a concurrent add (e.g. a periodic tick that fires
        // while we're draining) doesn't lose the new mark.
        java.util.Set<String> toRun = new java.util.HashSet<>(deferredCleanupDirs);
        deferredCleanupDirs.removeAll(toRun);
        logInfo("Draining deferred cleanup: " + toRun);

        // Per-dir try/catch: a failure on one dir must NOT cause the others
        // to be re-marked. The previous catch-all re-added the entire toRun
        // snapshot on any exception, including dirs that had already been
        // cleaned successfully — wasting the next tick on idempotent re-runs
        // (audit P1).
        // Same direct-ensureSpace pattern as onXxxFileSaved: we already
        // hold the per-category lock and already gated on isEncoderWriting
        // at the top. Going through ensureXxxSpace would re-take the
        // (reentrant) lock and re-check the encoder gate.
        if (toRun.contains(DEFERRED_RECORDINGS)) {
            try {
                synchronized (recordingsCleanupLock) {
                    long limitBytes = recordingsLimitMb * 1024 * 1024;
                    if (getRecordingsSize() > limitBytes) {
                        ensureSpace("recordings", getReapableDirs("recordings"), recordingsDir,
                            namePrefixForCategory("recordings"), limitBytes, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred recordings cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_RECORDINGS);
            }
        }
        if (toRun.contains(DEFERRED_SURVEILLANCE)) {
            try {
                synchronized (surveillanceCleanupLock) {
                    long limitBytes = surveillanceLimitMb * 1024 * 1024;
                    if (getSurveillanceSize() > limitBytes) {
                        ensureSpace("surveillance", getReapableDirs("surveillance"), surveillanceDir,
                            namePrefixForCategory("surveillance"), limitBytes, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred surveillance cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_SURVEILLANCE);
            }
        }
        if (toRun.contains(DEFERRED_PROXIMITY)) {
            try {
                synchronized (proximityCleanupLock) {
                    long limitBytes = proximityLimitMb * 1024 * 1024;
                    if (getProximitySize() > limitBytes) {
                        ensureSpace("proximity", getReapableDirs("proximity"), proximityDir,
                            namePrefixForCategory("proximity"), limitBytes, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred proximity cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_PROXIMITY);
            }
        }
        if (toRun.contains(DEFERRED_TRIPS)) {
            try {
                synchronized (tripsCleanupLock) {
                    long limitBytes = tripsLimitMb * 1024 * 1024;
                    if (getTripsSize() > limitBytes) {
                        ensureSpace("trips", getReapableDirs("trips"), tripsDir,
                            namePrefixForCategory("trips"), limitBytes, 0);
                    }
                }
            } catch (Exception e) {
                logWarn("Deferred trips cleanup error: " + e.getMessage());
                deferredCleanupDirs.add(DEFERRED_TRIPS);
            }
        }
    }

    /**
     * Stop periodic cleanup.
     */
    public void stopPeriodicCleanup() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupScheduler.shutdownNow();
            }
            cleanupScheduler = null;
            logInfo("Stopped periodic storage cleanup");
        }
    }

    /**
     * Start SD card / USB mount watchdog for sentry mode.
     * Periodically checks if the configured external volume(s) are still
     * mounted and re-mounts them if the system unmounted them (BYD/Android
     * tends to unmount SD when ACC is off; USB drops on bus glitches).
     *
     * Call this when entering sentry mode with an external volume selected.
     * The single watchdog now covers BOTH SD and USB so a USB-only config
     * doesn't go un-watched and silently fall back to internal forever.
     */
    public void startSdCardWatchdog() {
        // Start watchdog if ANY storage type uses SD or USB (not just surveillance).
        // The watchdog keeps the external volume mounted so recordings, events,
        // and trips remain accessible via the HTTP server even when surveillance
        // is suppressed.
        boolean anyOnSd  = surveillanceStorageType == StorageType.SD_CARD ||
                          recordingsStorageType   == StorageType.SD_CARD ||
                          tripsStorageType        == StorageType.SD_CARD;
        boolean anyOnUsb = surveillanceStorageType == StorageType.USB ||
                          recordingsStorageType   == StorageType.USB ||
                          tripsStorageType        == StorageType.USB;
        if (!anyOnSd && !anyOnUsb) {
            logDebug("Volume watchdog not needed - no storage type uses SD or USB");
            return;
        }

        stopSdCardWatchdog();  // Stop any existing watchdog first

        final boolean watchSd = anyOnSd;
        final boolean watchUsb = anyOnUsb;

        sdCardWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VolumeWatchdog");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);  // Normal priority - mount is critical
            return t;
        });

        sdCardWatchdog.scheduleAtFixedRate(() -> {
            try {
                // Use the cheap layered check (StatFs + canWrite, no shell
                // fork) for the watchdog tick. The expensive `touch+rm`
                // probe in isSdCardMounted() falsely reports unmounted under
                // FUSE binder contention from concurrent dir-walks, kicking
                // off a remount cascade that itself spawns more shell forks
                // and amplifies the contention. The cheap check has zero
                // such side effects.
                //
                // Two-strikes rule: a single negative reading is treated as
                // a transient probe failure. Only after TWO consecutive
                // failures do we fire the remount path. This eliminates
                // the false-positive "card unmounted" log that fires after
                // every UI settings save (when the page reflexively walks
                // the SD via /api/storage/external + /api/recordings/stats).
                // FIX (audit R8, LOW): track whether the SD branch fully
                // executed this tick. We must NOT short-circuit the whole
                // lambda on a first-strike SD failure — the USB branch
                // below has its own independent state machine and a
                // first-strike SD probe failure must not delay USB
                // unmount detection by a full 15s tick. We bypass the
                // SD-handling block via a scoped flag instead of the
                // historical `return;`.
                boolean sdHandled = false;
                if (watchSd && !isSdCardLikelyMounted()) {
                    sdWatchdogConsecutiveFailures++;

                    // First failure: silent, just record and let the USB
                    // branch still run. (FIX audit R8, LOW: was `return;`)
                    if (sdWatchdogConsecutiveFailures < 2) {
                        logDebug("SD watchdog: first-strike probe failure, deferring to next tick "
                            + "(USB branch still runs)");
                        sdHandled = true;
                    }
                    if (!sdHandled) {

                    // Only log verbosely for the first few failures, then quiet down
                    boolean shouldLog = sdWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                        sdWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;

                    if (shouldLog) {
                        logWarn("SD card watchdog: card unmounted, attempting remount... (attempt #" +
                            sdWatchdogConsecutiveFailures + ")");
                    }

                    if (ensureSdCardMounted(true)) {
                        logInfo("SD card watchdog: remounted successfully after " +
                            sdWatchdogConsecutiveFailures + " attempts");
                        sdWatchdogConsecutiveFailures = 0;

                        // Restore SD card directories now that card is back
                        initSdCardDirectories();
                        updateActiveDirectories();

                        // Update running sentry engine's output directory
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null) {
                                pipeline.getSentry().setEventOutputDir(getSurveillanceDir());
                                logInfo("SD card watchdog: updated sentry output dir to " +
                                    getSurveillanceDir().getAbsolutePath());
                            }

                            // Also re-poke the pano recorder if recordings
                            // are configured to land on SD. Without this,
                            // an in-progress pano recording silently
                            // continues writing to the vanished SD mount
                            // path captured at startRecording time —
                            // segments are lost until the next mode toggle.
                            // Only future segments / start-recording calls
                            // pick up the new dir; the in-flight segment is
                            // unrecoverable (encoder's segmentBasePath was
                            // fixed at the segment open).
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.SD_CARD) {
                                // FIX (audit R5, LOW): use canonical SD path
                                // directly rather than the volatile recordingsDir
                                // field (which is only swapped by
                                // updateActiveDirectories when no recording is
                                // active — so a hot remount during an in-flight
                                // segment would still read the vanished dir).
                                ResolvedDir rSd = resolveActive(StorageType.SD_CARD,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File newRecordingsDir = (sdCardRecordingsDir != null)
                                    ? sdCardRecordingsDir : rSd.dir;
                                // FIX (audit R2): if the encoder has already
                                // latched writerAbortedCorrupt (SD vanished
                                // mid-segment), the listener bridge in
                                // GpuMosaicRecorder MAY have already cleared
                                // wrapper.recording — but if it didn't fire
                                // (raced, exception path), wrapper.recording
                                // still lies. Belt-and-suspenders: force a
                                // stopRecording() on the wrapper before we
                                // poke setOutputDir. That guarantees
                                // recording=false + recordingActive=false so
                                // RMM's wedge detector + activateMode path
                                // can consume pendingOutputDirOverride on
                                // the very next tick instead of waiting for
                                // ACC OFF/ON or daemon restart.
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("SD card watchdog: encoder writer aborted "
                                            + "but wrapper.recording still true — forcing pipeline.stopRecording()"
                                            + " before setOutputDir to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for full rationale.
                                        // recorder-only stop leaves pipeline.currentMode
                                        // pinned at NORMAL_RECORDING and RMM rejects
                                        // every wedge-retry activateMode forever.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("SD card watchdog: writer-abort stop probe failed: "
                                        + t.getMessage());
                                }
                                pipeline.getRecorder().setOutputDir(newRecordingsDir);
                                logInfo("SD card watchdog: pano recorder output dir updated to " +
                                    newRecordingsDir.getAbsolutePath()
                                    + " (in-flight segment may be lost; future segments use new path)");

                                // FIX (audit R7, HIGH): kick RMM resync immediately
                                // so the freshly-cleared currentMode→IDLE state is
                                // re-armed without waiting up to 30s for the next
                                // ticker. Mirrors the setRecordingsStorageType kick.
                                try {
                                    com.overdrive.app.recording.RecordingModeManager rmm =
                                        com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                    if (rmm != null) {
                                        rmm.resyncFromHardware("sd-watchdog-remount-success");
                                        logInfo("SD card watchdog: kicked RMM resyncFromHardware "
                                            + "to re-arm recording on remounted volume");
                                    }
                                } catch (Throwable rt) {
                                    logWarn("SD card watchdog: RMM resync kick threw: "
                                        + rt.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("SD card watchdog: could not update recorder/sentry dir: " + t.getMessage());
                        }

                        // Re-arm RecordingsIndex against the freshly-mounted
                        // SD dirs + reconcile so existing files on the card
                        // populate the index immediately. Without this, a
                        // hot-mounted SD stays invisible to events.html and
                        // the native fragment until the 1-hour periodic
                        // reconcile.
                        notifyRecordingsIndexOfStorageChange("SD watchdog");
                    } else {
                        // FIX (audit R4): on remount failure, eagerly re-resolve
                        // active directories so recordingsDir / surveillanceDir
                        // fields fall back to internal NOW. Previously this
                        // branch only logged: cleanup + recordings-stats +
                        // pre-flight reserve callers that don't go through
                        // ensureStorageReady continued to hit the vanished SD
                        // path for 30-60s until RMM wedge detection kicked the
                        // pipeline into a fresh start(). Mirrors the success
                        // branch's updateActiveDirectories() call.
                        try {
                            discoverVolumes();
                            updateActiveDirectories();
                            logWarn("SD card watchdog: remount FAILED — fell back active dirs to internal "
                                + "(recordings=" + getRecordingsDir().getAbsolutePath() + ")");
                        } catch (Throwable t) {
                            logWarn("SD card watchdog: remount-failure fallback re-resolve threw: "
                                + t.getMessage());
                        }

                        // FIX (audit R5, HIGH): mirror SUCCESS branch on
                        // FAILURE too. Without this, encoder writer-abort may
                        // not have fired yet (3 disk-write fails needed) OR
                        // FUSE may block the writer indefinitely. Wrapper
                        // recording stays true, recordingsDir stays pinned at
                        // the vanished SD path, setOutputDir is never pushed,
                        // and segments are silently lost until ACC OFF/ON.
                        //
                        // (1) probe writer-aborted+isRecording — if true,
                        //     force stopRecording so wrapper.recording and
                        //     recordingActive synchronously flip false BEFORE
                        //     dir swap.
                        // (2) compute internal-fallback dir directly via
                        //     resolveActive(INTERNAL,...) — bypasses the
                        //     recordingActive-gated recordingsDir field which
                        //     was still pointing at the vanished mount until
                        //     step (1) cleared it.
                        // (3) push setOutputDir(internalFallbackDir) so RMM's
                        //     next start consumes the override and lands on
                        //     internal instead of the dead SD path.
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.SD_CARD) {
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("SD card watchdog: remount FAILED + encoder writer aborted "
                                            + "— forcing pipeline.stopRecording before internal-fallback setOutputDir "
                                            + "to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    } else if (pipeline.getRecorder().isRecording()) {
                                        logWarn("SD card watchdog: remount FAILED while recording — "
                                            + "forcing pipeline.stopRecording so internal-fallback setOutputDir "
                                            + "applies on next start (in-flight segment lost)");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("SD card watchdog: remount-failure stop probe threw: "
                                        + t.getMessage());
                                }
                                // Resolve INTERNAL directly — bypass the
                                // recordingsDir field which may still be stale
                                // until updateActiveDirectories above swapped it.
                                ResolvedDir rFallback = resolveActive(StorageType.INTERNAL,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File internalFallbackDir = rFallback.dir;
                                if (internalFallbackDir != null) {
                                    pipeline.getRecorder().setOutputDir(internalFallbackDir);
                                    logWarn("SD card watchdog: remount FAILED — pushed pano recorder dir "
                                        + "to INTERNAL fallback " + internalFallbackDir.getAbsolutePath()
                                        + " (future segments land on internal until SD recovers)");

                                    // FIX (audit R7, HIGH): kick RMM resync to
                                    // re-arm recording on the internal fallback
                                    // immediately, instead of waiting up to 30s.
                                    try {
                                        com.overdrive.app.recording.RecordingModeManager rmm =
                                            com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                        if (rmm != null) {
                                            rmm.resyncFromHardware("sd-watchdog-remount-failed");
                                            logInfo("SD card watchdog: kicked RMM resyncFromHardware "
                                                + "to re-arm recording on internal fallback");
                                        }
                                    } catch (Throwable rt) {
                                        logWarn("SD card watchdog: RMM resync kick threw: "
                                            + rt.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("SD card watchdog: remount-failure setOutputDir push threw: "
                                + t.getMessage());
                        }

                        if (shouldLog) {
                            logError("SD card watchdog: remount FAILED - surveillance may use internal fallback");
                        }
                    }
                    } // end if (!sdHandled) — FIX audit R8 LOW
                } else if (watchSd) {
                    // Card is healthy — reset failure counter (was a single
                    // transient probe failure, not a real unmount).
                    if (sdWatchdogConsecutiveFailures > 0) {
                        if (sdWatchdogConsecutiveFailures >= 2) {
                            logInfo("SD card watchdog: card is mounted again");
                        }
                        sdWatchdogConsecutiveFailures = 0;
                    }
                }

                // USB watchdog branch — independent state machine but shares
                // the schedule. Per user spec: USB-only configs must also fall
                // back to internal transparently when the stick disappears
                // mid-recording, but ALSO get a remount attempt when the
                // bus settles. Without this branch a USB-only surveillance
                // config that loses its drive stays on internal forever.
                // FIX (audit R5): mirror SD branch — use cheap fork-free
                // probe (StatFs+canWrite) so the per-minute tick never forks
                // a shell, AND apply two-strikes (single negative reading is
                // a transient probe failure, not real unmount). Eliminates the
                // false-positive remount cascade after every UI settings save.
                // FIX (audit R8, LOW): mirror SD-side fix — never `return;`
                // out of the whole tick on a first-strike USB probe
                // failure. The SD branch above is also independent and
                // its state must not be skipped if/when USB transient
                // failures stack at the start of a tick.
                boolean usbHandled = false;
                if (watchUsb && !isUsbLikelyMounted()) {
                    usbWatchdogConsecutiveFailures++;

                    // First failure: silent, just record and let the rest
                    // of the tick run. (FIX audit R8 LOW: was `return;`)
                    if (usbWatchdogConsecutiveFailures < 2) {
                        logDebug("USB watchdog: first-strike probe failure, deferring to next tick");
                        usbHandled = true;
                    }
                    if (!usbHandled) {

                    boolean shouldLogUsb = usbWatchdogConsecutiveFailures <= SD_WATCHDOG_MAX_VERBOSE_FAILURES ||
                                           usbWatchdogConsecutiveFailures % SD_WATCHDOG_QUIET_LOG_INTERVAL == 0;
                    if (shouldLogUsb) {
                        logWarn("USB watchdog: drive unmounted, attempting remount... (attempt #" +
                            usbWatchdogConsecutiveFailures + ")");
                    }
                    if (ensureUsbMounted(true)) {
                        logInfo("USB watchdog: remounted successfully after " +
                            usbWatchdogConsecutiveFailures + " attempts");
                        usbWatchdogConsecutiveFailures = 0;
                        initUsbDirectories();
                        updateActiveDirectories();
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getSentry() != null
                                    && surveillanceStorageType == StorageType.USB) {
                                pipeline.getSentry().setEventOutputDir(getSurveillanceDir());
                                logInfo("USB watchdog: updated sentry output dir to " +
                                    getSurveillanceDir().getAbsolutePath());
                            }

                            // Pano recorder dir re-poke: same rationale as
                            // the SD branch — gated to USB-backed recordings
                            // since otherwise the recordings dir didn't move.
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.USB) {
                                // FIX (audit R5, LOW): use canonical USB path
                                // directly — see SD branch comment for rationale.
                                ResolvedDir rUsb = resolveActive(StorageType.USB,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File newRecordingsDir = (usbRecordingsDir != null)
                                    ? usbRecordingsDir : rUsb.dir;
                                // FIX (audit R2): same writerAborted belt-and-
                                // suspenders as the SD branch — see comment
                                // there for full rationale.
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("USB watchdog: encoder writer aborted "
                                            + "but wrapper.recording still true — forcing pipeline.stopRecording()"
                                            + " before setOutputDir to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("USB watchdog: writer-abort stop probe failed: "
                                        + t.getMessage());
                                }
                                pipeline.getRecorder().setOutputDir(newRecordingsDir);
                                logInfo("USB watchdog: pano recorder output dir updated to " +
                                    newRecordingsDir.getAbsolutePath()
                                    + " (in-flight segment may be lost; future segments use new path)");

                                // FIX (audit R7, HIGH): kick RMM resync to re-arm
                                // recording immediately on the freshly-remounted
                                // USB volume. Mirrors SD watchdog success branch.
                                try {
                                    com.overdrive.app.recording.RecordingModeManager rmm =
                                        com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                    if (rmm != null) {
                                        rmm.resyncFromHardware("usb-watchdog-remount-success");
                                        logInfo("USB watchdog: kicked RMM resyncFromHardware "
                                            + "to re-arm recording on remounted volume");
                                    }
                                } catch (Throwable rt) {
                                    logWarn("USB watchdog: RMM resync kick threw: "
                                        + rt.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("USB watchdog: could not update recorder/sentry dir: " + t.getMessage());
                        }

                        // Same RecordingsIndex re-arm + reconcile pattern as
                        // the SD watchdog branch — see comment there. Hot-
                        // mounted USB sticks otherwise stay invisible to the
                        // index until the 1-hour periodic reconcile.
                        notifyRecordingsIndexOfStorageChange("USB watchdog");
                    } else {
                        // FIX (audit R4): symmetric to SD branch — eagerly fall
                        // back active dirs to internal on USB remount failure
                        // so cleanup / recordings-stats / pre-flight reserve
                        // callers see truthful paths immediately.
                        try {
                            discoverVolumes();
                            updateActiveDirectories();
                            logWarn("USB watchdog: remount FAILED — fell back active dirs to internal "
                                + "(recordings=" + getRecordingsDir().getAbsolutePath() + ")");
                        } catch (Throwable t) {
                            logWarn("USB watchdog: remount-failure fallback re-resolve threw: "
                                + t.getMessage());
                        }

                        // FIX (audit R5, HIGH): identical recovery to SD
                        // FAILURE branch — see comment there. Without this,
                        // a USB-only configuration that loses the stick mid-
                        // segment continues to write to the vanished mount
                        // until ACC OFF/ON or daemon restart.
                        try {
                            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                            if (pipeline != null && pipeline.getRecorder() != null
                                    && recordingsStorageType == StorageType.USB) {
                                try {
                                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                                        pipeline.getRecorder().getEncoder();
                                    if (enc != null && enc.isWriterAborted()
                                            && pipeline.getRecorder().isRecording()) {
                                        logWarn("USB watchdog: remount FAILED + encoder writer aborted "
                                            + "— forcing pipeline.stopRecording before internal-fallback setOutputDir "
                                            + "to unblock RMM wedge recovery");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    } else if (pipeline.getRecorder().isRecording()) {
                                        logWarn("USB watchdog: remount FAILED while recording — "
                                            + "forcing pipeline.stopRecording so internal-fallback setOutputDir "
                                            + "applies on next start (in-flight segment lost)");
                                        // FIX (audit R7, HIGH): pipeline.stopRecording()
                                        // not recorder.stopRecording(); see
                                        // setRecordingsStorageType for rationale.
                                        pipeline.stopRecording();
                                    }
                                } catch (Throwable t) {
                                    logWarn("USB watchdog: remount-failure stop probe threw: "
                                        + t.getMessage());
                                }
                                ResolvedDir rFallback = resolveActive(StorageType.INTERNAL,
                                    internalRecordingsDir, sdCardRecordingsDir, usbRecordingsDir,
                                    "recordings");
                                java.io.File internalFallbackDir = rFallback.dir;
                                if (internalFallbackDir != null) {
                                    pipeline.getRecorder().setOutputDir(internalFallbackDir);
                                    logWarn("USB watchdog: remount FAILED — pushed pano recorder dir "
                                        + "to INTERNAL fallback " + internalFallbackDir.getAbsolutePath()
                                        + " (future segments land on internal until USB recovers)");

                                    // FIX (audit R7, HIGH): kick RMM resync to
                                    // re-arm recording on the internal fallback
                                    // immediately, mirroring SD failure branch.
                                    try {
                                        com.overdrive.app.recording.RecordingModeManager rmm =
                                            com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                        if (rmm != null) {
                                            rmm.resyncFromHardware("usb-watchdog-remount-failed");
                                            logInfo("USB watchdog: kicked RMM resyncFromHardware "
                                                + "to re-arm recording on internal fallback");
                                        }
                                    } catch (Throwable rt) {
                                        logWarn("USB watchdog: RMM resync kick threw: "
                                            + rt.getMessage());
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("USB watchdog: remount-failure setOutputDir push threw: "
                                + t.getMessage());
                        }

                        if (shouldLogUsb) {
                            logError("USB watchdog: remount FAILED - surveillance may use internal fallback");
                        }
                    }
                    } // end if (!usbHandled) — FIX audit R8 LOW
                } else if (watchUsb) {
                    // FIX (audit R5): mirror SD reset path — only log "mounted
                    // again" when we actually crossed the two-strikes threshold,
                    // otherwise the tick is a single transient probe failure
                    // recovering on the very next read (not user-visible).
                    if (usbWatchdogConsecutiveFailures > 0) {
                        if (usbWatchdogConsecutiveFailures >= 2) {
                            logInfo("USB watchdog: drive is mounted again");
                        }
                        usbWatchdogConsecutiveFailures = 0;
                    }
                }
            } catch (Exception e) {
                logWarn("Volume watchdog error: " + e.getMessage());
            }
        }, SD_WATCHDOG_INTERVAL_SECONDS, SD_WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        logInfo("Started volume mount watchdog (interval=" + SD_WATCHDOG_INTERVAL_SECONDS +
            "s, sd=" + watchSd + ", usb=" + watchUsb + ")");
    }

    /**
     * Stop SD card mount watchdog (call when exiting sentry mode or ACC comes back on).
     */
    public void stopSdCardWatchdog() {
        if (sdCardWatchdog != null) {
            sdCardWatchdog.shutdown();
            try {
                if (!sdCardWatchdog.awaitTermination(3, TimeUnit.SECONDS)) {
                    sdCardWatchdog.shutdownNow();
                }
            } catch (InterruptedException e) {
                sdCardWatchdog.shutdownNow();
            }
            sdCardWatchdog = null;
            logInfo("Stopped SD card mount watchdog");
        }
    }
    
    /**
     * Set recording active state. Periodic cleanup runs continuously regardless
     * (started at daemon boot via {@link #startPeriodicCleanup()}); this flag
     * is kept for callers that may consult {@link #isRecordingActive()}.
     */
    public void setRecordingActive(boolean active) {
        recordingActive.set(active);
    }

    /**
     * Wires the authoritative "encoder is currently writing" probe used by
     * the cleanup gate. Should point at HardwareEventRecorderGpu.isWritingToFile.
     * Pipeline init wires this once; release-and-reinit cycles can re-wire.
     * Passing null reverts to the default (always false → cleanup never blocked).
     */
    public void setEncoderWritingProbe(java.util.function.BooleanSupplier probe) {
        this.encoderWritingProbe = probe != null ? probe : () -> false;
        if (probe != null) {
            probeWired.set(true);
        }
    }

    /**
     * True when the encoder is actively writing packets to disk. The cleanup
     * paths (post-save, periodic, sidecar) consult this before running
     * destructive deletes; if true, the cleanup is deferred to the deferred
     * queue and drained on the next non-recording pass.
     *
     * Cheap (volatile read) — safe to call from any thread, every iteration.
     */
    private boolean isEncoderWriting() {
        try {
            return encoderWritingProbe.getAsBoolean();
        } catch (Exception e) {
            // A buggy probe must never block cleanup forever — fail open on
            // recoverable exceptions. Errors (OOM, StackOverflow, LinkageError)
            // propagate; "treat the JVM as healthy and run a delete burst" is
            // the wrong default response to a process that's already broken.
            return false;
        }
    }

    /**
     * Set surveillance active state. See {@link #setRecordingActive(boolean)}
     * for periodic-cleanup lifetime semantics.
     */
    public void setSurveillanceActive(boolean active) {
        surveillanceActive.set(active);
    }

    /**
     * Mark a trip telemetry file as in-flight so {@link #ensureSpace} skips
     * it during cleanup. The recorder still writes through a buffered
     * GZIPOutputStream; if cleanup were to delete and unlink the file mid-write
     * on Linux, subsequent writes go to a still-open fd whose bytes are lost
     * once close() runs (the inode is reaped at fd-close, not at unlink).
     *
     * Pass {@code null} on stop. Path-based rather than a boolean so older
     * trip files can still be reaped during an active trip.
     */
    public void setActiveTripFile(File file) {
        activeTripFilePath = (file != null) ? file.getAbsolutePath() : null;
    }
    
    /**
     * Check if recording is active.
     */
    public boolean isRecordingActive() {
        return recordingActive.get();
    }
    
    /**
     * Check if surveillance is active.
     */
    public boolean isSurveillanceActive() {
        return surveillanceActive.get();
    }
    
    /**
     * Wipes every media file (and JSON sidecars) for the given category from
     * all known storage locations — active dir, internal fallback, and SD-card
     * mirror — plus thumbnails for that category.
     *
     * Used by the user-initiated "Reset Data" feature. Holds the per-category
     * cleanup lock for {@code category} so it cannot race with periodic cleanup
     * or any in-flight delete in that category.
     *
     * @param category one of "recordings", "surveillance", "proximity", "trips"
     * @return number of files deleted, or -1 on unknown category
     */
    public long wipeMediaCategory(String category) {
        if (category == null) return -1;
        List<File> dirs;
        switch (category) {
            case "recordings":  dirs = getAllRecordingsDirs(); break;
            case "surveillance": dirs = getAllSurveillanceDirs(); break;
            case "proximity":   dirs = getAllProximityDirs(); break;
            case "trips":       dirs = getAllTripsDirs(); break;
            default: return -1;
        }

        // FIX (audit R4): protect the in-flight encoder output and any *.tmp
        // newer than the 10-min grace window used by sweepOrphanTempFiles.
        // Without these gates, a user-initiated Reset Data → Recordings during
        // an active CONTINUOUS / DRIVE_MODE pano session unlinks the open
        // *.mp4.tmp the encoder is currently writing into; recovery only
        // happens after RMM wedge detection (~30-60 s).
        //
        // Probe the encoder's active path through the live pipeline. We look
        // up GpuSurveillancePipeline lazily so the wipe still works when no
        // recorder exists (e.g., daemon shutdown). For the trips category we
        // also honour activeTripFilePath like sweepOrphanTempFiles does.
        String activeEncoderPath = null;
        String activeEncoderTmpPath = null;
        if ("recordings".equals(category) || "surveillance".equals(category)
                || "proximity".equals(category)) {
            try {
                com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                if (pipeline != null && pipeline.getRecorder() != null) {
                    com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                        pipeline.getRecorder().getEncoder();
                    if (enc != null) {
                        // Force a clean segment finalise so the encoder is no
                        // longer holding an open fd against any path we are
                        // about to nuke. Best-effort — if it throws or the
                        // recorder isn't actually recording the call is a
                        // no-op.
                        try {
                            if (pipeline.getRecorder().isRecording()) {
                                logWarn("wipeMediaCategory(" + category + "): recording active, "
                                    + "forcing pipeline.stopRecording before wipe to finalise current segment");
                                // FIX (audit R7, HIGH): pipeline.stopRecording()
                                // not recorder.stopRecording(); recorder-only
                                // stop leaves pipeline.currentMode pinned at
                                // NORMAL_RECORDING and RMM rejects re-activation,
                                // wedging recording until ACC OFF/ON. See
                                // setRecordingsStorageType for full rationale.
                                pipeline.stopRecording();
                                // Kick RMM so re-activation runs immediately on
                                // the next ticker rather than after up-to-30s.
                                try {
                                    com.overdrive.app.recording.RecordingModeManager rmm =
                                        com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                    if (rmm != null) {
                                        rmm.resyncFromHardware("wipe-media-" + category);
                                    }
                                } catch (Throwable rt) {
                                    logWarn("wipeMediaCategory: RMM resync kick threw: "
                                        + rt.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            logWarn("wipeMediaCategory: stopRecording before wipe threw: "
                                + t.getMessage());
                        }
                        activeEncoderPath = enc.getCurrentOutputPath();
                        if (activeEncoderPath != null) {
                            activeEncoderTmpPath = activeEncoderPath + ".tmp";
                        }
                    }
                }
            } catch (Throwable t) {
                logWarn("wipeMediaCategory: encoder-path probe threw: " + t.getMessage());
            }
        }
        final String protectedTripPath = "trips".equals(category) ? activeTripFilePath : null;
        final String protEncoderPath = activeEncoderPath;
        final String protEncoderTmpPath = activeEncoderTmpPath;
        final long tmpGraceCutoff = System.currentTimeMillis() - (10L * 60L * 1000L);

        long deleted = 0;
        long skippedActive = 0;
        synchronized (lockForCategory(category)) {
            for (File dir : dirs) {
                if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f.isFile()) {
                        String name = f.getName();
                        String absPath = f.getAbsolutePath();
                        // Skip the encoder's currently-open output path and
                        // its .tmp companion.
                        if (protEncoderPath != null
                                && (absPath.equals(protEncoderPath)
                                    || absPath.equals(protEncoderTmpPath))) {
                            skippedActive++;
                            continue;
                        }
                        // Skip in-flight trip file (mirrors sweepOrphanTempFiles).
                        if (protectedTripPath != null
                                && (protectedTripPath.equals(absPath)
                                    || protectedTripPath.equals(absPath + ".tmp"))) {
                            skippedActive++;
                            continue;
                        }
                        // Honour the same 10-min grace window for any *.tmp
                        // partial — newer than that and a writer may still
                        // hold it open.
                        if (name.endsWith(".tmp") && f.lastModified() > tmpGraceCutoff) {
                            skippedActive++;
                            continue;
                        }
                        if (f.delete()) {
                            deleted++;
                            // Drop the H2 row eagerly so the next
                            // /api/recordings call doesn't return a phantom
                            // entry for a just-wiped file. Mirrors the
                            // single-file deleteRecording path.
                            if (name.endsWith(".mp4")) {
                                try {
                                    com.overdrive.app.server.RecordingsIndex
                                            .getInstance().remove(name);
                                } catch (Throwable ignored) {
                                    // Index not initialised in this
                                    // process; reconcile() will catch up.
                                }
                            }
                        }
                    }
                }
            }

            // Best-effort thumbnail cleanup. Thumbnails live alongside the
            // active dir's parent in a "thumbs" subfolder; nuking the whole
            // dir would also kill any other category's thumbs, so we limit
            // to those derived from the just-wiped filenames. Cheaper to
            // just blow away the whole thumbs dir on a media wipe.
            try {
                File baseDir = (dirs.isEmpty() || dirs.get(0).getParentFile() == null)
                    ? null : dirs.get(0).getParentFile();
                if (baseDir != null) {
                    File thumbs = new File(baseDir, "thumbs");
                    if (thumbs.exists() && thumbs.isDirectory()) {
                        File[] thumbFiles = thumbs.listFiles();
                        if (thumbFiles != null) {
                            for (File t : thumbFiles) if (t.isFile()) t.delete();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        logInfo("wipeMediaCategory(" + category + ") deleted " + deleted + " files"
            + (skippedActive > 0 ? " (skipped " + skippedActive
                + " in-flight/grace-window files)" : ""));
        return deleted;
    }

    /**
     * Shutdown all background threads.
     * Call this when the app is terminating.
     */
    public void shutdown() {
        stopPeriodicCleanup();
        stopSdCardWatchdog();
        
        asyncCleanupExecutor.shutdown();
        try {
            if (!asyncCleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                asyncCleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncCleanupExecutor.shutdownNow();
        }
        
        logInfo("StorageManager shutdown complete");
    }
}
