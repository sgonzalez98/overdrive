package com.overdrive.app.telemetry;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls BYD device APIs at 5 Hz via reflection to collect vehicle telemetry.
 * Produces immutable {@link TelemetrySnapshot} objects consumed by the overlay renderer.
 * Uses last-known-good fallback per field on device API failure.
 */
public class TelemetryDataCollector {

    private static final String TAG = "TelemetryDataCollector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // 2 Hz overlay poll. Dropped from 5 Hz → 2 Hz to cut CPU during ACC-ON
    // recording: every fast tick drives a reflective BYD-HAL sweep plus an
    // overlay bitmap re-raster + GL re-upload, so the rate is a direct CPU
    // multiplier on the recording path. Overlay text (speed/gear/pedals/turn/
    // belt) does not change meaningfully faster than ~2 Hz to a viewer, so 500ms
    // is visually indistinguishable while roughly halving the per-second
    // reflective-poll + raster cost. The tick-count constants below are
    // re-derived from this period so their WALL-CLOCK behavior is unchanged.
    private static final long POLL_INTERVAL_MS = 500; // 2 Hz — only used when overlay recording is active
    private static final long SLOW_POLL_INTERVAL_MS = 1000; // 1 Hz fallback when not recording

    // Slow-path sub-polling: seatbelts and brake-pedal state don't change fast.
    // Poll them at ~1Hz to save reflection calls per cycle.
    private static final int SLOW_FIELD_DIVISOR = 2; // every 2nd poll = 1Hz at 500ms base

    // Turn-signal sticky window: how many fast-path ticks to hold "on" after the
    // last observed flash, to bridge the off-phase of the ~1.5Hz blink cycle.
    // 2 ticks = ~1000ms at 2Hz — spans an off-frame (~333ms) with margin while
    // still clearing a cancelled indicator from the overlay within ~1s.
    private static final int TURN_STICKY_TICKS = 2;

    // BYDAutoSpeedDevice
    private Object speedDevice;
    private Method getCurrentSpeedMethod;
    private Method getAccelerateDeepnessMethod;
    private Method getBrakeDeepnessMethod;

    // BYDAutoGearboxDevice
    private Object gearboxDevice;
    private Method getGearboxAutoModeTypeMethod;
    private Method getBrakePedalStateMethod;

    // Turn signal detection via getTurnLightFlashState()
    // Returns: 0=off, 1=left, 2=right, 3=hazard (model-dependent)
    private Object lightDevice;
    private java.lang.reflect.Method getTurnLightFlashStateMethod;

    // Seatbelt via BYDAutoInstrumentDevice.getSafetyBeltStatus(int)
    // Fallback: BYDAutoSafetyBeltDevice.getPassengerStatus(int)
    private Object safetyBeltDevice;
    private java.lang.reflect.Method getPassengerStatusMethod;
    private Object instrumentDeviceForBelt;
    private java.lang.reflect.Method getSafetyBeltStatusMethod;

    // Polling
    private ScheduledExecutorService executor;
    private volatile TelemetrySnapshot latestSnapshot;
    
    // Recording mode: when true, polls at 200ms (5Hz) for overlay.
    // When false, polls at 1000ms (1Hz) for trip telemetry / ABRP only.
    private volatile boolean overlayRecordingActive = false;
    
    // Reference counting: polling stays alive as long as any consumer needs it
    // (pipeline overlay, trip recorder, etc.)
    private final java.util.concurrent.atomic.AtomicInteger pollingRefCount = 
        new java.util.concurrent.atomic.AtomicInteger(0);

    // Last-known-good values (used as fallback when a device call fails)
    private int lastSpeedKmh = 0;
    private int lastAccelPercent = 0;
    private int lastBrakePercent = 0;
    // Staleness detection: if speed stays identical for too long, force reconnect
    private int staleSpeedCount = 0;
    private int prevSpeedForStaleCheck = -1;
    private static final int STALE_THRESHOLD = 20; // 10 seconds at 2Hz
    private boolean lastBrakePedalPressed = false;
    private int lastGearMode = 1; // P
    private boolean lastLeftTurn = false;
    private boolean lastRightTurn = false;
    private boolean[] lastSeatbelts = new boolean[]{true, true}; // buckled by default
    private long pollCount = 0;
    private int leftTurnStickyCount = 0;
    private int rightTurnStickyCount = 0;

    // FIX H2: per-tick reusable scratch to avoid allocation churn at 5 Hz.
    // We can NOT mutate lastSeatbelts in place because the published snapshot
    // shares the reference with the consumer. Use a 2-slot scratch and only
    // promote into lastSeatbelts (which becomes the published reference) when
    // the values actually changed — typical case is "no change", so the
    // snapshot reuses the same array forever.
    private final boolean[] seatbeltScratch = new boolean[2];

    // FIX H2: log a WARN when a poll tick exceeds this budget. 50 ms at 5 Hz
    // is 25% of the period — anything above that and we're risking missed
    // ticks plus regressing the overlay's frame freshness.
    private static final long SLOW_TICK_LOG_BUDGET_MS = 50L;
    private long lastSlowTickWarnAtMs = 0L;

    // 750ms heartbeat at 1Hz idle polling. The poll itself fires every 1000ms
    // (SLOW_POLL_INTERVAL_MS) but ScheduledExecutorService.scheduleAtFixedRate
    // can fire 1-2ms early due to Linux timer slack — at 1000ms the heartbeat
    // gate would then slip to the NEXT tick (~2000ms total gap), exceeding
    // GearMonitor's 1000ms freshness window (GearMonitor.java:133) and the
    // 1000ms threshold of any other consumer. 750ms guarantees the heartbeat
    // always fires before the next poll tick, keeping the snapshot freshness
    // well under 1 second.
    private static final long HEARTBEAT_INTERVAL_MS = 750L;
    private long lastPublishedTimestampMs = 0L;

    /**
     * Initialize BYD device handles via reflection using PermissionBypassContext.
     * Each device is initialized independently — if one fails, others still work.
     */
    public void init(Context context) {
        logger.info("Initializing telemetry device access...");

        Context permissiveContext = new PermissionBypassContext(context);
        this.savedContext = permissiveContext;

        // BYDAutoSpeedDevice — getCurrentSpeed(), getAccelerateDeepness(), getBrakeDeepness()
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            speedDevice = getInstance.invoke(null, permissiveContext);
            getCurrentSpeedMethod = cls.getMethod("getCurrentSpeed");
            getAccelerateDeepnessMethod = cls.getMethod("getAccelerateDeepness");
            getBrakeDeepnessMethod = cls.getMethod("getBrakeDeepness");
            logger.info("BYDAutoSpeedDevice initialized");
        } catch (Exception e) {
            logger.warn("BYDAutoSpeedDevice unavailable: " + e.getMessage());
        }

        // BYDAutoGearboxDevice — getGearboxAutoModeType(), getBrakePedalState()
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            gearboxDevice = getInstance.invoke(null, permissiveContext);
            getGearboxAutoModeTypeMethod = cls.getMethod("getGearboxAutoModeType");
            getBrakePedalStateMethod = cls.getMethod("getBrakePedalState");
            logger.info("BYDAutoGearboxDevice initialized");
        } catch (Exception e) {
            logger.warn("BYDAutoGearboxDevice unavailable: " + e.getMessage());
        }

        // BYDAutoLightDevice — getTurnLightFlashState() (more reliable than getLightStatus)
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            lightDevice = getInstance.invoke(null, permissiveContext);
            getTurnLightFlashStateMethod = cls.getMethod("getTurnLightFlashState");
            logger.info("BYDAutoLightDevice initialized (using getTurnLightFlashState)");
        } catch (Exception e) {
            logger.warn("BYDAutoLightDevice unavailable: " + e.getMessage());
        }

        // Seatbelt: Try InstrumentDevice.getSafetyBeltStatus(int) first, fallback to SafetyBeltDevice
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            instrumentDeviceForBelt = getInstance.invoke(null, permissiveContext);
            getSafetyBeltStatusMethod = cls.getMethod("getSafetyBeltStatus", int.class);
            logger.info("Using InstrumentDevice for seatbelt status");
        } catch (Exception e) {

            try {
                Class<?> cls2 = Class.forName("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice");
                Method getInstance2 = cls2.getMethod("getInstance", Context.class);
                safetyBeltDevice = getInstance2.invoke(null, permissiveContext);
                getPassengerStatusMethod = cls2.getMethod("getPassengerStatus", int.class);
                logger.info("BYDAutoSafetyBeltDevice initialized (fallback)");
            } catch (Exception e2) {
                logger.warn("No seatbelt device available: " + e2.getMessage());
            }
        }

        // Initialize with safe defaults
        latestSnapshot = TelemetrySnapshot.createDefault();

        logger.info("Telemetry device initialization complete");
    }

    /**
     * Start polling BYD device APIs on a background thread.
     * Rate depends on overlayRecordingActive:
     *   true  → 200ms (5Hz) for video overlay — only polls speed/accel/brake/gear fast
     *   false → 1000ms (1Hz) for trip telemetry / ABRP
     * Uses reference counting — multiple callers can request polling,
     * and it only stops when ALL callers have called stopPolling().
     */
    public void startPolling() {
        int refs = pollingRefCount.incrementAndGet();
        if (executor != null && !executor.isShutdown()) {
            logger.info("Polling already running (refCount=" + refs + ")");
            return;
        }
        long interval = overlayRecordingActive ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TelemetryPoller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::poll, 0, interval, TimeUnit.MILLISECONDS);
        logger.info("Telemetry polling started at " + (1000 / interval) + " Hz (overlay=" + overlayRecordingActive + ", refCount=" + refs + ")");
    }

    /**
     * Set overlay recording mode. When active, polling runs at 2Hz.
     * When inactive, drops to 1Hz. Restarts the scheduler if the rate changes.
     */
    public void setOverlayRecordingActive(boolean active) {
        if (this.overlayRecordingActive == active) return;
        this.overlayRecordingActive = active;
        logger.info("Overlay recording " + (active ? "ACTIVE (2Hz)" : "INACTIVE (1Hz)"));
        // Restart scheduler at new rate if currently running
        restartAtCurrentRate();
    }

    /**
     * Restarts the polling scheduler at the rate matching the current overlayRecordingActive state.
     * No-op if the scheduler is not running.
     */
    private void restartAtCurrentRate() {
        if (executor == null || executor.isShutdown()) return;
        executor.shutdown();
        executor = null;
        long interval = overlayRecordingActive ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TelemetryPoller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::poll, 0, interval, TimeUnit.MILLISECONDS);
        logger.info("Telemetry polling restarted at " + (1000 / interval) + " Hz");
    }

    /**
     * Request to stop polling. Only actually stops when all consumers have released.
     * If overlay recording was deactivated but other consumers remain, downgrades to 1Hz.
     */
    public void stopPolling() {
        int refs = pollingRefCount.decrementAndGet();
        if (refs < 0) {
            pollingRefCount.set(0);
            refs = 0;
        }
        if (refs > 0) {
            logger.info("Polling stop requested but still needed (refCount=" + refs + ")");
            // If overlay just stopped but trip recorder still needs polling, downgrade rate
            if (!overlayRecordingActive) {
                restartAtCurrentRate();
            }
            return;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
            logger.info("Telemetry polling stopped (refCount=0)");
        }
    }
    
    /**
     * Force stop polling regardless of reference count.
     * Used during daemon shutdown.
     */
    public void forceStopPolling() {
        pollingRefCount.set(0);
        if (executor != null) {
            executor.shutdown();
            executor = null;
            logger.info("Telemetry polling force-stopped");
        }
    }

    /**
     * Returns the latest telemetry snapshot (thread-safe via volatile reference).
     */
    public TelemetrySnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    /**
     * Poll all BYD devices and produce a new TelemetrySnapshot.
     * On failure for any individual field, uses the last-known-good value.
     */
    private void poll() {
        // FIX H2: instrument tick duration. The 6+ reflective Method.invoke()
        // calls per tick are the dominant CPU cost at 5 Hz; if a single
        // BYD-HAL call gets slow (e.g., the speed device gets reconnected
        // mid-tick) the tick can blow past the 200 ms period. We rate-limit
        // the WARN to once per minute so a slow HAL doesn't spam the log.
        long startNanos = System.nanoTime();
        try {
            pollInner();
        } catch (Throwable t) {
            // CRITICAL: ScheduledExecutorService silently stops if task throws.
            // Catch everything to keep polling alive.
            logger.error("Poll error (keeping alive): " + t.getMessage());
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > SLOW_TICK_LOG_BUDGET_MS) {
            long now = System.currentTimeMillis();
            if (now - lastSlowTickWarnAtMs > 60_000L) {
                lastSlowTickWarnAtMs = now;
                logger.warn("Telemetry poll tick took " + elapsedMs
                        + " ms (budget " + SLOW_TICK_LOG_BUDGET_MS
                        + " ms) — BYD HAL may be slow");
            }
        }
    }

    private void pollInner() {
        int speedKmh = lastSpeedKmh;
        int accelPercent = lastAccelPercent;
        int brakePercent = lastBrakePercent;
        boolean brakePedalPressed = lastBrakePedalPressed;
        int gearMode = lastGearMode;
        boolean leftTurn = lastLeftTurn;
        boolean rightTurn = lastRightTurn;
        boolean[] seatbelts = lastSeatbelts;

        // ── FAST PATH: speed, accel, brake, gear (every poll) ──
        // These are the only fields that change rapidly during driving
        // and are needed for the video overlay at 5Hz.

        // Speed device: getCurrentSpeed(), getAccelerateDeepness(), getBrakeDeepness()
        if (speedDevice != null) {
            boolean deviceFailed = false;
            try {
                double rawSpeed = (double) getCurrentSpeedMethod.invoke(speedDevice);
                // BYDAutoSpeedDevice.getCurrentSpeed() returns the value in the
                // cluster's configured unit (mph on imperial trims), so normalize
                // to canonical km/h here — matching the contract every downstream
                // consumer assumes (overlay display, trip scoring). On km trims the
                // factor is 1.0, so this is a no-op. Mirrors the conversion
                // BydDataCollector already applies on its own speed path.
                //
                // Guard the SDK sentinel (-2.147482624E9, returned without throwing
                // when the value is unavailable) and any negative/NaN glitch BEFORE
                // the multiply: sentinel × the imperial factor overflows int32 into
                // a bogus +838M km/h that would poison the overlay and the trip's
                // max/avg/histogram. On a bad read we keep the last-known-good speed.
                if (isValidRawSpeed(rawSpeed)) {
                    speedKmh = (int) Math.round(rawSpeed * speedToKmhFactor());
                    lastSpeedKmh = speedKmh;
                }
            } catch (Exception e) {
                logger.warn("Failed to read speed: " + e.getMessage());
                deviceFailed = true;
            }
            try {
                accelPercent = (int) getAccelerateDeepnessMethod.invoke(speedDevice);
                lastAccelPercent = accelPercent;
            } catch (Exception e) {
                logger.warn("Failed to read accel pedal: " + e.getMessage());
                deviceFailed = true;
            }
            try {
                brakePercent = (int) getBrakeDeepnessMethod.invoke(speedDevice);
                lastBrakePercent = brakePercent;
            } catch (Exception e) {
                logger.warn("Failed to read brake depth: " + e.getMessage());
                deviceFailed = true;
            }
            // If any read failed, try to re-obtain the device reference
            if (deviceFailed) {
                boolean reconnected = tryReconnectSpeedDevice();
                if (reconnected) {
                    speedKmh = lastSpeedKmh;
                    accelPercent = lastAccelPercent;
                    brakePercent = lastBrakePercent;
                }
            }

            // Staleness detection: if speed value is identical for 10+ seconds, force reconnect
            if (speedKmh == prevSpeedForStaleCheck && !(speedKmh == 0 && lastGearMode == 1)) {
                staleSpeedCount++;
                if (staleSpeedCount >= STALE_THRESHOLD) {
                    long pollIntervalMs = overlayRecordingActive ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
                    logger.warn("Speed device appears stale (same value " + speedKmh + " for " + (staleSpeedCount * pollIntervalMs / 1000) + "s), reconnecting");
                    boolean reconnected = tryReconnectSpeedDevice();
                    if (reconnected) {
                        speedKmh = lastSpeedKmh;
                        accelPercent = lastAccelPercent;
                        brakePercent = lastBrakePercent;
                    }
                    staleSpeedCount = 0;
                    prevSpeedForStaleCheck = -1;
                }
            } else {
                staleSpeedCount = 0;
                prevSpeedForStaleCheck = speedKmh;
            }
        }

        // Gearbox: gear mode (every poll — changes on shift)
        if (gearboxDevice != null) {
            try {
                gearMode = (int) getGearboxAutoModeTypeMethod.invoke(gearboxDevice);
                lastGearMode = gearMode;
            } catch (Exception e) {
                logger.warn("Failed to read gear mode: " + e.getMessage());
            }
        }

        // Turn signals (every poll = 5Hz). Read on the fast path so a cancelled
        // indicator clears from the overlay within ~600ms instead of lingering
        // for up to 10s. Sticky counter bridges the off-phase of the blink cycle.
        if (lightDevice != null && getTurnLightFlashStateMethod != null) {
            try {
                int flashState = (int) getTurnLightFlashStateMethod.invoke(lightDevice);

                boolean leftNow = (flashState == 2 || flashState == 3);
                boolean rightNow = (flashState == 4 || flashState == 5);
                boolean hazardNow = (flashState == 6 || flashState == 7);

                if (hazardNow) { leftNow = true; rightNow = true; }

                if (leftNow) leftTurnStickyCount = TURN_STICKY_TICKS;
                if (rightNow) rightTurnStickyCount = TURN_STICKY_TICKS;

                leftTurn = leftTurnStickyCount > 0;
                rightTurn = rightTurnStickyCount > 0;

                if (leftTurnStickyCount > 0) leftTurnStickyCount--;
                if (rightTurnStickyCount > 0) rightTurnStickyCount--;

                lastLeftTurn = leftTurn;
                lastRightTurn = rightTurn;
            } catch (Exception e) {
                logger.warn("Failed to read turn signal: " + e.getMessage());
            }
        }

        // Seatbelt status (every poll = 5Hz). Drawn on the overlay every frame,
        // so a buckle/unbuckle must reflect within one frame instead of up to 1s.
        if (pollCount == 0) {
            probeSeatbeltApis(savedContext);
        }
        if (instrumentDeviceForBelt != null && getSafetyBeltStatusMethod != null) {
            try {
                // FIX H2: reuse a 2-slot scratch instead of allocating a fresh
                // boolean[2] every tick. We only publish a NEW array when the
                // values actually changed — the steady-state path (driver +
                // passenger both buckled, unchanging) never allocates.
                int driverRaw = (int) getSafetyBeltStatusMethod.invoke(instrumentDeviceForBelt, 1);
                int passengerRaw = (int) getSafetyBeltStatusMethod.invoke(instrumentDeviceForBelt, 2);
                seatbeltScratch[0] = (driverRaw != 0);
                seatbeltScratch[1] = (passengerRaw != 0);
                if (lastSeatbelts == null
                        || lastSeatbelts.length != 2
                        || lastSeatbelts[0] != seatbeltScratch[0]
                        || lastSeatbelts[1] != seatbeltScratch[1]) {
                    // State changed — allocate a fresh array so prior
                    // snapshots holding the old reference don't see a
                    // mid-flight mutation.
                    lastSeatbelts = new boolean[]{seatbeltScratch[0], seatbeltScratch[1]};
                }
                seatbelts = lastSeatbelts;
            } catch (Exception e) {
                // Use defaults
            }
        }

        // ── SLOW PATH: brake pedal pressed state (every 5th poll = 1Hz) ──
        // Not drawn on the overlay (renderer uses brakePercent), so 1Hz is fine.
        boolean doSlowFields = (pollCount % SLOW_FIELD_DIVISOR == 0);

        if (doSlowFields) {
            // Brake pedal pressed state (binary, not the depth %)
            if (gearboxDevice != null) {
                try {
                    int brakeState = (int) getBrakePedalStateMethod.invoke(gearboxDevice);
                    brakePedalPressed = brakeState == 1;
                    lastBrakePedalPressed = brakePedalPressed;
                } catch (Exception e) {
                    logger.warn("Failed to read brake pedal state: " + e.getMessage());
                }
            }
        }

        // FIX H2: skip the snapshot allocation when none of the consumed
        // fields have changed since the last published one. The overlay
        // renderer keys on the published reference; the timestampMs delta
        // alone is not user-visible and not worth the allocation+clone of
        // the seatbelt array on every 200 ms tick. At 5 Hz with a parked
        // car this collapses ~5 allocations/s into 0.
        TelemetrySnapshot prev = latestSnapshot;
        boolean fieldsChanged = (prev == null)
                || prev.speedKmh != speedKmh
                || prev.accelPedalPercent != accelPercent
                || prev.brakePedalPercent != brakePercent
                || prev.brakePedalPressed != brakePedalPressed
                || prev.gearMode != gearMode
                || prev.leftTurnSignal != leftTurn
                || prev.rightTurnSignal != rightTurn
                || prev.seatbeltBuckled == null
                || prev.seatbeltBuckled.length != (seatbelts == null ? 0 : seatbelts.length)
                || (seatbelts != null
                        && (prev.seatbeltBuckled[0] != seatbelts[0]
                            || prev.seatbeltBuckled[1] != seatbelts[1]));
        // FIX L1: heartbeat — even with no field change, publish at 1 Hz so
        // freshness-checking consumers (GearMonitor, TripTelemetryRecorder)
        // don't see a stale timestampMs.
        long now = System.currentTimeMillis();
        boolean heartbeatDue = (now - lastPublishedTimestampMs) >= HEARTBEAT_INTERVAL_MS;
        if (fieldsChanged || heartbeatDue) {
            latestSnapshot = new TelemetrySnapshot(
                    speedKmh, accelPercent, brakePercent,
                    brakePedalPressed, gearMode,
                    leftTurn, rightTurn,
                    seatbelts, now
            );
            lastPublishedTimestampMs = now;
        }

        pollCount++;
    }

    /**
     * Factor to convert a raw BYDAutoSpeedDevice.getCurrentSpeed() reading into
     * canonical km/h. On imperial trims this is MILES_TO_KM (~1.609); on metric
     * trims it is 1.0 (no-op). Sourced from {@link BydDataCollector}, the single
     * place that detects the cluster's mileage unit. Defensive: any failure (or
     * an uninitialized collector, where the factor still defaults to 1.0) falls
     * back to 1.0 so a metric trim — the overwhelming majority — is never altered.
     */
    private double speedToKmhFactor() {
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector != null) {
                double f = collector.getDistanceToKmFactor();
                if (f > 0) return f;
            }
        } catch (Throwable ignored) {
            // BydDataCollector unavailable — treat as metric (factor 1.0)
        }
        return 1.0;
    }

    /**
     * Whether a raw getCurrentSpeed() reading is a usable value rather than the
     * SDK's "not available" sentinel (-2.147482624E9, returned without throwing),
     * a negative glitch, or NaN. The {@code >= 0} floor covers all three (NaN
     * comparisons are false), and crucially prevents the sentinel from being
     * scaled by the imperial factor into an int32 overflow.
     */
    private static boolean isValidRawSpeed(double rawSpeed) {
        return rawSpeed >= 0
                && rawSpeed != com.overdrive.app.byd.BydFeatureIds.SDK_NOT_AVAILABLE;
    }

    /**
     * Try to re-obtain the BYDAutoSpeedDevice reference and verify it returns fresh data.
     * This can happen if the BYD service restarts between trips.
     * Returns true if reconnect succeeded and fresh data was obtained.
     */
    private boolean tryReconnectSpeedDevice() {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object newDevice = getInstance.invoke(null, savedContext);
            if (newDevice == null) return false;
            
            // Verify the new device actually returns data by doing a test read
            double testSpeed = (double) getCurrentSpeedMethod.invoke(newDevice);
            int testAccel = (int) getAccelerateDeepnessMethod.invoke(newDevice);
            int testBrake = (int) getBrakeDeepnessMethod.invoke(newDevice);

            // If we get here without exception, the device is alive
            speedDevice = newDevice;
            // Normalize to km/h, same as the main poll path (no-op on metric trims).
            // Guard the sentinel/negative so a not-yet-ready value doesn't overflow
            // into a bogus speed; the device is still considered alive (the reads
            // didn't throw), we just keep the last-known-good speed this tick.
            if (isValidRawSpeed(testSpeed)) {
                lastSpeedKmh = (int) Math.round(testSpeed * speedToKmhFactor());
            }
            lastAccelPercent = testAccel;
            lastBrakePercent = testBrake;
            logger.info("Re-obtained BYDAutoSpeedDevice — verified working (speed=" + lastSpeedKmh + ")");
            return true;
        } catch (Exception e) {
            logger.warn("Speed device reconnect failed: " + e.getMessage());
            return false;
        }
    }

    // Seatbelt alarm detection (discovered at runtime)
    private Object seatbeltAlarmDevice;
    private Method seatbeltAlarmMethod;
    private Context savedContext;
    
    /**
     * Probe multiple BYD devices for any seatbelt-related method.
     * Tries: BodyworkDevice alarm, InstrumentDevice malfunction indicators,
     * SafetyBeltDevice with various seat IDs.
     */
    private void probeSeatbeltApis(Context ctx) {
        logger.info("Probing BYD devices for seatbelt API...");
        
        // 1. Try BYDAutoBodyworkDevice — getAlarmState() or similar
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            
            // Probe all methods that might relate to seatbelt
            String[] methodNames = {
                "getAlarmState", "getSafetyBeltAlarm", "getSeatBeltWarning",
                "getSafetyBeltState", "getBeltAlarmState", "getAutoSystemState"
            };
            for (String name : methodNames) {
                try {
                    Method m = cls.getMethod(name);
                    int val = (int) m.invoke(device);
                    logger.info("Bodywork." + name + "() = " + val);
                    // If we find a working method, save it
                    if (val >= 0 && val < 100) {
                        seatbeltAlarmDevice = device;
                        seatbeltAlarmMethod = m;
                        logger.info("Using Bodywork." + name + "() for seatbelt alarm");
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, try next
                } catch (Exception e) {

                }
            }
        } catch (Exception e) {

        }
        
        // 2. Try BYDAutoInstrumentDevice — getMalfunctionState() or seatbelt-specific
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            
            String[] methodNames = {
                "getSafetyBeltStatus", "getSeatBeltAlarm", "getSafetyBeltAlarmState",
                "getMalfunctionState"
            };
            for (String name : methodNames) {
                // Try no-arg version
                try {
                    Method m = cls.getMethod(name);
                    int val = (int) m.invoke(device);
                    logger.info("Instrument." + name + "() = " + val);
                    if (seatbeltAlarmDevice == null && val >= 0 && val < 100) {
                        seatbeltAlarmDevice = device;
                        seatbeltAlarmMethod = m;
                        logger.info("Using Instrument." + name + "() for seatbelt alarm");
                    }
                } catch (NoSuchMethodException e) {
                    // Try int-arg version
                    try {
                        Method m = cls.getMethod(name, int.class);
                        StringBuilder sb = new StringBuilder("Instrument." + name + "(int):");
                        for (int i = 0; i <= 5; i++) {
                            try {
                                int val = (int) m.invoke(device, i);
                                sb.append(" [").append(i).append("]=").append(val);
                            } catch (Exception ex) {
                                sb.append(" [").append(i).append("]=ERR");
                            }
                        }
                        logger.info(sb.toString());
                    } catch (NoSuchMethodException e2) {
                        // Neither version exists
                    }
                } catch (Exception e) {

                }
            }
            
            // Also try MALFUNCTION_ELECTRIC_PARKING_BRAKE constant area
            // getMalfunctionState(int) with various malfunction IDs
            try {
                Method m = cls.getMethod("getMalfunctionState", int.class);
                StringBuilder sb = new StringBuilder("Instrument.getMalfunctionState(int):");
                // Try common malfunction IDs (0-20)
                for (int i = 0; i <= 20; i++) {
                    try {
                        int val = (int) m.invoke(device, i);
                        if (val != 0 && val != -2147482645) {
                            sb.append(" [").append(i).append("]=").append(val);
                        }
                    } catch (Exception ex) { /* skip */ }
                }
                logger.info(sb.toString());
            } catch (Exception e) { /* no getMalfunctionState(int) */ }
            
        } catch (Exception e) {

        }
        
        if (seatbeltAlarmDevice == null) {
            logger.warn("No working seatbelt API found — seatbelt status will show as buckled");
        }
    }

    /**
     * Context wrapper that bypasses BYD permission checks.
     * Required for accessing BYD hardware services without signature permissions from UID 2000.
     */
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) {
            super(base);
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public void enforcePermission(String permission, int pid, int uid, String message) {}

        @Override
        public void enforceCallingPermission(String permission, String message) {}

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }
}
