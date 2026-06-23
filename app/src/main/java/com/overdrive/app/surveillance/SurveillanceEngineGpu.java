package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.ai.YoloDetector;
import com.overdrive.app.telegram.TelegramNotifier;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SurveillanceEngineGpu - V2 Per-Quadrant Motion Detection Pipeline
 * 
 * Uses the V2 native pipeline for per-quadrant 6-stage motion detection
 * with staggered YOLO AI inference on active quadrants.
 */
public class SurveillanceEngineGpu {
    private static final String TAG = "SurveillanceEngineGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Motion detection buffers
    private ByteBuffer currentFrame;
    private long lastMotionTime = 0;
    private long firstMotionTime = 0;  // When sustained motion started (for duration check)
    
    // SUSTAINED MOTION: Base minimum before any trigger (prevents single-frame noise).
    // For THREAT_HIGH (loitering confirmed), this is the only delay needed.
    // For THREAT_MEDIUM (approaching), the loitering time setting adds additional delay.
    private static final long SUSTAINED_MOTION_BASE_MS = 500;

    // FLAG / SHADOW FALSE-POSITIVE GUARD.
    // A waving flag (or a sweeping cast shadow) is genuine, spatially-anchored
    // pixel motion: its connected-component centroid barely drifts, so the
    // native Stage-5 classifies it as THREAT_HIGH "loiter" — which historically
    // bypassed the YOLO confirmation gate and fired a recording after only
    // SUSTAINED_MOTION_BASE_MS. The discriminator is motion DIRECTIONALITY: a
    // real intruder TRANSLATES (per-block flow accumulates a coherent net
    // displacement, coherence ratio → 1); a flag/foliage/shadow OSCILLATES in
    // place (vectors cancel, ratio → 0, cumulative net drift ≈ 0).
    //
    // A THREAT_HIGH is "trusted" (keeps the fast 500ms, YOLO-exempt path) only
    // when an in-zone PERSON tracker holds it OR the native flow-coherence
    // signal says the motion is coherently translating. An untrusted HIGH
    // (flag/shadow) is DOWNGRADED to a YOLO-gated MEDIUM — it still records via
    // the existing YOLO-confirm / 2s-timeout / no-YOLO fallbacks, just on the
    // same evidence bar as MEDIUM, so a flag (never a YOLO person/car) stops
    // self-triggering. Tuning lives in MotionPipelineV2.Config.coherence*.
    private static final float COHERENCE_RATIO_MIN = 0.35f;
    private static final float COHERENCE_NET_MIN = 1.5f;

    // Loitering time in ms — derived from user setting (1-10 seconds).
    // THREAT_MEDIUM must persist for this duration before triggering recording.
    // THREAT_HIGH triggers after SUSTAINED_MOTION_BASE_MS (loitering already confirmed by native pipeline).
    private long loiteringTimeMs = 3000;  // Default 3 seconds

    // APPROACH FAST-PATH: sustained-motion ms required to record a MEDIUM(approach)
    // event ONCE YOLO has confirmed a real in-zone object during the sequence.
    // Short and responsive (default 2s) so someone walking up to / past the car is
    // recorded without waiting out the full loiter dwell. 0 disables it (every
    // MEDIUM then needs loiteringTimeMs, the legacy behavior). Motion with NO AI
    // confirmation always uses loiteringTimeMs regardless, so this can't lower the
    // bar for lighting artifacts / flags / shadows (they never yield a YOLO object).
    private long approachTriggerMs = 2000;

    // MOTION THROTTLING: Process motion at 10 FPS max (saves 66% CPU vs 30 FPS)
    private static final long MOTION_PROCESS_INTERVAL_MS = 100;  // 10 FPS
    private long lastMotionProcessTime = 0;
    
    // ROI mask (null = full frame, otherwise byte array with 0/1 values)
    private byte[] roiMask = null;
    private int roiPixelCount = 0;  // Number of pixels in ROI (for normalization)
    
    // Reference to downscaler for buffer recycling
    private GpuDownscaler downscaler;
    
    // Reference to mosaic recorder for triggering recording
    private GpuMosaicRecorder recorder;
    
    // SOTA: Grid Motion Configuration
    // 640x480 / 32 = 20x15 grid. 32px blocks are ideal for human detection at distance.
    private static final int GRID_BLOCK_SIZE = 32;
    private static final int GRID_COLS = 640 / GRID_BLOCK_SIZE;  // 20
    private static final int GRID_ROWS = 480 / GRID_BLOCK_SIZE;  // 15
    private static final int TOTAL_BLOCKS = GRID_COLS * GRID_ROWS;  // 300
    
    // SIMPLIFIED: Frame-to-frame motion detection
    private int requiredActiveBlocks = 3;    // Need 3+ blocks changed to trigger
    
    // SOTA: Flash Immunity Level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
    // Uses edge-based detection to ignore light flashes while detecting real motion
    private int flashImmunity = 2;  // Default: MEDIUM
    
    // SOTA: Unified configuration for motion detection, flash filtering, and distance estimation
    private SurveillanceConfig config = createDefaultConfig();
    
    /**
     * Creates default config with proper resolution for mosaic mode.
     * SOTA: Enables chroma filtering by default to ignore lighting changes.
     */
    private static SurveillanceConfig createDefaultConfig() {
        SurveillanceConfig cfg = new SurveillanceConfig(
            SurveillanceConfig.DistancePreset.MEDIUM,
            SurveillanceConfig.FlashMode.ADAPTIVE
        );
        // CRITICAL: Set resolution to match THUMBNAIL dimensions
        cfg.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        cfg.setIsMosaic(true);  // We use 2x2 mosaic layout
        cfg.setUseChroma(true); // SOTA: Enable chroma filtering to ignore lighting changes
        return cfg;
    }
    
    // Track active blocks for UI display
    private int lastActiveBlocksCount = 0;
    private int lastTemporalBlocksCount = 0;  // SOTA: Temporally consistent blocks
    private int lastMotionMinY = 0;           // SOTA: Top of motion bounding box
    private int lastMotionMaxY = 0;           // SOTA: Bottom of motion bounding box
    private float lastEstimatedDistance = 0;  // SOTA: Estimated distance in meters
    
    // Pre-record and post-record configuration (configurable via API)
    private long preRecordMs = 5000;    // 5 seconds before motion (default)
    private long postRecordMs = 10000;  // 10 seconds after motion (default)
    private long recordingStopTime = 0;  // When to stop recording (motion time + post-record)
    private long lastRecordingStopTime = 0;  // When last recording stopped (for cooldown)
    private static final long NO_AI_MIN_GAP_MS = 30_000;
    // Absolute wall-clock time of the trigger that started the current recording.
    // Used to enforce a hard ceiling on total recording length (3× postRecordMs)
    // so a stuck tracker, a sustained shadow loop, or a misbehaving residual
    // motion source cannot extend recording indefinitely. Reset to 0 when
    // recording stops. The user's earlier 50s clip for a brief approach was
    // diagnosed to this missing ceiling.
    private long recordingTriggerStartMs = 0;
    
    // DETERRENT FLASH SUPPRESSION: After the deterrent fires, suppress new motion triggers
    // for a window that covers the cloud API round-trip + flash sequence + ring buffer flush.
    // The BYD cloud flash_lights command has ~15s network latency (dispatch → poll → execute).
    // The lights then flash for 2-3 seconds. Total: 20 seconds from dispatch to scene stable.
    // This window prevents the deterrent's own light from triggering a second recording.
    //
    // WHY AI DOESN'T CATCH THIS (without the fix below):
    // The DetectionBaseline only filtered YOLO output for THREAT_LOW events. For MEDIUM/HIGH,
    // recording triggered purely from the motion pipeline. The deterrent light (and any
    // external light source) creates persistent edge differences that Stage 5 classifies as
    // THREAT_HIGH (loitering) or THREAT_MEDIUM (approaching). The fix extends the YOLO gate
    // to MEDIUM always, and to HIGH during the deterrent window.
    private static final long DETERRENT_SUPPRESSION_MS = 20000;
    private long deterrentFiredTime = 0;  // Timestamp when deterrent was last dispatched

    // NO-AI rate limit: minimum gap between consecutive motion-triggered recordings
    // when YOLO is unavailable (daemon-classpath OR user-disabled all classes). Without
    // YOLO, motion-only triggering can fire continuously on wind/shadow/streetlight
    // artifacts; each retrigger forces a fresh muxer init + pre-record flush, leaking
    // MediaCodec slots and direct-buffer RSS until the daemon SIGABRTs or LMK takes
    // it. 30s is empirically enough headroom that the muxer pool drains and the
    // codec's hardware refcount returns to baseline before the next event fires,
    // while still catching real intruders quickly (post-record covers the gap on
    // sustained loitering, and the trigger fires immediately past the gap if motion
    // continues). Only active when aiAvailable=false; YOLO installs are unaffected.
    
    // YOLO CONFIRMATION GATE: Track when YOLO last confirmed a real threat object.
    // For THREAT_MEDIUM: recording requires YOLO confirmation within the motion sequence
    // (with 2-second timeout fallback if YOLO is unavailable/broken).
    // For THREAT_HIGH: only gated during deterrent window (loitering evidence is strong enough otherwise).
    // This makes AI-based background subtraction effective against ALL lighting artifacts,
    // not just the deterrent flash.
    private volatile long lastAiConfirmationTimeMs = 0;  // When YOLO last found a real object
    
    // Detection mode
    private boolean useObjectDetection = false;
    // FIX (A8/B3): volatile so the AI executor sees writes from the UI thread
    // without a torn read. The lambda still snapshots into a local before use.
    private volatile YoloDetector yoloDetector = null;
    // FIX (Bug B): retain context references so we can lazily re-init the YOLO detector
    // when the user re-enables object detection after disabling all classes.
    private android.content.Context yoloContext = null;
    private android.content.res.AssetManager yoloAssetManager = null;
    
    // Object detection filters (SOTA: Quadrant-relative height filter in YoloDetector)
    private float minObjectSize = 0.12f;  // 12% of QUADRANT height (~8m for person in 2x2 grid)
    private float aiConfidence = 0.25f;  // 25% confidence (lowered for debugging)
    // FIX (Bug B): tri-state semantics for classFilter:
    //   null            -> uninitialised, fall back to "all classes" defaults
    //   length == 0     -> user explicitly disabled all classes; YOLO must be skipped
    //   length >  0     -> only those COCO class IDs are kept
    private int[] classFilter = null;
    // Mirror of "should AI run at all" derived from classFilter; cheaper to read on hot path.
    private volatile boolean aiEnabled = true;
    
    // AI throttling - only run YOLO every 500ms to save CPU
    private long lastAiTimeMs = 0;
    private static final long AI_COOLDOWN_MS = 500;
    
    // --- SOTA FIX: Persistent Resources (Eliminates GC Stutter) ---
    // 1. Reusable Buffer: Prevents ~900KB allocation per frame
    // Per-thread scratch buffer for cropFromMosaic. Previously a single
    // shared byte[] was racy: the main render thread (processFrame, tracker
    // update) and the aiExecutor thread (baseline seed / lighting refresh /
    // post-suppression refresh) both call cropFromMosaic, and concurrent
    // System.arraycopy into the same buffer can produce torn rows. All
    // call sites that retain the result already defensive-copy, but the
    // arraycopy ITSELF is racy — torn rows feed YOLO garbage. ThreadLocal
    // gives each thread its own scratch with no synchronization overhead.
    private final ThreadLocal<byte[]> aiBufferTL = new ThreadLocal<>();
    // 2. Single Thread Executor: Prevents OS thread creation overhead.
    //    Runs at THREAD_PRIORITY_BACKGROUND so a 200-300ms CPU YOLO inference
    //    can't preempt the camera-frame producer or encoder-feed thread.
    //
    //    GPU delegate intentionally removed (see YoloDetector class doc):
    //    on Adreno 610 / SD662 the GPU and the H.265 encoder share one DDR
    //    bus, and concurrent OpenCL inference produced 200–300 ms eglSwap
    //    stalls during recording. CPU XNNPACK at nice +10 wins/loses CFS
    //    contention as scheduled and never competes with the encoder for
    //    memory bandwidth on the GPU side.
    //
    //    The thread factory below sets BACKGROUND priority once at thread
    //    creation. {@link #priorityAffirmingExecutor} re-applies it at the
    //    start of every submitted task — this is the Android 11/12-portable
    //    defense against EAS scheduler migration that can otherwise reset a
    //    long-lived executor thread's priority class out from under us. On
    //    Android 10 the re-apply is a no-op steady-state; cost is one
    //    setThreadPriority syscall (~µs) per inference.
    private final ExecutorService aiExecutorRaw = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "SentryAiExecutor");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * Wraps {@link #aiExecutorRaw} so every submitted Runnable is preceded
     * by a {@code Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND)}
     * call. See the field comment above for why this is needed despite the
     * thread factory already setting priority once.
     */
    private final ExecutorService aiExecutor = new java.util.concurrent.AbstractExecutorService() {
        @Override public void execute(Runnable command) {
            aiExecutorRaw.execute(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                command.run();
            });
        }
        @Override public void shutdown()                         { aiExecutorRaw.shutdown(); }
        @Override public java.util.List<Runnable> shutdownNow()  { return aiExecutorRaw.shutdownNow(); }
        @Override public boolean isShutdown()                    { return aiExecutorRaw.isShutdown(); }
        @Override public boolean isTerminated()                  { return aiExecutorRaw.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException                      { return aiExecutorRaw.awaitTermination(timeout, unit); }
    };
    // 3. Atomic Flag for thread safety
    private final AtomicBoolean isAiRunning = new AtomicBoolean(false);

    // 4. Scheduler for staggered inference dispatch. Used by the baseline
    //    seeder to space the four per-quadrant YOLO calls ~500 ms apart.
    //    Firing them back-to-back back-pressures the single-thread
    //    aiExecutor with 4 × ~250 ms work items in a row (~1 s of pending
    //    inference), which delays the next legitimate motion-triggered
    //    YOLO call by up to a full second. Spacing the seed calls 500 ms
    //    apart keeps the executor available for live work between ticks.
    //    The actual detect() still runs on aiExecutor so all CPU TFLite
    //    state stays on a single thread (TFLite Interpreter is not
    //    thread-safe across runs).
    private final java.util.concurrent.ScheduledExecutorService aiScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SentryAiScheduler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    // --- END SOTA FIX ---
    
    // State
    private volatile boolean active = false;
    private boolean inActiveMode = false;
    private boolean recording = false;
    // True only when THIS engine's surveillance event opened the OEM
    // dashcam recording. Pre-fix the matching stop ran unconditionally,
    // finalizing a user-initiated continuous OEM recording mid-segment.
    // Now we track ownership: started-by-surveillance ⇒ stopped-by-surveillance;
    // already-recording-when-surveillance-fired ⇒ left alone on event end.
    private volatile boolean oemEventOwned = false;
    // Captured pipeline generation at the moment we acquired ownership.
    // If the pipeline is rebuilt (e.g. quality-mirror restart) BETWEEN
    // start and stop, the new instance's generation differs and we MUST
    // NOT stop it — that would kill the user's NEW continuous clip.
    private volatile int oemEventOwnedGeneration = -1;
    // ACC-OFF mode: false (default) runs the V2 motion + YOLO event pipeline;
    // true bypasses everything and writes a continuous rolling 4-cam mosaic.
    // Latched at enable() from UnifiedConfigManager.surveillance.accOffMode so
    // a mid-session config flip can't tear the recorder down half-way.
    private volatile boolean continuousMode = false;
    
    // V2 Pipeline: Per-quadrant 6-stage motion detection
    private MotionPipelineV2 pipelineV2 = null;
    private MotionPipelineV2.Config pipelineV2Config = null;
    // Staggered YOLO: queue of quadrants to run AI on.
    // Bounded + de-duplicating: there are only 4 quadrants, so a bitset-backed
    // ArrayDeque guarantees the queue can never grow past 4 entries no matter
    // how many motion events fire. Without this bound, sustained 4-quadrant
    // motion would pile up dozens of pending inferences (the executor processes
    // at AI_COOLDOWN_MS = 500ms; bursts at 10 FPS add 4 per frame), every one
    // of which holds GPU/DSP cycles when it eventually runs and contributes to
    // the recording stutter. add() is idempotent for already-queued quadrants.
    private final java.util.ArrayDeque<Integer> aiQuadrantQueue = new java.util.ArrayDeque<>(4);
    private int aiQuadrantQueueMask = 0;  // bit q = quadrant q is in queue
    // P1 #12: lock guards the deque + mask as one unit. Callers come from
    // AiLaneWorker (processFrameV2) and the aiExecutor lambda (heartbeat /
    // post-suppression refresh paths); without this the deque could throw
    // ConcurrentModificationException and mask/deque could decohere.
    private final Object aiQuadrantQueueLock = new Object();

    private void aiQuadrantQueueAdd(int q) {
        if (q < 0 || q >= MotionPipelineV2.NUM_QUADRANTS) return;
        int bit = 1 << q;
        synchronized (aiQuadrantQueueLock) {
            if ((aiQuadrantQueueMask & bit) != 0) return;  // already queued
            aiQuadrantQueueMask |= bit;
            aiQuadrantQueue.addLast(q);
        }
    }

    private Integer aiQuadrantQueuePoll() {
        synchronized (aiQuadrantQueueLock) {
            Integer q = aiQuadrantQueue.pollFirst();
            if (q != null) aiQuadrantQueueMask &= ~(1 << q);
            return q;
        }
    }

    private void aiQuadrantQueueClear() {
        synchronized (aiQuadrantQueueLock) {
            aiQuadrantQueue.clear();
            aiQuadrantQueueMask = 0;
        }
    }

    private boolean aiQuadrantQueueIsEmpty() {
        synchronized (aiQuadrantQueueLock) {
            return aiQuadrantQueue.isEmpty();
        }
    }
    
    // Foveated AI cropping: high-res 640×640 crop from raw camera strip
    private FoveatedCropper foveatedCropper = null;
    private int cameraTextureId = -1;  // OES texture for foveated crop
    // Vestigial. Foveated crops now run on AiLaneGl's dedicated GL thread
    // (which calls serviceFoveatedRequestsOnGlThread once per AI tick),
    // not posted via this handler. The setter is left in place because
    // PanoramicCameraGpu still calls it during initialization for backwards
    // compat with the API surface; the field is never read.
    private android.os.Handler glHandler = null;
    // Camera FPS — surfaced from PanoramicCameraGpu.setCameraTargetFps()
    // for diagnostic logging in the periodic stats line. No longer
    // load-bearing for any timeout (the cropOnGlThread path it used to
    // size was removed when the foveated mailbox replaced post-Runnable
    // dispatch).
    private volatile int cameraTargetFps = 0;

    // ==================== FOVEATED MAILBOX (Option B, full async) ====================
    //
    // Old design: AI worker called cropOnGlThread() which posted a Runnable to
    // glHandler and blocked waiting. That post competed with the render loop's
    // self-post for handler slots, AND FoveatedCropper.crop() internally called
    // glFinish() which blocks on any GPU work in flight (including unrelated
    // YOLO OpenCL jobs). Result: 100-300ms gaps in eglSwapBuffers cadence,
    // baked into the encoded MP4 as PTS jumps that play back as freeze+skip
    // at consistent timestamps regardless of player.
    //
    // New design: completely decoupled producer/consumer.
    //   - AI worker (any thread): calls requestFoveatedCrop(q, cx, cy) — sets
    //     a per-quadrant request flag with the latest centroid; non-blocking.
    //     Then calls pollFoveatedSlot(q) to read the most recent result; null
    //     if no result yet (caller falls back to mosaic for THIS tick).
    //   - GL thread: at the END of each renderLoop iteration, calls
    //     serviceFoveatedRequestsOnGlThread(). This walks the request flags,
    //     performs the crop synchronously on the GL thread (no hop, no post),
    //     deep-copies the result into the slot, clears the request flag.
    //
    // Cost: foveated results lag the requesting AI tick by ~1 motion cycle
    // (~100ms at V2's 10Hz). At 30 fps that's 3 frames behind — well below
    // human perception during playback overlay. Net benefit: zero GL handler
    // queue contention, zero glFinish cross-contention, encoder cadence
    // remains tight.
    private static final int FOVEATED_NUM_QUADRANTS = MotionPipelineV2.NUM_QUADRANTS;
    private final boolean[] foveatedRequested = new boolean[FOVEATED_NUM_QUADRANTS];
    private final float[] foveatedReqCentroidX = new float[FOVEATED_NUM_QUADRANTS];
    private final float[] foveatedReqCentroidY = new float[FOVEATED_NUM_QUADRANTS];
    private final Object foveatedRequestLock = new Object();
    /** Most-recent foveated crop result per quadrant. Slot value is immutable;
     *  publication replaces the AtomicReference. AI worker reads without lock. */
    @SuppressWarnings("unchecked")
    private final java.util.concurrent.atomic.AtomicReference<FoveatedSlot>[] foveatedSlots =
        (java.util.concurrent.atomic.AtomicReference<FoveatedSlot>[])
            new java.util.concurrent.atomic.AtomicReference[FOVEATED_NUM_QUADRANTS];
    {
        for (int i = 0; i < FOVEATED_NUM_QUADRANTS; i++) {
            foveatedSlots[i] = new java.util.concurrent.atomic.AtomicReference<>(null);
        }
    }
    /** Slot result is the deep-copied crop bytes plus the timestamp it was
     *  produced at (System.nanoTime() at publish). Consumers can compare the
     *  timestamp against frame time to decide whether the result is fresh
     *  enough to use; older than ~500ms = treat as stale. */
    private static final class FoveatedSlot {
        final byte[] rgb;
        final long publishedNanos;
        final int width;
        final int height;
        FoveatedSlot(byte[] rgb, long publishedNanos, int w, int h) {
            this.rgb = rgb;
            this.publishedNanos = publishedNanos;
            this.width = w;
            this.height = h;
        }
    }
    /** Stale threshold for slot results. ~500ms = ~15 frames at 30fps. */
    private static final long FOVEATED_SLOT_STALE_NANOS = 500_000_000L;

    // GL-thread service throttle. The cropper is double-buffered (async readback
    // is non-blocking) but we still pay the 640×640 RGBA→RGB Y-flip on every
    // call. Capping to one service call per ~150 ms keeps the encoder GL thread
    // well under its 33 ms (30 fps) / 66 ms (15 fps) per-frame budget even on
    // worst-case event frames. V2 motion runs at 10 Hz internally, so 150 ms
    // is exactly one motion tick — zero AI cadence loss.
    private long lastFoveatedServiceNs = 0L;
    private static final long FOVEATED_SERVICE_INTERVAL_NS = 150_000_000L;


    // Cross-quadrant object tracker
    private final CrossQuadrantTracker crossQuadrantTracker = new CrossQuadrantTracker();

    // Actor-layer tracker — sits ON TOP of the existing YOLO + cross-quadrant
    // pipeline and emits Actor records carrying proximity / trend / severity for
    // the timeline + thumbnail + notification + UI layers. Does not affect motion
    // detection or recording trigger logic.
    private final ActorTracker actorTracker = new ActorTracker();
    // Snapshot of the most recent Actor list, for callers that read state.
    // CopyOnWrite to keep reads lock-free for UI / API threads.
    private volatile java.util.List<Actor> lastActors = java.util.Collections.emptyList();

    // Event-level peak severity, latched across the WHOLE recording. The two
    // Telegram stages (start ping + final photo/video) must gate on the same
    // value, otherwise a threat that escalated after the start snapshot drops
    // the opening ping, and one that receded (its actor TTL-pruned from
    // lastActors before stop) drops the closing photo+video — both gating on
    // an instantaneous lastActors snapshot that no longer reflects the event's
    // peak. null = "no severity observed yet this event" (fail-open, like the
    // rest of the notification system treats null). Reset at trigger, advanced
    // wherever lastActors is written.
    private volatile Actor.Severity eventPeakSeverity = null;

    // Filename of the last event whose FINAL notification was emitted. Guards
    // against a double final-send when stopRecording() is entered twice for one
    // event (disable() on the control thread racing the engine's post-record
    // stop). AtomicReference so the claim is a single atomic getAndSet — a plain
    // volatile check-then-set still lets both threads pass before either writes.
    // Reset is unnecessary — event filenames are unique (timestamped to the
    // second + the recorder refuses a same-name re-trigger), so a stale value
    // never false-blocks a new event.
    private final java.util.concurrent.atomic.AtomicReference<String> lastFinalNotifiedEvent =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Latch the event peak from a fresh actor snapshot (non-static actors only). */
    private void updateEventPeakSeverity(java.util.List<Actor> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        Actor.Severity max = eventPeakSeverity;
        for (Actor a : snapshot) {
            // Skip non-person statics (parked cars — SeverityClassifier forces
            // them to NOTICE anyway) but KEEP a static PERSON: a loiterer who
            // stood still is the threat, and the gate already treats it CRITICAL.
            if (a.isStatic && a.classGroup != Actor.ClassGroup.PERSON) continue;
            if (a.peakSeverity != null
                    && (max == null || a.peakSeverity.ordinal() > max.ordinal())) {
                max = a.peakSeverity;
            }
        }
        eventPeakSeverity = max;
    }

    /** Higher of two severities; null is treated as "no opinion" (lowest). */
    private static Actor.Severity maxOf(Actor.Severity a, Actor.Severity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    // Thumbnail capture buffer (Block C). Field declared here so the wiring point
    // in runAiOnQuadrant compiles even before Block C lands. Constructed/reset by
    // recording lifecycle handlers.
    private ThumbnailBuffer thumbnailBuffer = null;

    // FIX (B1/H-a): recording-generation counter. Bumped whenever a recording
    // ends and Actor/Thumbnail state is reset. The aiExecutor lambda captures
    // the value at scheduling time; on completion, it compares against the
    // current value and drops its writes (lastActors update, ThumbnailBuffer
    // observe, baseline promotion) if the generation has advanced. Without
    // this, a slow YOLO inference scheduled before stopRecording can repopulate
    // state for a recording that has already finished, polluting the next
    // recording's first frames.
    private final java.util.concurrent.atomic.AtomicLong recordingGeneration =
            new java.util.concurrent.atomic.AtomicLong(0);
    
    // Heartbeat cooldown: prevent NCC tracker from spamming YOLO on every frame
    // when the template match is failing. Without this, a bad template causes
    // needsYoloHeartbeat=true on every frame, turning YOLO into a continuous
    // 10 FPS detector and destroying the battery savings of decoupled tracking.
    private static final long HEARTBEAT_COOLDOWN_MS = 5000;  // Min 5s between heartbeats per quadrant
    private final long[] lastHeartbeatTimeMs = new long[MotionPipelineV2.NUM_QUADRANTS];
    
    // Auto-exposure state (C++ handles per-quadrant threshold scaling,
    // Java only handles global params like brightness suppression and shadow filter mode)
    
    // Filter debug log: ring buffer of recent filter decisions (max 100 entries)
    private static final int FILTER_LOG_CAPACITY = 100;
    private final String[] filterLog = new String[FILTER_LOG_CAPACITY];
    private int filterLogIndex = 0;
    private int filterLogCount = 0;
    private boolean filterDebugEnabled = false;
    
    // SOTA: Event timeline collector for JSON sidecar files
    private final EventTimelineCollector timelineCollector = new EventTimelineCollector();
    
    // SOTA: Detection baseline for filtering static objects from YOLO output.
    // Maintains a per-quadrant "living memory" of known scene objects so that
    // motion-triggered YOLO detections of parked cars, trash cans, etc. are
    // suppressed — only NEW or MOVED objects trigger recording.
    private final DetectionBaseline detectionBaseline = new DetectionBaseline();
    // Track whether baseline has been seeded (one-time on sentry enable)
    private volatile boolean baselineSeeded = false;
    // Track last YOLO detections per quadrant for event-end baseline update.
    // P1 #13: AtomicReferenceArray — written from aiExecutor lambda, read by
    // stopRecording() (recorder drainer thread) and reset by enable() / disable().
    // Plain array slot publication wasn't safe-published across threads. Readers
    // must tolerate null (they already do — null check before deref).
    //
    // <b>Single-atomic publication:</b> the detection list and the coord-space
    // frame height are packed into one immutable {@link YoloPublication} record
    // so a single AtomicReference write/read covers both. The earlier version
    // used two parallel atomics ({@code lastYoloDetections} and a sibling
    // {@code lastYoloFrameHeight}); the writer published list-then-height, the
    // reader read list-then-height in the SAME order, but a re-publish from
    // the AI thread between the reader's two reads could compose new
    // detections with the OLD height (or vice-versa). Re-audit caught a torn-
    // read window where mosaic detections (240-space) got read with a
    // foveated frame-height (640) → reader assumed foveated FOV scaling →
    // 3-5× distance overestimate intermittently. One atomic eliminates the
    // window — the reader sees either the old YoloPublication or the new
    // one, never a mix.
    private final java.util.concurrent.atomic.AtomicReferenceArray<YoloPublication> lastYoloPublication =
            new java.util.concurrent.atomic.AtomicReferenceArray<>(MotionPipelineV2.NUM_QUADRANTS);

    /** Immutable snapshot of one quadrant's most recent YOLO output, plus
     *  the coord-space frame height the detections were computed against.
     *  Published as a unit so cross-thread readers can't see a torn state. */
    private static final class YoloPublication {
        final java.util.List<com.overdrive.app.ai.Detection> detections;
        final int frameHeightPx;
        YoloPublication(java.util.List<com.overdrive.app.ai.Detection> detections, int frameHeightPx) {
            this.detections = detections;
            this.frameHeightPx = frameHeightPx;
        }
    }
    // Track which quadrant had the last event (for event-end baseline update)
    private int lastEventQuadrant = -1;
    
    // POST-SUPPRESSION BASELINE REFRESH: When brightness suppression fires (lighting change),
    // queue a baseline refresh for after the scene stabilizes. This keeps the baseline
    // synchronized with what YOLO can actually see under the current lighting conditions.
    // Without this, a streetlight turning on makes a previously-invisible car "appear" to
    // YOLO as a new object → false trigger. Cost: 1 inference per quadrant per lighting
    // event (5-10 per night = negligible).
    //
    // We track per-quadrant: when suppression was last active, and whether we've already
    // refreshed after it. The refresh runs STABILIZATION_FRAMES after suppression ends
    // (to let the ISP settle and avoid refreshing on a transitional frame).
    private static final int BASELINE_STABILIZATION_FRAMES = 15;  // 1.5s at 10 FPS
    private final int[] framesSinceSuppressionEnded = new int[MotionPipelineV2.NUM_QUADRANTS];
    private final boolean[] suppressionWasActive = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    private final boolean[] baselineRefreshQueued = new boolean[MotionPipelineV2.NUM_QUADRANTS];
    
    // Output directory
    private File eventOutputDir;
    // volatile: read by main render thread (publishMotionFinal,
    // sendFinalTelegramNotification) and written by the encoder drainer
    // thread (segment listener at rotation time). Without this, the main
    // thread could observe a stale File reference.
    private volatile File currentEventFile;
    
    // Frame dimensions - SOTA: Increased to 640x480 for better AI detection
    // At 320x240 with quad view, each camera is 160x120 - too small for YOLO
    // At 640x480 with quad view, each camera is 320x240 - YOLO can detect people at 5m
    private static final int THUMBNAIL_WIDTH = 640;
    private static final int THUMBNAIL_HEIGHT = 480;
    private static final int BYTES_PER_PIXEL = 3;  // RGB
    private static final int FRAME_SIZE = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT * BYTES_PER_PIXEL;
    
    // Stats
    private int frameCount = 0;
    private int motionDetections = 0;
    
    // Cached latest mosaic frame for snapshot API (640×480 RGB)
    private volatile byte[] latestMosaicFrame = null;
    // Last wallclock time a HTTP/TCP client polled the snapshot getters.
    // Updated by getLatestMosaicFrame / getLatestMosaicJpeg. Used by
    // processFrame to skip the per-N-frame mosaic clone + JPEG encode
    // when no client has asked for a snapshot in a while — that work
    // costs ~3-5% sustained CPU on a parked car with no UI connected.
    private volatile long lastSnapshotPollMs = 0L;
    // 30 s grace window: any poll within the last 30 s keeps the snapshot
    // pipeline warm. Picked to match the WebSocket idle-shutdown cadence
    // and the typical web UI auto-refresh poll cycle (~5-10 s).
    private static final long SNAPSHOT_POLL_GRACE_MS = 30_000L;
    // JPEG-encoded snapshot of the cached mosaic. Refresh is OFF the
    // AiLaneWorker thread — every MOSAIC_JPEG_FRAME_MODULO frames we hand a
    // snapshot of the RGB mosaic to {@link #mosaicJpegExecutor} and let it
    // do the int[] alloc + Bitmap.compress. This keeps Bitmap.compress
    // (30–80 ms on the BYD SoC) off the motion-pipeline thread. Readers see
    // a plain volatile read.
    private volatile byte[] latestMosaicJpeg = null;
    // ~1 Hz at 15 fps. Fast enough that a dialog tile feels live, slow enough
    // that the JPEG encode doesn't impact other lanes.
    private static final int MOSAIC_JPEG_FRAME_MODULO = 15;
    // Single-thread bounded executor — drops new requests if the previous
    // encode hasn't completed (rather than queuing forever and producing
    // backlog). A bounded queue size of 1 with discardOldestPolicy keeps
    // the freshest pending request and skips intermediate ones. Daemon
    // thread so it doesn't block JVM shutdown.
    private final java.util.concurrent.ThreadPoolExecutor mosaicJpegExecutor =
        new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "MosaicJpegEncoder");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    // Reusable scratch buffers — avoid the per-encode int[] + Bitmap alloc
    // churn (~5 MB/sec at 30 fps cadence). Guarded by mosaicJpegExecutor's
    // single-thread invariant (no need for explicit lock).
    private int[] mosaicJpegPixelsScratch = null;
    private android.graphics.Bitmap mosaicJpegBitmapScratch = null;

    // Segment-metadata executor: hero JPEG compress + per-actor JPEGs +
    // JSON sidecar + SRT sidecar.
    //
    // PROBLEM IT FIXES: when called from the segment-rotation listener,
    // flushSegmentMetadata runs on a {@code GpuSegmentFinalizer-N} thread
    // which is what {@code waitForFinalizers} blocks on at recording-close
    // and pipeline-shutdown. Bitmap.compress(JPEG, 85) at 640×640 takes
    // ~50 ms on the BYD SoC; with 4 actors per segment that's 200+ ms of
    // the close-path's 2 s budget consumed by JPEG work. Multi-segment
    // events stack the cost across segments and can blow the budget.
    //
    // FIX: dispatch flushSegmentMetadata to this dedicated single-thread
    // executor so the finalizer thread returns immediately. The JPEG
    // writes proceed in the background and don't gate close-path or
    // rotation cadence. The executor has an unbounded queue (we want
    // every segment's metadata to be written eventually, never dropped),
    // but the close-path explicitly drains it before declaring shutdown
    // complete via {@link #drainSegmentMetadata}.
    //
    // Thread is BACKGROUND priority (nice +10) so its JPEG work yields to
    // the encoder/drainer/disk-writer if they need cycles.
    private final java.util.concurrent.ExecutorService segmentMetadataExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {}
                r.run();
            }, "SegmentMetadataWriter");
            t.setDaemon(true);
            return t;
        });
    // Track in-flight + queued metadata tasks so close-path can wait for
    // them to finish before tearing down state they reference.
    private final java.util.concurrent.atomic.AtomicInteger inFlightSegmentMetadata =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private final Object segmentMetadataDrainLock = new Object();
    
    /**
     * Initializes the surveillance engine.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     */
    public void init(File eventDir, GpuDownscaler downscaler) {
        init(eventDir, downscaler, null, null);
    }
    
    /**
     * Initializes the surveillance engine with optional AssetManager for YOLO loading.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager) {
        init(eventDir, downscaler, assetManager, null);
    }
    
    /**
     * Initializes the surveillance engine with Context for Java TFLite.
     * 
     * @param eventDir Directory for saving event recordings
     * @param downscaler GPU downscaler reference for buffer recycling
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     */
    public void init(File eventDir, GpuDownscaler downscaler, android.content.res.AssetManager assetManager, android.content.Context context) {
        this.eventOutputDir = eventDir;
        this.downscaler = downscaler;
        // Retain for lazy YOLO re-init (Bug B fix path)
        this.yoloContext = context;
        this.yoloAssetManager = assetManager;
        // Construct thumbnail buffer once; it is reused across recordings (it
        // clears its slots itself at recording-stop).
        if (this.thumbnailBuffer == null) {
            this.thumbnailBuffer = new ThumbnailBuffer();
        }
        
        if (!eventDir.exists()) {
            eventDir.mkdirs();
        }
        
        // Allocate direct buffer for V2 pipeline JNI
        currentFrame = ByteBuffer.allocateDirect(FRAME_SIZE);
        currentFrame.order(ByteOrder.nativeOrder());
        
        // Detect available features
        try {
            // Initialize Java TFLite YOLO detector
            // Note: We don't have a full Context in daemon mode, but we can create one from AssetManager
            if (context != null) {
                try {
                    logger.info("Initializing Java TFLite YOLO detector...");
                    yoloDetector = new YoloDetector(context);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("YOLO backend: CPU XNNPACK (4-thread, encoder-isolated via nice gradient)");
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error initializing YOLO detector: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else if (assetManager != null) {
                // Daemon mode: Create minimal context from AssetManager
                try {
                    logger.info("Creating AssetContext for TFLite (daemon mode)...");
                    android.content.Context assetContext = new com.overdrive.app.ai.AssetContext(assetManager);
                    
                    yoloDetector = new YoloDetector(assetContext);
                    boolean yoloLoaded = yoloDetector.init();
                    
                    if (yoloLoaded) {
                        useObjectDetection = true;
                        logger.info("YOLO model loaded successfully - object detection enabled");
                        logger.info("YOLO backend: CPU XNNPACK (4-thread, encoder-isolated via nice gradient)");
                    } else {
                        logger.warn("Failed to load YOLO model");
                        useObjectDetection = false;
                        yoloDetector = null;
                    }
                } catch (Exception e) {
                    logger.error("Error creating AssetContext: " + e.getMessage(), e);
                    useObjectDetection = false;
                    yoloDetector = null;
                }
            } else {
                logger.info("No Context or AssetManager provided - object detection disabled");
                useObjectDetection = false;
            }
        } catch (UnsatisfiedLinkError e) {
            logger.warn("Native features not available: " + e.getMessage());
            useObjectDetection = false;
        }
        
        logger.info("Initialized surveillance engine (buffer=" + FRAME_SIZE + " bytes)");

        // Sweep orphan recordings: any .mp4 without a sibling hero .jpg is
        // either pre-fix legacy or a daemon-killed-mid-finalizer victim.
        // Generate fallback heroes from the mp4's first keyframe so the
        // events UI never shows a thumbnail-less card.
        // Async — must not block init().
        new Thread(() -> {
            try {
                if (eventDir != null && eventDir.isDirectory()) {
                    sweepOrphanHeroThumbnails(eventDir);
                }
            } catch (Throwable t) {
                logger.debug("Orphan hero sweep failed: " + t.getMessage());
            }
        }, "OverdriveOrphanHeroSweep").start();

        // Initialize V2 per-quadrant pipeline
        try {
            pipelineV2 = new MotionPipelineV2();
            if (pipelineV2.init()) {
                pipelineV2Config = new MotionPipelineV2.Config();
                pipelineV2Config.applyEnvironmentPreset("outdoor");  // Default preset
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 per-quadrant pipeline initialized");
            } else {
                logger.error("V2 pipeline init failed");
                pipelineV2 = null;
            }
        } catch (Exception e) {
            logger.error("V2 pipeline not available: " + e.getMessage());
            pipelineV2 = null;
        }
    }
    
    /**
     * Sets the mosaic recorder for event recording.
     * 
     * @param recorder Mosaic recorder instance
     */
    public void setRecorder(GpuMosaicRecorder recorder) {
        this.recorder = recorder;
    }
    
    /**
     * Set the foveated cropper for high-res AI inference.
     * When set, YOLO runs on a 640×640 crop from the raw 5120×960 strip
     * instead of the 320×240 mosaic quadrant. Must be called from GL thread.
     *
     * @param cropper FoveatedCropper instance (initialized on GL thread)
     * @param textureId Camera OES texture ID for direct strip access
     */
    public void setFoveatedCropper(FoveatedCropper cropper, int textureId) {
        this.foveatedCropper = cropper;
        this.cameraTextureId = textureId;
        if (cropper != null && cropper.isInitialized()) {
            logger.info("Foveated AI cropping enabled (640×640 from raw strip)");
        }
    }

    /**
     * Mark a quadrant as wanting a foveated crop on the next render loop pass.
     * Non-blocking. Latest centroid wins (overwrite semantics). Safe from any
     * thread.
     */
    public void requestFoveatedCrop(int quadrant, float centroidX, float centroidY) {
        if (quadrant < 0 || quadrant >= FOVEATED_NUM_QUADRANTS) return;
        synchronized (foveatedRequestLock) {
            foveatedRequested[quadrant] = true;
            foveatedReqCentroidX[quadrant] = centroidX;
            foveatedReqCentroidY[quadrant] = centroidY;
        }
    }

    /**
     * Read the latest foveated crop for a quadrant, if fresh.
     * Returns null if no result is available or the result is older than
     * FOVEATED_SLOT_STALE_NANOS (the AI worker will fall back to mosaic).
     * Non-blocking. Safe from any thread.
     */
    public byte[] pollFoveatedSlot(int quadrant) {
        if (quadrant < 0 || quadrant >= FOVEATED_NUM_QUADRANTS) return null;
        FoveatedSlot slot = foveatedSlots[quadrant].get();
        if (slot == null) return null;
        long age = System.nanoTime() - slot.publishedNanos;
        if (age > FOVEATED_SLOT_STALE_NANOS) return null;
        return slot.rgb;
    }

    /**
     * GL-thread hook. Called from PanoramicCameraGpu.renderLoop once per
     * frame. Walks the per-quadrant request flags, performs each requested
     * crop synchronously on the GL thread (since this method is itself called
     * on the GL thread), publishes the result to the matching slot, and
     * clears the request flag. Deep-copies the cropper's internal buffer
     * because FoveatedCropper.crop() returns a pointer to a shared array.
     *
     * MUST be called on the GL thread that owns cameraTextureId.
     *
     * Performance: each crop is ~3-8ms (FBO blit + glReadPixels of 640×640
     * RGBA + Y-flip RGBA→RGB). Capped to one crop per render frame to keep
     * the per-frame budget predictable. If multiple quadrants request
     * simultaneously, they're serviced round-robin across consecutive
     * render frames.
     */
    public void serviceFoveatedRequestsOnGlThread() {
        FoveatedCropper cropper = this.foveatedCropper;
        int texId = this.cameraTextureId;
        if (cropper == null || texId < 0 || !cropper.isInitialized()) return;

        // SOTA throttle: even with double-buffered async readback, the row pack
        // + Y-flip costs ~3 ms per call on Adreno 610. Capping to one service
        // per 150 ms keeps total per-frame GL cost predictable across the
        // entire event window.
        long nowNs = System.nanoTime();
        if (nowNs - lastFoveatedServiceNs < FOVEATED_SERVICE_INTERVAL_NS) return;

        // Snapshot the requests under the lock, then service at most one per
        // call. Round-robin via a per-instance cursor so no quadrant starves.
        int qToServe = -1;
        float cx = 0f, cy = 0f;
        synchronized (foveatedRequestLock) {
            for (int i = 0; i < FOVEATED_NUM_QUADRANTS; i++) {
                int q = (foveatedRoundRobin + i) % FOVEATED_NUM_QUADRANTS;
                if (foveatedRequested[q]) {
                    qToServe = q;
                    cx = foveatedReqCentroidX[q];
                    cy = foveatedReqCentroidY[q];
                    foveatedRequested[q] = false;
                    foveatedRoundRobin = (q + 1) % FOVEATED_NUM_QUADRANTS;
                    break;
                }
            }
        }
        if (qToServe < 0) return;

        lastFoveatedServiceNs = nowNs;

        FoveatedCropper.Result result;
        try {
            // Async readback: this submits the CURRENT quadrant's render and
            // returns the PREVIOUS quadrant's bytes (which the GPU has already
            // finished). The result's quadrant field tells us which slot to
            // publish to. The very first call returns null — by design.
            result = cropper.crop(texId, qToServe, cx, cy);
        } catch (Throwable t) {
            logger.warn("Foveated crop (GL inline) failed: " + t.getMessage());
            return;
        }
        if (result == null || result.rgb == null || result.quadrant < 0
                || result.quadrant >= FOVEATED_NUM_QUADRANTS) {
            return;
        }
        // Deep-copy: cropper.rgb is a pointer to its shared internal buffer;
        // the next service call will overwrite it. The copy is 1.2 MB —
        // negligible vs. the 200 ms YOLO inference downstream.
        byte[] copy = new byte[result.rgb.length];
        System.arraycopy(result.rgb, 0, copy, 0, result.rgb.length);
        foveatedSlots[result.quadrant].set(new FoveatedSlot(
                copy, System.nanoTime(),
                result.width, result.height));
    }

    private int foveatedRoundRobin = 0;

    /** GL handler for posting foveated crops back to the GL thread.
     *  Required when processFrame runs on AiLaneWorker. */
    public void setGlHandler(android.os.Handler glHandler) {
        this.glHandler = glHandler;
    }

    /** Camera target FPS — sizes the foveated GL-hop wait budget so the
     *  AI lane never times out on a normal-load render frame. */
    public void setCameraTargetFps(int fps) {
        if (fps > 0) this.cameraTargetFps = fps;
    }

    // cropOnGlThread() removed — foveated crops now run synchronously on the
    // GL thread inside serviceFoveatedRequestsOnGlThread() (called from
    // PanoramicCameraGpu.renderLoop). The AI worker uses the mailbox
    // (requestFoveatedCrop / pollFoveatedSlot) and never blocks on GL.

    /**
     * Get the current foveated cropper (for lazy-init check).
     */
    public FoveatedCropper getFoveatedCropper() {
        return foveatedCropper;
    }
    
    /**
     * SOTA: Updates the event output directory.
     * Called when storage type changes (internal <-> SD card) to ensure
     * events are saved to the correct location.
     * 
     * @param eventDir New directory for saving event recordings
     */
    public void setEventOutputDir(File eventDir) {
        this.eventOutputDir = eventDir;
        if (eventDir != null && !eventDir.exists()) {
            boolean created = eventDir.mkdirs();
            logger.info("Updated event output directory: " + eventDir.getAbsolutePath() + " (created=" + created + ")");
            if (created) {
                eventDir.setReadable(true, false);
                eventDir.setExecutable(true, false);
            }
        } else {
            logger.info("Updated event output directory: " + (eventDir != null ? eventDir.getAbsolutePath() : "null"));
        }
    }
    
    /**
     * Processes a frame from the GPU downscaler.
     * 
     * This is called at 2 FPS during idle mode. When motion is detected,
     * it can be called at 5 FPS for more responsive AI.
     * 
     * CRITICAL: This method receives a BORROWED buffer from the pool.
     * The buffer MUST be recycled in a finally block to prevent pool exhaustion.
     * If async AI is needed, the data must be copied before recycling.
     * 
     * @param smallRgbFrame 320x240 RGB frame from GPU (borrowed from pool)
     */
    public void processFrame(byte[] smallRgbFrame) {
        if (!active) {
            // Still need to recycle even if not active
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }

        // Continuous mode: encoder is fed by the GL→encoder surface chain
        // independently of this method, so skip every CPU-side stage
        // (mosaic snapshot, motion throttle, V2 pipeline, YOLO). Still
        // recycle the borrowed buffer or the downscaler pool starves.
        if (continuousMode) {
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }

        // RACE CONDITION FIX (belt-and-suspenders): If somehow active=true but ACC is ON,
        // auto-disable. This catches the case where enable() raced with ACC ON and the
        // disable path hasn't run yet.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn("processFrame: ACC is ON but surveillance is active — auto-disabling");
            disable();
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        if (smallRgbFrame == null || smallRgbFrame.length != FRAME_SIZE) {
            logger.warn( "Invalid frame size: " + (smallRgbFrame != null ? smallRgbFrame.length : 0));
            if (downscaler != null && smallRgbFrame != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
            return;
        }
        
        try {
            frameCount++;
            long now = System.currentTimeMillis();
            
            // Cache latest frame for snapshot API (every 10th frame). Skip
            // the 920 KB System.arraycopy when no client has polled within
            // the grace window — on a parked car with no UI connected,
            // this saves ~1 MB/s of continuous memcpy. Any poll bumps
            // lastSnapshotPollMs and re-warms the cache on the next tick.
            boolean snapshotClientActive =
                (now - lastSnapshotPollMs) < SNAPSHOT_POLL_GRACE_MS;
            if (frameCount % 10 == 0 && snapshotClientActive) {
                if (latestMosaicFrame == null || latestMosaicFrame.length != smallRgbFrame.length) {
                    latestMosaicFrame = new byte[smallRgbFrame.length];
                }
                System.arraycopy(smallRgbFrame, 0, latestMosaicFrame, 0, smallRgbFrame.length);
            }

            // Hand a snapshot of the cached mosaic to a dedicated single-
            // thread encoder executor. Bitmap.compress (30–80 ms) does NOT
            // run on AiLaneWorker — the motion pipeline keeps its frame
            // budget. The executor's queue is depth-1 with DiscardOldest so
            // a stalled encoder doesn't grow a backlog: the next tick just
            // replaces the pending request with the freshest mosaic.
            //
            // We CLONE here rather than capturing the latestMosaicFrame
            // reference — the cache buffer is rewritten in-place every 10
            // frames via System.arraycopy, which would tear the encoder's
            // pixel reads if it shared the buffer. The clone is bounded
            // (640×480×3 = 920 KB once per ~15 frames at 15 fps ≈ 1 Hz).
            // Same client-poll gate as the mosaic clone above: skip the
            // 920 KB clone + 30-80 ms Bitmap.compress when no client has
            // asked for a JPEG within the grace window. Saves ~3.5%
            // sustained CPU on a parked car with no UI connected.
            if (frameCount % MOSAIC_JPEG_FRAME_MODULO == 0
                    && latestMosaicFrame != null
                    && snapshotClientActive) {
                final byte[] snapshot = latestMosaicFrame.clone();
                try {
                    mosaicJpegExecutor.execute(() -> {
                        try {
                            byte[] encoded = encodeMosaicJpeg(snapshot);
                            if (encoded != null) latestMosaicJpeg = encoded;
                        } catch (Throwable t) {
                            logger.warn("mosaic jpeg encode failed: " + t.getMessage());
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // Executor was shut down (engine releasing). Skip.
                }
            }
            
            // Log frame count every 100 frames to confirm frames are arriving
            if (frameCount % 100 == 0) {
                logger.info("Surveillance frame #" + frameCount + " received");
            }
            
            // MOTION THROTTLING: Skip frames to achieve 10 FPS (saves 66% CPU)
            if (now - lastMotionProcessTime < MOTION_PROCESS_INTERVAL_MS) {
                return;
            }
            lastMotionProcessTime = now;
            
            if (pipelineV2 == null) {
                logger.warn("V2 pipeline not initialized — skipping frame");
                return;
            }
            
            processFrameV2(smallRgbFrame, now);
            
        } finally {
            // CRITICAL: Always recycle buffer back to pool
            // This MUST happen in finally block to prevent pool exhaustion
            if (downscaler != null) {
                downscaler.recycleBuffer(smallRgbFrame);
            }
        }
    }
    
    /**
     * V2 Pipeline: Per-quadrant 6-stage motion detection.
     */
    // Track peak threat level during a motion sequence (reset when sequence ends)
    private int peakThreatDuringSequence = 0;

    // Whether the current sequence's THREAT_HIGH is "trusted" (coherent
    // translation OR an in-zone person tracker), latched at motion-start and
    // refreshed each qualifying frame. Read by the async YOLO decision matrix
    // (which runs on the aiExecutor thread and can't safely re-read the live
    // pipeline result) so both the synchronous gate and the matrix encode the
    // same "untrusted HIGH = flag/shadow → YOLO-gate it" invariant. volatile
    // for cross-thread visibility. Reset to false wherever firstMotionTime is
    // cleared. See COHERENCE_RATIO_MIN.
    private volatile boolean cachedHighIsTrusted = false;

    // Latched true when the native flow-coherence signal POSITIVELY reported an
    // incoherent in-place loiter (a confirmed flag / foliage / sweeping shadow)
    // during this sequence and no coherent/tracked frame ever appeared. Extends
    // the YOLO-confirmation timeout so a relentlessly-waving flag can't leak a
    // recording through the 2s fallback. NOT set on the fail-open (coherence
    // unavailable) path. Reset with cachedHighIsTrusted at each sequence start.
    private volatile boolean cachedIncoherentLoiter = false;

    // Previous frame sample for Java-side motion diff check (independent of native pipeline)
    private int[] prevFrameSamples = null;
    private int[] prevDenseHash = null;
    
    private void processFrameV2(byte[] smallRgbFrame, long now) {
        // Copy frame data into a direct ByteBuffer for JNI
        currentFrame.clear();
        currentFrame.put(smallRgbFrame);
        currentFrame.flip();
        
        // DIAGNOSTIC: Every 100 frames, check frame validity and inter-frame diff.
        // Only in debug builds — this is pure development tooling.
        if (com.overdrive.app.BuildConfig.DEBUG && frameCount % 100 == 0) {
            // Sample 16 pixels spread across the frame
            int[] currentSamples = new int[16];
            int[][] sampleCoords = {
                {60, 80}, {60, 240}, {60, 400}, {60, 560},    // Row 1
                {180, 80}, {180, 240}, {180, 400}, {180, 560}, // Row 2
                {300, 80}, {300, 240}, {300, 400}, {300, 560}, // Row 3
                {420, 80}, {420, 240}, {420, 400}, {420, 560}  // Row 4
            };
            boolean allBlack = true;
            for (int i = 0; i < 16; i++) {
                int off = (sampleCoords[i][0] * THUMBNAIL_WIDTH + sampleCoords[i][1]) * 3;
                if (off + 2 < smallRgbFrame.length) {
                    int r = smallRgbFrame[off] & 0xFF;
                    int g = smallRgbFrame[off + 1] & 0xFF;
                    int b = smallRgbFrame[off + 2] & 0xFF;
                    currentSamples[i] = (r << 16) | (g << 8) | b;
                    if (r > 5 || g > 5 || b > 5) allBlack = false;
                }
            }
            
            // Compare with previous frame samples
            int maxDiff = 0;
            int changedSamples = 0;
            if (prevFrameSamples != null) {
                for (int i = 0; i < 16; i++) {
                    int r1 = (currentSamples[i] >> 16) & 0xFF;
                    int g1 = (currentSamples[i] >> 8) & 0xFF;
                    int b1 = currentSamples[i] & 0xFF;
                    int r2 = (prevFrameSamples[i] >> 16) & 0xFF;
                    int g2 = (prevFrameSamples[i] >> 8) & 0xFF;
                    int b2 = prevFrameSamples[i] & 0xFF;
                    int diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                    if (diff > maxDiff) maxDiff = diff;
                    if (diff > 10) changedSamples++;
                }
            }
            prevFrameSamples = currentSamples;
            
            // Also compute a dense diff: scan every 20th pixel across the full frame
            int denseMaxDiff = 0;
            int denseChanged = 0;
            int denseSamples = 0;
            if (prevDenseHash != null) {
                for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                    for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                        int off = (y * THUMBNAIL_WIDTH + x) * 3;
                        if (off + 2 < smallRgbFrame.length) {
                            int r = smallRgbFrame[off] & 0xFF;
                            int g = smallRgbFrame[off + 1] & 0xFF;
                            int b = smallRgbFrame[off + 2] & 0xFF;
                            int idx = denseSamples;
                            if (idx < prevDenseHash.length) {
                                int pr = (prevDenseHash[idx] >> 16) & 0xFF;
                                int pg = (prevDenseHash[idx] >> 8) & 0xFF;
                                int pb = prevDenseHash[idx] & 0xFF;
                                int diff = Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb);
                                if (diff > denseMaxDiff) denseMaxDiff = diff;
                                if (diff > 30) denseChanged++;
                            }
                            denseSamples++;
                        }
                    }
                }
            }
            // Store dense samples for next comparison
            int totalDense = (THUMBNAIL_HEIGHT / 20) * (THUMBNAIL_WIDTH / 20);
            if (prevDenseHash == null || prevDenseHash.length != totalDense) {
                prevDenseHash = new int[totalDense];
            }
            int di = 0;
            for (int y = 0; y < THUMBNAIL_HEIGHT; y += 20) {
                for (int x = 0; x < THUMBNAIL_WIDTH; x += 20) {
                    int off = (y * THUMBNAIL_WIDTH + x) * 3;
                    if (off + 2 < smallRgbFrame.length && di < prevDenseHash.length) {
                        int r = smallRgbFrame[off] & 0xFF;
                        int g = smallRgbFrame[off + 1] & 0xFF;
                        int b = smallRgbFrame[off + 2] & 0xFF;
                        prevDenseHash[di++] = (r << 16) | (g << 8) | b;
                    }
                }
            }
            
            // Log sample pixels from each quadrant center
            int q0 = currentSamples[5];
            int q1 = currentSamples[6];
            int q2 = currentSamples[9];
            int q3 = currentSamples[10];
            
            logger.info(String.format("FRAME_DIAG #%d: %s | sparse: max=%d changed=%d/16 | dense: max=%d changed=%d/%d | Q0=(%d,%d,%d) Q1=(%d,%d,%d) Q2=(%d,%d,%d) Q3=(%d,%d,%d)",
                    frameCount,
                    allBlack ? "ALL_BLACK!" : "ok",
                    maxDiff, changedSamples,
                    denseMaxDiff, denseChanged, denseSamples,
                    (q0>>16)&0xFF, (q0>>8)&0xFF, q0&0xFF,
                    (q1>>16)&0xFF, (q1>>8)&0xFF, q1&0xFF,
                    (q2>>16)&0xFF, (q2>>8)&0xFF, q2&0xFF,
                    (q3>>16)&0xFF, (q3>>8)&0xFF, q3&0xFF));
        }
        
        // Run V2 pipeline (includes C++ Global Illumination Sync)
        MotionPipelineV2.QuadrantResult[] results = pipelineV2.processFrame(
                currentFrame, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        
        // SOTA: Seed detection baseline once after camera warmup (frame 30 = ~3s at 10 FPS).
        // Runs YOLO on each quadrant to catalog what's already in the scene (parked cars,
        // trash cans, etc.) so future motion-triggered detections can filter them out.
        // Cost: 4 inferences, one-time. Runs on AI executor thread to avoid blocking motion pipeline.
        if (!baselineSeeded && frameCount == 30 && useObjectDetection && yoloDetector != null) {
            baselineSeeded = true;  // Set immediately to prevent re-entry
            final byte[] seedFrame = new byte[smallRgbFrame.length];
            System.arraycopy(smallRgbFrame, 0, seedFrame, 0, smallRgbFrame.length);
            // Stagger the four per-quadrant inferences instead of running them
            // back-to-back. With CPU XNNPACK each detect() runs ~250 ms; four
            // back-to-back inferences would block aiExecutor for ~1 s during
            // the seeding window, delaying the first legitimate
            // motion-triggered YOLO call by up to a full second. Spacing
            // them 500 ms apart keeps aiExecutor available for live work
            // between ticks.
            //
            // Scheduling is on aiScheduler; each tick re-dispatches to
            // aiExecutor because the TFLite Interpreter is not thread-safe
            // across run() calls (interpLock serialises all detect() calls,
            // and the single-thread aiExecutor is the contract that keeps
            // the lock uncontended steady-state).
            final int qW = THUMBNAIL_WIDTH / 2;
            final int qH = THUMBNAIL_HEIGHT / 2;
            final long staggerMs = 500L;
            logger.info("Scheduling staggered detection baseline seed (4 quadrants × " +
                    staggerMs + "ms apart) starting at frame 30");
            for (int qi = 0; qi < MotionPipelineV2.NUM_QUADRANTS; qi++) {
                final int q = qi;
                aiScheduler.schedule(() -> {
                    // Outer-scheduler ACC-ON short-circuit: if ACC has turned
                    // ON between frame-30 enqueue and this tick (up to 1.5s
                    // for the 4th quadrant), don't even pay the
                    // aiExecutor.execute() dispatch cost. The inner lambda
                    // re-checks anyway as a defense in depth.
                    if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        if (q == 0) {
                            logger.info("Baseline seed dispatch skipped (surveillance inactive / ACC ON)");
                        }
                        return;
                    }
                    aiExecutor.execute(() -> {
                        // FIX (A8/B3): snapshot detector at lambda entry — see
                        // runAiOnQuadrant for rationale. Toggling AI off via
                        // setObjectFilters between schedule and execution
                        // would otherwise NPE or crash native TFLite.
                        final YoloDetector detectorSnap = yoloDetector;
                        if (detectorSnap == null || !aiEnabled) {
                            if (q == 0) {
                                logger.info("Baseline seed skipped (detector closed)");
                            }
                            return;
                        }
                        // ACC-ON guard: the staggered seed has up to ~1.5s of
                        // dispatch lag (4 quadrants × 500ms apart on
                        // aiScheduler, then re-dispatch onto aiExecutor). If
                        // ACC turns ON between schedule time and now, the
                        // surveillance session is logically over and a YOLO
                        // inference here would (a) burn CPU during a window
                        // where the user expects minimal load and (b) write
                        // baseline state for a session that's about to be
                        // torn down. Skip and let the next ACC-OFF session
                        // re-seed naturally.
                        if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                            if (q == 0) {
                                logger.info("Baseline seed skipped (surveillance inactive / ACC ON)");
                            }
                            return;
                        }
                        try {
                            byte[] quadCrop = cropFromMosaic(seedFrame, q, qW, qH);
                            if (quadCrop != null) {
                                java.util.List<com.overdrive.app.ai.Detection> dets =
                                        detectorSnap.detect(quadCrop, qW, qH,
                                                aiConfidence, true, true, false,
                                                true, minObjectSize);
                                detectionBaseline.seedFromDetections(q, dets, qW, qH);
                            }
                        } catch (Exception e) {
                            logger.warn("Baseline seed failed for Q" + q + ": " + e.getMessage());
                        }
                        if (q == MotionPipelineV2.NUM_QUADRANTS - 1) {
                            logger.info("Detection baseline seeded for all quadrants");
                        }
                    });
                }, q * staggerMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
        
        // Per-quadrant override post-filter. The native pipeline ran with the
        // aggregate (most-permissive) sensitivity/zone, so each quadrant's
        // result currently reflects the loosest gates. Walk the quadrants and
        // demote any result that wouldn't pass its own effective gates.
        applyQuadrantOverrides(results);

        // Check if any quadrant detected motion at MEDIUM or higher threat.
        int maxThreat = pipelineV2.getMaxThreatLevel();
        boolean anyMotion = maxThreat >= MotionPipelineV2.THREAT_MEDIUM;
        
        // SOTA: Tracker immunity from brightness suppression (Headlight Sweep Fix).
        // When a car's headlights sweep across the camera, the brightness suppression
        // stage kills ALL motion blocks in that quadrant. If a person is being tracked
        // in that quadrant, the motion sequence timer loses them and the recording
        // stops prematurely. Fix: if any quadrant is brightness-suppressed but the
        // NCC tracker has an active lock on it, keep anyMotion=true so the sequence
        // timer continues. The tracker's pixel-level lock is immune to global
        // brightness changes — it tracks texture, not absolute luminance.
        // FIX: Only person tracks (classId==0) get immunity. Vehicle tracks
        // (motorcycles, cars) should not override brightness suppression.
        if (!anyMotion) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].brightnessSuppressed) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0) { // person only
                                // ZONE GATE: don't grant immunity to a tracker
                                // bbox that's outside the user's configured
                                // detection zone. Without this check, a person
                                // tracked in row 0-1 (far from the car) would
                                // bypass the row gate during any brightness-
                                // suppression frame — recording fires for an
                                // object the user explicitly excluded.
                                if (!trackerInZone(q)) continue;
                                anyMotion = true;
                                if (maxThreat < MotionPipelineV2.THREAT_MEDIUM) {
                                    maxThreat = MotionPipelineV2.THREAT_MEDIUM;
                                }
                                if (frameCount % 50 == 0) {
                                    logger.info("Headlight sweep immunity: Q" + q +
                                            " [" + MotionPipelineV2.QUADRANT_NAMES[q] +
                                            "] suppressed but tracker holds person lock");
                                }
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        
        // Per-tick proximity state update — runs ONCE per quadrant per
        // processFrameV2 iteration, BEFORE any log site reads
        // proximityForQuadrant. Without this, multiple downstream log sites
        // in the same tick (per-quadrant summary, motion-start,
        // motion-building, recording-trigger) would each clobber each
        // other's prevLowestBlockY in the old unified proximityForQuadrant,
        // collapsing dt to 0 and silently breaking the trend signal
        // (audit H2). Runs unconditionally — quiet quadrants need their
        // stale prev state cleared too (audit M4), otherwise a 30-second-
        // old prevRow with a fresh nowMs produces a bogus APPROACHING the
        // next time blocks fire in that quadrant.
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            updateProximityState(q, results[q]);
        }

        // --- Diagnostic: Log per-quadrant pipeline results every time motion is detected ---
        // This shows exactly what the pipeline saw and why it did/didn't trigger.
        if (anyMotion || filterDebugEnabled) {
            String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
            int bestQ = pipelineV2.getHighestThreatQuadrant();

            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                if (r.activeBlocks == 0 && !r.brightnessSuppressed) continue;

                String qName = MotionPipelineV2.QUADRANT_NAMES[q];

                // SOTA proximity (bbox-height when YOLO has fired, tier+trend
                // pre-YOLO). Replaces the previous centroid-Y geometric
                // distance which was producing 0.4–0.9 m noise for objects
                // actually 3–8 m away (wrong projection model + Y=horizon
                // assumption + foot-vs-torso confusion). Read-only here —
                // state was updated above for this tick.
                DistanceEstimator.ProximityEstimate proxQ = proximityForQuadrant(q, r);
                String proxStr = proxQ.describe();

                // Zone cutoff is row-based natively (maxDistanceRow), so the
                // metric expression here is purely cosmetic. Keep the legacy
                // geometric estimate for that single label so users have
                // something stable to read against — it's wrong but it's
                // what the existing UI configuration text refers to.
                int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                String zoneStr = config != null ? config.getDetectionZone() : "?";
                String zoneLimitStr = maxRow > 0
                        ? String.format("%s(<%s)", zoneStr,
                                maxRow == 4 ? "close" : maxRow == 2 ? "normal" : "extended")
                        : zoneStr + "(no limit)";

                if (r.brightnessSuppressed) {
                    logger.debug(String.format(
                        "  [%s] BRIGHTNESS_SUPPRESSED luma=%.0f (light change detected)",
                        qName, r.meanLuma));
                } else if (r.shadowFiltered && !r.motionDetected) {
                    logger.debug(String.format(
                        "  [%s] SHADOW_FILTERED active=%d (shadow discrimination removed blocks)",
                        qName, r.activeBlocks));
                } else if (r.motionDetected) {
                    logger.info(String.format(
                        "  [%s] %s | prox=%s | blocks: active=%d confirmed=%d component=%d | zone=%s",
                        qName, threatNames[r.threatLevel], proxStr,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, zoneLimitStr));
                } else if (r.activeBlocks > 0) {
                    // Motion was detected at block level but rejected by later stages
                    String reason;
                    if (r.confirmedBlocks == 0) {
                        reason = "not yet confirmed (need more frames)";
                    } else if (r.componentSize < (pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1)) {
                        reason = String.format("component too small (%d blocks, need %d)", r.componentSize,
                                pipelineV2Config != null ? pipelineV2Config.minComponentSize : 1);
                    } else if (maxRow > 0 && r.centroidY < maxRow) {
                        reason = String.format("outside zone (%s)", zoneLimitStr);
                    } else if (r.confirmedBlocks < (pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2)) {
                        reason = String.format("below alarm threshold (%d blocks, need %d)", r.confirmedBlocks,
                                pipelineV2Config != null ? pipelineV2Config.alarmBlockThreshold : 2);
                    } else {
                        reason = "passing motion (" + threatNames[r.threatLevel] + ", ignored)";
                    }
                    logger.debug(String.format(
                        "  [%s] REJECTED: %s | prox=%s active=%d confirmed=%d",
                        qName, reason, proxStr, r.activeBlocks, r.confirmedBlocks));
                }
            }
        }
        
        // Update legacy tracking variables for compatibility
        if (anyMotion) {
            int bestQ = pipelineV2.getHighestThreatQuadrant();
            if (bestQ >= 0) {
                lastActiveBlocksCount = results[bestQ].activeBlocks;
                lastTemporalBlocksCount = results[bestQ].confirmedBlocks;
            }
        }
        
        if (anyMotion) {
            // GATING: only "qualifying" motion bumps lastMotionTime. The
            // post-record stop check uses (now - lastMotionTime) ≥ postRecordMs
            // as the gate that lets recording finally end. Bumping
            // lastMotionTime on EVERY anyMotion frame meant residual
            // shadow flickers, brightness blips, and out-of-zone motion
            // (which DON'T qualify as recording-extension events under
            // the recordingStopTime path) silently kept the post-record
            // clock alive — turning a brief 3-second approach into a
            // 50-second clip when shadows kept tickling the detector.
            //
            // Qualifying = at least one quadrant has MEDIUM+ threat AND
            // (an in-zone motion result OR an in-zone tracker lock). This
            // matches the same conditions that gate the recordingStopTime
            // extension at line ~1842.
            boolean qualifyingMotion = false;
            if (maxThreat >= MotionPipelineV2.THREAT_MEDIUM) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].threatLevel >= MotionPipelineV2.THREAT_MEDIUM
                            && (results[q].activeBlocks > 0 || results[q].confirmedBlocks > 0)) {
                        // Use trackerInZone as the cheap zone proxy when a
                        // tracker is present; otherwise fall back to the
                        // pipeline's own zone gating (already applied via
                        // applyQuadrantOverrides above — if threat survives
                        // post-override, it's in-zone).
                        qualifyingMotion = true;
                        break;
                    }
                }
            }
            // Tracker-immunity branch: the headlight-sweep fix at line ~1240
            // bumps `maxThreat` to MEDIUM but does NOT touch
            // `results[q].threatLevel`, so the loop above misses cases where
            // the only motion signal is an in-zone person tracker holding
            // through a brightness sweep. Those frames are exactly the
            // "real intrusion under flash" scenario the immunity branch
            // exists for — they MUST keep lastMotionTime alive, otherwise
            // the recording falls back to the hard ceiling instead of
            // stopping cleanly when the person actually leaves. Mirror the
            // same in-zone person-tracker probe used by trackerHolding
            // below (line ~1899).
            if (!qualifyingMotion && maxThreat >= MotionPipelineV2.THREAT_MEDIUM) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0
                                    && trackerInZone(q)) {
                                qualifyingMotion = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (qualifyingMotion) {
                lastMotionTime = now;
            }

            // Track peak threat across the entire motion sequence
            if (maxThreat > peakThreatDuringSequence) {
                peakThreatDuringSequence = maxThreat;
            }
            
            // Log motion to timeline — ALWAYS, even before recording starts.
            // The timeline collector's pre-trigger ring buffer captures events during
            // the approach phase. When recording triggers, these are flushed into the
            // active span array with timestamps aligned to the video's pre-record window.
            timelineCollector.onMotionDetected(lastActiveBlocksCount, pipelineV2.getActiveQuadrantMask());
            
            if (firstMotionTime == 0) {
                firstMotionTime = now;
                peakThreatDuringSequence = maxThreat;
                // New sequence: clear the latched HIGH-trust flag. It is
                // re-evaluated and re-latched below (and each subsequent frame)
                // from the live coherence/tracker signal for THIS sequence, so a
                // flag's untrusted HIGH can't inherit trust from a prior real
                // loiterer. Reset here (the single sequence-start point) rather
                // than at the 5 scattered sequence-end sites.
                cachedHighIsTrusted = false;
                cachedIncoherentLoiter = false;
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                // SOTA proximity: bbox-height (post-YOLO) or tier+trend (pre-YOLO).
                // At motion-start YOLO has almost certainly not fired yet, so this
                // log line will read e.g. "near approaching" rather than fabricate
                // a metric distance from the motion-block centroid.
                DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                        ? proximityForQuadrant(bestQ, bestR)
                        : DistanceEstimator.ProximityEstimate.tierOnly(
                                DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                String threatStr = maxThreat >= MotionPipelineV2.THREAT_HIGH ? "HIGH(loiter)" : "MEDIUM(approach)";
                // Trust probe at motion-start (re-latched each frame below). Tells
                // us up-front whether a HIGH is a real loiterer or a flag/shadow.
                boolean startTrusted = (maxThreat >= MotionPipelineV2.THREAT_HIGH)
                        && highThreatIsTrusted(bestQ, bestR);
                long needed = (maxThreat >= MotionPipelineV2.THREAT_HIGH && startTrusted)
                        ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                logger.info(String.format("Motion started: %s camera, threat=%s, prox=%s, %s, need %.1fs sustained...",
                        bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?",
                        threatStr, prox.describe(), describeHighTrust(maxThreat, startTrusted, bestR),
                        needed / 1000.0));
            }
            
            long motionDuration = now - firstMotionTime;

            // Use peak threat for duration requirement (not just current frame).
            // This prevents a brief MEDIUM→NONE→MEDIUM flicker from resetting the clock.
            int effectiveThreat = peakThreatDuringSequence;

            // FLAG/SHADOW GUARD: is this THREAT_HIGH a real loiterer (coherent
            // translation or an in-zone person tracker) or an in-place oscillator
            // (waving flag / sweeping shadow)? Evaluated against the current
            // best-threat quadrant. Latched into cachedHighIsTrusted for the
            // async YOLO matrix. Only meaningful at HIGH; harmless at MEDIUM.
            int trustQ = pipelineV2.getHighestThreatQuadrant();
            boolean highIsTrusted = (effectiveThreat >= MotionPipelineV2.THREAT_HIGH)
                    && highThreatIsTrusted(trustQ, trustQ >= 0 ? results[trustQ] : null);
            // Once trusted in a sequence, stay trusted (a real loiterer who goes
            // briefly still mid-sequence shouldn't be downgraded to a flag).
            if (highIsTrusted) cachedHighIsTrusted = true;

            // POSITIVE incoherence evidence for the timeout extension below.
            // Distinct from "untrusted": untrusted includes the fail-open
            // (coherence unavailable) case, whereas this requires the native
            // signal to have actually FIRED and reported incoherent flow — i.e.
            // a confirmed flag/foliage/shadow oscillation, not just "no signal".
            // Latched for the whole sequence and never set when a coherent or
            // tracked frame appeared (cachedHighIsTrusted), so a real approach
            // that briefly stalls can't arm the flag-extension.
            if (trustQ >= 0 && !cachedHighIsTrusted) {
                MotionPipelineV2.QuadrantResult tr = results[trustQ];
                if (tr != null && tr.flowCoherence >= 0f
                        && tr.flowCoherence < COHERENCE_RATIO_MIN
                        && tr.netDriftBlocks < COHERENCE_NET_MIN) {
                    cachedIncoherentLoiter = true;
                }
            }

            // Determine required sustained motion based on threat level:
            // - THREAT_HIGH, TRUSTED (coherent/tracked loiter): base delay 500ms.
            // - THREAT_HIGH, UNTRUSTED (flag/shadow): treated like MEDIUM —
            //   require the full loitering time so it must clear the YOLO gate.
            // - THREAT_MEDIUM (approaching) + YOLO-confirmed in-zone object:
            //   the short approachTriggerMs fast-path (records a walk-up/walk-past
            //   without waiting out the full loiter dwell).
            // - THREAT_MEDIUM, motion-only (no AI confirmation yet): the full
            //   loitering time, so lighting artifacts / flags / shadows (which
            //   never yield a YOLO object) can't take the fast path.
            long requiredDuration;
            if (effectiveThreat >= MotionPipelineV2.THREAT_HIGH && cachedHighIsTrusted) {
                requiredDuration = SUSTAINED_MOTION_BASE_MS;
            } else {
                requiredDuration = loiteringTimeMs;
                // Approach fast-path: enabled (approachTriggerMs>0), AI confirmed a
                // real object during THIS sequence, and the fast bar is shorter than
                // the loiter bar. firstMotionTime>0 guards against a stale prior-
                // sequence confirmation leaking in (lastAiConfirmationTimeMs is reset
                // on enable()/sequence handling).
                if (approachTriggerMs > 0
                        && firstMotionTime > 0
                        && lastAiConfirmationTimeMs >= firstMotionTime
                        && approachTriggerMs < requiredDuration) {
                    requiredDuration = approachTriggerMs;
                }
            }

            // --- Diagnostic: Log sustained motion progress ---
            if (motionDuration > 0 && motionDuration < requiredDuration) {
                // Log every second while waiting
                if (motionDuration % 1000 < MOTION_PROCESS_INTERVAL_MS) {
                    String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    MotionPipelineV2.QuadrantResult bestR = bestQ >= 0 ? results[bestQ] : null;
                    DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                            ? proximityForQuadrant(bestQ, bestR)
                            : DistanceEstimator.ProximityEstimate.tierOnly(
                                    DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                    logger.info(String.format("Motion building: %.1fs / %.1fs | threat=%s | prox=%s | loiterSetting=%ds",
                            motionDuration / 1000.0, requiredDuration / 1000.0,
                            threatNames[maxThreat], prox.describe(), (int)(loiteringTimeMs / 1000)));
                }
            }
            
            // FIX: Early AI initialization — queue YOLO on active quadrants as soon as
            // motion is detected, not after the loitering timer expires. This lets the
            // EventTimelineCollector build contextual history (person vs car vs bike)
            // BEFORE the MP4 write is triggered. Without this, if someone leaves the
            // frame right at the trigger threshold, YOLO runs on an empty frame and the
            // JSON sidecar records a generic "motion" event instead of classifying it.
            if (useObjectDetection && !isAiRunning.get() && aiQuadrantQueueIsEmpty()) {
                int bestQ = pipelineV2.getHighestThreatQuadrant();
                if (bestQ >= 0) aiQuadrantQueueAdd(bestQ);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (q != bestQ && results[q].motionDetected) {
                        aiQuadrantQueueAdd(q);
                    }
                }
                // Kick off AI immediately if cooldown allows
                if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                    runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                }
            }
            
            if (motionDuration >= requiredDuration) {
                inActiveMode = true;
                
                // Filter debug log
                if (filterDebugEnabled) {
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                    String[] threatNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                    MotionPipelineV2.QuadrantResult r = bestQ >= 0 ? results[bestQ] : null;
                    DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                            ? proximityForQuadrant(bestQ, r)
                            : DistanceEstimator.ProximityEstimate.tierOnly(
                                    DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                    addFilterLogEntry(String.format("[%s] TRIGGER: %s threat=%s prox=%s active=%d confirmed=%d component=%d sustained=%.1fs",
                            new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                            qName, threatNames[maxThreat], prox.describe(),
                            r != null ? r.activeBlocks : 0, r != null ? r.confirmedBlocks : 0,
                            r != null ? r.componentSize : 0, motionDuration / 1000.0));
                }
                
                if (!recording) {
                    // AI CONFIRMATION GATE: For THREAT_MEDIUM, require YOLO to have confirmed
                    // a real object during this motion sequence before committing a recording.
                    // This prevents lighting artifacts (streetlights, porch lights, deterrent
                    // flashes, slow headlight sweeps) from triggering false recordings.
                    //
                    // For THREAT_HIGH (loitering), only gate during the deterrent window —
                    // loitering is confirmed by 10+ seconds of centroid analysis, which is
                    // strong enough evidence on its own. But the deterrent's own light creates
                    // a static centroid that mimics loitering, so we gate it there.
                    //
                    // TIMEOUT FALLBACK: If YOLO hasn't confirmed within 2 seconds past the
                    // required sustained duration, let it through anyway. This handles:
                    // - YOLO model not loaded (useObjectDetection=false)
                    // - YOLO busy on another quadrant (AI cooldown)
                    // - Object too small/dark for YOLO but real (motion evidence sufficient)
                    //
                    // The 2-second grace is safe because YOLO gets queued at motion start
                    // (early AI init). If it hasn't confirmed in 5+ seconds of motion, the
                    // object is genuinely undetectable by YOLO and motion evidence alone
                    // must be trusted.
                    boolean deterrentActive = deterrentFiredTime > 0
                            && (now - deterrentFiredTime) < DETERRENT_SUPPRESSION_MS;
                    boolean aiRecentlyConfirmed = lastAiConfirmationTimeMs >= firstMotionTime;  // Confirmed during THIS sequence
                    // FIX: aiAvailable must also reflect the user-side gate
                    // (classFilter empty OR aiEnabled false). Previously this
                    // only tracked the daemon-classpath state, so a user who
                    // turned all object classes off in the UI fell into the
                    // "no AI" path on every loop iteration — and combined
                    // with the missing post-stop cooldown below, every gust
                    // of motion fired a recording. That leaked MediaCodec
                    // slots and thumbnail-buffer allocations until the
                    // daemon was killed by SIGABRT (codec exhaustion) or
                    // OOM, eventually tripping wrapper retry exhaustion.
                    boolean aiAvailable = useObjectDetection && yoloDetector != null
                            && aiEnabled
                            && (classFilter == null || classFilter.length > 0);
                    long timePastRequired = motionDuration - requiredDuration;  // How long past the trigger threshold
                    
                    // TIMEOUT FALLBACK: Let motion through if YOLO hasn't confirmed in time.
                    // BUT: If brightness suppression fired during this motion sequence, the
                    // motion is likely a lighting artifact. In that case, extend the timeout
                    // to the full deterrent window (5s) — persistent lights (streetlights)
                    // create motion that lasts indefinitely, so a short timeout would let
                    // them through. If it's a real person in changing light, YOLO WILL see
                    // them within 5 seconds (multiple inference opportunities).
                    boolean brightnessEventDuringSequence = false;
                    for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                        if (suppressionWasActive[q] || results[q].brightnessSuppressed) {
                            brightnessEventDuringSequence = true;
                            break;
                        }
                    }
                    // Extend the YOLO-confirm timeout when EITHER a brightness
                    // event occurred (lighting artifact) OR the native signal
                    // positively confirmed an incoherent in-place loiter (a
                    // relentlessly-waving flag / sweeping shadow). Both are
                    // motion sources that persist indefinitely, so the short 2s
                    // fallback would otherwise leak one recording. A real person
                    // is confirmed by YOLO well within this window; if YOLO
                    // genuinely can't see them, the extended timeout still fires
                    // (downgrade, not drop). cachedIncoherentLoiter is never set
                    // once a coherent/tracked frame appeared, so a real approach
                    // that briefly stalls is unaffected.
                    long timeoutMs = (brightnessEventDuringSequence || cachedIncoherentLoiter)
                            ? DETERRENT_SUPPRESSION_MS : 2000;
                    boolean timeoutExpired = timePastRequired > timeoutMs;
                    
                    boolean shouldSuppress = false;
                    if (aiAvailable && !aiRecentlyConfirmed && !timeoutExpired) {
                        if (effectiveThreat <= MotionPipelineV2.THREAT_MEDIUM) {
                            // MEDIUM: always require AI confirmation (with timeout fallback)
                            shouldSuppress = true;
                        } else if (deterrentActive) {
                            // HIGH during deterrent: require AI confirmation (deterrent mimics loitering)
                            shouldSuppress = true;
                        } else if (!cachedHighIsTrusted) {
                            // HIGH but UNTRUSTED (flag/shadow: incoherent in-place
                            // oscillation, no in-zone person tracker) → require AI
                            // confirmation exactly like MEDIUM. A real loiterer is
                            // either coherently translating or tracker-held, so this
                            // only gates the waving-flag / sweeping-shadow case. Still
                            // records via the 2s timeout fallback / no-YOLO paths, so
                            // a genuinely YOLO-invisible loiterer is delayed, not lost.
                            shouldSuppress = true;
                        }
                    }
                    // NO-YOLO DETERRENT FALLBACK: When object detection is not available
                    // (daemon mode without Context/AssetManager), the AI gate can't function.
                    // But we still know when OUR OWN deterrent fired. Use a pure time-based
                    // suppression: block new recordings for the full deterrent window after
                    // we fired the lights. This prevents the exact scenario from the logs:
                    // deterrent fires → light flash → motion re-triggers → second recording.
                    // Without YOLO there's no way to confirm "is this a real person or just
                    // our own lights?" so we err on the side of suppressing false positives.
                    // Real threats that arrive during the 5s window will still be caught
                    // because the first recording's post-record (10s) covers the gap.
                    if (!aiAvailable && deterrentActive && !recording) {
                        shouldSuppress = true;
                        if (frameCount % 50 == 0) {
                            logger.debug(String.format(
                                "No-YOLO deterrent guard: suppressing (deterrent %.1fs ago, no AI available)",
                                (now - deterrentFiredTime) / 1000.0));
                        }
                        firstMotionTime = 0;
                        peakThreatDuringSequence = 0;
                    }

                    // NO-AI RATE LIMIT: when YOLO is off, motion-only triggers re-fire
                    // on every wind gust / shadow / streetlight artifact. Each retrigger
                    // forces a fresh muxer init + pre-record flush; over a multi-hour
                    // park that storm leaks MediaCodec instance slots on the Adreno 610
                    // and steadily inflates direct-buffer RSS until the daemon takes
                    // SIGABRT (codec exhaustion) or SIGKILL (LMK). YOLO confirmation is
                    // the natural rate-limit on real installs — without it, enforce a
                    // minimum gap between consecutive recordings so a noisy parking lot
                    // can't trigger more than once per NO_AI_MIN_GAP_MS.
                    //
                    // Only applies when AI is genuinely unavailable (daemon-classpath
                    // OR user-disabled) AND we're not already recording AND the last
                    // recording stopped recently. The post-record window (10s default)
                    // already covers the immediate aftermath; this gate runs after that.
                    if (!aiAvailable && !recording && lastRecordingStopTime > 0) {
                        long sinceLastStop = now - lastRecordingStopTime;
                        if (sinceLastStop < NO_AI_MIN_GAP_MS) {
                            shouldSuppress = true;
                            if (frameCount % 50 == 0) {
                                logger.debug(String.format(
                                    "No-AI rate limit: suppressing (last recording stopped %.1fs ago, min gap %.1fs)",
                                    sinceLastStop / 1000.0, NO_AI_MIN_GAP_MS / 1000.0));
                            }
                            firstMotionTime = 0;
                            peakThreatDuringSequence = 0;
                        }
                    }
                    
                    if (shouldSuppress) {
                        if (frameCount % 50 == 0) {
                            String[] tNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                            logger.debug(String.format(
                                "AI gate holding: threat=%s, motion=%.1fs, grace=%.1fs remaining, deterrent=%s, brightnessEvent=%s",
                                tNames[effectiveThreat], motionDuration / 1000.0,
                                Math.max(0, timeoutMs - timePastRequired) / 1000.0,
                                deterrentActive ? "active" : "inactive",
                                brightnessEventDuringSequence ? "yes" : "no"));
                        }
                        // Don't reset firstMotionTime — let the timer keep running.
                        // When YOLO confirms (or timeout expires), the trigger fires immediately.
                    }
                    // SOTA: Event stitching — if new motion appears shortly after the last
                    // recording stopped, start a new recording immediately. The previous
                    // recentlyStoppedRecording cooldown blocked new recordings for the entire
                    // postRecordMs window after a stop, causing missed events when someone
                    // lingered near the car. The 3-second sustained motion requirement already
                    // prevents rapid-fire false triggers, so the cooldown is unnecessary.
                    else {
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        // If no quadrant has motion (e.g., tracker held through flash),
                        // fall back to the quadrant with an active tracker lock.
                        // ZONE GATE: only consider trackers whose bbox bottom is
                        // in-zone — otherwise an out-of-zone person locked by
                        // the tracker can leak past the user's "close"/"normal"
                        // setting and trigger recording. trackerInZone() returns
                        // true when the gate is disabled ("extended") so this
                        // doesn't change behaviour for users who chose extended.
                        if (bestQ < 0) {
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq) && trackerInZone(tq)) {
                                        bestQ = tq;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        String qName = bestQ >= 0 ? MotionPipelineV2.QUADRANT_NAMES[bestQ] : "?";
                        String triggerSource = (pipelineV2.getMaxThreatLevel() >= MotionPipelineV2.THREAT_MEDIUM)
                                ? "motion" : "tracker";
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        MotionPipelineV2.QuadrantResult bestResult = bestQ >= 0 ? results[bestQ] : null;

                        // SOTA proximity: prefers post-YOLO bbox-height inference,
                        // falls back to discrete tier+trend pre-YOLO. The trigger
                        // gate itself doesn't depend on this value (zone gating is
                        // row-based natively); this is for the human-readable log
                        // line and the downstream notification copy.
                        DistanceEstimator.ProximityEstimate prox = bestQ >= 0
                                ? proximityForQuadrant(bestQ, bestResult)
                                : DistanceEstimator.ProximityEstimate.tierOnly(
                                        DistanceEstimator.Tier.UNKNOWN, DistanceEstimator.Trend.UNKNOWN);
                        String proxStr = prox.describe();

                        String detectionZone = config != null ? config.getDetectionZone() : "?";
                        int sensitivityLevel = config != null ? config.getSensitivityLevel() : -1;
                        int loiteringSec = config != null ? config.getLoiteringTimeSeconds() : -1;
                        int maxRow = pipelineV2Config != null ? pipelineV2Config.maxDistanceRow : 0;
                        String zoneLimitStr = maxRow > 0
                                ? (maxRow == 4 ? "close" : maxRow == 2 ? "normal" : "extended")
                                : "none";

                        logger.info(String.format(
                            ">>> RECORDING TRIGGERED <<<\n" +
                            "  Camera: %s | Threat: %s | Proximity: %s | Sustained: %.1fs | Source: %s | %s\n" +
                            "  Blocks: active=%d, confirmed=%d, component=%d\n" +
                            "  Settings: sensitivity=%d, zone=%s (limit %s), loiterTime=%ds\n" +
                            "  Why: threat %s >= MEDIUM ✓, duration %.1fs >= %.1fs ✓, proximity %s within zone ✓",
                            qName, threatNames[maxThreat], proxStr, motionDuration / 1000.0, triggerSource,
                            describeHighTrust(maxThreat, cachedHighIsTrusted, bestResult),
                            bestResult != null ? bestResult.activeBlocks : 0,
                            bestResult != null ? bestResult.confirmedBlocks : 0,
                            bestResult != null ? bestResult.componentSize : 0,
                            sensitivityLevel, detectionZone,
                            zoneLimitStr,
                            loiteringSec,
                            threatNames[maxThreat], motionDuration / 1000.0, requiredDuration / 1000.0,
                            proxStr));
                        
                        recordingStopTime = now + postRecordMs;
                        recordingTriggerStartMs = now;
                        startRecording();

                        // Only fire the start-stage notifications when a recording
                        // is actually active. startRecording() can refuse (encoder
                        // savedFormat barrier on cold boot) and leave recording=false
                        // — without this guard a "Recording in progress" Telegram
                        // ping + push banner would fire for an event that never
                        // started and whose final-stage replacement never comes,
                        // leaving a dangling never-resolved notification.
                        if (recording) {
                            try {
                                String videoFilename = currentEventFile != null ? currentEventFile.getName() : null;
                                sendRichMotionNotifications(videoFilename);
                                publishMotionNotification(videoFilename);
                            } catch (Exception e) {
                                logger.warn("Failed to send motion notification: " + e.getMessage());
                            }
                        }

                        // SOTA: Fire deterrents (cloud + screen). Both run on
                        // background threads and never block the surveillance pipeline.
                        // Each one independently honors its own enabled flag and cooldown.
                        try {
                            com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                            ScreenDeterrent.getInstance().onMotionDetected();
                            deterrentFiredTime = now;  // Track when deterrent was dispatched
                        } catch (Exception e) {
                            logger.debug("Deterrent dispatch failed: " + e.getMessage());
                        }
                    }
                } else {
                    // Already recording — extend recording timer on continued motion.
                    // Any quadrant with MEDIUM+ threat extends the recording.
                    long newStopTime = now + postRecordMs;
                    if (newStopTime > recordingStopTime) {
                        recordingStopTime = newStopTime;
                    }

                    // SOTA: Recurring deterrent — re-trigger while motion continues.
                    // Per-deterrent cooldowns prevent spamming.
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                        ScreenDeterrent.getInstance().onMotionDetected();
                        deterrentFiredTime = now;  // Track latest deterrent dispatch
                    } catch (Exception e) {
                        // Fail silently — never block surveillance
                    }
                    
                    // Also run YOLO on new quadrants that have motion (even if different from original)
                    if (useObjectDetection && !isAiRunning.get()) {
                        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                            if (results[q].motionDetected && results[q].threatLevel >= MotionPipelineV2.THREAT_MEDIUM) {
                                aiQuadrantQueueAdd(q);  // dedups internally
                            }
                        }
                        // FIX: Check cooldown before consuming queue item
                        if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                            runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                        }
                    }
                }

                // Staggered YOLO: queue active quadrants for AI detection
                if (useObjectDetection && !isAiRunning.get()) {
                    aiQuadrantQueueClear();
                    // Add quadrants sorted by threat level (highest first)
                    int bestQ = pipelineV2.getHighestThreatQuadrant();
                    if (bestQ >= 0) aiQuadrantQueueAdd(bestQ);
                    for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                        if (q != bestQ && results[q].motionDetected) {
                            aiQuadrantQueueAdd(q);
                        }
                    }
                    // FIX: Check cooldown before consuming queue item
                    if (!aiQuadrantQueueIsEmpty() && (System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                        runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
                    }
                }
            }
        } else {
            // No motion detected on this frame (all quadrants below MEDIUM threat).
            // Don't immediately end the sequence — allow gaps up to 2 seconds.
            // A person walking past creates motion bursts with brief gaps as they
            // move between quadrants or between block boundaries. A 500ms gap was
            // too tight and caused the sequence to reset before reaching the trigger.
            if (!recording) {
                long timeSinceLastMotion = now - lastMotionTime;
                
                // SOTA: Extend gap tolerance during cross-quadrant transit.
                // When a person walks from the left camera to the rear camera, there's
                // a brief gap where neither camera has MEDIUM+ threat (left is decaying,
                // rear hasn't confirmed yet). Without this fix, the 2-second timeout
                // resets firstMotionTime, and the rear camera starts a fresh sequence
                // from zero — the person's total approach time is never accumulated.
                //
                // If the texture tracker has an active track, we know an object is still
                // physically present. Extend the gap tolerance to 4 seconds to bridge
                // the cross-quadrant handoff. Also check for any quadrant with active
                // blocks (even below MEDIUM threat) as a secondary signal.
                //
                // FIX: Also extend tolerance if YOLO is currently running or queued.
                // The tracker is started inside the async YOLO lambda. If motion drops
                // before the lambda executes, trackerHasActiveTrack returns false even
                // though YOLO is about to classify the person and start a track.
                boolean trackerActive = false;
                boolean anyLowActivity = false;
                boolean aiPending = isAiRunning.get() || !aiQuadrantQueueIsEmpty();
                // SOTA: If YOLO confirmed a person during this motion sequence, extend
                // gap tolerance. The person may have briefly moved between block boundaries
                // or between quadrants, causing motion to drop below MEDIUM. But YOLO
                // already verified they're real — don't kill the sequence prematurely.
                boolean aiConfirmedDuringSequence = (firstMotionTime > 0) 
                        && (lastAiConfirmationTimeMs >= firstMotionTime);
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(q)) {
                            float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                            if (trackBox != null && (int) trackBox[5] == 0) { // person only
                                // ZONE GATE: only extend gap tolerance for an
                                // in-zone person. An out-of-zone tracker lock
                                // shouldn't keep the motion sequence alive
                                // past the normal 2s gap.
                                if (trackerInZone(q)) {
                                    trackerActive = true;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    if (results[q].activeBlocks > 0) anyLowActivity = true;
                }
                long gapTolerance = (trackerActive || anyLowActivity || aiPending || aiConfirmedDuringSequence) ? 4000 : 2000;
                
                // DEFERRED TRIGGER: If the motion duration already exceeded the threshold
                // during this sequence AND YOLO confirmed a real person, trigger NOW even
                // though the current frame has no MEDIUM+ motion. This catches the case
                // where the person is still present (YOLO confirmed) but motion blocks
                // briefly dipped below MEDIUM on the exact frame where duration crossed
                // the threshold. Without this, the sequence dies at gap tolerance expiry
                // even though all conditions were met.
                if (firstMotionTime != 0 && !recording && aiConfirmedDuringSequence) {
                    long motionDuration = lastMotionTime - firstMotionTime;
                    // Mirror the inline requiredDuration logic EXACTLY: only a
                    // TRUSTED HIGH (coherent/tracked loiter) gets the 500ms base —
                    // an untrusted HIGH (flag/shadow) is treated like MEDIUM. Else
                    // the loiter bar, shortened to approachTriggerMs because YOLO has
                    // confirmed a real object during this sequence (the gate above).
                    long requiredMs;
                    if (peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH && cachedHighIsTrusted) {
                        requiredMs = SUSTAINED_MOTION_BASE_MS;
                    } else {
                        requiredMs = loiteringTimeMs;
                        if (approachTriggerMs > 0 && approachTriggerMs < requiredMs) {
                            requiredMs = approachTriggerMs;
                        }
                    }
                    if (motionDuration >= requiredMs && peakThreatDuringSequence >= MotionPipelineV2.THREAT_MEDIUM) {
                        // All conditions met: duration exceeded, threat was MEDIUM+, YOLO confirmed person
                        logger.info(String.format("DEFERRED TRIGGER: motion=%.1fs >= %.1fs, AI confirmed, triggering from gap phase",
                                motionDuration / 1000.0, requiredMs / 1000.0));
                        inActiveMode = true;
                        motionDetections++;
                        int bestQ = pipelineV2.getHighestThreatQuadrant();
                        if (bestQ < 0) {
                            // No quadrant has motion right now — use the last known active quadrant.
                            // ZONE GATE: only fall back to trackers whose bbox bottom is in-zone.
                            for (int tq = 0; tq < MotionPipelineV2.NUM_QUADRANTS; tq++) {
                                try {
                                    if (NativeMotion.trackerHasActiveTrack(tq) && trackerInZone(tq)) { bestQ = tq; break; }
                                } catch (Exception ignored) {}
                            }
                        }
                        recordingStopTime = now + postRecordMs;
                        recordingTriggerStartMs = now;
                        startRecording();
                        // Guard on recording (see the other trigger site): no
                        // start-stage notification when startRecording() refused.
                        if (recording) {
                            try {
                                String videoFilename = currentEventFile != null ? currentEventFile.getName() : null;
                                sendRichMotionNotifications(videoFilename);
                                publishMotionNotification(videoFilename);
                            } catch (Exception e) {
                                logger.warn("Failed to send motion notification: " + e.getMessage());
                            }
                        }
                        try {
                            com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().onMotionDetected();
                            ScreenDeterrent.getInstance().onMotionDetected();
                            deterrentFiredTime = now;
                        } catch (Exception e) {
                            logger.debug("Deterrent dispatch failed: " + e.getMessage());
                        }
                    }
                }

                if (firstMotionTime != 0 && timeSinceLastMotion > gapTolerance) {
                    // Motion sequence ended without triggering
                    long motionDuration = lastMotionTime - firstMotionTime;
                    if (motionDuration > 200) {
                        String[] threatNames = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
                        long requiredMs = (peakThreatDuringSequence >= MotionPipelineV2.THREAT_HIGH)
                                ? SUSTAINED_MOTION_BASE_MS : loiteringTimeMs;
                        logger.info(String.format("Motion ended WITHOUT trigger: lasted=%.1fs, peakThreat=%s, required=%.1fs, gapTolerance=%.1fs%s",
                                motionDuration / 1000.0, threatNames[peakThreatDuringSequence],
                                requiredMs / 1000.0, gapTolerance / 1000.0,
                                trackerActive ? " (tracker was active)" : ""));
                    }
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                }
            }
        }
        
        // Post-record check: stop recording when no motion for postRecordMs.
        // SOTA: Also check ANY quadrant for activity (not just MEDIUM+ threat).
        // A person standing still near the car produces minimal block changes but
        // is still a valid reason to keep recording. Use a lower threshold:
        // any quadrant with confirmedBlocks > 0 counts as "activity" for post-record.
        if (recording && now >= recordingStopTime && recordingStopTime > 0) {
            // HARD CEILING. The post-record window can be extended forever
            // by a stuck tracker, sustained shadow loop, or out-of-zone
            // residual motion. Cap total recording length at 3× postRecordMs
            // (e.g. 30s for the default 10s setting) measured from the
            // original trigger time. Beyond this, force-stop regardless of
            // tracker state. Bounded blast radius for any future detector
            // bug that creates an extension loop.
            long elapsedSinceTrigger = recordingTriggerStartMs > 0
                    ? now - recordingTriggerStartMs : 0;
            long maxRecordingMs = postRecordMs * 3L;
            if (recordingTriggerStartMs > 0 && elapsedSinceTrigger >= maxRecordingMs) {
                logger.warn(String.format(
                        "V2 post-record HARD CEILING reached (%.1fs >= %.1fs cap = 3× postRecord). "
                        + "Force-stopping regardless of tracker/activity state.",
                        elapsedSinceTrigger / 1000.0, maxRecordingMs / 1000.0));
                stopRecording();
                recordingStopTime = 0;
                recordingTriggerStartMs = 0;
                firstMotionTime = 0;
                peakThreatDuringSequence = 0;
                return;
            }

            // Check if any quadrant has residual activity (even below MEDIUM threat)
            boolean anyActivity = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].confirmedBlocks > 0 || results[q].activeBlocks > 0) {
                    anyActivity = true;
                    break;
                }
            }
            
            // SOTA: Also check texture tracker — a person standing still produces
            // zero motion blocks but the NCC tracker holds a lock on their pixel texture.
            // This is the "Static Foreground Victory" — recording stays alive as long as
            // the tracked object is present, even with zero motion pipeline activity.
            // FIX: Only person tracks (classId==0) can hold recording open.
            // Parked vehicles (motorcycles, cars) would otherwise keep recording
            // indefinitely via the "hitchhiker" pattern.
            boolean trackerHolding = false;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        float[] trackBox = NativeMotion.trackerGetTrackBox(q);
                        if (trackBox != null && (int) trackBox[5] == 0) { // class 0 = person only
                            // ZONE GATE: only let an in-zone tracker hold the
                            // post-record window open. An out-of-zone lock
                            // shouldn't keep recording alive after motion ends.
                            if (trackerInZone(q)) {
                                trackerHolding = true;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            
            if (anyActivity || trackerHolding) {
                // Still some activity or tracker holding — extend recording
                recordingStopTime = now + postRecordMs;
                if (trackerHolding && !anyActivity && frameCount % 100 == 0) {
                    logger.info("Post-record extended by texture tracker (no motion, object still present)");
                }
            } else {
                long timeSinceLastMotion = now - lastMotionTime;
                if (timeSinceLastMotion >= postRecordMs) {
                    logger.info(String.format("V2 post-record complete — stopping (no motion for %.1fs)",
                            timeSinceLastMotion / 1000.0));
                    stopRecording();
                    recordingStopTime = 0;
                    recordingTriggerStartMs = 0;
                    firstMotionTime = 0;
                    peakThreatDuringSequence = 0;
                }
            }
        }
        
        // SOTA: Update texture tracker on every frame (runs NCC template matching).
        // This is the core of the decoupled tracking — YOLO sleeps, NCC tracks.
        // Also handles YOLO heartbeat: when NCC confidence drops below 0.60 or
        // 3 seconds have elapsed, the tracker requests YOLO re-verification.
        if (recording) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                try {
                    if (NativeMotion.trackerHasActiveTrack(q)) {
                        // Feed the quadrant crop to the tracker
                        int qW = THUMBNAIL_WIDTH / 2;
                        int qH = THUMBNAIL_HEIGHT / 2;
                        byte[] quadCrop = cropFromMosaic(smallRgbFrame, q, qW, qH);
                        if (quadCrop != null) {
                            NativeMotion.trackerUpdate(quadCrop, qW, qH, q, now);
                        }
                        
                        // Check if tracker wants YOLO heartbeat (NCC score dropped or timer expired).
                        // FIX: Enforce a hard 2-second cooldown per quadrant to prevent heartbeat spam.
                        // Without this, a failing NCC tracker fires needsYoloHeartbeat=true on every
                        // single frame, turning YOLO into a 10 FPS continuous detector and destroying
                        // the battery savings of the decoupled architecture.
                        if (NativeMotion.trackerNeedsYoloHeartbeat(q)) {
                            long timeSinceLastHeartbeat = now - lastHeartbeatTimeMs[q];
                            if (timeSinceLastHeartbeat >= HEARTBEAT_COOLDOWN_MS
                                    && useObjectDetection && !isAiRunning.get()) {
                                aiQuadrantQueueAdd(q);  // dedups internally
                                lastHeartbeatTimeMs[q] = now;
                                logger.info("Tracker heartbeat: waking YOLO for Q" + q +
                                        " [" + MotionPipelineV2.QUADRANT_NAMES[q] + "]");
                            }
                        }
                    }
                } catch (Exception e) {
                    // Tracker not available — continue without it
                }
            }
        }
        
        // Process staggered YOLO queue (one per frame)
        // FIX: Check cooldown BEFORE polling the queue. Previously, poll() consumed
        // the quadrant, then runAiOnQuadrant's internal cooldown check rejected it —
        // permanently vaporizing that quadrant's AI pass.
        if (useObjectDetection && !isAiRunning.get() && !aiQuadrantQueueIsEmpty()) {
            if ((System.currentTimeMillis() - lastAiTimeMs) >= AI_COOLDOWN_MS) {
                runAiOnQuadrant(smallRgbFrame, aiQuadrantQueuePoll());
            }
        }
        
        // Periodic stats
        if (frameCount % 500 == 0) {
            logger.info(String.format("V2 stats: frames=%d, motions=%d, recording=%b",
                    frameCount, motionDetections, recording));
            // Log per-quadrant status for debugging
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                MotionPipelineV2.QuadrantResult r = results[q];
                String status = r.brightnessSuppressed ? "SUPPRESSED" : 
                    (r.motionDetected ? "MOTION(t=" + r.threatLevel + ")" : "quiet");
                logger.info(String.format("  Q%d[%s]: %s active=%d confirmed=%d component=%d luma=%.0f",
                        q, MotionPipelineV2.QUADRANT_NAMES[q], status,
                        r.activeBlocks, r.confirmedBlocks, r.componentSize, r.meanLuma));
            }
            
            // SOTA: Auto day/night mode switch based on ambient light.
            // The BYD's camera ISP boosts ISO at night, pushing mean luma to ~75-85.
            // Daytime luma sits at ~115-130. The threshold of 95 cleanly splits the two.
            // Night mode relaxes edge/shadow thresholds to handle ISO noise and headlights.
            float avgLuma = 0;
            int lumaCount = 0;
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (results[q].meanLuma > 0) {
                    avgLuma += results[q].meanLuma;
                    lumaCount++;
                }
            }
            if (lumaCount > 0) {
                avgLuma /= lumaCount;
                
                // Java-level auto-exposure: simple day/night switch for global params.
                // Threshold scaling is handled per-quadrant in C++ with relative multipliers.
                boolean shouldBeNight = avgLuma < 90.0f;
                boolean currentlyNight = isNightMode();
                
                if (shouldBeNight != currentlyNight && pipelineV2Config != null) {
                    // Restore base global params from user's preset
                    String preset = config != null ? config.getEnvironmentPreset() : "outdoor";
                    MotionPipelineV2.Config tempCfg = new MotionPipelineV2.Config();
                    tempCfg.applyEnvironmentPreset(preset);
                    
                    pipelineV2Config.brightnessShiftThreshold = tempCfg.brightnessShiftThreshold;
                    pipelineV2Config.brightnessSuppressionFrames = tempCfg.brightnessSuppressionFrames;
                    pipelineV2Config.shadowFilterMode = tempCfg.shadowFilterMode;
                    pipelineV2Config.chromaRatioTolerance = tempCfg.chromaRatioTolerance;
                    pipelineV2Config.shadowPixelFraction = tempCfg.shadowPixelFraction;
                    pipelineV2Config.oscillationThreshold = tempCfg.oscillationThreshold;
                    
                    if (shouldBeNight) {
                        pipelineV2Config.brightnessShiftThreshold = 0.35f;
                        pipelineV2Config.brightnessSuppressionFrames = 8;
                        pipelineV2Config.shadowFilterMode = 1;  // LIGHT
                        pipelineV2Config.chromaRatioTolerance = 0.25f;
                        pipelineV2Config.shadowPixelFraction = 0.7f;
                        pipelineV2Config.oscillationThreshold = 4;
                        setNightMode(true);
                        logger.info(String.format("Auto NIGHT mode (avgLuma=%.0f < 95)", avgLuma));
                    } else {
                        setNightMode(false);
                        logger.info(String.format("Auto NORMAL mode (avgLuma=%.0f >= 95)", avgLuma));
                    }
                    pipelineV2.applyConfig(pipelineV2Config);
                    
                    // SOTA: Refresh detection baseline on lighting transition.
                    // Dawn/dusk changes affect detector confidence scores and object
                    // appearance. Refresh all quadrants to keep baseline accurate.
                    // Cost: 4 inferences, happens 2-3 times per night.
                    // Runs on AI executor thread to avoid blocking motion pipeline.
                    if (baselineSeeded && useObjectDetection && yoloDetector != null) {
                        logger.info("Queuing detection baseline refresh (lighting transition)...");
                        final byte[] frameSnapshot = new byte[smallRgbFrame.length];
                        System.arraycopy(smallRgbFrame, 0, frameSnapshot, 0, smallRgbFrame.length);
                        aiExecutor.execute(() -> {
                            // FIX (A8/B3): snapshot detector at lambda entry.
                            final YoloDetector detectorSnap = yoloDetector;
                            if (detectorSnap == null || !aiEnabled) {
                                logger.info("Lighting-transition baseline refresh skipped (detector closed)");
                                return;
                            }
                            // ACC-ON guard: lambda is dispatched onto the
                            // single-thread aiExecutor and may sit behind
                            // an in-flight detect() for up to ~300ms. If
                            // ACC has turned ON in that window, drop the
                            // refresh — the next ACC-OFF session's frame
                            // 30 baseline seed will re-cover this case.
                            if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                                logger.info("Lighting-transition baseline refresh skipped (surveillance inactive / ACC ON)");
                                return;
                            }
                            logger.info("Refreshing detection baseline (lighting transition)...");
                            int qW = THUMBNAIL_WIDTH / 2;
                            int qH = THUMBNAIL_HEIGHT / 2;
                            for (int qr = 0; qr < MotionPipelineV2.NUM_QUADRANTS; qr++) {
                                try {
                                    byte[] quadCrop = cropFromMosaic(frameSnapshot, qr, qW, qH);
                                    if (quadCrop != null) {
                                        java.util.List<com.overdrive.app.ai.Detection> dets =
                                                detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                                        detectionBaseline.refreshQuadrant(qr, dets, qW, qH);
                                    }
                                } catch (Exception e) {
                                    logger.warn("Baseline refresh failed for Q" + qr + ": " + e.getMessage());
                                }
                            }
                        });
                    }
                }
            }
            
            // Log suppressed quadrants for debug
            if (filterDebugEnabled) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].brightnessSuppressed) {
                        addFilterLogEntry(String.format("[%s] SUPPRESSED: %s (brightness shift, luma=%.0f)",
                                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                                MotionPipelineV2.QUADRANT_NAMES[q], results[q].meanLuma));
                    }
                }
            }
            
            // POST-SUPPRESSION BASELINE REFRESH: Track per-quadrant suppression state.
            // When suppression ends and the scene stabilizes (15 frames later), run YOLO
            // once to update the baseline with what's currently visible. This prevents
            // the "4 objects in day, 3 visible at night" mismatch from causing false triggers.
            if (baselineSeeded && useObjectDetection && yoloDetector != null) {
                for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                    if (results[q].brightnessSuppressed) {
                        // Suppression is active — mark it and reset the stabilization counter
                        suppressionWasActive[q] = true;
                        framesSinceSuppressionEnded[q] = 0;
                        baselineRefreshQueued[q] = false;
                    } else if (suppressionWasActive[q]) {
                        // Suppression just ended — start counting stabilization frames
                        framesSinceSuppressionEnded[q]++;
                        
                        if (framesSinceSuppressionEnded[q] >= BASELINE_STABILIZATION_FRAMES) {
                            // Scene has stabilized — always clear the flag to prevent
                            // permanently extending the AI gate timeout on future sequences.
                            suppressionWasActive[q] = false;
                            
                            if (!baselineRefreshQueued[q] && !recording) {
                                // Queue a baseline refresh for this quadrant.
                                // Don't refresh during active recording (the person is still there,
                                // we don't want to accidentally add them to baseline).
                                baselineRefreshQueued[q] = true;
                            
                                final int qToRefresh = q;
                                final byte[] frameSnapshot = new byte[smallRgbFrame.length];
                                System.arraycopy(smallRgbFrame, 0, frameSnapshot, 0, smallRgbFrame.length);
                            
                                aiExecutor.execute(() -> {
                                    // FIX (A8/B3): snapshot detector at lambda entry.
                                    final YoloDetector detectorSnap = yoloDetector;
                                    if (detectorSnap == null || !aiEnabled) {
                                        logger.debug("Post-suppression baseline refresh skipped (detector closed)");
                                        return;
                                    }
                                    // ACC-ON guard — see baseline-seed lambda for
                                    // rationale. Same dispatch-lag race; skip if
                                    // surveillance was torn down before the
                                    // executor reached this task.
                                    if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                                        logger.debug("Post-suppression baseline refresh skipped (surveillance inactive / ACC ON)");
                                        return;
                                    }
                                    try {
                                        int qW = THUMBNAIL_WIDTH / 2;
                                        int qH = THUMBNAIL_HEIGHT / 2;
                                        byte[] quadCrop = cropFromMosaic(frameSnapshot, qToRefresh, qW, qH);
                                        if (quadCrop != null) {
                                            java.util.List<com.overdrive.app.ai.Detection> dets =
                                                    detectorSnap.detect(quadCrop, qW, qH, aiConfidence, true, true, false, true, minObjectSize);
                                            detectionBaseline.refreshQuadrant(qToRefresh, dets, qW, qH);
                                            logger.debug("Post-suppression baseline refresh Q" + qToRefresh +
                                                    " [" + MotionPipelineV2.QUADRANT_NAMES[qToRefresh] + "]: " +
                                                    (dets != null ? dets.size() : 0) + " detections");
                                        }
                                    } catch (Exception e) {
                                        logger.warn("Post-suppression baseline refresh failed Q" + qToRefresh + ": " + e.getMessage());
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Run YOLO on a single quadrant (cropped from the mosaic).
     */
    private void runAiOnQuadrant(byte[] mosaicRgb, int quadrant) {
        // FIX (Bug B): respect user's class toggles. Empty classFilter = sentinel for
        // "all classes disabled" — skip YOLO entirely. Saves ~50-80ms per quadrant per
        // wake event and frees the TFLite interpreter.
        if (!useObjectDetection) return;
        if (!aiEnabled || (classFilter != null && classFilter.length == 0)) return;
        if (yoloDetector == null) return;
        if (isAiRunning.get()) return;
        
        long now = System.currentTimeMillis();
        if ((now - lastAiTimeMs) < AI_COOLDOWN_MS) return;
        lastAiTimeMs = now;
        
        // Determine crop dimensions and data source.
        // If foveated cropper is available, extract a 640×640 window from the raw
        // 5120×960 strip centered on the motion centroid. This gives YOLO ~4× more
        // pixels per object compared to the 320×240 mosaic quadrant.
        final int qW;
        final int qH;
        final byte[] cropData;
        
        MotionPipelineV2.QuadrantResult motionResult = pipelineV2 != null ? pipelineV2.getResults()[quadrant] : null;
        
        // For heartbeat runs, the person may be stationary (zero motion blocks).
        // Use the tracker's last known position as the centroid for foveated crop
        // instead of requiring active motion blocks from the V2 pipeline.
        boolean heartbeatHasTrackerPos = false;
        float trackerCentroidX = 0, trackerCentroidY = 0;
        try {
            float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
            if (trackBox != null && trackBox[6] > 0) {  // trackBox[6] = active flag
                // Convert tracker bbox (pixel coords) to block coords for foveated crop
                trackerCentroidX = (trackBox[0] + trackBox[2] / 2.0f) / 32.0f;
                trackerCentroidY = (trackBox[1] + trackBox[3] / 2.0f) / 32.0f;
                heartbeatHasTrackerPos = true;
            }
        } catch (Exception ignored) {}
        
        // Detect heartbeat runs early so we can skip the foveated GL hop
        // entirely on this code path. Heartbeats fire when the NCC tracker
        // wants to refresh its template — which only happens once the
        // tracked person has been stationary long enough for the score to
        // drift, so by definition the subject is NOT moving and a 320×240
        // mosaic crop carries enough detail. Doing the foveated 640×640
        // dance for it is pure cost: it forces a GL-thread hop while the
        // Adreno 610 is already saturated by the YOLO inference itself,
        // which produces the 296ms `acq` stall (and the 1500ms "Foveated
        // crop GL hop timed out" warning) we see in field logs during
        // active recording.
        boolean heartbeatRunEarly = false;
        try {
            heartbeatRunEarly = NativeMotion.trackerNeedsYoloHeartbeat(quadrant)
                    && NativeMotion.trackerHasActiveTrack(quadrant);
        } catch (Exception ignored) {}

        if (!heartbeatRunEarly
                && foveatedCropper != null && foveatedCropper.isInitialized() && cameraTextureId >= 0
                && ((motionResult != null && motionResult.componentSize > 0) || heartbeatHasTrackerPos)) {
            // Foveated path (Option B mailbox).
            //
            // We never block the GL thread or post a Runnable to its handler
            // queue. Instead:
            //   1. Mark this quadrant as wanting a foveated crop on the next
            //      render frame (requestFoveatedCrop, non-blocking).
            //   2. Read the most recent crop from the slot. If present and
            //      fresh (< 500ms old), use it. If absent or stale, fall
            //      back to mosaic for THIS tick — the next tick's slot will
            //      have been filled by the render loop in the interim.
            //
            // First-tick latency: the very first foveated tick after motion
            // starts will see an empty slot and fall back to mosaic. By the
            // second tick (~100ms later via V2's MOTION_PROCESS_INTERVAL_MS)
            // the slot is populated. Steady-state behavior is foveated;
            // start-of-event behavior is mosaic-then-foveated. Acceptable.
            float centroidX = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidX : trackerCentroidX;
            float centroidY = (motionResult != null && motionResult.componentSize > 0)
                    ? motionResult.centroidY : trackerCentroidY;
            requestFoveatedCrop(quadrant, centroidX, centroidY);

            byte[] foveatedRgb = pollFoveatedSlot(quadrant);
            if (foveatedRgb != null) {
                qW = FoveatedCropper.CROP_SIZE;
                qH = FoveatedCropper.CROP_SIZE;
                // Slot deep-copies on publish; the bytes we got here are
                // ours alone (no further copy needed for thread safety).
                cropData = foveatedRgb;
            } else {
                // Slot empty or stale — fall back to mosaic for this tick.
                qW = THUMBNAIL_WIDTH / 2;
                qH = THUMBNAIL_HEIGHT / 2;
                byte[] mosaicShared = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
                cropData = new byte[mosaicShared.length];
                System.arraycopy(mosaicShared, 0, cropData, 0, mosaicShared.length);
            }
        } else {
            // Legacy path: 320×240 from mosaic. See note above — must copy.
            qW = THUMBNAIL_WIDTH / 2;
            qH = THUMBNAIL_HEIGHT / 2;
            byte[] mosaicShared = cropFromMosaic(mosaicRgb, quadrant, qW, qH);
            cropData = new byte[mosaicShared.length];
            System.arraycopy(mosaicShared, 0, cropData, 0, mosaicShared.length);
        }
        
        if (cropData == null) return;
        
        isAiRunning.set(true);
        final int qIdx = quadrant;
        
        // FIX: Snapshot block confidences on the main thread BEFORE dispatching to aiExecutor.
        // The live pipelineV2.getResults() array is mutated by the JNI backend on every frame.
        // By the time the aiExecutor thread runs (150-300ms later), the main loop has processed
        // 2-3 new frames. If the person briefly stopped, confirmedBlocks will be 0 on the new
        // frame, and a valid YOLO detection gets thrown away because it doesn't overlap with
        // the "current" empty motion mask. Deep-copy the confidence array now.
        final float[] blockConfSnapshot = new float[MotionPipelineV2.TOTAL_BLOCKS];
        final int snapshotConfirmedBlocks;
        if (pipelineV2 != null) {
            MotionPipelineV2.QuadrantResult snapResult = pipelineV2.getResults()[qIdx];
            System.arraycopy(snapResult.blockConfidence, 0, blockConfSnapshot, 0, MotionPipelineV2.TOTAL_BLOCKS);
            snapshotConfirmedBlocks = snapResult.confirmedBlocks;
        } else {
            snapshotConfirmedBlocks = 0;
        }
        
        final boolean usedFoveated = (qW == FoveatedCropper.CROP_SIZE);
        
        // Capture whether this YOLO run is a heartbeat verification BEFORE the lambda.
        // Reuses the early decision computed above the foveated branch — the C++
        // tracker's needsYoloVerification flag is mutated by trackerUpdate() on
        // every frame, so reading it again here would race with the live state
        // and could disagree with the foveated-skip decision.
        final boolean isHeartbeatRun = heartbeatRunEarly;

        // FIX (B1/H-a): capture the recording generation NOW. The lambda below
        // will check it on completion and skip cross-recording writes if the
        // generation has advanced (i.e. stopRecording fired in the meantime).
        final long generationAtSchedule = recordingGeneration.get();
        
        // Capture mosaic quadrant crop for the texture tracker (always 320×240).
        // The tracker needs the mosaic-scale image regardless of whether YOLO used foveated.
        final byte[] mosaicQuadCrop;
        {
            int mqW = THUMBNAIL_WIDTH / 2;
            int mqH = THUMBNAIL_HEIGHT / 2;
            byte[] tmp = cropFromMosaic(mosaicRgb, quadrant, mqW, mqH);
            if (tmp != null) {
                mosaicQuadCrop = new byte[tmp.length];
                System.arraycopy(tmp, 0, mosaicQuadCrop, 0, tmp.length);
            } else {
                mosaicQuadCrop = null;
            }
        }
        
        aiExecutor.execute(() -> {
            try {
                // FIX (A8/B3): Snapshot the detector reference at lambda entry.
                // The line-1450 guard runs on the calling thread BEFORE this
                // lambda is scheduled. Between scheduling and execution, the UI
                // thread can call setObjectFilters() which closes the
                // interpreter and nulls yoloDetector. Without this snapshot,
                // the .detect() call below would NPE on a null field — or
                // worse, race against close() and crash the native interpreter.
                final YoloDetector detectorSnap = yoloDetector;
                if (detectorSnap == null || !aiEnabled) {
                    isAiRunning.set(false);
                    return;
                }
                // ACC-ON guard. processFrameV2's caller (processFrame) ran the
                // active+isAccOn() gate, but the aiExecutor lambda can be
                // scheduled while ACC was still OFF and pulled off the queue
                // up to ~250-300ms later (one in-flight detect() ahead of us).
                // If ACC turned ON in that window — typical for the
                // ACC-OFF→ON transition with a motion event in flight — we
                // must NOT run inference: it (a) burns CPU during a window
                // where surveillance is logically disabled, (b) writes
                // lastYoloPublication / actor state for a session that's
                // about to be torn down, polluting the next session's first
                // frames. Drop the detect() and the downstream writes.
                if (!active || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                    isAiRunning.set(false);
                    return;
                }

                boolean detectPerson = true, detectCar = true, detectBike = true;
                if (classFilter != null && classFilter.length > 0) {
                    detectPerson = false; detectCar = false; detectBike = false;
                    for (int cls : classFilter) {
                        if (cls == 0) detectPerson = true;
                        if (cls == 2 || cls == 5 || cls == 7) detectCar = true;
                        if (cls == 1 || cls == 3) detectBike = true;
                    }
                }
                
                java.util.List<com.overdrive.app.ai.Detection> detections = detectorSnap.detect(
                        cropData, qW, qH, aiConfidence, detectPerson, detectCar, false, detectBike, minObjectSize);
                
                // Track how many motion-filtered detections we found (accessible outside the block
                // for the teardown gate that kills zombie tracks when YOLO returns empty)
                int motionFilteredCount = 0;
                
                if (detections != null && !detections.isEmpty()) {
                    // Filter detections: only keep objects that overlap with active motion blocks.
                    // A static parked car detected by YOLO should be ignored if no motion blocks
                    // overlap with it. Only the moving person (whose bounding box overlaps with
                    // active motion blocks) should be reported to the timeline.
                    // FIX: Use the snapshot taken on the main thread, NOT the live pipeline results.
                    // The live results have been mutated by 2-3 frames by now.
                    
                    java.util.List<com.overdrive.app.ai.Detection> motionFiltered = new java.util.ArrayList<>();
                    for (com.overdrive.app.ai.Detection det : detections) {
                        int classId = det.getClassId();
                        
                        // Respect user's class filter settings.
                        // Only keep detections for classes the user has enabled.
                        if (classFilter != null && classFilter.length > 0) {
                            boolean classAllowed = false;
                            for (int allowedCls : classFilter) {
                                if (classId == allowedCls) {
                                    classAllowed = true;
                                    break;
                                }
                            }
                            if (!classAllowed) continue;
                        } else {
                            // No filter set — only allow known relevant classes
                            if (classId != 0 && classId != 1 && classId != 2 && 
                                classId != 3 && classId != 5 && classId != 7) continue;
                        }
                        
                        // Check if detection overlaps with any confirmed motion blocks
                        // using the SNAPSHOT taken on the main thread.
                        // When using foveated crop (640×640), detection coords are in a different
                        // coordinate space than the block grid (320×240 with 32px blocks).
                        // Scale detection coords to match the block grid.
                        //
                        // FIX: During a heartbeat run, BYPASS the spatial filter entirely.
                        // The whole point of the heartbeat is to verify a STATIONARY person
                        // who has zero motion blocks. If we require motion-block overlap,
                        // the heartbeat will always fail for stationary objects, the teardown
                        // gate will kill the track, and the recording will stop even though
                        // the person is still standing right there.
                        // Spatial filter: check if detection overlaps with active motion blocks.
                        // Heartbeat bypass: persons (class 0) skip the spatial check because
                        // a stationary person has zero motion blocks but is a real threat.
                        // Vehicles during heartbeat still require motion blocks — a parked car
                        // is never a threat and will hold the recording open forever otherwise.
                        boolean passesFilter = false;
                        
                        if (isHeartbeatRun && classId == 0) {
                            // Heartbeat + person: bypass spatial filter
                            passesFilter = true;
                        } else if (snapshotConfirmedBlocks > 0) {
                            // Normal path: require overlap with active motion blocks
                            float scaleX = usedFoveated ? (320.0f / FoveatedCropper.CROP_SIZE) : 1.0f;
                            float scaleY = usedFoveated ? (240.0f / FoveatedCropper.CROP_SIZE) : 1.0f;
                            int detLeft = (int)(det.getX() * scaleX);
                            int detTop = (int)(det.getY() * scaleY);
                            int detRight = (int)((det.getX() + det.getW()) * scaleX);
                            int detBottom = (int)((det.getY() + det.getH()) * scaleY);
                            
                            for (int bi = 0; bi < MotionPipelineV2.TOTAL_BLOCKS; bi++) {
                                if (blockConfSnapshot[bi] < 0.5f) continue;
                                
                                int bx = (bi % MotionPipelineV2.GRID_COLS) * 32;
                                int by = (bi / MotionPipelineV2.GRID_COLS) * 32;
                                int bRight = bx + 32;
                                int bBottom = by + 32;
                                
                                if (detLeft < bRight && detRight > bx && detTop < bBottom && detBottom > by) {
                                    passesFilter = true;
                                    break;
                                }
                            }
                        } else {
                            // No motion data available — keep all detections (fallback)
                            passesFilter = true;
                        }
                        
                        if (passesFilter) {
                            motionFiltered.add(det);
                        }
                    }
                    
                    int relevantCount = motionFiltered.size();
                    motionFilteredCount = relevantCount;
                    
                    if (relevantCount > 0) {
                        // SOTA: Record person detections for spatial veto baseline tracking.
                        // This must happen BEFORE baseline filtering so the person positions
                        // are available for the spatial veto check during event-end update.
                        int qWNorm = usedFoveated ? FoveatedCropper.CROP_SIZE : (THUMBNAIL_WIDTH / 2);
                        int qHNorm = usedFoveated ? FoveatedCropper.CROP_SIZE : (THUMBNAIL_HEIGHT / 2);
                        for (com.overdrive.app.ai.Detection det : motionFiltered) {
                            if (det.getClassId() == 0) {  // person
                                detectionBaseline.recordPersonDetection(qIdx, det, qWNorm, qHNorm);
                            }
                        }
                        
                        // SOTA: Filter detections against baseline — suppress known static objects.
                        // Only NEW or MOVED objects pass through. This eliminates false recordings
                        // from shadows/headlights that trigger motion near parked cars or trash cans.
                        // Skip baseline filtering for person detections (class 0) — a person is
                        // never a legitimate static background object for a sentry system.
                        java.util.List<com.overdrive.app.ai.Detection> baselineFiltered = new java.util.ArrayList<>();
                        int baselineSuppressed = 0;
                        for (com.overdrive.app.ai.Detection det : motionFiltered) {
                            if (det.getClassId() == 0) {
                                // Person — always pass through, never check baseline
                                baselineFiltered.add(det);
                            } else if (detectionBaseline.isInBaseline(det, qIdx, qWNorm, qHNorm)) {
                                // Known static object — suppress
                                baselineSuppressed++;
                            } else {
                                // New or moved non-person object — pass through
                                baselineFiltered.add(det);
                            }
                        }
                        
                        if (baselineSuppressed > 0) {
                            logger.info("Baseline filter Q" + qIdx + ": " + baselineSuppressed + 
                                    " static objects suppressed, " + baselineFiltered.size() + " new/moved pass");
                        }
                        
                        // Store last detections + coord-space frame height for the
                        // event-end baseline update AND DistanceEstimator's bbox-height
                        // inference. Single atomic publication so the reader can't
                        // see new detections paired with stale frame height (or
                        // vice-versa). qH=240 for mosaic, qH=640 for foveated.
                        lastYoloPublication.set(qIdx, new YoloPublication(
                                new java.util.ArrayList<>(motionFiltered), qH));
                        lastEventQuadrant = qIdx;
                        
                        // THREAT-LEVEL DECISION MATRIX (AI background subtraction gate):
                        //
                        // THREAT_LOW:    Require YOLO to find a non-baseline object. If all
                        //                detections are known static objects → suppress entirely.
                        //
                        // THREAT_MEDIUM: Require YOLO to find a non-baseline object. This prevents
                        //                lighting artifacts (streetlights, porch lights, slow
                        //                headlight sweeps below brightness threshold) from triggering
                        //                recordings. These create persistent edge differences that
                        //                Stage 5 classifies as "approaching" because the centroid
                        //                drifts slightly as shadows shift.
                        //
                        // THREAT_HIGH:   Auto-record ONLY when the loiter is TRUSTED — i.e. the
                        //                motion is coherently translating (native flow coherence)
                        //                or an in-zone person tracker holds it. An UNTRUSTED HIGH
                        //                (a waving flag / sweeping shadow whose stationary centroid
                        //                merely looks like loitering) is gated exactly like MEDIUM:
                        //                it must yield a non-baseline YOLO object, otherwise suppress.
                        //                A flag never produces a person/car box, so it stops here.
                        //
                        // Safety: The motion pipeline already queues YOLO at the START of motion
                        // (early AI init, line ~752). By the time MEDIUM's 3-second sustained
                        // timer expires, YOLO has had 2.7+ seconds to run. If baselineFiltered
                        // is empty, it means YOLO ran and found nothing real — the "motion" is
                        // a lighting artifact. cachedHighIsTrusted is latched on the main thread
                        // at motion-start/per-frame; read here on the aiExecutor thread (volatile).
                        int currentThreat = pipelineV2 != null ? pipelineV2.getMaxThreatLevel() : MotionPipelineV2.THREAT_MEDIUM;
                        boolean untrustedHigh = (currentThreat >= MotionPipelineV2.THREAT_HIGH) && !cachedHighIsTrusted;
                        if ((currentThreat <= MotionPipelineV2.THREAT_MEDIUM || untrustedHigh) && baselineFiltered.isEmpty()) {
                            // LOW/MEDIUM, or an UNTRUSTED HIGH (flag/shadow), + all detections are
                            // known static objects (or none) → suppress.
                            String[] tNames = {"NONE", "LOW", "MEDIUM", "HIGH"};
                            logger.info("AI gate: " + tNames[currentThreat]
                                    + (untrustedHigh ? "(untrusted loiter)" : "")
                                    + " + no new objects → suppressing for Q" + qIdx);
                            relevantCount = 0;
                            motionFilteredCount = 0;
                        } else {
                            // Use baseline-filtered detections for downstream processing
                            motionFiltered = baselineFiltered;
                            relevantCount = motionFiltered.size();
                            motionFilteredCount = relevantCount;
                        }
                    }
                    
                    if (relevantCount > 0) {
                        // YOLO confirmed a real object — update AI confirmation timestamp.
                        // This is used by the deterrent flash guard to allow recording
                        // even during the suppression window if YOLO sees a real threat.
                        lastAiConfirmationTimeMs = System.currentTimeMillis();
                        
                        long timeSinceMotion = System.currentTimeMillis() - lastMotionTime;
                        if (timeSinceMotion < 2000) {
                            lastMotionTime = System.currentTimeMillis();
                        }
                        
                        boolean hasActiveMotion = timeSinceMotion < 2000;
                        // Always send to timeline — pre-trigger ring buffer captures
                        // events before recording starts for the JSON sidecar.
                        timelineCollector.onAiDetection(motionFiltered, hasActiveMotion, 1 << qIdx);
                        
                        // Cross-quadrant tracking REQUIRES bboxes in 320×240 quadrant
                        // pixel space — its centroid threshold (dist < 120) and edge
                        // margin (48 px) are hardcoded against Q_WIDTH=320 / Q_HEIGHT=240
                        // (CrossQuadrantTracker.java:59-60). When foveated, rescale a
                        // separate copy for CQT only; the tracker needs centroids, not
                        // bboxes, and rescaling distorts that minimally.
                        java.util.List<com.overdrive.app.ai.Detection> cqtDetections;
                        if (usedFoveated) {
                            cqtDetections = new java.util.ArrayList<>(motionFiltered.size());
                            float scaleToQuad = 320.0f / FoveatedCropper.CROP_SIZE;  // 0.5
                            for (com.overdrive.app.ai.Detection det : motionFiltered) {
                                cqtDetections.add(new com.overdrive.app.ai.Detection(
                                        det.getClassId(),
                                        det.getConfidence(),
                                        (int)(det.getX() * scaleToQuad),
                                        (int)(det.getY() * scaleToQuad),
                                        (int)(det.getW() * scaleToQuad),
                                        (int)(det.getH() * scaleToQuad)
                                ));
                            }
                        } else {
                            cqtDetections = motionFiltered;
                        }

                        java.util.List<CrossQuadrantTracker.TrackResult> tracked =
                                crossQuadrantTracker.processDetections(cqtDetections, qIdx);

                        // ActorTracker + ThumbnailBuffer want bboxes in cropData's
                        // NATIVE coord space so the bbox-vs-rgb pair stays coherent
                        // when ThumbnailBuffer draws the box on the hero JPEG. In
                        // foveated mode that's 640×640 (motionFiltered's native space);
                        // in mosaic mode, 320×240 (also motionFiltered's native space).
                        // Either way, motionFiltered is what we want — NOT cqtDetections,
                        // which is forced to 320 for CQT's hardcoded thresholds.
                        java.util.List<com.overdrive.app.ai.Detection> trackableDetections = motionFiltered;

                        // Build a parallel array of cross-quadrant track IDs to
                        // hand to ActorTracker. This binds the per-quadrant
                        // ActorTracker to the cross-camera identity assigned by
                        // CrossQuadrantTracker, so a person walking front→right
                        // doesn't get two actorIds. The arrays line up by index
                        // because processDetections returns one TrackResult per
                        // input Detection in order — and motionFiltered + cqtDetections
                        // share the same iteration order so indices stay aligned.
                        int[] xqTrackIds = new int[trackableDetections.size()];
                        for (int ti = 0; ti < tracked.size() && ti < xqTrackIds.length; ti++) {
                            xqTrackIds[ti] = tracked.get(ti).trackId;
                        }

                        // Actor layer: convert YOLO detections (in cropData's native
                        // coord space) into persistent Actor records. Snapshot is
                        // published as lastActors for downstream consumers (timeline,
                        // thumbnails, notifications, UI). Recording-relative timestamps
                        // require the recording start time which we look up from the
                        // recorder.
                        try {
                            // FIX (B1/H-a): if stopRecording bumped the generation
                            // while we were running, our writes belong to a
                            // recording that's already finalised. Skip them so
                            // we don't pollute the *next* recording's state.
                            if (recordingGeneration.get() != generationAtSchedule) {
                                logger.debug("AI lambda completed after recording stop (gen "
                                        + generationAtSchedule + " vs " + recordingGeneration.get()
                                        + ") — skipping Actor/Thumbnail writes");
                            } else {
                            long recordingStartWall = (timelineCollector != null && timelineCollector.isCollecting())
                                    ? timelineCollector.getRecordingStartTimeMs() : 0L;
                            // Pass the ACTUAL crop dims (qW × qH) used for this YOLO
                            // run. trackableDetections (motionFiltered) is in qW × qH
                            // pixel space, so the proximity ratio (bboxH / quadH) is
                            // self-consistent. ThumbnailBuffer also receives qW × qH
                            // and the same bbox space — bbox draws on the right pixels.
                            java.util.List<Actor> actorSnapshot = actorTracker.update(
                                    trackableDetections,
                                    xqTrackIds,
                                    qIdx,
                                    qW,
                                    qH,
                                    recordingStartWall,
                                    System.currentTimeMillis());
                            lastActors = actorSnapshot;
                            // Latch the event-level peak severity so the two
                            // Telegram stages gate on the worst the event ever
                            // reached, not whatever happens to be in lastActors
                            // at stop time (which TTL-prunes departed actors).
                            updateEventPeakSeverity(actorSnapshot);
                            // Forward to thumbnail buffer so it can capture the peak-severity frame.
                            // Block C wires this; safe no-op if buffer not yet attached.
                            if (thumbnailBuffer != null && cropData != null) {
                                thumbnailBuffer.observe(actorSnapshot, cropData, qW, qH, qIdx);
                            }
                            // Mid-event baseline promotion: if the Actor layer
                            // has classified a vehicle / non-person actor as
                            // static, promote it into the baseline now so the
                            // *next* motion event suppresses it without waiting
                            // for stopRecording → updateFromEventEnd. Closes
                            // the loop with the Actor tracker's "static" flag.
                            for (Actor a : actorSnapshot) {
                                if (!a.isStatic) continue;
                                if (a.classGroup == Actor.ClassGroup.PERSON
                                        || a.classGroup == Actor.ClassGroup.ANIMAL
                                        || a.classGroup == Actor.ClassGroup.UNKNOWN) continue;
                                int cocoCls;
                                switch (a.classGroup) {
                                    case VEHICLE: cocoCls = 2; break;  // car
                                    case BIKE:    cocoCls = 1; break;  // bicycle
                                    default: continue;
                                }
                                // Baseline promotion only on mosaic frames.
                                // The DetectionBaseline normalizes coords to
                                // [0,1] of the QUADRANT for stable cross-event
                                // comparison. Foveated crops are a moving 640×640
                                // window centered on motion centroid, so their
                                // normalized coords don't refer to a stable
                                // physical region — promoting a foveated bbox
                                // would seed an entry that nothing in the next
                                // event can possibly match. Mosaic frames are
                                // the full quadrant downscaled, which is the
                                // stable reference baseline expects.
                                if (usedFoveated) continue;
                                detectionBaseline.promoteStaticActor(qIdx, cocoCls,
                                        a.lastBboxX, a.lastBboxY, a.lastBboxW, a.lastBboxH,
                                        qW, qH);
                            }
                            }  // close else { generation guard
                        } catch (Exception aEx) {
                            logger.warn("ActorTracker.update failed: " + aEx.getMessage());
                        }
                        
                        // SOTA: Start/refresh texture tracker on the highest-confidence detection.
                        // YOLO's job is done — the NCC tracker takes over frame-by-frame
                        // tracking. YOLO only wakes up again on heartbeat or NCC score drop.
                        if (!trackableDetections.isEmpty() && mosaicQuadCrop != null) {
                            com.overdrive.app.ai.Detection best = trackableDetections.get(0);
                            for (com.overdrive.app.ai.Detection d : trackableDetections) {
                                if (d.getConfidence() > best.getConfidence()) best = d;
                            }
                            try {
                                // FIX: Use the pre-captured isHeartbeatRun flag, NOT a live
                                // call to trackerNeedsYoloHeartbeat(). The live flag is mutated
                                // by trackerUpdate() on the main thread between when we queued
                                // this quadrant and when the lambda executes (100-200ms later).
                                if (isHeartbeatRun) {
                                    // SEMANTIC LOCK: The track was born with a specific classId.
                                    // If YOLO now sees a different class, the tracker has morphed
                                    // onto a background object (e.g., person → parked car).
                                    // Reject the heartbeat and let the track die.
                                    float[] trackBox = NativeMotion.trackerGetTrackBox(qIdx);
                                    int trackClassId = (trackBox != null) ? (int) trackBox[5] : -1;
                                    
                                    if (trackClassId >= 0 && best.getClassId() != trackClassId
                                            && best.getConfidence() > 0.70f) {
                                        // Semantic mismatch with high confidence — tracker morphed
                                        // onto a different object (e.g., person → parked car).
                                        // Require >70% confidence to avoid killing tracks on
                                        // low-confidence misclassifications (person torso → bus).
                                        logger.info("Semantic mismatch: track Q" + qIdx + 
                                                " born as class " + trackClassId + 
                                                " but YOLO sees class " + best.getClassId() + 
                                                " @" + String.format("%.0f%%", best.getConfidence() * 100) +
                                                " — killing track");
                                        NativeMotion.trackerDropTrack(qIdx);
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                    } else {
                                        // Class matches — refresh the template
                                        NativeMotion.trackerRefreshTemplate(
                                                mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                                qIdx,
                                                best.getX(), best.getY(), best.getW(), best.getH(),
                                                System.currentTimeMillis());
                                        NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                                        logger.info("Tracker heartbeat confirmed: refreshed template for Q" + qIdx +
                                                " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                                    }
                                } else {
                                    // First detection: start a new track
                                    NativeMotion.trackerStartTrack(
                                            mosaicQuadCrop, THUMBNAIL_WIDTH / 2, THUMBNAIL_HEIGHT / 2,
                                            qIdx, best.getClassId(),
                                            best.getX(), best.getY(), best.getW(), best.getH(),
                                            System.currentTimeMillis());
                                }
                            } catch (Exception e) {
                                logger.warn("Tracker start/refresh failed: " + e.getMessage());
                            }
                        } else if (trackableDetections.isEmpty() && isHeartbeatRun) {
                            // (Moved to teardown gate outside the detections block)
                        }
                        
                        String qName = MotionPipelineV2.QUADRANT_NAMES[qIdx];
                        String cropMode = usedFoveated ? "foveated 640×640" : "mosaic 320×240";
                        logger.info(String.format("V2 AI [%s] (%s): %d objects (motion-filtered from %d), %d tracks",
                                qName, cropMode, relevantCount, detections.size(),
                                crossQuadrantTracker.getActiveTrackCount()));
                    }
                }
                
                // TEARDOWN GATE: When YOLO returns 0 objects during a heartbeat,
                // the object has left the scene. Kill the zombie track immediately.
                // Previously this was nested inside the `if (!detections.isEmpty())` block,
                // so it never executed when YOLO returned empty — the track stayed alive
                // forever, spamming heartbeats on every frame.
                if (isHeartbeatRun && (detections == null || detections.isEmpty()
                        || motionFilteredCount == 0)) {
                    try {
                        if (NativeMotion.trackerHasActiveTrack(qIdx)) {
                            NativeMotion.trackerDropTrack(qIdx);
                            NativeMotion.trackerConfirmHeartbeat(qIdx, System.currentTimeMillis());
                            logger.info("Tracker teardown: YOLO heartbeat found nothing, killed track Q" + qIdx +
                                    " [" + MotionPipelineV2.QUADRANT_NAMES[qIdx] + "]");
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                logger.error("V2 AI detection error (Q" + qIdx + ")", e);
            } finally {
                isAiRunning.set(false);
            }
        });
    }
    
    /**
     * Crop a quadrant from the 640×480 mosaic into the reusable aiBuffer.
     * Legacy path used when foveated cropper is not available.
     */
    private byte[] cropFromMosaic(byte[] mosaicRgb, int quadrant, int qW, int qH) {
        int startX = (quadrant % 2) * qW;
        int startY = (quadrant / 2) * qH;

        int cropSize = qW * qH * BYTES_PER_PIXEL;
        byte[] aiBuffer = aiBufferTL.get();
        if (aiBuffer == null || aiBuffer.length != cropSize) {
            aiBuffer = new byte[cropSize];
            aiBufferTL.set(aiBuffer);
        }

        for (int y = 0; y < qH; y++) {
            int srcOffset = ((startY + y) * THUMBNAIL_WIDTH + startX) * BYTES_PER_PIXEL;
            int dstOffset = y * qW * BYTES_PER_PIXEL;
            System.arraycopy(mosaicRgb, srcOffset, aiBuffer, dstOffset, qW * BYTES_PER_PIXEL);
        }
        return aiBuffer;
    }
    
    /**
     * Sets the Region of Interest (ROI) mask for motion detection.
     * 
     * @param mask Byte array (320×240) where 1 = check motion, 0 = ignore
     *             Pass null to use entire frame (default)
     */
    public void setRoiMask(byte[] mask) {
        if (mask != null && mask.length != THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) {
            logger.error("Invalid ROI mask size: " + mask.length + 
                       " (expected " + (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT) + ")");
            return;
        }
        
        this.roiMask = mask;
        
        // Count pixels in ROI for normalization
        if (mask != null) {
            roiPixelCount = 0;
            for (byte b : mask) {
                if (b != 0) roiPixelCount++;
            }
            logger.info("ROI mask set: " + roiPixelCount + " pixels (" + 
                      (roiPixelCount * 100 / (THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT)) + "%)");
        } else {
            roiPixelCount = THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT;
            logger.info("ROI mask cleared (using full frame)");
        }
    }
    
    /**
     * Sets ROI from polygon points (normalized 0.0-1.0 coordinates).
     * 
     * @param points Array of [x, y] pairs defining polygon vertices
     */
    public void setRoiFromPolygon(float[][] points) {
        if (points == null || points.length < 3) {
            setRoiMask(null);  // Clear ROI
            return;
        }
        
        // Create mask by rasterizing polygon
        byte[] mask = new byte[THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT];
        
        for (int y = 0; y < THUMBNAIL_HEIGHT; y++) {
            for (int x = 0; x < THUMBNAIL_WIDTH; x++) {
                float nx = (float) x / THUMBNAIL_WIDTH;
                float ny = (float) y / THUMBNAIL_HEIGHT;
                
                // Point-in-polygon test (ray casting algorithm)
                if (isPointInPolygon(nx, ny, points)) {
                    mask[y * THUMBNAIL_WIDTH + x] = 1;
                }
            }
        }
        
        setRoiMask(mask);
    }
    
    /**
     * Point-in-polygon test using ray casting.
     */
    private boolean isPointInPolygon(float x, float y, float[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = polygon[i][0], yi = polygon[i][1];
            float xj = polygon[j][0], yj = polygon[j][1];
            
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        
        return inside;
    }
    
    // ========================================================================
    // Per-Quadrant ROI (Region of Interest)
    // ========================================================================
    
    /**
     * Applies a polygon ROI to a specific quadrant.
     * Converts the polygon (normalized 0-1 coords) to a 10×7 block mask
     * and passes it to the C++ pipeline via JNI.
     *
     * @param quadrant Quadrant index (0-3)
     * @param polygon  Array of [x, y] vertex pairs in normalized coords (0.0-1.0)
     */
    public void applyQuadrantRoi(int quadrant, float[][] polygon) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        if (polygon == null || polygon.length < 3) {
            clearQuadrantRoi(quadrant);
            return;
        }
        
        // Convert polygon to 10×7 block mask.
        // For each block, check if its center is inside the polygon.
        byte[] blockMask = new byte[MotionPipelineV2.TOTAL_BLOCKS];
        int enabledCount = 0;
        
        for (int by = 0; by < MotionPipelineV2.GRID_ROWS; by++) {
            for (int bx = 0; bx < MotionPipelineV2.GRID_COLS; bx++) {
                int blockIdx = by * MotionPipelineV2.GRID_COLS + bx;
                // Block center in normalized coordinates
                float cx = (bx + 0.5f) / MotionPipelineV2.GRID_COLS;
                float cy = (by + 0.5f) / MotionPipelineV2.GRID_ROWS;
                
                if (isPointInPolygon(cx, cy, polygon)) {
                    blockMask[blockIdx] = 1;
                    enabledCount++;
                }
            }
        }
        
        try {
            NativeMotion.setQuadrantRoi(quadrant, blockMask);
            logger.info("ROI applied to Q" + quadrant + " [" + 
                    MotionPipelineV2.QUADRANT_NAMES[quadrant] + "]: " + 
                    enabledCount + "/" + MotionPipelineV2.TOTAL_BLOCKS + " blocks enabled");
        } catch (Exception e) {
            logger.warn("Failed to apply ROI to Q" + quadrant + ": " + e.getMessage());
        }
    }
    
    /**
     * Clears the ROI for a specific quadrant (all blocks enabled).
     */
    public void clearQuadrantRoi(int quadrant) {
        if (quadrant < 0 || quadrant >= MotionPipelineV2.NUM_QUADRANTS) return;
        try {
            NativeMotion.setQuadrantRoi(quadrant, null);
            logger.info("ROI cleared for Q" + quadrant + " [" + 
                    MotionPipelineV2.QUADRANT_NAMES[quadrant] + "] (all blocks enabled)");
        } catch (Exception e) {
            logger.warn("Failed to clear ROI for Q" + quadrant + ": " + e.getMessage());
        }
    }
    
    /**
     * Sets the SOTA surveillance configuration.
     * 
     * @param config Configuration object with distance preset, flash mode, and camera calibration
     */
    public void setConfig(SurveillanceConfig config) {
        this.config = config;

        // Sync legacy fields for backward compatibility
        this.flashImmunity = config.getFlashImmunity();
        this.requiredActiveBlocks = config.getRequiredBlocks();
        this.minObjectSize = config.getMinObjectSize();
        this.aiConfidence = config.getAiConfidence();
        this.preRecordMs = config.getPreRecordSeconds() * 1000L;
        this.postRecordMs = config.getPostRecordSeconds() * 1000L;

        // FIX (Bug A): propagate the loaded pre-record duration to the encoder's
        // circular buffer. Without this the encoder retains its hardcoded 5s
        // allocation from init() until the user re-saves the setting, which makes
        // the persisted value look like it was reset.
        if (recorder != null && recorder.getEncoder() != null) {
            try {
                recorder.getEncoder().setPreRecordDuration(config.getPreRecordSeconds());
            } catch (Exception e) {
                logger.warn("Failed to propagate pre-record duration to encoder: " + e.getMessage());
            }
        }
        
        // Sync loitering time for Java-side sustained motion enforcement
        this.loiteringTimeMs = config.getLoiteringTimeSeconds() * 1000L;
        // Sync the approach fast-path bar (0 = disabled).
        this.approachTriggerMs = config.getApproachTriggerSeconds() * 1000L;
        
        // Update frame dimensions in config for distance estimation
        config.setResolution(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
        config.setIsMosaic(true);  // We use 2x2 mosaic layout
        
        // Apply object detection filters from saved config.
        // This rebuilds the classFilter array so YOLO respects detectPerson/detectCar/detectBike.
        setObjectFilters(config.getMinObjectSize(), config.getAiConfidence(),
                config.isDetectPerson(), config.isDetectCar(), config.isDetectBike());
        
        // Apply V2 pipeline settings from loaded config.
        // Order matters: environment preset sets all defaults, then sensitivity and
        // detection zone override their specific parameters, then loitering and cameras.
        if (pipelineV2Config != null && pipelineV2 != null) {
            pipelineV2Config.applyEnvironmentPreset(config.getEnvironmentPreset());

            // The native pipeline runs once with a single config. To honor
            // per-quadrant overrides we feed the *most-permissive* aggregate
            // (highest sensitivity, widest detection zone) to native, then
            // demote per-quadrant in Java via applyQuadrantOverrides().
            int aggSens = config.getSensitivityLevel();
            String aggZone = config.getDetectionZone();
            for (int q = 0; q < 4; q++) {
                aggSens = Math.max(aggSens, config.getEffectiveSensitivityLevel(q));
                if ("extended".equals(config.getEffectiveDetectionZone(q))) {
                    aggZone = "extended";
                } else if ("normal".equals(config.getEffectiveDetectionZone(q))
                        && "close".equals(aggZone)) {
                    aggZone = "normal";
                }
            }
            pipelineV2Config.applySensitivity(aggSens);
            pipelineV2Config.applyDetectionZone(aggZone);
            pipelineV2Config.loiteringFrames = config.getLoiteringTimeSeconds() * 10;
            // Apply saved shadow filter mode (after preset, so user override takes precedence)
            pipelineV2Config.shadowFilterMode = config.getShadowFilterMode();
            boolean[] cameras = config.getCameraEnabled();
            for (int i = 0; i < 4; i++) {
                pipelineV2Config.quadrantEnabled[i] = cameras[i];
            }
            pipelineV2.applyConfig(pipelineV2Config);
            logger.info(String.format("V2 pipeline config applied: env=%s, sens=%d (agg=%d), zone=%s (agg=%s), loiter=%ds, cameras=[%b,%b,%b,%b]",
                    config.getEnvironmentPreset(), config.getSensitivityLevel(), aggSens,
                    config.getDetectionZone(), aggZone,
                    config.getLoiteringTimeSeconds(), cameras[0], cameras[1], cameras[2], cameras[3]));
        }
        
        // Apply filter debug setting
        this.filterDebugEnabled = config.isFilterDebugLogEnabled();
        
        // Apply per-quadrant ROI from config (if surveillance is active, apply immediately)
        if (active) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (config.isRoiEnabled(q) && config.getRoiPolygon(q) != null) {
                    applyQuadrantRoi(q, config.getRoiPolygon(q));
                } else {
                    clearQuadrantRoi(q);
                }
            }
        }
        
        logger.info("Config applied: " + config.toString());
    }
    
    /**
     * Gets the current SOTA configuration.
     * 
     * @return Current configuration
     */
    public SurveillanceConfig getConfig() {
        return config;
    }
    
    /**
     * Gets the last estimated distance to motion.
     * 
     * @return Distance in meters, or 0 if no motion detected
     */
    public float getLastEstimatedDistance() {
        return lastEstimatedDistance;
    }

    /**
     * Snapshot of currently-active Actors. Lock-free read suitable for UI / API
     * threads. May be empty if no detections have been observed yet.
     */
    public java.util.List<Actor> getLastActors() {
        return lastActors;
    }
    
    /**
     * Re-evaluate each quadrant's result against its effective (possibly
     * overridden) sensitivity / zone gates. The native pipeline already ran
     * with the most-permissive aggregate config, so we only ever demote — we
     * never falsely promote, so this can't synthesize motion that the native
     * stage didn't see.
     *
     * Demotion clears motionDetected, threatLevel, confirmedBlocks, and
     * componentSize. activeBlocks and per-block confidences are preserved for
     * diagnostics.
     */
    private void applyQuadrantOverrides(MotionPipelineV2.QuadrantResult[] results) {
        if (config == null || results == null) return;

        // Fast path: nothing overridden → skip entirely.
        boolean anyOverride = false;
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            if (config.getQuadrantSensitivityOverride(q) != null
                    || config.getQuadrantDetectionZoneOverride(q) != null) {
                anyOverride = true;
                break;
            }
        }
        if (!anyOverride) return;

        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            MotionPipelineV2.QuadrantResult r = results[q];
            if (r == null || !r.motionDetected) continue;

            int effSens = config.getEffectiveSensitivityLevel(q);
            String effZone = config.getEffectiveDetectionZone(q);
            MotionPipelineV2.Config.GateThresholds gates =
                    MotionPipelineV2.Config.gatesForSensitivity(effSens);
            int maxRow = MotionPipelineV2.Config.maxDistanceRowForZone(effZone);

            // Recount confirmed blocks at this quadrant's stricter confidence threshold.
            int confirmedAtThreshold = 0;
            if (r.blockConfidence != null) {
                for (int i = 0; i < r.blockConfidence.length; i++) {
                    if (r.blockConfidence[i] >= gates.confidenceThreshold) confirmedAtThreshold++;
                }
            } else {
                confirmedAtThreshold = r.confirmedBlocks;
            }

            boolean failsAlarm = confirmedAtThreshold < gates.alarmBlockThreshold;
            boolean failsComponent = r.componentSize < gates.minComponentSize;
            boolean failsZone = (maxRow > 0) && (r.centroidY < maxRow);

            if (failsAlarm || failsComponent || failsZone) {
                r.motionDetected = false;
                r.threatLevel = MotionPipelineV2.THREAT_NONE;
                r.confirmedBlocks = confirmedAtThreshold;
                if (filterDebugEnabled) {
                    String reason = failsAlarm ? "alarm" : failsComponent ? "component" : "zone";
                    logger.debug(String.format(
                            "  [%s] OVERRIDE_DEMOTED reason=%s sens=%d zone=%s confirmed@%.2f=%d/%d component=%d/%d centroidRow=%.1f/cutoff=%d",
                            MotionPipelineV2.QUADRANT_NAMES[q], reason, effSens, effZone,
                            gates.confidenceThreshold, confirmedAtThreshold, gates.alarmBlockThreshold,
                            r.componentSize, gates.minComponentSize, r.centroidY, maxRow));
                }
            } else {
                // Pass: update confirmedBlocks to reflect the stricter count
                // so downstream consumers see consistent numbers.
                r.confirmedBlocks = confirmedAtThreshold;
            }
        }
    }

    /**
     * Estimate real-world distance from a centroid Y position in block coordinates.
     * 
     * The centroid Y is in block coordinates (0 = top row / far, GRID_ROWS-1 = bottom / close).
     * We convert to pixel coordinates in the full mosaic, then use SurveillanceConfig's
     * camera calibration to estimate distance in meters.
     * 
     * @param quadrant Quadrant index (0=front, 1=right, 2=rear, 3=left)
     * @param centroidBlockY Centroid Y in block coordinates (0-6)
     * @return Estimated distance in meters, or -1 if unavailable
     */
    private float estimateDistanceFromCentroid(int quadrant, float centroidBlockY) {
        if (config == null) return -1;

        // Convert block Y to pixel Y within the quadrant
        // Block size is 32px, centroid is center of the block cluster
        float pixelY = centroidBlockY * GRID_BLOCK_SIZE + (GRID_BLOCK_SIZE / 2.0f);

        // Convert quadrant-local pixel Y to global mosaic Y
        // Mosaic layout: top row = quadrants 0,1; bottom row = quadrants 2,3
        int quadrantOffsetY = (quadrant >= 2) ? (THUMBNAIL_HEIGHT / 2) : 0;
        int globalY = quadrantOffsetY + (int) pixelY;

        return config.estimateDistanceForQuadrant(quadrant, globalY);
    }

    // ===== SOTA proximity estimation =====
    //
    // Per-quadrant tracking state for the trend computation. lowestY is the
    // row of the lowest active motion block on the previous tick; the trend
    // is the sign of (now - then). Mutated EXACTLY ONCE per processFrameV2
    // tick by {@link #updateProximityState}; downstream log sites within the
    // same tick read {@link #cachedTrend} (the result of that one update)
    // rather than re-mutating these fields. See audit H2.
    private final int[] prevLowestBlockY = new int[]{-1, -1, -1, -1};
    private final long[] prevLowestBlockYAtMs = new long[]{0, 0, 0, 0};

    // Effective vertical FOV after the BYD AVM HAL's dewarp. Stored
    // per-quadrant because front/rear cameras (ultra-wide fisheye in the
    // grille and rear plate) carry a wider vertical FOV than the
    // mirror-housing side cameras. A single global constant inflated
    // side-camera distances by ~70% per the validation analysis;
    // per-quadrant values close that gap with no calibration cost.
    //
    // Foveated 640×640 crops are a moving window inside one tile, so
    // their effective vertical FOV is smaller —
    // {@code per_quadrant_FOV × (CROP_SIZE / camStripHeight)}, computed
    // dynamically (Seal stripHeight=960 → 0.667; Tang stripHeight=720
    // → 0.889).
    //
    // Quadrant order: 0=front, 1=right, 2=rear, 3=left.
    private static final int FOVEATED_CROP_SIZE_PX = 640;  // matches FoveatedCropper.CROP_SIZE
    private static final float[] DEFAULT_VERTICAL_FOV_DEG = { 115f, 95f, 115f, 95f };

    /**
     * Per-vehicle camera-tile height in pixels (Seal=960, Tang=720).
     * Set by {@link #setCameraStripHeight} during pipeline init; used
     * to compute the foveated-crop FOV scale dynamically.
     */
    private volatile int cameraStripHeightPx = 960;  // Seal default
    /**
     * Per-quadrant vertical FOV in degrees. Set by
     * {@link #setCameraVerticalFovDeg(float[])} from the active
     * {@link com.overdrive.app.camera.CameraProfile}.
     */
    private volatile float[] cameraVerticalFovDeg = DEFAULT_VERTICAL_FOV_DEG.clone();

    /**
     * Configure the per-vehicle camera-tile height so the foveated-crop
     * FOV scaling is correct. Without this, the foveated path uses a
     * Seal-specific 640/960 ratio and reads ~30% long on Tang.
     */
    public void setCameraStripHeight(int stripHeightPx) {
        if (stripHeightPx > 0) this.cameraStripHeightPx = stripHeightPx;
    }

    /**
     * Configure per-quadrant vertical FOV (degrees) for the
     * bbox-height distance inference. Source: the active
     * {@link com.overdrive.app.camera.CameraProfile}'s
     * {@code getVerticalFovDeg(q)} values, written once during pipeline
     * init. Out-of-shape input is ignored (defaults retained).
     */
    public void setCameraVerticalFovDeg(float[] fovDegPerQuadrant) {
        if (fovDegPerQuadrant != null && fovDegPerQuadrant.length == 4) {
            float[] copy = new float[4];
            for (int i = 0; i < 4; i++) {
                copy[i] = fovDegPerQuadrant[i] > 0 ? fovDegPerQuadrant[i] : DEFAULT_VERTICAL_FOV_DEG[i];
            }
            this.cameraVerticalFovDeg = copy;
        }
    }

    /**
     * True iff the NCC tracker's bbox in {@code quadrant} sits inside the
     * user's configured detection zone. The motion-block path is gated
     * by {@code maxDistanceRow} natively + via {@link #applyQuadrantOverrides};
     * the NCC tracker bbox isn't, so an out-of-zone tracker lock can
     * leak past the zone gate via four paths:
     *
     * <ul>
     *   <li>brightness-suppression immunity ({@code !anyMotion} → forced
     *       {@code anyMotion=true} when a tracker has a person lock)</li>
     *   <li>{@code bestQ} fallback when {@code getHighestThreatQuadrant()}
     *       returns -1 because every quadrant got demoted by the row gate</li>
     *   <li>gap-tolerance extension ({@code trackerActive} extends 2s gap
     *       to 4s)</li>
     *   <li>post-record extension ({@code trackerHolding} keeps recording
     *       alive past motion-end)</li>
     * </ul>
     *
     * <p>Each of those gives recording a way to fire / persist for an
     * object outside the user's chosen zone. Gating those four sites on
     * {@code trackerInZone(q)} preserves the legitimate use cases (a
     * person walking in close gets locked and held through a headlight
     * sweep) while honouring the user's zone choice.
     *
     * <p>The check uses the bbox's <b>bottom edge</b> in row units against
     * the effective {@code maxDistanceRow} for that quadrant. Bbox-bottom
     * mirrors what the row gate uses for motion centroids — bottom-of-
     * silhouette ≈ closest point on the object to the camera ground plane.
     * Returns true when zone gating is disabled ({@code maxRow == 0},
     * "extended"), or when {@code maxRow} is unset.
     *
     * @return true if the tracker bbox bottom is at or below {@code maxRow},
     *         OR the zone gate is off, OR the tracker has no lock
     *         (in-zone is moot — caller will skip via the existing
     *         {@code trackerHasActiveTrack} check). Returns false only
     *         when an active tracker lock is OUTSIDE the configured zone.
     */
    /**
     * Whether a THREAT_HIGH (loiter) in the given quadrant is "trusted" enough
     * to keep the fast, YOLO-exempt 500ms recording path — i.e. it is a real
     * loiterer, not a waving flag / sweeping shadow whose stationary centroid
     * merely looks like loitering.
     *
     * Trusted iff EITHER:
     *   (a) an in-zone PERSON tracker holds the quadrant (classId==0 +
     *       trackerInZone) — a person who walked up and stopped; or
     *   (b) the native flow-coherence signal says the motion is coherently
     *       translating: flowCoherence >= COHERENCE_RATIO_MIN, OR the windowed
     *       cumulative net drift >= COHERENCE_NET_MIN blocks (a slow but steady
     *       approach with low per-frame coherence still accumulates net drift).
     *
     * FAIL-OPEN on the native signal: when the loaded .so doesn't compute
     * coherence (flowCoherence < 0, the pre-Phase-2 state), this method does
     * NOT treat that as "incoherent" — it simply relies on the tracker test
     * (a). The net effect pre-Phase-2 is that an unconfirmed HIGH with no
     * in-zone person tracker is YOLO-gated like MEDIUM, which is exactly the
     * flag-rejection behaviour we want, with the tracker preserving genuine
     * standing-person loiter.
     *
     * @param quadrant best-threat quadrant; if &lt; 0 (e.g. only the
     *                 headlight-immunity branch bumped maxThreat) returns false
     *                 so the caller falls through to its tracker-fallback path.
     */
    private boolean highThreatIsTrusted(int quadrant,
                                        MotionPipelineV2.QuadrantResult result) {
        // (a) In-zone person tracker — genuine standing loiterer keeps fast path.
        if (quadrant >= 0) {
            try {
                if (NativeMotion.trackerHasActiveTrack(quadrant)) {
                    float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
                    if (trackBox != null && trackBox.length >= 7
                            && (int) trackBox[5] == 0  // person
                            && trackerInZone(quadrant)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // (b) Native flow coherence (Phase 2). Negative = unavailable → fail
        // open to the tracker test above (don't demote on a missing signal).
        if (result != null && result.flowCoherence >= 0f) {
            if (result.flowCoherence >= COHERENCE_RATIO_MIN
                    || result.netDriftBlocks >= COHERENCE_NET_MIN) {
                return true;
            }
            return false;  // coherence computed AND incoherent → flag/shadow
        }
        // No coherence signal and no in-zone person tracker → not trusted.
        return false;
    }

    /**
     * Human-readable trust label for the trigger logs. Surfaces WHY a HIGH was
     * (or wasn't) granted the fast path so an on-device debug round can see the
     * flag/shadow demotion without a rebuild. Prints the native coherence values
     * when available, or "n/a" pre-Phase-2.
     */
    private String describeHighTrust(int threat, boolean trusted,
                                     MotionPipelineV2.QuadrantResult r) {
        if (threat < MotionPipelineV2.THREAT_HIGH) return "trust=n/a(<HIGH)";
        String coh = (r != null && r.flowCoherence >= 0f)
                ? String.format("coherence=%.2f netDrift=%.1f", r.flowCoherence, r.netDriftBlocks)
                : "coherence=n/a";
        return String.format("trust=%s(%s)", trusted ? "YES" : "NO→YOLO-gated", coh);
    }

    private boolean trackerInZone(int quadrant) {
        if (config == null) return true;  // no config → no gate
        // Resolve the per-quadrant effective zone (per-quadrant override
        // wins over global). maxDistanceRowForZone returns 0 for "extended"
        // (gate off), 2 for "normal", 4 for "close". Higher = stricter.
        String effZone = config.getEffectiveDetectionZone(quadrant);
        int maxRow = MotionPipelineV2.Config.maxDistanceRowForZone(effZone);
        if (maxRow <= 0) return true;  // gate disabled

        try {
            float[] trackBox = NativeMotion.trackerGetTrackBox(quadrant);
            if (trackBox == null || trackBox.length < 7) return false;
            // trackBox[6] == active flag; if no active lock, "in zone" is
            // vacuously true so callers don't double-gate themselves.
            if (trackBox[6] <= 0) return true;
            // bbox y is in 0..240 quadrant pixel space; row index = y / 32.
            // Bottom edge of bbox in row units = (y + h) / 32.
            float bottomRow = (trackBox[1] + trackBox[3]) / (float) GRID_BLOCK_SIZE;
            return bottomRow >= maxRow;
        } catch (Throwable t) {
            // Any native failure → fail safe to "in zone" so we don't
            // accidentally suppress a legitimate trigger because the
            // tracker JNI burped.
            return true;
        }
    }

    /**
     * Edge-band sentinel for the tile-edge guard. Validation analysis
     * showed the BYD HAL fisheye dewarp's angular-density-per-pixel is
     * roughly uniform in the central 60% of a tile and degrades steeply
     * in the outer 20% on each side. When the closest detection's bbox
     * center lands in that outer band we don't trust the bbox-height
     * inference and fall back to tier+trend.
     *
     * <p>Returns true if the closest predicted detection's bbox center
     * is in the outermost {@code edgeFrac} of the frame width on either
     * side. Mirrors {@link DistanceEstimator#fromYoloDetections}'s
     * "smallest distance wins" selection so we guard the same
     * detection that would actually be returned.
     */
    private static final float TILE_EDGE_BAND_FRAC = 0.20f;

    private boolean isInTileEdgeBand(java.util.List<com.overdrive.app.ai.Detection> dets,
                                     int frameH) {
        if (dets == null || dets.isEmpty()) return false;
        // Use bbox aspect to back into a sensible frame width assumption.
        // Foveated frames are square (640×640); mosaic quadrants are 4:3
        // (320×240). Code path that sets isFoveated already gates on
        // frameH ≥ CROP_SIZE so we know it's the square case here.
        final int frameW = frameH;  // foveated path is always square

        // Pick the closest valid detection (matches the selection rule
        // inside DistanceEstimator.fromYoloDetections).
        com.overdrive.app.ai.Detection closest = null;
        float closestRatio = Float.MAX_VALUE;
        for (com.overdrive.app.ai.Detection d : dets) {
            if (d.getH() <= 0) continue;
            // We only need ranking, not absolute distance — bbox-h ratio
            // is monotonic with closeness given a fixed real height prior.
            float invSize = 1.0f / (float) d.getH();
            if (invSize < closestRatio) {
                closestRatio = invSize;
                closest = d;
            }
        }
        if (closest == null) return false;
        float centerX = closest.getX() + closest.getW() / 2.0f;
        float frac = centerX / (float) frameW;
        return frac < TILE_EDGE_BAND_FRAC || frac > (1.0f - TILE_EDGE_BAND_FRAC);
    }

    /**
     * Compute the row of the lowest active motion block in a quadrant.
     * "Lowest" = highest row index = closest to the bottom of the FOV
     * which (after dewarp) corresponds to closest to the car. Returns -1
     * if no block is confirmed.
     */
    private int lowestActiveBlockRow(MotionPipelineV2.QuadrantResult r) {
        if (r == null || r.blockConfidence == null) return -1;
        // blockConfidence is row-major: index = row * GRID_COLS + col
        for (int row = MotionPipelineV2.GRID_ROWS - 1; row >= 0; row--) {
            for (int col = 0; col < MotionPipelineV2.GRID_COLS; col++) {
                int idx = row * MotionPipelineV2.GRID_COLS + col;
                if (idx < r.blockConfidence.length && r.blockConfidence[idx] > 0f) {
                    return row;
                }
            }
        }
        return -1;
    }

    // Cached per-tick trend per quadrant. The state-update half of
    // proximityForQuadrant runs once per processFrameV2 tick (in the
    // per-quadrant motion summary loop) and writes here; the read-only
    // half consults this cache so subsequent log sites in the same tick
    // (motion-start, motion-building, recording-trigger) report a
    // consistent trend instead of clobbering each other's state.
    //
    // Indexed by quadrant. Cleared to UNKNOWN at engine shutdown.
    private final DistanceEstimator.Trend[] cachedTrend = {
            DistanceEstimator.Trend.UNKNOWN, DistanceEstimator.Trend.UNKNOWN,
            DistanceEstimator.Trend.UNKNOWN, DistanceEstimator.Trend.UNKNOWN };

    /**
     * <b>State-mutating tick.</b> Updates {@code prevLowestBlockY[q]} and
     * caches the per-quadrant trend for downstream read-only callers in
     * the same tick. Call EXACTLY ONCE per quadrant per processFrameV2
     * iteration (currently from the per-quadrant motion summary loop).
     *
     * <p>Audit H2: previously {@link #proximityForQuadrant} mutated
     * prevLowestBlockY on every call. Multiple log sites in one tick
     * (motion-summary → motion-start → motion-building → trigger) hit
     * this path, the second call saw {@code dt=0} between sibling reads,
     * and trendFromBlockY's elapsedMs guard returned UNKNOWN. The trend
     * signal was silently lost on every multi-call frame. Splitting
     * mutation from query fixes that.
     */
    private void updateProximityState(int quadrant, MotionPipelineV2.QuadrantResult result) {
        if (quadrant < 0 || quadrant >= prevLowestBlockY.length) return;
        int lowestNow = lowestActiveBlockRow(result);
        long nowMs = System.currentTimeMillis();

        DistanceEstimator.Trend trend = DistanceEstimator.Trend.UNKNOWN;
        int prevRow = prevLowestBlockY[quadrant];
        long prevMs = prevLowestBlockYAtMs[quadrant];
        if (prevRow >= 0 && prevMs > 0 && lowestNow >= 0) {
            trend = DistanceEstimator.trendFromBlockY(prevRow, lowestNow, nowMs - prevMs);
        }

        // Reset state on quiet quadrants so a 30s-old prevRow doesn't
        // produce a bogus APPROACHING the next time blocks fire (audit M4).
        if (lowestNow < 0) {
            prevLowestBlockY[quadrant] = -1;
            prevLowestBlockYAtMs[quadrant] = 0;
        } else {
            prevLowestBlockY[quadrant] = lowestNow;
            prevLowestBlockYAtMs[quadrant] = nowMs;
        }
        cachedTrend[quadrant] = trend;
    }

    /**
     * SOTA proximity estimate for a given quadrant. <b>Read-only.</b>
     * Composes:
     *   1. <b>Technique A</b> (preferred) — class-conditional bbox-height
     *      inference from the latest YOLO detections in this quadrant.
     *      Uses the coord-space frame height the AI ran against
     *      (mosaic 240 vs foveated 640) so the focal/bbox ratio is
     *      coherent. Picks the closest predicted detection (audit M2).
     *   2. <b>Technique B</b> (fallback) — discrete tier from motion-block
     *      density + lowest-active-row, plus trend from the per-tick
     *      cache. No metric distance.
     *
     * <p>Idempotent within a tick — does not mutate state. Call
     * {@link #updateProximityState} once per tick before any read sites
     * fire to keep the trend signal current.
     */
    private DistanceEstimator.ProximityEstimate proximityForQuadrant(
            int quadrant, MotionPipelineV2.QuadrantResult result) {
        DistanceEstimator.Trend trend = (quadrant >= 0 && quadrant < cachedTrend.length)
                ? cachedTrend[quadrant]
                : DistanceEstimator.Trend.UNKNOWN;

        // Technique A: try the latest YOLO detections for this quadrant.
        // Single atomic read produces the (detections, frameHeight) pair
        // atomically — no torn read between two sibling atomics. Either
        // we see the previous tick's complete YoloPublication or we see
        // the new tick's; never new detections paired with old frame H.
        if (quadrant >= 0 && quadrant < lastYoloPublication.length()) {
            YoloPublication pub = lastYoloPublication.get(quadrant);
            if (pub != null && pub.detections != null && !pub.detections.isEmpty()) {
                int frameH = pub.frameHeightPx > 0 ? pub.frameHeightPx : (THUMBNAIL_HEIGHT / 2);
                // Per-quadrant base FOV from the active CameraProfile.
                // Side-camera FOV is materially narrower than front/rear,
                // and a single 110° constant was producing ~70%-high
                // estimates on side cameras per validation analysis.
                float baseFovDeg = (quadrant < cameraVerticalFovDeg.length)
                        ? cameraVerticalFovDeg[quadrant]
                        : DEFAULT_VERTICAL_FOV_DEG[0];

                // Foveated crops sample a sub-window of one tile so
                // their effective vertical FOV is narrower:
                // baseFOV × (CROP_SIZE / stripHeight).
                final float fovDeg;
                final boolean isFoveated = frameH >= FOVEATED_CROP_SIZE_PX;
                if (isFoveated) {
                    int strip = cameraStripHeightPx > 0 ? cameraStripHeightPx : 960;
                    float scale = (float) FOVEATED_CROP_SIZE_PX / (float) strip;
                    if (scale > 1f) scale = 1f;  // foveated FOV can never exceed tile FOV
                    fovDeg = baseFovDeg * scale;
                } else {
                    fovDeg = baseFovDeg;
                }

                // Tile-edge guard (validation report recommendation #2).
                // The HAL fisheye dewarp is non-uniform: angular density
                // per pixel varies across the tile. Bbox-height inference
                // assumes locally affine projection, which holds near tile
                // center but degrades at the edges (errors of 30-50% in
                // the outer 20% of the tile). When the closest valid
                // detection's bbox center is in that outer band, drop to
                // tier-only — honest "near approaching" beats a wrong
                // metric number. Only applies to foveated detections
                // (the moving window can land at any tile-X); mosaic
                // quadrants are bounded to one camera's tile and don't
                // have this edge problem.
                if (isFoveated && isInTileEdgeBand(pub.detections, frameH)) {
                    // Fall through to Technique B below.
                } else {
                    DistanceEstimator.ProximityEstimate est =
                            DistanceEstimator.fromYoloDetections(
                                    pub.detections, frameH, fovDeg, trend);
                    if (est != null) return est;
                }
            }
        }

        // Technique B: pre-YOLO tier + trend. Honest absence of meters.
        // We re-scan the lowest active block here (cheap; just an O(70)
        // pass over blockConfidence) rather than caching it, because
        // tierFromMotion needs the *current* result not what
        // updateProximityState saw — multiple log sites can be reading
        // results at slightly different points within one tick.
        int lowestNow = lowestActiveBlockRow(result);
        DistanceEstimator.Tier tier = DistanceEstimator.tierFromMotion(
                result != null ? result.activeBlocks : 0,
                lowestNow,
                MotionPipelineV2.GRID_ROWS);
        return DistanceEstimator.ProximityEstimate.tierOnly(tier, trend);
    }
    
    /**
     * Gets the last temporal blocks count (blocks with temporal consistency).
     * 
     * @return Number of temporally consistent blocks
     */
    public int getLastTemporalBlocksCount() {
        return lastTemporalBlocksCount;
    }
    
    /**
     * Gets the last motion bounding box Y coordinates.
     * 
     * @return int array [minY, maxY] or null if no motion
     */
    public int[] getLastMotionBounds() {
        if (lastMotionMaxY > lastMotionMinY) {
            return new int[] { lastMotionMinY, lastMotionMaxY };
        }
        return null;
    }
    
    /**
     * Gets class name from COCO class ID.
     */
    private String getClassName(int classId) {
        switch (classId) {
            case 0: return "person";
            case 2: return "car";
            case 3: return "motorcycle";
            case 5: return "bus";
            case 7: return "truck";
            default: return "object_" + classId;
        }
    }
    
    /**
     * Sets object detection filters.
     * 
     * Also adjusts motion detection sensitivity based on minSize:
     * - Lower minSize (for distant objects) = lower motion sensitivity
     * - Higher minSize (for close objects) = higher motion sensitivity
     * 
     * @param minSize Minimum object size (0.0-1.0, fraction of frame area)
     * @param confidence Minimum confidence (0.0-1.0)
     * @param detectPerson Enable person detection
     * @param detectCar Enable car detection
     * @param detectBike Enable bike detection
     */
    public void setObjectFilters(float minSize, float confidence,
                                 boolean detectPerson, boolean detectCar, boolean detectBike) {
        this.minObjectSize = minSize;
        this.aiConfidence = confidence;

        // Build class filter for YOLO
        java.util.ArrayList<Integer> classes = new java.util.ArrayList<>();
        if (detectPerson) classes.add(0);  // COCO: person
        if (detectCar) {
            classes.add(2);  // COCO: car
            classes.add(5);  // COCO: bus
            classes.add(7);  // COCO: truck
        }
        if (detectBike) {
            classes.add(1);  // COCO: bicycle
            classes.add(3);  // COCO: motorcycle
        }

        // FIX (Bug B): empty list now means "user disabled all classes" — represented as
        // an empty array (sentinel) rather than null. The hot-path guard short-circuits
        // YOLO entirely. This also lets us unload the TFLite interpreter to reclaim
        // ~50MB of native memory until detection is re-enabled.
        boolean hadAi = this.aiEnabled;
        if (classes.isEmpty()) {
            classFilter = new int[0];
            this.aiEnabled = false;
        } else {
            classFilter = new int[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                classFilter[i] = classes.get(i);
            }
            this.aiEnabled = true;
        }

        // Unload YOLO when AI is now off; load lazily again on next call when re-enabled
        // (lazy re-init is handled where YoloDetector is constructed in init()).
        if (!aiEnabled && yoloDetector != null) {
            try {
                yoloDetector.close();
            } catch (Exception e) {
                logger.warn("YoloDetector close failed: " + e.getMessage());
            }
            yoloDetector = null;
            logger.info("YOLO detector closed: all object classes disabled by user");
        } else if (aiEnabled && !hadAi) {
            logger.info("Object detection re-enabled; YOLO will be reloaded on next inference");
        }

        logger.info(String.format("Object filters: minSize=%.1f%%, confidence=%.0f%%, aiEnabled=%s, classes=%s",
                minSize * 100, confidence * 100, aiEnabled, classes));

        // Lazily reload YOLO if it was previously closed and we now need it again
        if (aiEnabled && yoloDetector == null) {
            reloadYoloDetectorIfPossible();
        }
    }

    /**
     * (Bug B helper) Lazily re-initialise the YOLO detector after a previous unload.
     * Uses the same Context/AssetManager paths as the original init() so daemon and
     * regular runs both work. Idempotent and safe to call when AI is already loaded.
     */
    private void reloadYoloDetectorIfPossible() {
        if (yoloDetector != null) return;
        try {
            if (yoloContext != null) {
                yoloDetector = new YoloDetector(yoloContext);
            } else if (yoloAssetManager != null) {
                yoloDetector = new YoloDetector(new com.overdrive.app.ai.AssetContext(yoloAssetManager));
            } else {
                logger.warn("Cannot reload YOLO: no context/assetManager retained");
                return;
            }
            boolean ok = yoloDetector.init();
            if (ok) {
                useObjectDetection = true;
                logger.info("YOLO detector reloaded (object detection re-enabled)");
            } else {
                logger.warn("YOLO reload failed");
                yoloDetector = null;
            }
        } catch (Exception e) {
            logger.warn("YOLO reload threw: " + e.getMessage());
            yoloDetector = null;
        }
    }
    
    /**
     * Publish a motion notification onto the cross-cutting NotificationBus.
     *
     * <p>Filename is the not-yet-finalized {@code currentEventFile} name —
     * recording is still in progress when this fires. Delivering the push
     * immediately (rather than waiting for finalization) prioritizes alert
     * latency over tap-to-play polish; the events page will surface a
     * "still recording" state until the file closes.
     */
    /**
     * Send a Telegram motion notification enriched with the current Actor
     * snapshot. Falls back to a generic "motion" payload when no Actors are
     * known yet (e.g. recording started purely on motion before YOLO ran).
     * Honours the user's notification tier toggles (item 8).
     */
    private void sendRichMotionNotifications(String videoFilename) {
        // User opt-out: by default, Telegram only gets the recording-CLOSE
        // photo (sendFinalTelegramNotification, fired from stopRecording). The
        // start-stage text message is suppressed because the user-visible end
        // result is two messages back-to-back — same content, no replace
        // semantics in Telegram. Telegram-only users who want low-latency
        // pings can flip telegramSendStartPing on in Sentry settings.
        //
        // Treat null config as "default" (off). Without this, an early-startup
        // motion event before config has been wired would leak through with
        // legacy "always send" behaviour, contradicting the documented default.
        if (config == null || !config.isTelegramSendStartPing()) {
            return;
        }
        java.util.List<Actor> snap = lastActors;
        Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
        // Per-tier muting for the web push system happens device-side via
        // muted-categories. Telegram has its own subscription model; we still
        // pass severity so the daemon can format the message accordingly.
        // Static actors (parked cars next to ours) MUST NOT count or be picked
        // as the detection label — see publishMotionNotification for the same
        // reasoning.
        int persons = 0, vehicles = 0, bikes = 0, animals = 0;
        Actor.Proximity closest = null;
        String detectionLabel = "motion";
        Actor threat = null;
        long nowMs = System.currentTimeMillis();
        for (Actor a : snap) {
            // FRESHNESS GATE: the tracker retains actors for TRACK_TTL_MS (5s)
            // after they leave; a caption must describe the LIVE scene, so skip
            // any actor not observed within ACTOR_CAPTION_FRESHNESS_MS. Without
            // this, a person who crossed and left keeps captioning the event as
            // the threat for up to 5s ("Person at front" when none is there).
            if (!isActorFresh(a, nowMs)) continue;
            // Keep a static PERSON (loiterer = the threat, gated CRITICAL); skip
            // only non-person statics (parked cars, forced to NOTICE anyway).
            if (a.isStatic && a.classGroup != Actor.ClassGroup.PERSON) continue;
            switch (a.classGroup) {
                case PERSON:  persons++;  break;
                case VEHICLE: vehicles++; break;
                case BIKE:    bikes++;    break;
                case ANIMAL:  animals++;  break;
                default: break;
            }
            if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                closest = a.peakProximity;
            }
            if (threat == null
                    || a.peakSeverity.ordinal() > threat.peakSeverity.ordinal()
                    || (a.peakSeverity == threat.peakSeverity
                        && classRank(a.classGroup) > classRank(threat.classGroup))) {
                threat = a;
            }
        }
        // camHint follows the threat actor so the title's "X at <camera>" phrase
        // names the camera that saw X, not whichever actor happened to be closest.
        String camHint = cameraNameFor(threat);
        float bestConf = threat != null ? threat.peakConfidence : 0f;
        if (threat != null) detectionLabel = Actor.groupLabel(threat.classGroup);
        // Telegram tier mute — mirrors the push tier toggles so a
        // Telegram-only user can keep CRITICAL/ALERT and silence NOTICE.
        if (!com.overdrive.app.notifications.NotificationGate.shouldTelegram(peakSev, config)) {
            logger.debug("Telegram start-stage suppressed by per-tier toggle (sev=" + peakSev + ")");
            return;
        }
        try {
            TelegramNotifier.notifyMotion(
                    detectionLabel,
                    bestConf > 0f ? bestConf : 1.0f,
                    videoFilename,
                    peakSev != null ? peakSev.name() : null,
                    persons, vehicles, bikes, animals,
                    closest != null ? closest.name() : null,
                    camHint);
        } catch (Throwable t) {
            logger.debug("Telegram notify failed: " + t.getMessage());
        }
    }

    /**
     * Final Telegram notification at recording-end. Computes the same actor
     * summary as {@link #sendRichMotionNotifications} but routes via
     * {@code notifyMotionFinalized} so the daemon sends a photo (with the hero
     * JPEG as the image and the threat summary as the caption) instead of a
     * text-only message. Falls back gracefully on the daemon side if the photo
     * can't be sent.
     */
    private void sendFinalTelegramNotification(String videoFilename, String heroPhotoPath) {
        java.util.List<Actor> snap = lastActors;
        Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
        int persons = 0, vehicles = 0, bikes = 0, animals = 0;
        Actor.Proximity closest = null;
        Actor threat = null;
        for (Actor a : snap) {
            // Keep a static PERSON so the body matches the CRITICAL the gate
            // already sends for a loiterer; skip only non-person statics.
            if (a.isStatic && a.classGroup != Actor.ClassGroup.PERSON) continue;
            switch (a.classGroup) {
                case PERSON:  persons++;  break;
                case VEHICLE: vehicles++; break;
                case BIKE:    bikes++;    break;
                case ANIMAL:  animals++;  break;
                default: break;
            }
            if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                closest = a.peakProximity;
            }
            if (threat == null
                    || a.peakSeverity.ordinal() > threat.peakSeverity.ordinal()
                    || (a.peakSeverity == threat.peakSeverity
                        && classRank(a.classGroup) > classRank(threat.classGroup))) {
                threat = a;
            }
        }
        // camHint follows the threat actor — see sendRichMotionNotifications.
        String camHint = cameraNameFor(threat);
        // Telegram tier mute. By stop time, an actor that was CRITICAL mid-event
        // may have been TTL-pruned from lastActors, collapsing the instantaneous
        // snapshot to NOTICE and silently dropping the closing photo+video of a
        // genuinely severe event. So consider BOTH the instantaneous snapshot
        // and the event-level peak latched across the whole recording.
        //
        // The tier toggles (tierNotices/tierAlerts/tierCritical) are INDEPENDENT
        // booleans, NOT an ordinal threshold — so we can't just gate on the max
        // (that could flip SEND→SUPPRESS when a lower tier is on and a higher
        // tier off). Send if EITHER severity passes its own toggle: this never
        // suppresses anything the old instantaneous gate would have sent, and
        // additionally rescues the receded-CRITICAL case.
        boolean snapOk = com.overdrive.app.notifications.NotificationGate.shouldTelegram(peakSev, config);
        // Only let the latch CONTRIBUTE when it was actually observed. A null
        // latch (no actor ever classified this event) must NOT fail-open via
        // shouldTelegram(null)=true — that would force-send actor-less events
        // and break the default NOTICE-suppression. Snapshot remains the
        // baseline; the latch only ever ADDS a reason to send.
        boolean peakOk = eventPeakSeverity != null
                && com.overdrive.app.notifications.NotificationGate.shouldTelegram(eventPeakSeverity, config);
        if (!snapOk && !peakOk) {
            logger.debug("Telegram final-stage suppressed by per-tier toggle (eventPeak="
                    + eventPeakSeverity + ", snapshot=" + peakSev + ")");
            return;
        }
        // Report the higher of the two as the header severity so a receded
        // CRITICAL still reads CRITICAL when that's why we're sending.
        Actor.Severity gateSev = maxOf(eventPeakSeverity, peakSev);
        try {
            TelegramNotifier.notifyMotionFinalized(
                    videoFilename,
                    heroPhotoPath,
                    // Report the event-level peak so the message header matches
                    // the gate decision (a receded CRITICAL still reads CRITICAL).
                    gateSev != null ? gateSev.name() : null,
                    persons, vehicles, bikes, animals,
                    closest != null ? closest.name() : null,
                    camHint);
        } catch (Throwable t) {
            logger.debug("Telegram finalized notify failed: " + t.getMessage());
        }

        // Surveillance video upload. We're past the shouldTelegram() tier gate
        // above, so the video honours the same per-severity toggle as the text
        // + photo — fixing "NOTICE muted but the clip still arrives". The
        // generic recorder (HardwareEventRecorderGpu) deliberately does NOT
        // auto-send event_*.mp4 for exactly this reason; this is the single
        // gated send for surveillance clips. notifyVideoRecorded applies its
        // own videoUploads gate, so the net rule is "tier enabled AND video
        // uploads on" — the user's expected behaviour. heroPhotoPath is the
        // absolute hero path, so its parent is the event directory; derive the
        // clip's absolute path from there (videoFilename is bare).
        try {
            String videoPath = null;
            if (heroPhotoPath != null && !heroPhotoPath.isEmpty()) {
                java.io.File heroFile = new java.io.File(heroPhotoPath);
                java.io.File parent = heroFile.getParentFile();
                if (parent != null) {
                    videoPath = new java.io.File(parent, videoFilename).getAbsolutePath();
                }
            }
            if (videoPath == null && currentEventFile != null) {
                // Fallback when no hero was written (text-only short clips):
                // currentEventFile is the just-closed event's absolute path.
                videoPath = currentEventFile.getAbsolutePath();
            }
            if (videoPath != null && new java.io.File(videoPath).exists()) {
                int durationSec = 0;
                try {
                    HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                    if (enc != null) durationSec = enc.getLastFinalizedDurationSec();
                } catch (Throwable ignored) {}
                String label = threat != null ? Actor.groupLabel(threat.classGroup) : null;
                TelegramNotifier.notifyVideoRecorded(videoPath, label, durationSec);
            } else {
                logger.debug("Surveillance video upload skipped — clip path unresolved/missing");
            }
        } catch (Throwable t) {
            logger.debug("Telegram surveillance video send failed: " + t.getMessage());
        }
    }

    /**
     * Rank a class group for "which actor is the threat in this scene". Higher
     * = more important to surface. Mirrors {@link ThumbnailBuffer}'s scoring so
     * the notification title agrees with the thumbnail.
     */
    private static int classRank(Actor.ClassGroup g) {
        if (g == null) return 0;
        switch (g) {
            case PERSON:  return 4;
            case BIKE:    return 3;
            case VEHICLE: return 2;
            case ANIMAL:  return 1;
            default:      return 0;
        }
    }

    /** How recently an actor must have been observed to be eligible to drive a
     *  caption/notification. The tracker retains tracks for TRACK_TTL_MS (5s)
     *  after an actor leaves so cross-quadrant handoff and post-record framing
     *  work — but a caption must describe the LIVE scene, not a ghost that left
     *  up to 5s ago.
     *
     *  Sized above the WORST-CASE AI refresh latency: the AI lane runs at most
     *  one quadrant per AI_COOLDOWN_MS (500ms), round-robin over up to 4
     *  quadrants, so a non-priority quadrant's actor refreshes only ~every 2s.
     *  A present actor must stay "fresh" across that gap (and across the
     *  deferred/timeout trigger paths, which can fire several seconds after the
     *  last AI hit), so 2500ms > the ~2000ms round-robin worst case — while
     *  still well under TRACK_TTL_MS so a departed actor is excluded. A tighter
     *  value dropped the real triggering actor on the live START ping
     *  (degrading the caption to a bare "Motion detected"). */
    private static final long ACTOR_CAPTION_FRESHNESS_MS = 2500;

    /** True iff the actor was observed recently enough to caption the live scene. */
    private boolean isActorFresh(Actor a, long now) {
        return a != null && a.lastSeenWallMs > 0
                && (now - a.lastSeenWallMs) <= ACTOR_CAPTION_FRESHNESS_MS;
    }

    /** Camera name for a caption — the actor's CURRENT (last-seen) quadrant, NOT
     *  the lifetime peakCamera high-water latch. Naming peakCamera captioned a
     *  quadrant the actor may have already left ("person at front" when it has
     *  moved to / off the side). Forensic/thumbnail paths still use peakCamera. */
    private static String cameraNameFor(Actor a) {
        if (a == null) return null;
        if (a.lastCamera < 0 || a.lastCamera >= MotionPipelineV2.QUADRANT_NAMES.length) return null;
        return MotionPipelineV2.QUADRANT_NAMES[a.lastCamera];
    }

    /**
     * Stable per-event tag used by both the initial quick notification and the
     * finalized rich one. Same tag → OS replaces the first banner with the
     * second instead of stacking. Tag is derived from the filename so it's
     * unique per recording (a 1-minute dedupe window across recordings is no
     * longer needed since the tag is already event-scoped).
     */
    private static String notificationTagFor(String videoFilename) {
        if (videoFilename != null && !videoFilename.isEmpty()) {
            return "motion:" + videoFilename;
        }
        // Fallback when we don't yet have a filename — minute-bucket dedupe
        // (matches legacy behaviour).
        return "motion-" + (System.currentTimeMillis() / 60000L);
    }

    /**
     * Fallback hero JPEG: extract a keyframe from the MP4 itself when
     * ThumbnailBuffer didn't capture one. Saves to the same path
     * `<videoBase>.jpg` so the rest of the pipeline (sidecar reference,
     * Telegram sendPhoto, PWA push image) works unchanged. Atomic write
     * via .tmp + rename, world-readable so the Telegram daemon (different
     * UID) can read it.
     *
     * Idempotent and exception-safe — failure is logged but never thrown
     * to the caller. The notification path treats absence of the file as
     * "text-only", which is the correct degraded behaviour.
     */
    /**
     * On daemon startup, sweep the surveillance directory for {@code .mp4}
     * files whose sibling hero {@code .jpg} is missing — daemon SIGKILL
     * between rename and finalizer would leave that pair dangling. Generate
     * fallback heroes so the events UI never shows a thumbnail-less card.
     *
     * <p>Idempotent: skips files whose hero already exists. Bounded: only
     * looks at files older than 30 seconds (anything younger is probably
     * still being finalized by a peer daemon process). 5-second budget per
     * file (MediaMetadataRetriever can hang on a malformed mp4).
     */
    private void sweepOrphanHeroThumbnails(File dir) {
        File[] mp4s = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (mp4s == null || mp4s.length == 0) return;
        long cutoff = System.currentTimeMillis() - 30_000L;
        int generated = 0;
        // Per-file timeout: MediaMetadataRetriever can hang indefinitely on
        // a malformed mp4 (truncated moov, corrupt sample tables) and
        // Future.cancel(true) only flips the interrupt flag — it does NOT
        // abort the underlying native JNI call. So a single-thread executor
        // would queue all subsequent files behind the stuck worker and they'd
        // ALL time out without ever running. Spawn a fresh daemon thread per
        // file so a hung worker leaks one thread but the sweep keeps making
        // progress on the remaining files. 5s budget per file is generous
        // (a healthy keyframe extract is <500 ms even on a slow SD card).
        for (File mp4 : mp4s) {
            if (mp4.lastModified() > cutoff) continue;
            String name = mp4.getName();
            String heroName = name.substring(0, name.length() - 4) + ".jpg";
            File heroFile = new File(dir, heroName);
            if (heroFile.exists()) continue;

            final File mp4Final = mp4;
            final File heroFinal = heroFile;
            final Object done = new Object();
            final boolean[] finished = { false };
            Thread worker = new Thread(() -> {
                try {
                    writeFallbackHeroFromMp4(mp4Final, heroFinal);
                } catch (Throwable t) {
                    logger.debug("Orphan hero extract worker failed for "
                            + name + ": " + t.getMessage());
                } finally {
                    synchronized (done) {
                        finished[0] = true;
                        done.notifyAll();
                    }
                }
            }, "OverdriveOrphanHeroExtract-" + name);
            worker.setDaemon(true);
            worker.start();
            synchronized (done) {
                long deadline = System.currentTimeMillis() + 5_000L;
                while (!finished[0]) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try { done.wait(remaining); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (!finished[0]) {
                worker.interrupt();
                logger.warn("Orphan hero extract timed out for " + name
                        + " — leaking worker thread, continuing sweep");
            }
            if (heroFile.exists()) generated++;
        }
        if (generated > 0) {
            logger.info("Orphan hero sweep: generated " + generated
                    + " fallback thumbnails in " + dir.getName());
        }
    }

    /**
     * Run {@link #writeFallbackHeroFromMp4} on a fresh daemon thread with a
     * hard timeout. Returns when the worker finishes or {@code timeoutMs}
     * elapses, whichever first. On timeout the worker is interrupted and
     * abandoned — {@link android.media.MediaMetadataRetriever} runs in JNI
     * and ignores Thread.interrupt(), so a hung worker will eventually be
     * killed when its ANR budget exhausts or the process exits. Daemon flag
     * means the leak doesn't block JVM shutdown.
     */
    private void writeFallbackHeroWithTimeout(File mp4File, File outFile, long timeoutMs) {
        if (mp4File == null || outFile == null) return;
        final Object done = new Object();
        final boolean[] finished = { false };
        Thread worker = new Thread(() -> {
            try {
                writeFallbackHeroFromMp4(mp4File, outFile);
            } catch (Throwable t) {
                logger.debug("Fallback hero worker failed for "
                        + mp4File.getName() + ": " + t.getMessage());
            } finally {
                synchronized (done) {
                    finished[0] = true;
                    done.notifyAll();
                }
            }
        }, "OverdriveFallbackHero-" + mp4File.getName());
        worker.setDaemon(true);
        worker.start();
        synchronized (done) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!finished[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { done.wait(remaining); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (!finished[0]) {
            worker.interrupt();
            logger.warn("Fallback hero extract timed out for " + mp4File.getName()
                    + " — leaking worker thread, continuing publish");
        }
    }

    private void writeFallbackHeroFromMp4(File mp4File, File outFile) {
        if (mp4File == null || outFile == null) return;
        if (outFile.exists()) return;          // ThumbnailBuffer already wrote one
        if (!mp4File.exists() || mp4File.length() == 0) return;

        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(mp4File.getAbsolutePath());
            // Sample at ~1s in (or 0 if the clip is shorter) to skip the
            // black frame the encoder sometimes leads with.
            String dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 0;
            try { if (dur != null) durMs = Long.parseLong(dur); } catch (Exception ignored) {}
            long sampleUs = durMs >= 1500 ? 1_000_000L : Math.max(0L, (durMs * 500L));
            android.graphics.Bitmap frame = mmr.getFrameAtTime(sampleUs,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                logger.debug("Fallback hero: getFrameAtTime returned null for " + mp4File.getName());
                return;
            }
            File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            } finally {
                frame.recycle();
            }
            try { tmpFile.setReadable(true, /*ownerOnly=*/false); } catch (Throwable ignored) {}
            if (!tmpFile.renameTo(outFile)) {
                outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete();
                    logger.warn("Fallback hero rename failed for " + outFile.getName());
                    return;
                }
            }
            logger.info("Fallback hero (from mp4 keyframe): " + outFile.getName());
        } catch (Throwable t) {
            logger.debug("Fallback hero extraction failed for " + mp4File.getName()
                    + ": " + t.getMessage());
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Capitalised, human-readable proximity phrase used as the lead clause
     * in notification bodies. "VERY_CLOSE" → "Very close", etc.
     */
    private static String proximityPhrase(Actor.Proximity p) {
        if (p == null) return "";
        switch (p) {
            case VERY_CLOSE: return "Very close";
            case CLOSE:      return "Close";
            case MID:        return "Mid range";
            case FAR:        return "Far";
            default:         return "";
        }
    }

    /**
     * Pluralised count list for notification bodies.
     * (1, 0, 0, 0) → "1 person"
     * (2, 1, 0, 0) → "2 people, 1 vehicle"
     * (0, 2, 0, 1) → "2 vehicles, 1 animal"
     * Skips zero counts; uses proper plurals; drops the "× n" formatter.
     */
    private static String formatActorCounts(int persons, int vehicles, int bikes, int animals) {
        java.util.List<String> parts = new java.util.ArrayList<>(4);
        if (persons > 0)  parts.add(persons  + " " + (persons  == 1 ? "person"  : "people"));
        if (vehicles > 0) parts.add(vehicles + " " + (vehicles == 1 ? "vehicle" : "vehicles"));
        if (bikes > 0)    parts.add(bikes    + " " + (bikes    == 1 ? "bike"    : "bikes"));
        if (animals > 0)  parts.add(animals  + " " + (animals  == 1 ? "animal"  : "animals"));
        return String.join(", ", parts);
    }

    /**
     * Initial low-priority notification at the moment recording starts.
     *
     * Why we still publish at start: the user expects feedback "something just
     * happened" with low latency. The hero thumbnail isn't ready yet (it's
     * written by ThumbnailBuffer at recording-end), so this banner is text-
     * only and minimum-severity. The matching {@link #publishMotionFinal}
     * call after recording-end carries the rich title + thumbnail and uses
     * the SAME tag — so the OS replaces this quick banner instead of stacking.
     *
     * Routing: always to {@code surveillance.motion.notice} so users who only
     * want Alerts/Criticals at start can mute this tier and just receive the
     * final notification.
     */
    private void publishMotionNotification(String videoFilename) {
        try {
            // Honour the user's per-tier toggle. The start banner is always
            // routed to surveillance.motion.notice (final stage carries the
            // real severity), so it's gated by isPushNotices(). With the
            // default config (pushNotices=false) start banners are off and
            // the user only sees the rich final notification.
            if (config != null && !config.isPushNotices()) {
                return;
            }
            org.json.JSONObject data = new org.json.JSONObject();
            String url;
            if (videoFilename != null && !videoFilename.isEmpty()) {
                String enc = java.net.URLEncoder.encode(videoFilename, "UTF-8");
                data.put("filename", videoFilename);
                // Deliberately NOT setting data.snapshot here. At start time the
                // hero JPEG hasn't been written yet and /thumb/<mp4> will return
                // 202 or, worse, a mid-event MMR frame off the in-flight .tmp.
                // iOS Safari Web Push caches the resolved image bytes against
                // the tag on first paint and refuses to swap them when the
                // matching `final` push arrives — so leaving snapshot out keeps
                // the start banner intentionally text-only and lets the final
                // push install the real hero image cleanly.
                data.put("stage", "start");
                url = "/events.html?filter=sentry&file=" + enc;
            } else {
                url = "/events.html?filter=sentry";
            }

            // Name the quadrant where an actor IS NOW (lastCamera via cameraNameFor),
            // and only from a FRESH actor — a TTL-retained ghost that already left
            // must not label this live "Motion at <camera>" banner.
            long nowMs = System.currentTimeMillis();
            String camHint = null;
            for (Actor a : lastActors) {
                if (!isActorFresh(a, nowMs)) continue;
                String name = cameraNameFor(a);
                if (name != null) { camHint = name; break; }
            }
            String title = (camHint != null) ? "Motion at " + camHint : "Motion detected";
            String body = "Recording in progress";

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            "surveillance.motion.notice",
                            com.overdrive.app.notifications.NotificationEvent.Severity.INFO,
                            title,
                            body,
                            notificationTagFor(videoFilename),
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishMotionNotification (start) failed: " + t.getMessage());
        }
    }

    /**
     * Finalized rich notification fired from stopRecording AFTER the hero JPEG
     * has been written by ThumbnailBuffer. Uses the same tag as the initial
     * notification so the OS replaces the "Recording in progress…" banner
     * with the proper threat summary + image.
     *
     * Routes to the severity-appropriate subcategory ({@code .notice/.alert/.critical})
     * so per-tier muting works.
     */
    private void publishMotionFinal(String videoFilename, String heroJpegName) {
        try {
            // Snapshot the current Actor view — at this point the recording has
            // closed so lastActors holds the final state.
            java.util.List<Actor> snap = lastActors;
            Actor.Severity peakSev = com.overdrive.app.notifications.NotificationGate.maxSeverity(snap);
            // Per-tier gate (config-level): if the user has unchecked the push
            // toggle for this tier in surveillance.html, suppress the publish
            // entirely. Per-device subcategory muting still happens downstream
            // in PushSink for users who want to silence individual devices.
            if (!com.overdrive.app.notifications.NotificationGate.shouldPush(peakSev, config)) {
                logger.debug("publishMotionFinal suppressed by per-tier toggle (sev=" + peakSev + ")");
                return;
            }

            // Build per-class counts + closest proximity from snapshot.
            //
            // ONLY count non-static actors. The user's worry: two cars parked
            // next to ours while a person walks in. YOLO returns 3 detections;
            // the parked cars are flagged isStatic by the tracker; we exclude
            // them from counts so the notification reads "1 person near front
            // camera" — not "1 person, 2 vehicles".
            int persons = 0, vehicles = 0, bikes = 0, animals = 0;
            Actor.Proximity closest = null;
            // Threat actor = highest-severity, then best class rank
            // (person > bike > vehicle > animal).
            Actor threat = null;
            for (Actor a : snap) {
                // Keep a static PERSON (loiterer = threat, gated CRITICAL); skip
                // only non-person statics (parked cars → NOTICE anyway).
                if (a.isStatic && a.classGroup != Actor.ClassGroup.PERSON) continue;
                switch (a.classGroup) {
                    case PERSON:  persons++;  break;
                    case VEHICLE: vehicles++; break;
                    case BIKE:    bikes++;    break;
                    case ANIMAL:  animals++;  break;
                    default: break;
                }
                if (closest == null || a.peakProximity.ordinal() < closest.ordinal()) {
                    closest = a.peakProximity;
                }
                if (threat == null
                        || a.peakSeverity.ordinal() > threat.peakSeverity.ordinal()
                        || (a.peakSeverity == threat.peakSeverity
                            && classRank(a.classGroup) > classRank(threat.classGroup))) {
                    threat = a;
                }
            }
            // camHint follows the threat actor so the title's "X at <camera>"
            // phrase names the camera that saw X, not whichever actor was closest.
            String camHint = cameraNameFor(threat);

            // ---- Title (severity tier + threat class + camera) ----
            // Format: "CRITICAL · Person at front" or "Alert · Vehicle at rear"
            // or plain "Motion at front" when AI didn't classify.
            String title;
            if (threat == null) {
                title = (camHint != null) ? "Motion at " + camHint : "Motion detected";
            } else {
                StringBuilder sb = new StringBuilder();
                if (peakSev == Actor.Severity.CRITICAL) sb.append("CRITICAL · ");
                else if (peakSev == Actor.Severity.ALERT) sb.append("Alert · ");
                String label = Actor.groupLabel(threat.classGroup);
                if (!label.isEmpty()) {
                    label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
                }
                sb.append(label);
                if (camHint != null) sb.append(" at ").append(camHint);
                title = sb.toString();
            }

            // ---- Body (proximity phrase + counts when relevant) ----
            // Single actor: "Very close" / "Close" / "Mid range" / "Far".
            // Multiple actors: "Very close · 1 person, 2 vehicles".
            // When a hero JPEG was written, append "close-up view" so the user
            // knows the attached image is the foveated crop around the threat,
            // not a wide shot of the camera frame.
            String body;
            int totalActors = persons + vehicles + bikes + animals;
            boolean hasHero = heroJpegName != null && !heroJpegName.isEmpty();
            if (threat == null) {
                body = "Recording in progress";
            } else {
                StringBuilder sb = new StringBuilder();
                if (closest != null && closest != Actor.Proximity.UNKNOWN) {
                    sb.append(proximityPhrase(closest));
                }
                if (totalActors > 1) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(formatActorCounts(persons, vehicles, bikes, animals));
                }
                if (sb.length() == 0) sb.append("Motion detected");
                if (hasHero) sb.append(" · close-up view");
                body = sb.toString();
            }

            // Place suffix — appended only when the resolver has a synchronous
            // hit (cache or SafeLocation). Online resolution is deliberately
            // never awaited from the publish path: the push must fire as
            // soon as the .mp4 finalizes, not after a 6-second Nominatim
            // round-trip. A miss leaves the body unchanged. Coords are
            // never leaked into the body — better silent on location than
            // posting "3.0509, 101.7166" into a Telegram chat.
            String placeMid = null;
            String placeCC = null;
            try {
                HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                if (enc != null && enc.hasStartGeo()) {
                    // Always "surveillance" flow on this path — this is the
                    // sentry/proximity publish exit.
                    com.overdrive.app.geo.PlaceResult place =
                            com.overdrive.app.geo.GeocodingResolver.getInstance()
                                    .resolveCachedOnly(enc.getStartGeoLat(),
                                            enc.getStartGeoLng(), "surveillance");
                    if (place != null) {
                        String mid = place.mediumLabel();
                        if (mid != null && !mid.isEmpty()) {
                            body = body + " · " + mid;
                            placeMid = mid;
                            if (!place.countryCode.isEmpty()) {
                                placeCC = place.countryCode;
                            }
                        }
                    }
                }
            } catch (Throwable placeErr) {
                logger.debug("publishMotionFinal place lookup failed: " + placeErr.getMessage());
            }

            org.json.JSONObject data = new org.json.JSONObject();
            String url;
            if (videoFilename != null) {
                String enc = java.net.URLEncoder.encode(videoFilename, "UTF-8");
                data.put("filename", videoFilename);
                // Prefer the just-written hero JPEG for the OS banner image.
                // Falls back to /thumb/<mp4-name> which the server also resolves
                // to the hero sibling, but the explicit JPEG path skips a layer
                // of resolution and avoids any 202-while-generating window.
                String snapshotName = (heroJpegName != null && !heroJpegName.isEmpty())
                        ? heroJpegName : videoFilename;
                String encSnap = java.net.URLEncoder.encode(snapshotName, "UTF-8");
                // Carry a single-purpose signed token so Web Push service
                // workers / OS notification banners can fetch the thumbnail
                // without an Authorization header. 10 min TTL is plenty for
                // a banner that the user dismisses or taps within seconds.
                String thumbTok = com.overdrive.app.auth.AuthManager
                        .signThumbToken(snapshotName, 600L);
                String snapUrl = "/thumb/" + encSnap;
                if (thumbTok != null) snapUrl += "?t=" + thumbTok;
                data.put("snapshot", snapUrl);
                data.put("stage", "final");
                url = "/events.html?filter=sentry&file=" + enc;
            } else {
                url = "/events.html?filter=sentry";
            }
            // Surface the new metadata so the notification UI / SW can render it
            data.put("severity", peakSev.name());
            data.put("personCount", persons);
            data.put("vehicleCount", vehicles);
            data.put("bikeCount", bikes);
            data.put("animalCount", animals);
            if (closest != null && closest != Actor.Proximity.UNKNOWN) {
                data.put("closestProximity", closest.name());
            }
            if (camHint != null) data.put("camera", camHint);
            if (placeMid != null) data.put("place", placeMid);
            if (placeCC  != null) data.put("placeCountry", placeCC);

            com.overdrive.app.notifications.NotificationEvent.Severity nsev;
            if (peakSev == Actor.Severity.CRITICAL) {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL;
            } else if (peakSev == Actor.Severity.ALERT) {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
            } else {
                nsev = com.overdrive.app.notifications.NotificationEvent.Severity.INFO;
            }

            // Route to severity-specific subcategory so per-tier muting works
            // (item 8). Devices that have not yet learned the new IDs still
            // receive the parent "surveillance.motion" event below.
            String subCategory;
            if (peakSev == Actor.Severity.CRITICAL) subCategory = "surveillance.motion.critical";
            else if (peakSev == Actor.Severity.ALERT) subCategory = "surveillance.motion.alert";
            else subCategory = "surveillance.motion.notice";

            com.overdrive.app.notifications.NotificationBus.get().publish(
                    new com.overdrive.app.notifications.NotificationEvent(
                            subCategory,
                            nsev,
                            title,
                            body,
                            notificationTagFor(videoFilename),
                            url,
                            data));
        } catch (Throwable t) {
            logger.debug("publishMotionFinal failed: " + t.getMessage());
        }
    }

    /**
     * Continuous-mode entry. Bypasses motion / YOLO / baseline entirely and
     * starts a plain rolling recording. Segment rotation @
     * {@code SEGMENT_DURATION_MS} (2 min) still runs so a long session is
     * split into manageable .mp4 files. Filename uses the same {@code event_}
     * prefix as smart-mode events so recordings library / Telegram /
     * storage-bucket plumbing handles them without separate code paths.
     *
     * No timeline collector, no per-segment hero JPEG, no Telegram
     * notifications, no screen deterrent — the user opted in knowing the
     * whole park is recorded; per-segment notifications would just spam.
     *
     * Stop happens at {@link #disable()} (ACC ON or owner-unlock disarm).
     */
    private void startContinuousRecording() {
        if (recorder == null) {
            logger.error("Cannot start continuous recording — recorder is null");
            return;
        }
        if (recording) {
            logger.debug("Already recording");
            return;
        }

        // Same SD/USB mount sanity-check as startRecording(); otherwise the
        // first segment can land on a stale path right after a card eject.
        com.overdrive.app.storage.StorageManager storageManager;
        try {
            storageManager = com.overdrive.app.storage.StorageManager.getInstance();
            com.overdrive.app.storage.StorageManager.StorageType type =
                    storageManager.getSurveillanceStorageType();
            if (type == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD
                    && !storageManager.isSdCardMounted()) {
                logger.warn("SD card unmounted before continuous recording — attempting remount");
                storageManager.ensureSdCardMounted(true);
            } else if (type == com.overdrive.app.storage.StorageManager.StorageType.USB
                    && !storageManager.isUsbMounted()) {
                logger.warn("USB unmounted before continuous recording — attempting remount");
                storageManager.ensureUsbMounted(true);
            }
        } catch (Exception e) {
            logger.warn("Storage mount check failed: " + e.getMessage());
            storageManager = null;
        }
        final com.overdrive.app.storage.StorageManager smRef = storageManager;
        if (smRef != null) {
            // Continuous mode generates much more data than smart mode; keep
            // the periodic cleanup running and trigger an immediate prune so
            // the first segment doesn't land on a near-full card.
            new Thread(() -> {
                try { smRef.ensureSurveillanceSpace(50 * 1024 * 1024); }
                catch (Exception e) { logger.warn("Cleanup failed: " + e.getMessage()); }
            }, "ContinuousCleanup").start();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        // Reuse the `event_` prefix so everything downstream (recordings
        // library scanner, /api/recordings/<file> server, Telegram /download,
        // StorageManager surveillance bucket, daily-prune watcher) handles
        // continuous-mode segments without separate code paths. The mode
        // distinction lives in the user's config, not in the filename.
        String fileName = "event_" + timestamp + ".mp4";
        // Re-read the live surveillance dir: enableSurveillance() may have
        // snapshotted the internal fallback during the boot mount-race, and the
        // SD/USB mount (incl. the smRef.ensure*Mounted attempt just above) may
        // have landed since. We MUST resolve LIVE (getLiveSurveillanceDir)
        // rather than read getSurveillanceDir(): the volatile surveillanceDir
        // field is frozen while the arm session is active (updateActiveDirectories
        // skips the surveillance branch), so getSurveillanceDir() would return
        // the stale internal fallback for a mount that landed after the bounded
        // enable-wait. getLiveSurveillanceDir() bypasses that freeze and falls
        // back to internal when the external is genuinely unavailable. Resolved
        // ONCE here (recording == false above), so the in-flight clip can't be
        // split across volumes; null-guard back to the snapshot.
        File trigDir = (smRef != null) ? smRef.getLiveSurveillanceDir() : eventOutputDir;
        if (trigDir == null) trigDir = eventOutputDir;
        // ENOSPC internal-spill: if the configured external is mounted-but-FULL,
        // redirect THIS clip to the INTERNAL SURVEILLANCE dir so it isn't
        // quarantined as .broken on a packed card. Uses the surveillance-bucket
        // helper (NOT resolveTargetWithEnospcFallback, which spills to the
        // recordings folder and would orphan an event_* clip from both cleanup
        // pools). Defensive: a throw returns trigDir unchanged.
        if (smRef != null && trigDir != null) {
            try {
                File spill = smRef.resolveSurveillanceTargetWithEnospcFallback(trigDir, 100L * 1024 * 1024);
                if (spill != null) trigDir = spill;
            } catch (Throwable t) {
                logger.warn("ENOSPC-fallback resolve failed (continuous): " + t.getMessage());
            }
        }
        if (trigDir != null && !trigDir.equals(eventOutputDir)) {
            eventOutputDir = trigDir;
        }
        currentEventFile = new File(trigDir, fileName);

        logger.info("Starting continuous recording: " + currentEventFile.getAbsolutePath());

        // postRecordMs=0: the engine itself owns the stop schedule (it stops
        // at disable()), so we don't want the recorder to schedule any
        // automatic close. The encoder's pre-record buffer is harmless here
        // — it just front-loads the first segment with a few seconds of
        // pre-arm video, which is fine.
        //
        // Honor triggerEventRecording's return — the recorder refuses to
        // build a muxer when the encoder hasn't published its format
        // (savedFormat barrier). Flipping `recording = true` regardless
        // would leave the engine bookkeeping advanced (rotation listener
        // attached, segment counter incremented) against a no-op recorder
        // → user sees a "recording" indicator but no clip lands on disk.
        if (!recorder.triggerEventRecording(currentEventFile.getAbsolutePath(), 0L)) {
            logger.warn("Continuous-mode triggerEventRecording refused "
                + "(encoder format not ready); will retry on next event");
            currentEventFile = null;
            return;
        }
        recording = true;

        // Segment rotation listener: track the new segment filename for the
        // future stop() AND submit the closed segment to the geo sidecar
        // writer. Continuous-mode reuses the event_*.mp4 prefix to share
        // downstream plumbing, so the recorder's own filename-based
        // skip-sentry guard correctly skips these in HardwareEventRecorderGpu;
        // we have to do the submit here. Flow="recording" because
        // continuous-mode is NOT sentry surveillance — it's a parking
        // dashcam mode that the user opts into separately, and gating
        // on the "recording" flow's geocoding toggle matches that
        // mental model.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) {
                enc.setSegmentListener((closedSegment, newSegment) -> {
                    if (closedSegment != null) {
                        try {
                            com.overdrive.app.geo.GeoSnapshot startGeo;
                            if (enc.hasClosedStartGeo()) {
                                startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                        enc.getClosedStartGeoLat(), enc.getClosedStartGeoLng(),
                                        enc.getClosedStartGeoAccuracy(), enc.getClosedStartGeoAgeMs(),
                                        enc.getClosedStartGeoCapturedAtMs(), 0L);
                            } else {
                                startGeo = com.overdrive.app.geo.GeoSnapshot.empty();
                            }
                            com.overdrive.app.geo.LocationSidecarWriter
                                    .getInstance()
                                    .submit(closedSegment, "recording", startGeo);
                        } catch (Throwable t) {
                            logger.warn("Continuous segment sidecar submit failed: "
                                    + t.getMessage());
                        }
                    }
                    if (newSegment != null) {
                        currentEventFile = newSegment;
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Could not register continuous segment listener: " + e.getMessage());
        }

        logger.info("Continuous recording started successfully");
    }

    /**
     * Stops a continuous-mode recording. Mirrors the close path in
     * {@link #stopRecording()} but skips the YOLO baseline update, timeline
     * flush, hero JPEG, and final-segment metadata sidecar — none of which
     * apply when motion was never analyzed.
     */
    private void stopContinuousRecording() {
        if (recorder == null || !recording) {
            return;
        }
        logger.info("Stopping continuous recording");

        // Snapshot the final-segment file + active geo BEFORE the close.
        // After stopEventRecording the recorder clears its state and we
        // lose the binding from "active recording" → "the file that just
        // finalized." The recorder's own close path will run its
        // filename-based sidecar submit, but it skips event_*.mp4 to
        // avoid double-writing for sentry — which means continuous-mode
        // (which intentionally reuses the event_* prefix) gets skipped
        // too. We submit explicitly here, mirroring the rotation
        // listener's behavior for the final segment.
        File finalSegment = currentEventFile;
        com.overdrive.app.geo.GeoSnapshot finalStartGeo = null;
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null && enc.hasStartGeo()) {
                finalStartGeo = new com.overdrive.app.geo.GeoSnapshot(
                        enc.getStartGeoLat(), enc.getStartGeoLng(),
                        enc.getStartGeoAccuracy(), enc.getStartGeoAgeMs(),
                        enc.getStartGeoCapturedAtMs(), 0L);
            }
        } catch (Throwable t) {
            logger.warn("Continuous final-segment geo snapshot failed: " + t.getMessage());
        }

        try {
            recorder.stopEventRecording(true, 0);
        } catch (Throwable t) {
            logger.warn("Continuous recorder stop error: " + t.getMessage());
        }
        // Mirror stop on the OEM dashcam pipeline if it was running this
        // continuous segment alongside pano (gate is in startContinuousRecording).
        try {
            com.overdrive.app.camera.OemDashcamPipeline oemPipe =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            // Only stop if (a) we owned it AND (b) the pipeline is still
            // the same instance we acquired ownership on. A rebuild between
            // start and stop (quality-mirror restart, ACC cycle) advances
            // the generation counter; the new instance's recording is
            // user-initiated, not ours.
            int curGen = com.overdrive.app.daemon.CameraDaemon
                .getOemDashcamPipelineGeneration();
            boolean canStop = oemEventOwned && curGen == oemEventOwnedGeneration;
            // Continuous segment ends on a hard boundary (no post-event
            // tail) — pass 0 so the OEM recorder finalizes immediately,
            // matching pano's stopEventRecording(true, 0) on this path.
            if (oemPipe != null) oemPipe.stopRecordingIfOwned(canStop, 0L);
            oemEventOwned = false;
            oemEventOwnedGeneration = -1;
        } catch (Throwable t) {
            logger.warn("OEM dashcam stop on continuous end failed: " + t.getMessage());
        }
        // Drop the segment listener that startContinuousRecording set so
        // a leftover closure can't fire during the gap between sessions.
        // Mirrors what the smart-mode close path does inside
        // stopRecording. If the next mode start is smart vs continuous,
        // its own setSegmentListener() will install the right one.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) enc.setSegmentListener(null);
        } catch (Throwable ignored) {}
        recording = false;
        currentEventFile = null;

        // Submit AFTER the close so the .mp4 has been renamed from .tmp.
        // LocationSidecarWriter is non-blocking — disk work happens on
        // its own background executor.
        if (finalSegment != null) {
            try {
                com.overdrive.app.geo.GeoSnapshot startGeo = finalStartGeo != null
                        ? finalStartGeo
                        : com.overdrive.app.geo.GeoSnapshot.empty();
                com.overdrive.app.geo.LocationSidecarWriter
                        .getInstance()
                        .submit(finalSegment, "recording", startGeo);
            } catch (Throwable t) {
                logger.warn("Continuous final-segment sidecar submit failed: " + t.getMessage());
            }
        }
    }

    /**
     * Starts recording an event with pre-record support.
     *
     * The encoder is always running and buffering frames. This method
     * triggers the flush of the pre-record buffer and starts writing to file.
     */
    private void startRecording() {
        if (recorder == null) {
            logger.error("Cannot start recording - recorder is null");
            return;
        }
        
        if (recording) {
            logger.debug("Already recording");
            return;
        }
        
        // SOTA: Storage cleanup happens off the trigger thread.
        // The periodic cleanup (StorageManager.startPeriodicCleanup, 30s cadence
        // with a 90%-of-limit threshold) is the steady-state mechanism. Doing
        // a synchronous scan + delete here was costing 100-200ms on the
        // motion-detection thread on near-full SD cards, which was the
        // dominant contributor to lag at motion onset. Two changes:
        //   1. SD-card mount check stays sync (cheap, microseconds, and we
        //      need a valid path before triggerEventRecording).
        //   2. ensureSurveillanceSpace fires on aiExecutor — a cheap noop if
        //      already under the limit, and worst case it runs in parallel
        //      with the recording itself. New recordings still write; old
        //      ones get pruned a beat later.
        com.overdrive.app.storage.StorageManager storageManager;
        try {
            storageManager = com.overdrive.app.storage.StorageManager.getInstance();
            com.overdrive.app.storage.StorageManager.StorageType type =
                    storageManager.getSurveillanceStorageType();
            if (type == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD &&
                    !storageManager.isSdCardMounted()) {
                logger.warn("SD card unmounted before recording - attempting remount");
                if (!storageManager.ensureSdCardMounted(true)) {
                    logger.error("SD card remount failed - event may write to stale path");
                }
            } else if (type == com.overdrive.app.storage.StorageManager.StorageType.USB &&
                    !storageManager.isUsbMounted()) {
                logger.warn("USB unmounted before recording - attempting remount");
                if (!storageManager.ensureUsbMounted(true)) {
                    logger.error("USB remount failed - event may write to stale path");
                }
            }
        } catch (Exception e) {
            logger.warn("Storage mount check failed: " + e.getMessage());
            storageManager = null;
        }
        final com.overdrive.app.storage.StorageManager smRef = storageManager;
        if (smRef != null) {
            aiExecutor.execute(() -> {
                try { smRef.ensureSurveillanceSpace(50 * 1024 * 1024); }
                catch (Exception e) { logger.warn("Async storage cleanup failed: " + e.getMessage()); }
            });
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "event_" + timestamp + ".mp4";
        // Re-read the live surveillance dir: enableSurveillance() may have
        // snapshotted the internal fallback during the boot mount-race, and the
        // SD/USB mount (incl. the smRef.ensure*Mounted attempt just above) may
        // have landed since. We MUST resolve LIVE (getLiveSurveillanceDir)
        // rather than read getSurveillanceDir(): the volatile surveillanceDir
        // field is frozen while the arm session is active (updateActiveDirectories
        // skips the surveillance branch), so getSurveillanceDir() would return
        // the stale internal fallback for a mount that landed after the bounded
        // enable-wait. getLiveSurveillanceDir() bypasses that freeze and falls
        // back to internal when the external is genuinely unavailable. Resolved
        // ONCE here (recording == false above), so the in-flight clip can't be
        // split across volumes; null-guard back to the snapshot.
        File trigDir = (smRef != null) ? smRef.getLiveSurveillanceDir() : eventOutputDir;
        if (trigDir == null) trigDir = eventOutputDir;
        // ENOSPC internal-spill: if the configured external is mounted-but-FULL,
        // redirect THIS event to the INTERNAL SURVEILLANCE dir so it isn't
        // quarantined as .broken on a packed card. Surveillance-bucket helper so
        // the spilled event_* clip stays in the surveillance reap pool.
        // Defensive: a throw returns trigDir unchanged.
        if (smRef != null && trigDir != null) {
            try {
                File spill = smRef.resolveSurveillanceTargetWithEnospcFallback(trigDir, 100L * 1024 * 1024);
                if (spill != null) trigDir = spill;
            } catch (Throwable t) {
                logger.warn("ENOSPC-fallback resolve failed (event): " + t.getMessage());
            }
        }
        if (trigDir != null && !trigDir.equals(eventOutputDir)) {
            eventOutputDir = trigDir;
        }
        currentEventFile = new File(trigDir, fileName);

        logger.info("Triggering event recording: " + currentEventFile.getAbsolutePath());
        logger.info(String.format("Pre-record: %d sec, Post-record: %d sec", 
                preRecordMs / 1000, postRecordMs / 1000));
        
        // Trigger event recording (flushes pre-record buffer).
        // Honor the boolean — savedFormat barrier can refuse on cold-boot
        // encoder warmup. See continuous-mode site above for rationale.
        if (!recorder.triggerEventRecording(currentEventFile.getAbsolutePath(), postRecordMs)) {
            logger.warn("Event triggerEventRecording refused (encoder format "
                + "not ready); event skipped, will fire on next motion");
            currentEventFile = null;
            return;
        }
        recording = true;
        // Fresh event → reset the latched peak severity. Each lastActors write
        // during this recording advances it; both Telegram stages read it.
        eventPeakSeverity = null;

        // OEM Dashcam parallel event recording. When the user has opted into
        // surveillance-driven OEM clips (oemDashcam.surveillance.enabled),
        // we ALSO trigger a recording on the OEM forward-sensor pipeline.
        //
        // Ownership: tryStartIfIdle returns true only when WE actually
        // opened the OEM clip. If OEM was already recording (user enabled
        // continuous mode), we MUST NOT stop it on event end — that would
        // finalize the user's clip prematurely. Track the result and
        // stopRecordingIfOwned matches the contract.
        oemEventOwned = false;
        oemEventOwnedGeneration = -1;
        try {
            // Read surveillanceMode directly — the legacy nested
            // oemDashcam.surveillance.enabled boolean is a one-way mirror of
            // surveillanceMode != off, kept in sync by the POST handler and the
            // migration. Reading the mode-tier accessor is the authoritative
            // path and survives any future drift between mirror and mode.
            String oemSurvMode = com.overdrive.app.config.UnifiedConfigManager
                .getOemSurveillanceMode();
            boolean oemSurvEnabled = !"off".equals(oemSurvMode);
            if (oemSurvEnabled) {
                com.overdrive.app.camera.OemDashcamPipeline oemPipe =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                if (oemPipe != null && oemPipe.isRunning()) {
                    // Capture the generation BEFORE start so a concurrent
                    // pipeline rebuild (quality-mirror restart) doesn't
                    // make us think we own a clip on a new instance.
                    int gen = com.overdrive.app.daemon.CameraDaemon
                        .getOemDashcamPipelineGeneration();
                    // Pass our configured post-window so the OEM clip's tail
                    // matches pano's. Pre-roll is handled by the pre-record
                    // ring inside the OEM encoder (sized off
                    // surveillance.preRecordSeconds at OEM init time).
                    boolean started = oemPipe.tryStartIfIdle(postRecordMs);
                    if (started) {
                        oemEventOwned = true;
                        oemEventOwnedGeneration = gen;
                    }
                    logger.info("OEM dashcam event recording: "
                        + (started ? "started (owned, gen=" + gen + ")"
                                   : "skipped (already in flight)"));
                } else {
                    logger.debug("OEM dashcam event recording skipped — pipeline not running");
                }
            }
        } catch (Throwable t) {
            // Surveillance must NEVER break because OEM dashcam threw; this
            // is a parallel sink, not a primary one.
            logger.warn("OEM dashcam event trigger failed: " + t.getMessage());
        }

        // Per-segment hero / sidecar plumbing.
        // The recorder rotates .mp4 files at SEGMENT_DURATION_MS (2 min) so a
        // long event spans multiple files. Without this listener, the
        // ThumbnailBuffer + EventTimelineCollector would only flush against
        // the FIRST segment's filename at stopRecording — segments 2..N would
        // appear in the recordings library as plain MP4s with no badges, no
        // actor counts, no hero thumbnail. With this listener, each rotated
        // segment gets its own coherent (hero JPEG, per-actor JPEGs, JSON
        // sidecar) tied to its own filename. The metadata buffers are reset
        // on rotation so segment N+1 collects independent state.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) {
                enc.setSegmentListener((closedSegment, newSegment) -> {
                    // CRITICAL: this listener fires on the
                    // GpuSegmentFinalizer-N thread, which is what
                    // waitForFinalizers blocks the recording-close and
                    // pipeline-shutdown paths on. Heavy work here
                    // (JPEG compress, fsync) directly extends close
                    // latency and risks the 2s waitForFinalizers timeout
                    // on multi-segment events.
                    //
                    // Ordering matters: the engine thread's observe() may
                    // be writing slots for segment N+1 frames concurrently
                    // with this listener firing. Drain the buffer's slot
                    // snapshot AS THE FIRST ACT so we minimize the window
                    // during which N+1 observations land in the
                    // closed-segment's snapshot. observe() and
                    // drainSnapshotForAsync are both synchronized on the
                    // buffer instance; whichever takes the monitor first
                    // wins. By draining first here we shrink the window
                    // proportional to whatever observe() calls were
                    // already in flight when the listener fired.
                    //
                    // After the drain, reset currentEventFile + timeline
                    // so subsequent observe() / timeline-collect calls
                    // attribute to N+1.
                    final java.util.List<ThumbnailBuffer.Slot> closedSnap;
                    final ThumbnailBuffer buf = thumbnailBuffer;
                    if (buf != null) {
                        closedSnap = buf.drainSnapshotForAsync();
                    } else {
                        closedSnap = java.util.Collections.emptyList();
                    }
                    // Capture closed-segment start time BEFORE the timeline
                    // collector restarts for N+1.
                    final long closedSegmentStartMs =
                            timelineCollector.getRecordingStartTimeMs();
                    final java.util.List<Actor> closedSegmentActors = lastActors;

                    // Update currentEventFile + reset timeline INLINE on
                    // the finalizer thread so the next segment's collector
                    // origin is correct from frame 1. This part is cheap
                    // (no I/O) and must not race the next rotation tick.
                    if (newSegment != null) {
                        currentEventFile = newSegment;
                        timelineCollector.startCollectingNoPreRing();
                    }

                    // Now hand off the closed segment's snapshot to the
                    // metadata flush. We pre-drained, so the flush method
                    // must not drain again — pass the snap directly via
                    // the segment-aware overload.
                    if (closedSegment != null) {
                        scheduleSegmentMetadataFlushWithSnapshot(
                                closedSegment, closedSegmentActors,
                                closedSegmentStartMs,
                                closedSnap,
                                /* syncHero = */ false);
                    }
                });
            }
        } catch (Exception e) {
            logger.warn("Could not register segment listener: " + e.getMessage());
        }
        
        // SOTA: Start timeline event collection for this recording.
        // Use the ACTUAL pre-record duration from the H.264 circular buffer, not the
        // configured preRecordMs. The circular buffer starts from the nearest keyframe,
        // which can be significantly longer than the configured pre-record window.
        // Example: configured preRecordMs=5000, but buffer flushed 14.1 sec of video.
        // If we use 5000ms as the origin, timeline events appear 9 seconds too early.
        long actualPreRecordMs = preRecordMs;
        try {
            HardwareEventRecorderGpu encoder = recorder.getEncoder();
            if (encoder != null) {
                long actual = encoder.getActualPreRecordDurationMs();
                if (actual > 0) {
                    actualPreRecordMs = actual;
                    logger.info("Timeline using actual pre-record duration: " + actual + "ms (configured: " + preRecordMs + "ms)");
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get actual pre-record duration: " + e.getMessage());
        }
        timelineCollector.startCollecting(actualPreRecordMs);
        
        logger.info("Event recording triggered successfully");
    }
    
    /**
     * Stops recording an event with post-record support.
     */
    private void stopRecording() {
        if (recorder == null || !recording) {
            return;
        }
        
        // SOTA: Update detection baseline from the last YOLO detections of this event.
        // This is the event-driven baseline update — zero extra inferences.
        // Only updates the quadrant where the event happened.
        // P1 #13: snapshot the slot once via getAndSet() so a late aiExecutor
        // lambda writing the same slot can't corrupt the value mid-read.
        if (lastEventQuadrant >= 0) {
            YoloPublication pub = lastYoloPublication.getAndSet(lastEventQuadrant, null);
            if (pub != null && pub.detections != null) {
                // updateFromEventEnd needs the bbox-coord-space dims that
                // produced these detections — pull from the publication so
                // a foveated event passes 640×640 instead of mosaic 320×240.
                int qH = pub.frameHeightPx > 0 ? pub.frameHeightPx : (THUMBNAIL_HEIGHT / 2);
                int qW = qH >= FOVEATED_CROP_SIZE_PX ? FOVEATED_CROP_SIZE_PX : (THUMBNAIL_WIDTH / 2);
                detectionBaseline.updateFromEventEnd(lastEventQuadrant, pub.detections, qW, qH);
            }
            lastEventQuadrant = -1;
        }
        
        // Stop immediately (post-record already handled by timeout). Synchronously
        // joins the encoder drainer thread, after which no further frames or
        // segment-rotation listener calls can fire.
        recorder.stopEventRecording(true, 0);
        // Symmetric OEM stop — fire-and-forget; never block pano teardown.
        try {
            com.overdrive.app.camera.OemDashcamPipeline oemPipe =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            // Only stop if (a) we owned it AND (b) the pipeline is still
            // the same instance we acquired ownership on. A rebuild between
            // start and stop (quality-mirror restart, ACC cycle) advances
            // the generation counter; the new instance's recording is
            // user-initiated, not ours.
            int curGen = com.overdrive.app.daemon.CameraDaemon
                .getOemDashcamPipelineGeneration();
            boolean canStop = oemEventOwned && curGen == oemEventOwnedGeneration;
            // Engine has already absorbed the post-record window via its
            // own loop (lastMotionTime + postRecordMs gate). Pass 0 so the
            // OEM recorder finalizes promptly, matching pano's behaviour.
            if (oemPipe != null) oemPipe.stopRecordingIfOwned(canStop, 0L);
            oemEventOwned = false;
            oemEventOwnedGeneration = -1;
        } catch (Throwable t) {
            logger.warn("OEM dashcam stop on event end failed: " + t.getMessage());
        }
        recording = false;
        lastRecordingStopTime = System.currentTimeMillis();  // Track when we stopped
        // Hygiene: clear the trigger-start clock on every stop path so a
        // stale value can never leak into the next event's hard-ceiling
        // calculation. Triggers always overwrite this with `now` before
        // calling startRecording(), so the reset here is belt-and-braces —
        // protects against future code that might call stopRecording without
        // a paired trigger reset.
        recordingTriggerStartMs = 0;

        // FIX (B1/H-a): bump the generation counter NOW — before the publish
        // path reads lastActors. Any aiExecutor lambda still in flight will,
        // when it completes, observe gen != generationAtSchedule and skip its
        // writes (Actor/Thumbnail/lastActors mutation). Without this fence,
        // a late lambda landing between the recorder stop and publishMotionFinal
        // could overwrite lastActors, making the notification mis-attribute
        // the event. The actorTracker.reset() / lastActors = empty / clear()
        // calls below run AFTER publish so the publish path sees the snapshot
        // that was current at recorder-stop time.
        recordingGeneration.incrementAndGet();

        if (currentEventFile != null && currentEventFile.exists()) {
            logger.info( String.format("Saved: %s (%d KB)",
                    currentEventFile.getName(), currentEventFile.length() / 1024));

            // Flush metadata for the FINAL segment. Earlier segments were
            // scheduled via the rotation listener and run on
            // segmentMetadataExecutor in the background.
            //
            // SOTA split: scheduleSegmentMetadataFlush now writes the hero
            // synchronously on this thread (so the publish path below sees
            // a deterministic on-disk hero file) and only schedules the
            // per-actor JPEGs asynchronously. No drainSegmentMetadata is
            // needed before publish — the hero is already on disk by the
            // time flushSegmentMetadata returns.
            flushSegmentMetadata(currentEventFile);

            // Two-stage notification: replace the "Recording in progress…"
            // banner that fired at recording start with the rich threat
            // summary + hero image. Same notification tag → OS replaces, not
            // stacks. Telegram gets the equivalent rich path with photo.
            // Use the final segment's metadata for the user-facing notif —
            // earlier segments already published their own banners on close.
            String videoName = currentEventFile.getName();
            String heroSibling = videoName.replace(".mp4", ".jpg");
            File heroSiblingFile = new File(currentEventFile.getParentFile(), heroSibling);

            // If ThumbnailBuffer didn't write a YOLO-derived hero (no actor
            // ever classified during this event — e.g. very short motion-only
            // clips, or every actor was filtered out as static-NOTICE
            // background) fall back to a single keyframe extracted from the
            // recorded MP4 so Telegram and the PWA push always have an image
            // to show. Without this, the user gets a text-only "Motion
            // detected" alert with no preview, which looks broken.
            //
            // Wrapped in a per-call timeout: MediaMetadataRetriever can
            // hang indefinitely on a malformed mp4 (truncated moov, corrupt
            // sample tables — exactly what a SIGKILL boundary or SD-card
            // unmount mid-write produces). Without the timeout, this path
            // would block the engine thread forever, wedging stop +
            // disable + shutdown. Same pattern as sweepOrphanHeroThumbnails
            // (5s budget, daemon worker, accept thread leak on hang).
            if (!heroSiblingFile.exists()) {
                writeFallbackHeroWithTimeout(currentEventFile, heroSiblingFile, 5_000L);
            }

            String heroName = heroSiblingFile.exists() ? heroSibling : null;
            String heroPath = heroSiblingFile.exists() ? heroSiblingFile.getAbsolutePath() : null;
            // Per-event dedup: stopRecording() can be entered twice for one
            // event if disable() (control thread) and the engine post-record
            // stop interleave before either clears `recording`. The final
            // notifications have no internal dedup (the OS-push tag covers only
            // the push, not Telegram), so a double entry would double-send the
            // photo + full-clip upload. Claim the event by filename so only the
            // first caller emits — leaves the `recording` lifecycle flag alone
            // (clearing it early would risk a new overlap-start race).
            // Atomic claim: getAndSet returns the PREVIOUS value; only the
            // caller that finds it != videoName proceeds, so two interleaving
            // stopRecording() entries for the same event can't both emit.
            if (!videoName.equals(lastFinalNotifiedEvent.getAndSet(videoName))) {
                try { publishMotionFinal(videoName, heroName); }
                catch (Throwable t) { logger.debug("publishMotionFinal threw: " + t.getMessage()); }
                try { sendFinalTelegramNotification(videoName, heroPath); }
                catch (Throwable t) { logger.debug("sendFinalTelegramNotification threw: " + t.getMessage()); }
            } else {
                logger.debug("Final notification already sent for " + videoName + "; skipping duplicate");
            }
        }

        // Detach the segment listener so a stale lambda from a previous event
        // can't reset state for the next event's segments.
        try {
            HardwareEventRecorderGpu enc = recorder.getEncoder();
            if (enc != null) enc.setSegmentListener(null);
        } catch (Exception ignored) {}

        currentEventFile = null;
        // Reset Actor state for the next event so IDs / dwell windows don't leak across recordings
        actorTracker.reset();
        lastActors = java.util.Collections.emptyList();
        if (thumbnailBuffer != null) thumbnailBuffer.clear();
        logger.info("Recording stopped, motion detection continues");
    }

    /**
     * Write hero JPEG + per-actor thumbnails + JSON timeline sidecar
     * alongside the given .mp4 segment. Used by both stopRecording (final
     * segment) and the rotation listener (intermediate segments). Resets
     * {@link #thumbnailBuffer} and {@link #timelineCollector} after writing
     * so the next segment starts with empty metadata buffers.
     *
     * Idempotent and exception-safe — failures are logged but never
     * thrown to the caller, since rotation can't be aborted by a hero
     * write failure.
     */
    /**
     * Schedule the segment metadata flush (thumbnails + JSON + SRT) for
     * the given closed segment. Snapshots all engine state needed for the
     * write at dispatch time so the actual I/O can happen on
     * {@link #segmentMetadataExecutor} without racing the next segment's
     * mutations.
     *
     * <p>The JSON+SRT sidecar write is dispatched synchronously here (it
     * already routes onto its own writeExecutor inside the timeline
     * collector — cheap on this thread). The thumbnail JPEG compress
     * (the actually-expensive part) is what runs async.
     *
     * <p>Called from the segment-rotation listener and from
     * {@link #stopRecording}'s final-segment flush. The close path drains
     * via {@link #drainSegmentMetadata} before returning.
     */
    /**
     * @param syncHero  true when the caller is the publish path
     *                  ({@link #flushSegmentMetadata} from {@link #stopRecording})
     *                  and the hero JPEG MUST be on disk before this method
     *                  returns. false (rotation-listener path) lets the hero
     *                  go to {@link #segmentMetadataExecutor} so the
     *                  GpuSegmentFinalizer thread returns immediately.
     *                  Explicit parameter — previously a volatile flag, but
     *                  that introduced a cross-thread race where the rotation
     *                  listener could consume the flag intended for the
     *                  publish path, sending the publish-segment's hero to
     *                  the executor and re-introducing the mosaic-fallback
     *                  symptom under multi-segment events.
     */
    private void scheduleSegmentMetadataFlush(File segmentMp4,
                                              java.util.List<Actor> actorsAtRotation,
                                              long segmentStartMs,
                                              boolean syncHero) {
        // Drain on caller thread, then delegate. Used ONLY by the publish
        // path (stopRecording → flushSegmentMetadata). By the time the
        // publish path runs, recorder.stopEventRecording has already
        // joined the encoder drainer, so no further frames will trigger
        // observe() — the buffer is quiescent. Stop-during-rotation: if
        // a rotation listener fired right before stopRecording, it has
        // ALREADY drained on its own thread (synchronized) — by the time
        // we get here, slots map holds whatever observe() landed during
        // the brief window between listener-release and recorder-stop.
        // That's the FINAL segment's authentic slot set; not a race loser.
        java.util.List<ThumbnailBuffer.Slot> snap;
        ThumbnailBuffer buf = thumbnailBuffer;
        if (buf != null) {
            snap = buf.drainSnapshotForAsync();
        } else {
            snap = java.util.Collections.emptyList();
        }
        scheduleSegmentMetadataFlushWithSnapshot(segmentMp4, actorsAtRotation,
                segmentStartMs, snap, syncHero);
    }

    /**
     * Variant that takes a pre-drained slot snapshot. Used by the
     * rotation-listener path so the drain happens AS THE FIRST ACT on the
     * finalizer thread (minimizing the window during which observe() for
     * segment N+1 can pollute the closed segment's snapshot).
     */
    private void scheduleSegmentMetadataFlushWithSnapshot(
            File segmentMp4,
            java.util.List<Actor> actorsAtRotation,
            long segmentStartMs,
            java.util.List<ThumbnailBuffer.Slot> snap,
            boolean syncHero) {
        if (segmentMp4 == null) return;

        // Renormalize peak times against this segment's window — same logic
        // as the inline path used to do.
        final java.util.List<Actor> segmentActors;
        if (actorsAtRotation == null || actorsAtRotation.isEmpty() || segmentStartMs <= 0) {
            segmentActors = actorsAtRotation;
        } else {
            java.util.List<Actor> renormalized = new java.util.ArrayList<>(actorsAtRotation.size());
            for (Actor a : actorsAtRotation) {
                long renormalizedRelMs;
                if (a.peakSeverityWallMs > 0 && a.peakSeverityWallMs >= segmentStartMs) {
                    renormalizedRelMs = a.peakSeverityWallMs - segmentStartMs;
                } else {
                    renormalizedRelMs = -1L;
                }
                if (renormalizedRelMs == a.peakSeverityRelMs) {
                    renormalized.add(a);
                } else {
                    renormalized.add(new Actor(
                            a.actorId, a.classGroup,
                            a.firstSeenWallMs, a.lastSeenWallMs,
                            a.firstSeenRelMs, a.lastSeenRelMs,
                            a.cameraMask,
                            a.peakProximity, a.lastProximity,
                            a.trend, a.isStatic,
                            a.peakSeverity, a.peakSeverityWallMs, renormalizedRelMs,
                            a.peakConfidence,
                            a.peakBboxX, a.peakBboxY, a.peakBboxW, a.peakBboxH,
                            a.peakBboxQuadW, a.peakBboxQuadH, a.peakCamera,
                            a.lastBboxX, a.lastBboxY, a.lastBboxW, a.lastBboxH,
                            a.lastCamera));
                }
            }
            segmentActors = renormalized;
        }

        // Compute the deterministic hero filename now (we don't yet know
        // whether ThumbnailBuffer will produce one, but the JSON sidecar
        // can record the filename it WILL have if produced).
        String base = segmentMp4.getName();
        if (base.endsWith(".mp4")) base = base.substring(0, base.length() - 4);
        final String expectedHeroName = base + ".jpg";

        // Build the actorId → relMs map up front (cheap; pure read).
        final java.util.Map<Long, Long> relMap = new java.util.HashMap<>();
        if (segmentActors != null) {
            for (Actor a : segmentActors) {
                if (a.peakSeverityRelMs >= 0) {
                    relMap.put(a.actorId, a.peakSeverityRelMs);
                }
            }
        }

        // SOTA split: hero is load-bearing (publish notification reads it
        // directly), per-actor JPEGs are decorative (only the events page
        // consumes them). Doing both in the same async task forced the
        // notification path to block on drainSegmentMetadata(2s) waiting for
        // ALL JPEGs to finish — under multi-actor events the drain timed
        // out, the fallback (mp4 keyframe = 2×2 mosaic) fired, the
        // notification went out with the wrong image, and only later did
        // the proper YOLO hero overwrite the file. Result: users saw the
        // mosaic in PWA / Telegram pushes despite a valid YOLO hero
        // existing on disk seconds afterwards.
        //
        // The snapshot was already drained by the caller (this is the
        // {@code WithSnapshot} variant). Ordering:
        //   1. If syncHero=true (publish path), write the hero inline.
        //      Otherwise (rotation listener) schedule it on the executor
        //      so the finalizer thread returns immediately.
        //   2. Per-actor JPEGs always go to the executor.
        ThumbnailBuffer bufferAtDispatch = thumbnailBuffer;
        if (bufferAtDispatch != null && snap != null) {
            // Step 2: hero. Whether sync or async depends on the explicit
            // syncHero parameter. The publish path passes true; the
            // rotation listener passes false. Per-call argument means
            // concurrent rotation+stop callers can't steal each other's
            // signal — the previous volatile flag had exactly that race.
            if (syncHero) {
                File heroFile;
                try {
                    // Window-gate the hero so it can't depict a peak frame evicted
                    // from the bounded pre-record ring (segmentStartMs is this
                    // segment's window start; open upper bound = still finalizing).
                    heroFile = bufferAtDispatch.writeHeroFromSnapshot(snap, segmentMp4, segmentStartMs, 0L);
                } catch (Throwable t) {
                    logger.warn("Hero thumbnail sync write failed for "
                            + segmentMp4.getName() + ": " + t.getMessage());
                    heroFile = null;
                }
                if (heroFile != null) {
                    logger.info("Hero thumbnail (" + segmentMp4.getName() + "): "
                            + heroFile.getName());
                }
            } else if (!snap.isEmpty()) {
                // Async hero path (rotation listener — no publish blocking
                // on this segment). Schedule on the executor so we return
                // off the GpuSegmentFinalizer-N thread fast.
                inFlightSegmentMetadata.incrementAndGet();
                final ThumbnailBuffer bufFinal = bufferAtDispatch;
                final long heroWindowStartMs = segmentStartMs;
                segmentMetadataExecutor.execute(() -> {
                    try {
                        File heroFile = bufFinal.writeHeroFromSnapshot(snap, segmentMp4, heroWindowStartMs, 0L);
                        if (heroFile != null) {
                            logger.info("Hero thumbnail (" + segmentMp4.getName() + "): "
                                    + heroFile.getName());
                        }
                    } catch (Throwable t) {
                        logger.warn("Hero thumbnail async write failed for "
                                + segmentMp4.getName() + ": " + t.getMessage());
                    } finally {
                        inFlightSegmentMetadata.decrementAndGet();
                        synchronized (segmentMetadataDrainLock) {
                            segmentMetadataDrainLock.notifyAll();
                        }
                    }
                });
            }

            // Step 3: per-actor JPEGs always async.
            if (!snap.isEmpty()) {
                inFlightSegmentMetadata.incrementAndGet();
                final ThumbnailBuffer bufFinal = bufferAtDispatch;
                segmentMetadataExecutor.execute(() -> {
                    try {
                        File parent = segmentMp4.getParentFile();
                        if (parent != null) {
                            String tmpBase = segmentMp4.getName();
                            if (tmpBase.endsWith(".mp4")) {
                                tmpBase = tmpBase.substring(0, tmpBase.length() - 4);
                            }
                            for (ThumbnailBuffer.Slot s : snap) {
                                try {
                                    Long relBoxed = relMap.get(s.actorId);
                                    long rel = relBoxed != null ? relBoxed : -1L;
                                    String jpegName = "thumb_" + tmpBase + "_a" + s.actorId
                                            + (rel >= 0 ? ("_" + rel) : "") + ".jpg";
                                    File jpeg = new File(parent, jpegName);
                                    bufFinal.writePerActorJpeg(s, jpeg);
                                } catch (Exception e) {
                                    logger.warn("Per-actor thumb write failed: " + e.getMessage());
                                }
                            }
                        }
                    } finally {
                        inFlightSegmentMetadata.decrementAndGet();
                        synchronized (segmentMetadataDrainLock) {
                            segmentMetadataDrainLock.notifyAll();
                        }
                    }
                });
            }
        }

        // The JSON+SRT sidecar's stopAndWrite ALREADY routes file I/O
        // through its own writeExecutor — cheap to call inline here.
        // Calling it on the dispatch thread (rotation listener or
        // stopRecording) means the timeline collector's collecting=false
        // gate flips immediately, so the next segment's
        // startCollectingNoPreRing call observes the correct state.
        try {
            // Build geo snapshots from the recorder's captured startGeo
            // fields plus the peak-actor's wall-clock instant. The end
            // snapshot is captured inside stopAndWrite at executor-dispatch
            // time so it reflects the moment of finalize.
            //
            // syncHero==true means "publish path / final segment" — read
            // the active startGeo*. syncHero==false means "rotation
            // listener / closed segment" — read closedStartGeo* which
            // rotateSegmentLocked stashed before refreshing the active
            // fields for the new segment. Without this distinction every
            // rotated segment's geo.start would carry the NEXT segment's
            // GPS, misattributing location on multi-segment recordings.
            com.overdrive.app.geo.GeoSnapshot startGeo =
                    com.overdrive.app.geo.GeoSnapshot.empty();
            com.overdrive.app.geo.GeoSnapshot peakGeo  = null;
            try {
                HardwareEventRecorderGpu enc = (recorder != null) ? recorder.getEncoder() : null;
                if (enc != null) {
                    if (syncHero && enc.hasStartGeo()) {
                        startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                enc.getStartGeoLat(), enc.getStartGeoLng(),
                                enc.getStartGeoAccuracy(), enc.getStartGeoAgeMs(),
                                enc.getStartGeoCapturedAtMs(), 0L);
                    } else if (!syncHero && enc.hasClosedStartGeo()) {
                        startGeo = new com.overdrive.app.geo.GeoSnapshot(
                                enc.getClosedStartGeoLat(), enc.getClosedStartGeoLng(),
                                enc.getClosedStartGeoAccuracy(), enc.getClosedStartGeoAgeMs(),
                                enc.getClosedStartGeoCapturedAtMs(), 0L);
                    }
                }
            } catch (Throwable t) {
                logger.warn("startGeo lookup failed: " + t.getMessage());
            }
            // Peak: the threat actor is the highest-severity non-static
            // entry in segmentActors. Use peakSeverityRelMs (already
            // renormalized to this segment's window earlier in this
            // method). Capturing GPS at THIS moment is the closest we'll
            // get to the threat-time location in real time, since we don't
            // log lat/lng per-frame in the engine. For "at-rest" parked
            // surveillance this collapses to startGeo, which is correct.
            if (segmentActors != null) {
                long peakRelMs = -1L;
                for (Actor a : segmentActors) {
                    if (a == null || a.isStatic) continue;
                    if (a.peakSeverityRelMs >= 0
                            && (peakRelMs < 0 || a.peakSeverityRelMs > peakRelMs)) {
                        peakRelMs = a.peakSeverityRelMs;
                    }
                }
                if (peakRelMs >= 0) {
                    peakGeo = com.overdrive.app.geo.GeoSnapshot.capture(peakRelMs);
                }
            }
            timelineCollector.stopAndWrite(segmentMp4, segmentActors,
                    expectedHeroName, startGeo, peakGeo, /* endGeo = capture inside */ null);
        } catch (Exception e) {
            logger.warn("Timeline write failed for " + segmentMp4.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Wait until all queued segment-metadata writes have finished. Called
     * by the close path so a stopRecording → daemon-shutdown sequence
     * doesn't lose the last segment's hero thumbnail. Bounded by
     * timeoutMs; logs and returns false if we time out (the daemon will
     * still proceed with shutdown, the metadata may be corrupt — but the
     * partial state on disk is recoverable on next launch).
     */
    private boolean drainSegmentMetadata(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (segmentMetadataDrainLock) {
            while (inFlightSegmentMetadata.get() > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    logger.warn("drainSegmentMetadata timed out with "
                            + inFlightSegmentMetadata.get() + " in flight");
                    return false;
                }
                try { segmentMetadataDrainLock.wait(remaining); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * FINAL-segment flush in {@link #stopRecording}. Snapshots
     * {@code lastActors} + the timeline collector's current start time, then
     * delegates to {@link #scheduleSegmentMetadataFlush} with
     * {@code syncHero=true} so the hero JPEG is written inline on this
     * thread — the publish path immediately after needs the file on disk
     * before sending notifications. The rotation-listener path passes
     * {@code syncHero=false}; rotated segments' heroes go to the
     * background executor so the GpuSegmentFinalizer-N thread returns fast.
     */
    private void flushSegmentMetadata(File segmentMp4) {
        if (segmentMp4 == null) return;
        // Publish path: hero MUST be on disk before stopRecording proceeds
        // to publishMotionFinal / sendFinalTelegramNotification, so write
        // it inline on this thread.
        scheduleSegmentMetadataFlush(segmentMp4, lastActors,
                timelineCollector.getRecordingStartTimeMs(),
                /* syncHero = */ true);
    }
    
    /**
     * Enables surveillance (starts monitoring).
     */
    public void enable() {
        // RACE CONDITION FIX (defense in depth): Final guard at the engine level.
        // If ACC is ON, refuse to enable. This catches any edge case where the
        // higher-level guards in CameraDaemon/AccSentryDaemon were bypassed.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn(">>> Surveillance enable REJECTED at engine level — ACC is ON");
            return;
        }

        // Latch ACC-OFF mode from unified config. forceReload because the
        // setter writes go through the app process; the daemon's UCM cache
        // would otherwise see a stale value on the first enable() after a
        // user toggle.
        try {
            org.json.JSONObject surv = com.overdrive.app.config.UnifiedConfigManager
                    .forceReload().optJSONObject("surveillance");
            String mode = (surv != null) ? surv.optString("accOffMode", "smart") : "smart";
            continuousMode = "continuous".equals(mode);
        } catch (Throwable t) {
            continuousMode = false;
        }

        if (continuousMode) {
            // Plain rolling 4-cam recording: skip motion + YOLO + baseline
            // entirely. NativeMotion is not required, so don't gate on it.
            logger.info("Enabling surveillance engine (CONTINUOUS — no motion, no AI)");
            active = true;
            try {
                com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(true);
            } catch (Exception e) {
                logger.warn("Could not set surveillance active state: " + e.getMessage());
            }
            startContinuousRecording();
            return;
        }

        // Check if native library is loaded
        if (!NativeMotion.isLibraryLoaded()) {
            logger.error(">>> Cannot enable surveillance: NativeMotion library not loaded! Error: " +
                NativeMotion.getLoadError());
            return;
        }

        logger.info("Enabling surveillance engine (pipelineV2=" + (pipelineV2 != null) +
            ", pipelineV2init=" + (pipelineV2 != null && pipelineV2.isInitialized()) + ")");

        active = true;
        frameCount = 0;
        motionDetections = 0;
        firstMotionTime = 0;  // Reset sustained motion timer
        deterrentFiredTime = 0;  // Reset deterrent suppression
        lastAiConfirmationTimeMs = 0;  // Reset AI confirmation gate
        peakThreatDuringSequence = 0;
        cachedHighIsTrusted = false;  // Reset flag/shadow HIGH-trust latch
        cachedIncoherentLoiter = false;  // Reset confirmed-incoherent-loiter latch
        
        // Reset post-suppression baseline refresh tracking
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            framesSinceSuppressionEnded[q] = 0;
            suppressionWasActive[q] = false;
            baselineRefreshQueued[q] = false;
        }
        
        // Reset SOTA tracking variables
        lastTemporalBlocksCount = 0;
        lastMotionMinY = 0;
        lastMotionMaxY = 0;
        lastEstimatedDistance = 0;
        
        // SOTA: Notify StorageManager that surveillance is active (for periodic cleanup)
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(true);
        } catch (Exception e) {
            logger.warn("Could not set surveillance active state: " + e.getMessage());
        }
        
        // V2: Re-initialize pipeline for clean start
        if (pipelineV2 != null) {
            try {
                NativeMotion.initPipelineV2();
                logger.info("V2 pipeline reset for new surveillance session");
            } catch (Exception e) {
                logger.warn("V2 pipeline reset failed: " + e.getMessage());
            }
        }
        
        // Reset cross-quadrant tracker for clean session
        crossQuadrantTracker.reset();

        // Reset Actor layer too — fresh ID space for each session
        actorTracker.reset();
        lastActors = java.util.Collections.emptyList();
        
        // Reset detection baseline for clean session
        detectionBaseline.reset();
        baselineSeeded = false;
        // Clear ALL per-quadrant proximity-related state so a new session
        // doesn't inherit a 30-second-old prevLowestBlockY (which combined
        // with a fresh nowMs would compute a bogus APPROACHING/RECEDING
        // trend on the very first tick). See audit re-pass session-residual.
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastYoloPublication.set(q, null);
            cachedTrend[q] = DistanceEstimator.Trend.UNKNOWN;
            prevLowestBlockY[q] = -1;
            prevLowestBlockYAtMs[q] = 0;
        }
        lastEventQuadrant = -1;

        // Foveated mailbox cleanup. Without this, a request flag set by the
        // FINAL frames of the previous surveillance session (a YOLO confirmer
        // that asked for a foveated crop ~ms before disable) survives the
        // ACC-OFF→ON→OFF window and fires on the next session's first tick —
        // dispatching a crop with the prior session's centroid coordinates
        // against this session's first frame. The per-poll 500ms staleness
        // check bounds the impact (consumers reject the result), but the
        // gl-thread service still runs the wasted readback. Cheap to clear.
        synchronized (foveatedRequestLock) {
            for (int q = 0; q < FOVEATED_NUM_QUADRANTS; q++) {
                foveatedRequested[q] = false;
                foveatedReqCentroidX[q] = 0f;
                foveatedReqCentroidY[q] = 0f;
                foveatedSlots[q].set(null);
            }
        }
        foveatedRoundRobin = 0;
        lastFoveatedServiceNs = 0L;

        // Apply per-quadrant ROI from persisted config
        if (config != null) {
            for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
                if (config.isRoiEnabled(q) && config.getRoiPolygon(q) != null) {
                    applyQuadrantRoi(q, config.getRoiPolygon(q));
                } else {
                    // Also check for direct block masks in unified config
                    try {
                        org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                        String[] qKeys = {"Q0", "Q1", "Q2", "Q3"};
                        boolean roiEnabled = survCfg.optBoolean("roiEnabled_" + qKeys[q], false);
                        org.json.JSONArray blockArr = survCfg.optJSONArray("roiBlocks_" + qKeys[q]);
                        if (roiEnabled && blockArr != null && blockArr.length() == MotionPipelineV2.TOTAL_BLOCKS) {
                            byte[] blockMask = new byte[MotionPipelineV2.TOTAL_BLOCKS];
                            for (int i = 0; i < MotionPipelineV2.TOTAL_BLOCKS; i++) {
                                blockMask[i] = (byte)(blockArr.optInt(i, 1) != 0 ? 1 : 0);
                            }
                            NativeMotion.setQuadrantRoi(q, blockMask);
                            logger.info("ROI blocks loaded for Q" + q + " from persisted config");
                        } else {
                            clearQuadrantRoi(q);
                        }
                    } catch (Exception e) {
                        clearQuadrantRoi(q);
                    }
                }
            }
        }
        
        // Initialize native texture tracker (YOLO + NCC hybrid VOT)
        try {
            NativeMotion.initTracker();
            logger.info("Texture tracker initialized (YOLO + NCC hybrid)");
        } catch (Exception e) {
            logger.warn("Texture tracker init failed: " + e.getMessage());
        }
        
        logger.info("Surveillance enabled (V2 per-quadrant pipeline)");
    }
    
    /**
     * Disables surveillance (stops monitoring).
     */
    public void disable() {
        if (continuousMode) {
            // Continuous-mode disable: no motion / YOLO state to drain and
            // no event-end baseline update to run. Just stop the recorder
            // and flip flags. continuousMode itself stays true so a stale
            // late-arriving processFrame still no-ops via active=false.
            if (recording) {
                stopContinuousRecording();
            }
            active = false;
            inActiveMode = false;
            try {
                com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(false);
            } catch (Exception e) {
                logger.warn("Could not set surveillance inactive state: " + e.getMessage());
            }
            // Clear the latch so the next enable() reads the latest config
            // (user may have toggled back to smart while ACC was on).
            continuousMode = false;
            logger.info("Surveillance disabled (continuous)");
            return;
        }
        if (recording) {
            stopRecording();
        }
        active = false;
        inActiveMode = false;

        // Tear down any in-progress screen deterrent. cancel() is non-blocking;
        // the render thread (in this same process) sees the flag, releases its
        // surface, and turns the backlight off in its finally block.
        try {
            ScreenDeterrent.getInstance().cancel();
        } catch (Throwable ignored) {}

        // P1 #15: cancel pending YOLO work and clear shared state BEFORE
        // resetting the baseline. Without this, an aiExecutor lambda already
        // mid-flight can still write lastActors / lastYoloPublication after
        // disable returns, polluting the next session's first frames.
        // Same set of arrays cleared as in enable() (audit re-pass
        // session-residual): YoloPublication, cachedTrend, prevLowestBlockY,
        // prevLowestBlockYAtMs.
        aiQuadrantQueueClear();
        for (int q = 0; q < MotionPipelineV2.NUM_QUADRANTS; q++) {
            lastYoloPublication.set(q, null);
            cachedTrend[q] = DistanceEstimator.Trend.UNKNOWN;
            prevLowestBlockY[q] = -1;
            prevLowestBlockYAtMs[q] = 0;
        }
        // Foveated mailbox cleanup — symmetric with enable(). A request flag
        // set in the last few frames before disable would otherwise persist
        // through ACC-ON and fire stale on the next session's first service
        // pass with the previous session's centroid coordinates.
        synchronized (foveatedRequestLock) {
            for (int q = 0; q < FOVEATED_NUM_QUADRANTS; q++) {
                foveatedRequested[q] = false;
                foveatedReqCentroidX[q] = 0f;
                foveatedReqCentroidY[q] = 0f;
                foveatedSlots[q].set(null);
            }
        }
        foveatedRoundRobin = 0;
        lastFoveatedServiceNs = 0L;
        // Brief drain so any inference already running observes active=false
        // and skips its writes. Bounded to 50ms — disable() is on the daemon
        // thread; we don't want to block the caller for a full inference.
        try {
            long drainDeadline = System.currentTimeMillis() + 50;
            while (isAiRunning.get() && System.currentTimeMillis() < drainDeadline) {
                Thread.sleep(5);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // SOTA: Notify StorageManager that surveillance is inactive
        try {
            com.overdrive.app.storage.StorageManager.getInstance().setSurveillanceActive(false);
        } catch (Exception e) {
            logger.warn("Could not set surveillance inactive state: " + e.getMessage());
        }

        // Reset detection baseline for clean session
        detectionBaseline.reset();
        baselineSeeded = false;

        logger.info("Surveillance disabled");
    }
    
    /**
     * Checks if surveillance is active.
     * 
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * @return true when surveillance is in CONTINUOUS (always-record) ACC-OFF
     * mode, where motion detection / YOLO / mosaic readback are NOT used —
     * recording is fed directly by the GL→encoder surface chain. The AI lane
     * has no consumer in this mode, so the pipeline skips bringing it up.
     */
    public boolean isContinuousMode() {
        return continuousMode;
    }

    /**
     * Checks if currently recording.
     *
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * Checks if in active mode (heavy AI).
     * 
     * @return true if in active mode, false if idle
     */
    public boolean isInActiveMode() {
        return inActiveMode;
    }
    
    /**
     * Gets the current SAD threshold.
     * 
     * @return Threshold value (0.0-1.0)
     */
    public float getSadThreshold() {
        return config.getSensitivity();
    }
    
    /**
     * Gets the grid motion block sensitivity.
     * 
     * @return Sensitivity value (0.0-1.0, typically 0.04-0.10)
     */
    public float getBlockSensitivity() {
        return config.getSensitivity();
    }
    
    /**
     * Sets the grid motion block sensitivity.
     * Lower values detect more distant/subtle motion.
     * 
     * @param sensitivity Sensitivity value (0.01-0.20, default 0.04)
     */
    public void setBlockSensitivity(float sensitivity) {
        float clamped = Math.max(0.01f, Math.min(0.20f, sensitivity));
        config.setSensitivity(clamped);
        logger.info("Block sensitivity set to: " + clamped);
    }
    
    /**
     * Sets the unified motion sensitivity (0-100%).
     * 
     * This is the recommended API for controlling motion detection.
     * A single slider that intelligently adjusts:
     * - Density Threshold: How many pixels must change per block
     * - Alarm Threshold: How many blocks must trigger to start recording
     * 
     * Mapping:
     * - 0-30%:   LOW (large/close objects only)
     * - 31-60%:  MEDIUM (balanced, default)
     * - 61-80%:  HIGH (detects distant objects)
     * - 81-100%: VERY HIGH (any motion)
     * 
     * @param sensitivity 0-100 percentage
     */
    public void setUnifiedSensitivity(int sensitivity) {
        config.setUnifiedSensitivity(sensitivity);
        
        // Sync legacy fields for backward compatibility
        this.requiredActiveBlocks = config.getAlarmBlockThreshold();
        
        logger.info(String.format("Unified sensitivity set to: %d%% (alarm=%d blocks, density=%d pixels, shadow=%d)",
                sensitivity, config.getAlarmBlockThreshold(), config.getDensityThreshold(), config.getShadowThreshold()));
    }
    
    /**
     * Gets the unified motion sensitivity (0-100%).
     * 
     * @return Sensitivity percentage
     */
    public int getUnifiedSensitivity() {
        return config.getUnifiedSensitivity();
    }
    
    /**
     * Sets night mode (affects shadow threshold).
     * 
     * Night mode uses a higher shadow threshold (40 vs 25) to filter
     * out headlight reflections and other light artifacts.
     * 
     * @param enabled true for night mode
     */
    public void setNightMode(boolean enabled) {
        config.setNightMode(enabled);
        // Log the V2 pipeline's actual shadow threshold (not the legacy config's)
        int v2Shadow = pipelineV2Config != null ? pipelineV2Config.shadowThreshold : -1;
        int v2Filter = pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : -1;
        logger.info("Night mode set to: " + enabled + 
                " (V2 shadow threshold=" + v2Shadow + ", filter=" + v2Filter + ")");
    }
    
    /**
     * Gets night mode state.
     * 
     * @return true if night mode is enabled
     */
    public boolean isNightMode() {
        return config.isNightMode();
    }
    
    /**
     * Gets the required active blocks threshold.
     * 
     * @return Number of blocks required to trigger motion
     */
    public int getRequiredActiveBlocks() {
        return requiredActiveBlocks;
    }
    
    /**
     * Sets the required active blocks threshold.
     * Lower values are more sensitive to small/distant motion.
     * 
     * @param blocks Number of blocks (1-10, default 2)
     */
    public void setRequiredActiveBlocks(int blocks) {
        this.requiredActiveBlocks = Math.max(1, Math.min(10, blocks));
        // Sync with SOTA config
        config.setRequiredBlocks(this.requiredActiveBlocks);
        logger.info("Required active blocks set to: " + this.requiredActiveBlocks);
    }
    
    /**
     * Gets the flash immunity level.
     * 
     * @return Flash immunity level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
     */
    public int getFlashImmunity() {
        return flashImmunity;
    }
    
    /**
     * Gets the minimum object size for detection.
     * 
     * @return Minimum object size as fraction of frame (0.02 = 2% = ~15m, 0.20 = 20% = ~3m)
     */
    public float getMinObjectSize() {
        return minObjectSize;
    }
    
    /**
     * Sets the flash immunity level.
     * 
     * Uses edge-based detection to ignore light flashes (headlights, lightning, etc.)
     * while still detecting real object motion.
     * 
     * Levels:
     * - 0 = OFF: Legacy pixel differencing, sensitive to flashes
     * - 1 = LOW: Edge-based, some flash filtering
     * - 2 = MEDIUM: Edge-based + brightness normalization (default)
     * - 3 = HIGH: Edge-based + aggressive flash rejection
     * 
     * @param level Flash immunity level (0-3)
     */
    public void setFlashImmunity(int level) {
        this.flashImmunity = Math.max(0, Math.min(3, level));
        // Sync with SOTA config
        config.setFlashImmunity(this.flashImmunity);
        String[] levelNames = {"OFF", "LOW", "MEDIUM", "HIGH"};
        logger.info("Flash immunity set to: " + levelNames[this.flashImmunity] + " (" + this.flashImmunity + ")");
    }
    
    /**
     * Gets the total number of grid blocks.
     * 
     * @return Total blocks (300 for 640x480 with 32px blocks)
     */
    public int getTotalBlocks() {
        return TOTAL_BLOCKS;
    }
    
    /**
     * Gets the last active blocks count (for UI display).
     * 
     * @return Number of blocks that were active in the last frame
     */
    public int getLastActiveBlocksCount() {
        return lastActiveBlocksCount;
    }
    
    /**
     * Gets the baseline noise blocks count (deprecated - always returns 0).
     * 
     * @return Always 0 (baseline logic removed)
     */
    public int getBaselineNoiseBlocks() {
        return 0;  // Baseline logic removed for simplicity
    }
    
    /**
     * Sets the SAD threshold for motion detection.
     * 
     * @param threshold Threshold value (0.0-1.0, typically 0.05 for 5%)
     */
    public void setSadThreshold(float threshold) {
        config.setSensitivity(threshold);
        logger.info( "SAD threshold set to: " + threshold);
    }
    
    /**
     * Gets the pre-record duration in seconds.
     * 
     * @return Pre-record duration in seconds
     */
    public int getPreRecordSeconds() {
        return (int) (preRecordMs / 1000);
    }
    
    /**
     * Sets the pre-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 10 for 10 seconds before motion)
     */
    public void setPreRecordSeconds(int seconds) {
        this.preRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPreRecordSeconds(seconds);
        logger.info("Pre-record duration set to: " + seconds + " seconds");
        
        // Update the circular buffer size in the recorder's encoder
        if (recorder != null && recorder.getEncoder() != null) {
            recorder.getEncoder().setPreRecordDuration(seconds);
        }
    }
    
    /**
     * Gets the post-record duration in seconds.
     * 
     * @return Post-record duration in seconds
     */
    public int getPostRecordSeconds() {
        return (int) (postRecordMs / 1000);
    }
    
    /**
     * Sets the post-record duration.
     * 
     * @param seconds Duration in seconds (e.g., 5 for 5 seconds after motion stops)
     */
    public void setPostRecordSeconds(int seconds) {
        this.postRecordMs = seconds * 1000L;
        // Sync with SOTA config
        config.setPostRecordSeconds(seconds);
        logger.info("Post-record duration set to: " + seconds + " seconds");
    }
    
    /**
     * Gets the frame count.
     * 
     * @return Total frames processed
     */
    public int getFrameCount() {
        return frameCount;
    }
    
    /**
     * Gets the motion detection count.
     * 
     * @return Total motion events detected
     */
    public int getMotionDetections() {
        return motionDetections;
    }
    
    /**
     * Gets the latest cached mosaic frame (640×480 RGB) for snapshot API.
     * Returns null if no frame has been cached yet. Records the poll
     * timestamp so processFrame keeps refreshing while clients are active.
     */
    public byte[] getLatestMosaicFrame() {
        lastSnapshotPollMs = System.currentTimeMillis();
        return latestMosaicFrame;
    }

    /**
     * Latest JPEG-encoded mosaic snapshot (Option C side-output). Refreshed
     * every {@link #MOSAIC_JPEG_FRAME_MODULO} frames on the surveillance
     * worker thread WHILE clients are polling; idle-skipped otherwise.
     * Readers pay one volatile read + one volatile write (poll timestamp).
     * Null until the first encode lands or when surveillance hasn't started.
     */
    public byte[] getLatestMosaicJpeg() {
        lastSnapshotPollMs = System.currentTimeMillis();
        return latestMosaicJpeg;
    }

    /**
     * Encodes the 640×480 RGB mosaic into a JPEG byte[]. Always invoked from
     * {@link #mosaicJpegExecutor}'s single thread so the scratch buffers
     * ({@link #mosaicJpegPixelsScratch}, {@link #mosaicJpegBitmapScratch})
     * are accessed without a lock. Reusing them avoids ~5 MB/s of int[] +
     * Bitmap allocation that would otherwise hit the young-gen.
     *
     * Returns null on bad input or encode failure.
     */
    private byte[] encodeMosaicJpeg(byte[] mosaicRgb) {
        final int W = 640, H = 480;
        if (mosaicRgb == null || mosaicRgb.length < W * H * 3) return null;
        if (mosaicJpegPixelsScratch == null || mosaicJpegPixelsScratch.length < W * H) {
            mosaicJpegPixelsScratch = new int[W * H];
        }
        int[] pixels = mosaicJpegPixelsScratch;
        for (int y = 0; y < H; y++) {
            int rowBase = y * W * 3;
            int dstBase = y * W;
            for (int x = 0; x < W; x++) {
                int srcIdx = rowBase + x * 3;
                int r = mosaicRgb[srcIdx] & 0xFF;
                int g = mosaicRgb[srcIdx + 1] & 0xFF;
                int b = mosaicRgb[srcIdx + 2] & 0xFF;
                pixels[dstBase + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        // Reuse a mutable Bitmap and re-feed pixels via setPixels rather
        // than allocating a new immutable Bitmap each call.
        android.graphics.Bitmap bitmap = mosaicJpegBitmapScratch;
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() != W || bitmap.getHeight() != H) {
            if (bitmap != null && !bitmap.isRecycled()) {
                try { bitmap.recycle(); } catch (Exception ignored) {}
            }
            bitmap = android.graphics.Bitmap.createBitmap(
                    W, H, android.graphics.Bitmap.Config.ARGB_8888);
            mosaicJpegBitmapScratch = bitmap;
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
        // Bitmap NOT recycled — it's reused for the next encode.
    }

    /**
     * Releases all resources.
     */
    /**
     * Updates the V2 pipeline configuration.
     * Call this when user changes settings via IPC.
     */
    public void updateV2Config(MotionPipelineV2.Config newConfig) {
        if (pipelineV2 != null) {
            if (newConfig != null) {
                pipelineV2Config = newConfig;
            }
            if (pipelineV2Config != null) {
                pipelineV2.applyConfig(pipelineV2Config);
                logger.info("V2 pipeline config updated");
            }
        }
    }
    
    /**
     * Apply a V2 environment preset (outdoor/garage/street).
     */
    public void applyV2EnvironmentPreset(String preset) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applyEnvironmentPreset(preset);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 environment preset applied: " + preset);
        }
    }
    
    /**
     * Apply V2 sensitivity level (1-5).
     */
    public void applyV2Sensitivity(int level) {
        if (pipelineV2Config != null) {
            pipelineV2Config.applySensitivity(level);
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 sensitivity set to " + level);
        }
    }
    
    /**
     * Set V2 loitering time in seconds.
     */
    public void setV2LoiteringTime(int seconds) {
        if (pipelineV2Config != null) {
            pipelineV2Config.loiteringFrames = seconds * 10;  // 10 FPS
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
        }
        // Also update Java-side sustained motion threshold.
        // THREAT_MEDIUM must persist for this duration before triggering.
        this.loiteringTimeMs = seconds * 1000L;
        logger.info("V2 loitering time set to " + seconds + "s (native=" + (seconds * 10) + " frames, java=" + loiteringTimeMs + "ms)");
    }

    /** Live-update the approach fast-path bar (0 = disabled). Records a
     *  YOLO-confirmed MEDIUM(approach) after this many seconds instead of the
     *  full loitering time. */
    public void setV2ApproachTrigger(int seconds) {
        this.approachTriggerMs = (seconds <= 0) ? 0L : seconds * 1000L;
        logger.info("V2 approach trigger set to " + seconds + "s (java=" + approachTriggerMs + "ms"
                + (approachTriggerMs == 0 ? ", DISABLED)" : ")"));
    }
    
    /**
     * Enable/disable a specific camera quadrant for V2 detection.
     * @param quadrant 0=front, 1=right, 2=left, 3=rear
     * @param enabled true to enable, false to disable
     */
    public void setV2QuadrantEnabled(int quadrant, boolean enabled) {
        if (pipelineV2Config != null && quadrant >= 0 && quadrant < 4) {
            pipelineV2Config.quadrantEnabled[quadrant] = enabled;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            logger.info("V2 quadrant " + MotionPipelineV2.QUADRANT_NAMES[quadrant] + 
                    " " + (enabled ? "enabled" : "disabled"));
        }
    }
    
    /**
     * Get V2 pipeline results (for heatmap overlay / debug).
     */
    public MotionPipelineV2.QuadrantResult[] getV2Results() {
        return pipelineV2 != null ? pipelineV2.getResults() : null;
    }
    
    /**
     * Set shadow filter mode for V2 pipeline.
     * @param mode 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public void setV2ShadowFilterMode(int mode) {
        if (pipelineV2Config != null && mode >= 0 && mode <= 3) {
            pipelineV2Config.shadowFilterMode = mode;
            if (pipelineV2 != null) {
                pipelineV2.applyConfig(pipelineV2Config);
            }
            String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
            logger.info("V2 shadow filter mode set to " + modeNames[mode]);
        }
    }
    
    /**
     * Get current shadow filter mode.
     * @return 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
     */
    public int getV2ShadowFilterMode() {
        return pipelineV2Config != null ? pipelineV2Config.shadowFilterMode : 0;
    }
    
    /**
     * Enable/disable filter debug logging.
     */
    public void setFilterDebugEnabled(boolean enabled) {
        this.filterDebugEnabled = enabled;
        if (!enabled) {
            synchronized (filterLog) {
                filterLogCount = 0;
                filterLogIndex = 0;
            }
        }
        logger.info("Filter debug log " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Add an entry to the filter debug log ring buffer.
     */
    private void addFilterLogEntry(String entry) {
        if (!filterDebugEnabled) return;
        synchronized (filterLog) {
            filterLog[filterLogIndex] = entry;
            filterLogIndex = (filterLogIndex + 1) % FILTER_LOG_CAPACITY;
            if (filterLogCount < FILTER_LOG_CAPACITY) filterLogCount++;
        }
    }
    
    /**
     * Get recent filter log entries (newest first).
     */
    public String[] getFilterLogEntries() {
        synchronized (filterLog) {
            String[] entries = new String[filterLogCount];
            for (int i = 0; i < filterLogCount; i++) {
                int idx = (filterLogIndex - 1 - i + FILTER_LOG_CAPACITY) % FILTER_LOG_CAPACITY;
                entries[i] = filterLog[idx];
            }
            return entries;
        }
    }
    
    public void release() {
        disable();

        // Drain any in-flight segment-metadata writes BEFORE we shut
        // down the executor. Skipping this would discard hero JPEGs
        // mid-compress. 2s budget matches stopRecording's drain budget.
        drainSegmentMetadata(2_000);
        segmentMetadataExecutor.shutdown();
        try {
            if (!segmentMetadataExecutor.awaitTermination(500,
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                segmentMetadataExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            segmentMetadataExecutor.shutdownNow();
        }

        // SOTA FIX: Shutdown the executor
        aiExecutor.shutdownNow();
        // Cancel any pending staggered seed dispatches and stop the scheduler
        // thread so it doesn't outlive the engine.
        aiScheduler.shutdownNow();

        // Shut down the mosaic JPEG encoder thread + recycle its scratch
        // Bitmap. shutdownNow() interrupts any in-flight encode; we await
        // termination with a bounded budget so we don't recycle the scratch
        // Bitmap mid-Bitmap.compress — recycling under a live JNI compress
        // crashes native code.
        boolean encoderTerminated = false;
        try {
            mosaicJpegExecutor.shutdownNow();
            // Bounded wait: 200ms × up to 10 attempts = 2s total. Bitmap
            // compress at 640×480 q=82 typically completes in <80ms so a
            // single 200ms wait is almost always sufficient; the loop
            // covers a worst-case GC stall on the BYD SoC.
            int attempts = 0;
            while (attempts++ < 10) {
                if (mosaicJpegExecutor.awaitTermination(200,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    encoderTerminated = true;
                    break;
                }
            }
            if (!encoderTerminated) {
                logger.warn("mosaicJpegExecutor did not terminate within 2s — "
                        + "skipping bitmap recycle to avoid native crash");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {}
        if (encoderTerminated && mosaicJpegBitmapScratch != null) {
            try {
                if (!mosaicJpegBitmapScratch.isRecycled()) mosaicJpegBitmapScratch.recycle();
            } catch (Exception ignored) {}
        }
        // Drop refs unconditionally — if we couldn't recycle (encoder still
        // running), the GC will reclaim once the lingering encode finishes
        // and releases its strong reference.
        mosaicJpegBitmapScratch = null;
        mosaicJpegPixelsScratch = null;
        latestMosaicJpeg = null;

        // Clean up YOLO detector
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }

        currentFrame = null;
        // ThreadLocal: clear this thread's scratch. Other threads' entries
        // (aiExecutor, drainer) will be reclaimed when those threads exit
        // or the next allocation replaces them.
        aiBufferTL.remove();

        logger.info("Released");
    }
    
}
