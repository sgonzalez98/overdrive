package com.overdrive.app.trips;

import android.content.Context;

import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;

import java.io.File;
import java.util.List;

/**
 * Top-level coordinator for Trip Analytics & Driving DNA.
 * Single entry point for CameraDaemon integration.
 *
 * Lifecycle:
 *   CameraDaemon.main() → init(context, telemetryDataCollector, sohEstimator)
 *   CameraDaemon.shutdown() → shutdown()
 *   GearMonitor callback → onGearChanged(newGear)
 */
public class TripAnalyticsManager {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripAnalyticsManager");

    private TripConfig config;
    private TripDatabase database;
    private TripDetector detector;
    private TripTelemetryRecorder recorder;
    private TripScoreEngine scoreEngine;
    private RangeEstimator rangeEstimator;

    private TelemetryDataCollector telemetryDataCollector;
    private SohEstimator sohEstimator;

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== LIFECYCLE ====================

    /**
     * Initialize trip analytics. Called from CameraDaemon.main() after ABRP init.
     *
     * 1. Load TripConfig from properties file
     * 2. If enabled: initialize TripDatabase, TripDetector, TripTelemetryRecorder,
     *    TripScoreEngine, RangeEstimator
     * 3. Set TripDetector listener to handle trip start/end events
     * 4. Ensure StorageManager.getInstance().getTripsDir() exists
     * 5. Log initialization status
     */
    public void init(Context context, TelemetryDataCollector telemetryDataCollector,
                     SohEstimator sohEstimator) {
        this.telemetryDataCollector = telemetryDataCollector;
        this.sohEstimator = sohEstimator;

        // 1. Load config
        config = new TripConfig();
        config.load();

        // 4. Ensure trips directory exists
        File tripsDir = StorageManager.getInstance().getTripsDir();
        if (tripsDir != null && !tripsDir.exists()) {
            boolean created = tripsDir.mkdirs();
            logger.info("Trips directory created: " + tripsDir.getAbsolutePath()
                    + " (success=" + created + ")");
        }

        // 2. If enabled, initialize all components
        if (config.isEnabled()) {
            initComponents();
        }

        initialized = true;

        // 5. Log status
        logger.info("TripAnalyticsManager initialized — enabled=" + config.isEnabled());
    }

    /**
     * Shut down trip analytics. Called from CameraDaemon.shutdown().
     *
     * 1. Finalize active trip via TripDetector
     * 2. Close TripDatabase
     * 3. Log shutdown
     */
    public void shutdown() {
        logger.info("Shutting down TripAnalyticsManager");

        // 1. Finalize active trip
        if (detector != null) {
            detector.finalizeActiveTrip();
        }

        // 2. Close database
        if (database != null) {
            database.close();
        }

        enabled = false;
        initialized = false;

        // 3. Log shutdown
        logger.info("TripAnalyticsManager shut down");
    }

    // ==================== GEAR FORWARDING ====================

    /**
     * Forward gear change to TripDetector if enabled.
     * Called from CameraDaemon.onGearChanged().
     */
    public void onGearChanged(int newGear) {
        if (enabled && detector != null) {
            detector.onGearChanged(newGear);
        }
    }

    // ==================== ACC LIFECYCLE ====================

    /**
     * Called when ACC goes OFF (car powering down / entering sentry mode).
     * Finalizes any active trip immediately — the gear change to P may not
     * fire reliably during power-down, so this is a safety net.
     */
    public void onAccOff() {
        if (!enabled || detector == null) return;
        if (detector.isTripActive()) {
            logger.info("ACC OFF — finalizing active trip");
            detector.finalizeActiveTrip();
        }
    }

    /**
     * Called when ACC comes ON (car powering up).
     * Probe current gear and auto-start trip if already in a driving gear.
     * This handles the case where gear changed to D before the GearMonitor
     * listener was re-registered, or where the gear event was lost during
     * the ACC transition.
     */
    public void onAccOn() {
        if (!enabled) return;
        logger.info("ACC ON — trip detection ready (waiting for gear D/R)");

        // Safety net: probe current gear in case we missed the gear change event
        // during the ACC OFF→ON transition
        try {
            int currentGear = GearMonitor.getInstance().getCurrentGear();
            if (currentGear != GearMonitor.GEAR_P && detector != null && !detector.isTripActive()) {
                logger.info("ACC ON + gear already " + GearMonitor.gearToString(currentGear)
                        + " — auto-starting trip");
                detector.onGearChanged(currentGear);
            }
        } catch (Exception e) {
            logger.warn("ACC ON gear probe failed: " + e.getMessage());
        }
    }

    // ==================== RUNTIME CONFIG ====================

    /**
     * Enable or disable trip analytics at runtime.
     *
     * If disabling while a trip is active, finalize the trip first.
     * If enabling while gear != P, start trip detection immediately.
     */
    public void onConfigChanged(boolean newEnabled) {
        logger.info("onConfigChanged: " + enabled + " → " + newEnabled);

        if (newEnabled == enabled) {
            return; // No change
        }

        if (!newEnabled) {
            // Disabling — finalize active trip first
            if (detector != null && detector.isTripActive()) {
                logger.info("Disabling while trip active — finalizing trip");
                detector.finalizeActiveTrip();
            }
            enabled = false;
            config.setEnabled(false);
            config.save();
            logger.info("Trip analytics disabled");
        } else {
            // Enabling
            config.setEnabled(true);
            config.save();

            if (!enabled) {
                initComponents();
            }

            // If gear is not P, trigger trip detection
            int currentGear = GearMonitor.getInstance().getCurrentGear();
            if (currentGear != GearMonitor.GEAR_P && detector != null) {
                logger.info("Enabling while gear=" + GearMonitor.gearToString(currentGear)
                        + " — forwarding gear to detector");
                detector.onGearChanged(currentGear);
            }

            logger.info("Trip analytics enabled");
        }
    }

    // ==================== ACCESSORS ====================

    public TripDatabase getDatabase() {
        return database;
    }

    public RangeEstimator getRangeEstimator() {
        return rangeEstimator;
    }

    public TripConfig getConfig() {
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if a trip is currently being tracked (ACTIVE or PARK_PENDING).
     */
    public boolean isTripActive() {
        return enabled && detector != null && detector.isTripActive();
    }

    /**
     * Get the active trip record, or null if no trip is active.
     */
    public TripRecord getActiveTrip() {
        return (detector != null) ? detector.getActiveTrip() : null;
    }

    /**
     * Update the TelemetryDataCollector reference after late initialization.
     * Called by CameraDaemon once TelemetryDataCollector is ready (after GPU init delay).
     */
    public void setTelemetryDataCollector(TelemetryDataCollector collector) {
        this.telemetryDataCollector = collector;
        if (recorder != null) {
            recorder.setTelemetryDataCollector(collector);
        }
    }

    // ==================== PRIVATE ====================

    /**
     * Initialize all trip analytics components and wire up the TripDetector listener.
     */
    private void initComponents() {
        // Database
        database = new TripDatabase();
        database.init();
        
        // Clean up orphaned trips from previous daemon crashes
        // (trips with no end_time that are older than 24 hours)
        try {
            long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
            database.deleteOrphanedTrips(cutoff);
        } catch (Exception e) {
            logger.warn("Orphaned trip cleanup failed: " + e.getMessage());
        }
        
        // Backfill route_id for existing trips (idempotent — skips already-assigned trips)
        database.backfillRouteIds();

        // Detector
        detector = new TripDetector();
        detector.setListener(new TripDetector.TripListener() {
            @Override
            public void onTripStarted(TripRecord trip) {
                handleTripStarted(trip);
            }

            @Override
            public void onTripEnded(TripRecord trip) {
                handleTripEnded(trip);
            }

            @Override
            public void onTripDiscarded(TripRecord trip, String reason) {
                handleTripDiscarded(trip, reason);
            }

            @Override
            public double getRecordedDistanceKm() {
                return recorder != null ? recorder.getTotalDistanceKm() : 0;
            }
        });

        // Recorder
        recorder = new TripTelemetryRecorder(telemetryDataCollector);

        // Score engine
        scoreEngine = new TripScoreEngine();

        // Range estimator
        rangeEstimator = new RangeEstimator(database, sohEstimator);

        enabled = true;
        logger.info("Trip analytics components initialized");
    }

    /**
     * Handle trip started event from TripDetector.
     * Start the TripTelemetryRecorder using startTime as the trip ID.
     */
    private void handleTripStarted(TripRecord trip) {
        logger.info("Trip started at " + trip.startTime);

        // Ensure TelemetryDataCollector is polling so we get fresh data
        // It may not be polling if no recording/overlay is active
        if (telemetryDataCollector != null) {
            try {
                telemetryDataCollector.startPolling();
                logger.info("TelemetryDataCollector polling ensured for trip recording");
            } catch (Exception e) {
                logger.warn("Failed to start TelemetryDataCollector polling: " + e.getMessage());
            }
        }

        if (recorder != null) {
            // Use startTime as the unique trip identifier for the recorder
            // (before DB insert gives us the auto-increment ID)
            recorder.startRecording(trip.startTime);
        }
    }

    /**
     * Handle trip ended event from TripDetector.
     *
     * 1. Stop recorder, get samples
     * 2. Compute scores via TripScoreEngine
     * 3. Populate TripRecord with recorder stats (maxSpeed, avgSpeed)
     * 4. Insert into TripDatabase
     * 5. Update rollups
     * 6. Update range estimator
     * 7. Update telemetry file path in the record
     */
    private void handleTripEnded(TripRecord trip) {
        logger.info("Trip ended — duration=" + trip.durationSeconds + "s, distance="
                + trip.distanceKm + "km");

        // Release telemetry polling ref (acquired in handleTripStarted)
        if (telemetryDataCollector != null) {
            telemetryDataCollector.stopPolling();
        }

        String telemetryPath = null;

        // 1. Stop recorder, get samples
        List<TelemetrySample> samples = null;
        if (recorder != null) {
            telemetryPath = recorder.stopRecording();
            samples = recorder.getSamplesForScoring();
        }

        // stopRecording() clears the in-flight marker, but the DB row for this
        // trip isn't inserted until step 4 below. In that gap the <startTime>
        // .jsonl.gz file is on disk with NO row and NO active-file marker, so a
        // concurrent /api/trips/recover would rebuild a phantom duplicate.
        // Re-assert the marker over the file until the row exists; cleared in
        // the finally after insert+rename.
        boolean reArmedMarker = false;
        if (telemetryPath != null) {
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .setActiveTripFile(new File(telemetryPath));
                reArmedMarker = true;
            } catch (Exception ignored) {}
        }
        try {

        // 2. Resolve trip energy (kWh) BEFORE scoring.
        //    The efficiency axis scores against a single kWh/km band, so
        //    trip.energyPerKm must be populated first — either from direct BMS
        //    kWh readings or, when those are absent (the common case), estimated
        //    from the SoC delta via the SohEstimator's calibrated capacity. This
        //    ordering is the fix for efficiency/consistency scoring on a
        //    different unit axis than stored history. Used again below for cost.
        double energyUsed = resolveTripEnergyKwh(trip);
        if (energyUsed > 0 && trip.distanceKm > 0) {
            trip.energyPerKm = energyUsed / trip.distanceKm;
        }

        // 3. Compute scores — all five DNA axes in a single pass. Consistency is
        //    now an intra-trip behavioral-uniformity metric computed inside
        //    computeSummary (no DB lookup, no unit confusion).
        if (scoreEngine != null && samples != null && !samples.isEmpty()) {
            scoreEngine.computeSummary(trip, samples);
        }

        // 4. Populate recorder stats (recorder is authoritative for avg/max speed)
        if (recorder != null) {
            trip.maxSpeedKmh = recorder.getMaxSpeedKmh();
            trip.avgSpeedKmh = recorder.getAvgSpeedKmh();
        }

        // Snapshot electricity rate and compute trip cost.
        //
        // PHEV vs BEV cost math
        // ─────────────────────
        //   electric leg  = energyUsedKwh × electricityRate
        //   fuel leg      = (Δfuel% / 100) × tankCapacityL × fuelPricePerL   (PHEV only)
        //   tripCost      = electric leg + fuel leg
        //
        // Floors:
        //   - fuel leg requires Δfuel% ≥ 1 (sensor resolution is 1%).
        //   - fuel leg requires tankCapacityL > 0 AND fuelPricePerL > 0 from
        //     user config; otherwise the trip simply doesn't charge a fuel
        //     leg (UI surfaces this with a "Set tank capacity" hint).
        //
        // Regression-safety: BEV trips have isPhev=false and fuelPctStart/End
        // at -1, so the entire fuel branch is skipped — behaviour is bit-for-
        // bit identical to the pre-PHEV implementation.
        if (config != null) {
            trip.electricityRate = config.getElectricityRate();
            trip.currency = config.getCurrency();

            // energyUsed (kWh) and trip.energyPerKm were already resolved above,
            // before scoring — reuse them here for the cost math.

            // Electric leg
            double electricCost = 0;
            if (energyUsed > 0 && trip.electricityRate > 0) {
                electricCost = energyUsed * trip.electricityRate;
            }
            trip.electricCost = electricCost;

            // Fuel leg (PHEV only).
            //
            // PRIMARY — hardware cumulative-fuel accumulator delta. The BYD
            // statistic HAL exposes getTotalFuelConValue(), a lifetime
            // litres-burned counter; (end - start) is the vehicle's own metered
            // burn for this trip. This is independent of tank size and free of
            // the 1%-resolution gauge quantisation, and it captures idle /
            // charge-sustain burn the gauge barely moves on. Guarded on
            // end >= start so a counter reset/rollover falls through to the
            // estimate rather than emitting a negative volume. (This is the
            // approach the BYD OEM "diplus" app uses; we add the reset guard.)
            //
            // FALLBACK — legacy fuelPct×tank estimate, for trips logged before
            // the accumulator was captured, or trims that don't report it.
            // Δfuel% < 1 ⇒ below sensor resolution, floored to 0 to avoid
            // phantom costs from integer flicker; requires user-set tankL.
            double fuelCost = 0;
            if (trip.isPhev) {
                double pricePerL = config.getFuelPricePerL();
                trip.fuelPricePerL = pricePerL;

                double litres = 0;
                if (trip.fuelConStart >= 0 && trip.fuelConEnd >= 0
                        && trip.fuelConEnd >= trip.fuelConStart) {
                    // Metered burn — preferred. A flat counter (EV-only leg)
                    // correctly yields 0 litres.
                    litres = trip.fuelConEnd - trip.fuelConStart;
                } else {
                    double tankL = config.getTankCapacityL();
                    if (trip.fuelPctStart >= 0 && trip.fuelPctEnd >= 0
                            && trip.fuelPctStart >= trip.fuelPctEnd
                            && (trip.fuelPctStart - trip.fuelPctEnd) >= 1.0
                            && tankL > 0) {
                        litres = ((trip.fuelPctStart - trip.fuelPctEnd) / 100.0) * tankL;
                    }
                }

                if (litres > 0) {
                    trip.litresUsed = litres;
                    if (pricePerL > 0) {
                        fuelCost = litres * pricePerL;
                    }
                }
            }
            trip.fuelCost = fuelCost;
            trip.tripCost = electricCost + fuelCost;

            if (trip.tripCost > 0) {
                if (trip.isPhev && fuelCost > 0) {
                    logger.info(String.format(
                            "Trip cost: electric %.2f kWh × %s%.2f = %s%.2f + petrol %.2f L × %s%.2f = %s%.2f → %s%.2f total",
                            energyUsed, trip.currency, trip.electricityRate, trip.currency, electricCost,
                            trip.litresUsed, trip.currency, trip.fuelPricePerL, trip.currency, fuelCost,
                            trip.currency, trip.tripCost));
                } else {
                    logger.info(String.format("Trip cost: %.2f kWh × %s%.2f = %s%.2f",
                            energyUsed, trip.currency, trip.electricityRate, trip.currency, trip.tripCost));
                }
            }
        }

        // Set telemetry file path (using startTime-based filename)
        trip.telemetryFilePath = telemetryPath;

        // Stat the finalized .jsonl.gz so we can store its size on the
        // row at insert time. This single local-FS stat is what lets
        // StorageManager.getTripsSize() answer via SUM(size_bytes)
        // instead of walking every trips dir on every page load. The
        // file isn't renamed yet (the dbId-based rename happens after
        // insertTrip below) but the byte count is identical, so we stat
        // the startTime-named file here. Errors leave sizeBytes at 0 —
        // the backfill thread will catch it on the next daemon start.
        if (telemetryPath != null) {
            try {
                File f = new File(telemetryPath);
                if (f.exists() && f.isFile()) {
                    trip.sizeBytes = f.length();
                }
            } catch (Throwable e) {
                logger.warn("Failed to stat telemetry file for size accounting: " + e.getMessage());
            }
        }
        // No sidecars in current builds; sidecarSizeBytes stays 0.

        // 4. Insert into database
        if (database != null) {
            long dbId = database.insertTrip(trip);

            if (dbId > 0) {
                // After DB insert, rename telemetry file to use the DB ID
                // and update the record's telemetry file path
                String newPath = recorder != null
                        ? recorder.getTelemetryFilePath(dbId) : null;

                if (newPath != null && telemetryPath != null) {
                    File oldFile = new File(telemetryPath);
                    File newFile = new File(newPath);
                    if (oldFile.exists() && !oldFile.getAbsolutePath().equals(newFile.getAbsolutePath())) {
                        if (oldFile.renameTo(newFile)) {
                            trip.telemetryFilePath = newPath;
                            database.updateTrip(trip);
                            logger.info("Telemetry file renamed: " + oldFile.getName()
                                    + " → " + newFile.getName());
                        } else {
                            logger.warn("Failed to rename telemetry file to " + newFile.getName());
                        }
                    }
                }

                // 5. Update rollups
                database.updateWeeklyRollup(trip);
                database.updateMonthlyRollup(trip);

                // 6. Assign route_id for O(1) similar-trip lookups
                if (trip.startLat != 0 && trip.startLon != 0) {
                    long routeId = database.findOrCreateRoute(
                            trip.startLat, trip.startLon, trip.endLat, trip.endLon, trip.distanceKm);
                    if (routeId > 0) {
                        trip.routeId = routeId;
                        database.updateTrip(trip);
                        logger.info("Trip assigned to route " + routeId);
                    }
                }

                logger.info("Trip saved — id=" + dbId
                        + " scores=[A=" + trip.anticipationScore
                        + " S=" + trip.smoothnessScore
                        + " SD=" + trip.speedDisciplineScore
                        + " E=" + trip.efficiencyScore
                        + " C=" + trip.consistencyScore + "]");
            }
        }

        // 6. Update range estimator
        if (rangeEstimator != null) {
            rangeEstimator.onTripCompleted(trip);
        }
        } finally {
            // Row now exists (or insert failed) — drop the in-flight marker so
            // the file is reapable again and recovery treats it normally.
            if (reArmedMarker) {
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().setActiveTripFile(null);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Resolve the trip's electrical energy use in kWh.
     *
     * <p>Prefers direct BMS kWh readings ({@link TripRecord#getEnergyUsedKwh()}).
     * When those are absent — the common case on trims that don't expose a clean
     * BMS energy channel — estimate from the SoC delta using the SohEstimator's
     * calibrated nominal pack capacity. Returns 0 when neither source is usable
     * (e.g. SoC flat or rose, no capacity estimate).
     *
     * <p>Called BEFORE scoring so the efficiency axis and the cost math both see
     * the same kWh figure on a single unit axis.
     */
    private double resolveTripEnergyKwh(TripRecord trip) {
        double energyUsed = trip.getEnergyUsedKwh();
        if (energyUsed > 0) {
            return energyUsed;
        }

        // Estimate from SoC delta via SohEstimator's calibrated nominal capacity.
        if (trip.socStart > 0 && trip.socEnd > 0 && trip.socStart > trip.socEnd) {
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null && soh.getNominalCapacityKwh() > 0) {
                    double nominalKwh = soh.getNominalCapacityKwh();
                    // BYD packs are LFP (Blade) and hold ≥98% SOH for the first
                    // ~1500 cycles, so 100% is a fair default until a live
                    // estimate seeds. Log when SOH is unseeded so a wrong number
                    // can be traced back to this branch.
                    // Use the DISPLAYED SOH (capped, anchored) so trip energy is in
                    // the same frame as the live remaining-kWh / SOH the user sees.
                    boolean hasSoh = soh.hasDisplaySoh();
                    double sohPercent = hasSoh ? soh.getDisplaySoh() : 100.0;
                    double usableKwh = nominalKwh * (sohPercent / 100.0);
                    energyUsed = ((trip.socStart - trip.socEnd) / 100.0) * usableKwh;
                    logger.info(String.format("Energy estimated from SoC: %.1f%% → %.1f%% = %.2f kWh (nominal=%.1f, SOH=%.1f%%%s)",
                            trip.socStart, trip.socEnd, energyUsed, nominalKwh, sohPercent,
                            hasSoh ? "" : ", default — no SOH seeded yet (LFP)"));
                }
            } catch (Exception e) {
                logger.warn("SohEstimator not available for energy estimation: " + e.getMessage());
            }
        }
        return energyUsed;
    }

    /**
     * Handle trip discarded event from TripDetector.
     * Stop recorder and clean up the telemetry file.
     */
    private void handleTripDiscarded(TripRecord trip, String reason) {
        logger.info("Trip discarded: " + reason);

        // Release telemetry polling ref (acquired in handleTripStarted)
        if (telemetryDataCollector != null) {
            telemetryDataCollector.stopPolling();
        }

        if (recorder != null) {
            String telemetryPath = recorder.stopRecording();

            // Clean up telemetry file
            if (telemetryPath != null) {
                File telemetryFile = new File(telemetryPath);
                if (telemetryFile.exists()) {
                    if (telemetryFile.delete()) {
                        logger.info("Discarded telemetry file: " + telemetryFile.getName());
                    } else {
                        logger.warn("Failed to delete discarded telemetry file: "
                                + telemetryFile.getName());
                    }
                }
            }
        }
    }
}
