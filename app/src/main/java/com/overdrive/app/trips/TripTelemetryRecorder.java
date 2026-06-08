package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;
import com.overdrive.app.telemetry.TelemetrySnapshot;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Captures 5Hz telemetry by reading from existing singleton monitors.
 * Buffers samples in memory, flushes 1Hz-downsampled gzipped JSON-lines to disk.
 *
 * Does NOT create its own BYD device handles — reads from TelemetryDataCollector,
 * GpsMonitor, VehicleDataMonitor, and GearMonitor which are already running.
 */
public class TripTelemetryRecorder {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripTelemetryRecorder");

    private static final long SAMPLE_INTERVAL_MS = 200;       // 5Hz
    private static final long FLUSH_INTERVAL_MS = 60_000;     // 60s
    private static final long MAX_BUFFER_BYTES = 10 * 1024 * 1024; // 10MB

    // Dependencies: existing singleton monitors
    private volatile TelemetryDataCollector telemetryDataCollector;

    // Executor for 5Hz sampling
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> sampleFuture;
    private ScheduledFuture<?> flushFuture;

    // Buffer (guarded by bufferLock)
    private final Object bufferLock = new Object();
    private ArrayList<TelemetrySample> buffer = new ArrayList<>();
    private long estimatedBufferBytes = 0;

    // All captured 5Hz samples for scoring (not cleared on flush)
    private final Object allSamplesLock = new Object();
    private ArrayList<TelemetrySample> allSamples = new ArrayList<>();

    // Trip state
    private volatile boolean recording = false;
    private long currentTripId = -1;
    private File outputFile;

    // Stats tracking
    private int maxSpeedKmh = 0;
    private long speedSumKmh = 0;
    private long speedSampleCount = 0;
    
    // GPS distance tracking (running haversine sum).
    // volatile: written only on the 5Hz sampler thread but read on the
    // detector/finalize thread via getTotalDistanceKm() (the GPS-distance
    // fallback). Single writer, so volatile fully resolves the cross-thread
    // visibility — the finalize read sees the latest accumulated distance.
    private volatile double totalDistanceKm = 0;
    private double lastLat = 0;
    private double lastLon = 0;
    private boolean hasLastGps = false;

    // GPS coverage tracking — how many samples landed valid lat/lon. Logged
    // at trip end so the daemon log alone tells us why a trip's map is blank
    // (no GPS during recording vs. file write failure vs. UI bug).
    private long sampleCountTotal = 0;
    private long sampleCountWithGps = 0;

    /**
     * Constructor takes TelemetryDataCollector as parameter (injected from TripAnalyticsManager).
     * May be null if TelemetryDataCollector hasn't been initialized yet (GPU init delay).
     */
    public TripTelemetryRecorder(TelemetryDataCollector telemetryDataCollector) {
        this.telemetryDataCollector = telemetryDataCollector;
    }

    /**
     * Update the TelemetryDataCollector reference after late initialization.
     * Called by CameraDaemon once TelemetryDataCollector is ready (after GPU init delay).
     */
    public void setTelemetryDataCollector(TelemetryDataCollector collector) {
        this.telemetryDataCollector = collector;
    }

    /**
     * Start recording telemetry for the given trip.
     * Starts the 5Hz sampling timer and periodic flush timer.
     */
    public void startRecording(long tripId) {
        if (recording) {
            logger.warn("Already recording trip " + currentTripId + ", ignoring start for " + tripId);
            return;
        }

        this.currentTripId = tripId;
        this.outputFile = new File(StorageManager.getInstance().getTripsDir(),
                tripId + ".jsonl.gz");
        this.maxSpeedKmh = 0;
        this.speedSumKmh = 0;
        this.speedSampleCount = 0;
        this.totalDistanceKm = 0;
        this.lastLat = 0;
        this.lastLon = 0;
        this.hasLastGps = false;
        this.sampleCountTotal = 0;
        this.sampleCountWithGps = 0;

        synchronized (bufferLock) {
            buffer.clear();
            estimatedBufferBytes = 0;
        }
        synchronized (allSamplesLock) {
            allSamples.clear();
        }

        recording = true;

        // Mark this file as in-flight so StorageManager.ensureTripsSpace
        // won't unlink it during a limit-change cleanup mid-trip.
        try {
            StorageManager.getInstance().setActiveTripFile(outputFile);
        } catch (Exception e) {
            logger.warn("Failed to mark active trip file: " + e.getMessage());
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TripTelemetry-" + tripId);
            t.setDaemon(true);
            return t;
        });

        // 5Hz sampling
        sampleFuture = executor.scheduleAtFixedRate(
                this::sample, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Periodic flush every 60s
        flushFuture = executor.scheduleAtFixedRate(
                this::flushBuffer, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        logger.info("Started recording trip " + tripId + " → " + outputFile.getAbsolutePath());
    }

    /**
     * Stop recording. Flushes remaining buffer, closes file.
     * @return the telemetry file path, or null if not recording
     */
    public String stopRecording() {
        if (!recording) {
            logger.warn("Not recording, ignoring stop");
            return null;
        }

        recording = false;
        logger.info("Stopping recording for trip " + currentTripId);

        // Cancel scheduled tasks
        if (sampleFuture != null) sampleFuture.cancel(false);
        if (flushFuture != null) flushFuture.cancel(false);

        // Final flush of remaining buffer
        flushBuffer();

        // Shutdown executor
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        // Notify StorageManager — clear the in-flight marker first so the
        // post-save cleanup it triggers can reap this file if a downward
        // limit change made it the oldest over-limit file.
        try {
            StorageManager.getInstance().setActiveTripFile(null);
            StorageManager.getInstance().onTripFileSaved();
        } catch (Exception e) {
            logger.warn("Failed to notify StorageManager: " + e.getMessage());
        }

        String path = outputFile != null ? outputFile.getAbsolutePath() : null;
        long fileBytes = (outputFile != null && outputFile.exists()) ? outputFile.length() : -1;
        long gpsPct = sampleCountTotal == 0
                ? 0
                : Math.round(100.0 * sampleCountWithGps / sampleCountTotal);
        logger.info("Stopped recording trip " + currentTripId +
                " (samples=" + speedSampleCount +
                ", maxSpeed=" + maxSpeedKmh +
                ", gps=" + sampleCountWithGps + "/" + sampleCountTotal + " (" + gpsPct + "%)" +
                ", fileBytes=" + (fileBytes < 0 ? "missing" : Long.toString(fileBytes)) +
                ", file=" + path + ")");

        return path;
    }

    /**
     * Returns the file path for a given trip ID.
     */
    public String getTelemetryFilePath(long tripId) {
        return new File(StorageManager.getInstance().getTripsDir(),
                tripId + ".jsonl.gz").getAbsolutePath();
    }

    /**
     * Returns all captured 5Hz samples for score computation (before downsampling).
     */
    public List<TelemetrySample> getSamplesForScoring() {
        synchronized (allSamplesLock) {
            return new ArrayList<>(allSamples);
        }
    }

    /**
     * Get the maximum speed recorded during this trip.
     */
    public int getMaxSpeedKmh() {
        return maxSpeedKmh;
    }

    /**
     * Get the average speed recorded during this trip.
     */
    public double getAvgSpeedKmh() {
        return speedSampleCount > 0 ? (double) speedSumKmh / speedSampleCount : 0.0;
    }

    /**
     * Get the total GPS distance recorded during this trip (km).
     */
    public double getTotalDistanceKm() {
        return totalDistanceKm;
    }

    /**
     * Haversine formula: distance between two GPS coordinates in km.
     */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ==================== PRIVATE: Sampling ====================

    /**
     * Called at 5Hz. Reads from existing singleton monitors and buffers a sample.
     */
    private void sample() {
        if (!recording) return;

        try {
            long now = System.currentTimeMillis();

            // Read speed/accel/brake/brakePedalPressed from TelemetryDataCollector
            TelemetryDataCollector collector = telemetryDataCollector;
            TelemetrySnapshot snapshot = collector != null ? collector.getLatestSnapshot() : null;
            int speedKmh = 0;
            int accelPedal = 0;
            int brakePedal = 0;
            boolean brakePedalPressed = false;
            // True when we couldn't read a fresh dynamics snapshot this tick.
            // Such a sample carries synthetic zeros — fine to persist in the raw
            // .jsonl.gz for timeline continuity, but it must NOT feed the scoring
            // buffer or the speed stats, where a fabricated 0 km/h would
            // manufacture a phantom stop / launch / coast and dilute the jerk and
            // consistency windows.
            boolean dynamicsStale = true;
            if (snapshot != null) {
                // Check if snapshot is stale (older than 2 seconds means poller may have died)
                long snapshotAge = now - snapshot.timestampMs;
                if (snapshotAge < 2000) {
                    speedKmh = snapshot.speedKmh;
                    accelPedal = snapshot.accelPedalPercent;
                    brakePedal = snapshot.brakePedalPercent;
                    brakePedalPressed = snapshot.brakePedalPressed;
                    dynamicsStale = false;
                } else {
                    // Stale snapshot — record zeros instead of frozen values
                    if (snapshotAge < 5000) {
                        // Only log once per staleness episode (within first 5s)
                        logger.warn("Telemetry snapshot stale (" + snapshotAge + "ms old), recording zeros");
                    }
                }
            }

            // Read GPS from GpsMonitor
            GpsMonitor gps = GpsMonitor.getInstance();
            double lat = gps.getLatitude();
            double lon = gps.getLongitude();
            double altitude = gps.getAltitude();

            // Read gear from GearMonitor
            int gearMode = GearMonitor.getInstance().getCurrentGear();

            TelemetrySample sample = new TelemetrySample(
                    now, speedKmh, accelPedal, brakePedal,
                    brakePedalPressed, gearMode, lat, lon, altitude);

            // Track GPS distance (haversine) and coverage stats.
            sampleCountTotal++;
            if (lat != 0 && lon != 0) {
                sampleCountWithGps++;
                if (hasLastGps && lastLat != 0 && lastLon != 0) {
                    double dist = haversineKm(lastLat, lastLon, lat, lon);
                    // Filter out GPS jumps (>500m in 200ms is impossible at any speed)
                    if (dist < 0.5) {
                        totalDistanceKm += dist;
                    }
                }
                lastLat = lat;
                lastLon = lon;
                hasLastGps = true;
            }

            // Track stats — only from real readings; synthetic stale zeros would
            // drag the average down and never affect max anyway.
            if (!dynamicsStale) {
                if (speedKmh > maxSpeedKmh) {
                    maxSpeedKmh = speedKmh;
                }
                speedSumKmh += speedKmh;
                speedSampleCount++;
            }

            // Add to scoring buffer (real-dynamics 5Hz samples only). Stale
            // synthetic-zero samples are still written to the flush buffer below
            // for raw-timeline continuity, but are kept out of the single-pass
            // scoring stream so they can't fabricate stop/launch/coast events.
            if (!dynamicsStale) {
                synchronized (allSamplesLock) {
                    allSamples.add(sample);
                }
            }

            // Add to flush buffer
            synchronized (bufferLock) {
                buffer.add(sample);
                // Rough estimate: ~100 bytes per sample
                estimatedBufferBytes += 100;

                // Force flush if buffer exceeds 10MB threshold
                if (estimatedBufferBytes >= MAX_BUFFER_BYTES) {
                    logger.info("Buffer exceeded 10MB threshold, force-flushing");
                    executor.execute(this::flushBuffer);
                }
            }
        } catch (Throwable e) {
            // Catch Throwable to prevent ScheduledExecutorService from silently stopping
            logger.warn("Sample error: " + e.getMessage());
        }
    }

    // ==================== PRIVATE: Flush Pipeline ====================

    /**
     * Flush pipeline:
     * 1. Copy buffer to local list, clear buffer
     * 2. Downsample 5Hz → 1Hz: group by second, pick closest to each whole-second boundary
     * 3. Serialize each 1Hz sample as JSON line using TelemetrySample.toJson()
     * 4. Write gzipped chunk and append to the output file
     */
    private void flushBuffer() {
        List<TelemetrySample> toFlush;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) return;
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
            estimatedBufferBytes = 0;
        }

        // Downsample 5Hz → 1Hz
        List<TelemetrySample> downsampled = downsampleTo1Hz(toFlush);

        if (downsampled.isEmpty()) return;

        // Serialize and write gzipped chunk
        try {
            writeGzippedChunk(downsampled);
        } catch (IOException e) {
            logger.error("Failed to write telemetry chunk: " + e.getMessage());
            // Per requirement 2.7: log error and continue, don't crash
        }
    }

    /**
     * 1Hz downsampling: for each whole-second boundary present in the data,
     * select the sample with timestamp closest to that boundary.
     */
    static List<TelemetrySample> downsampleTo1Hz(List<TelemetrySample> samples) {
        if (samples == null || samples.isEmpty()) return new ArrayList<>();

        // Group samples by their whole-second (floor to nearest second)
        Map<Long, List<TelemetrySample>> bySecond = new HashMap<>();
        for (TelemetrySample s : samples) {
            long secondBoundary = (s.timestampMs / 1000) * 1000;
            List<TelemetrySample> group = bySecond.get(secondBoundary);
            if (group == null) {
                group = new ArrayList<>();
                bySecond.put(secondBoundary, group);
            }
            group.add(s);
        }

        // For each second boundary, pick the sample closest to that boundary
        List<Long> sortedSeconds = new ArrayList<>(bySecond.keySet());
        java.util.Collections.sort(sortedSeconds);

        List<TelemetrySample> result = new ArrayList<>(sortedSeconds.size());
        for (long boundary : sortedSeconds) {
            List<TelemetrySample> group = bySecond.get(boundary);
            TelemetrySample closest = null;
            long closestDist = Long.MAX_VALUE;
            for (TelemetrySample s : group) {
                long dist = Math.abs(s.timestampMs - boundary);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = s;
                }
            }
            if (closest != null) {
                result.add(closest);
            }
        }

        return result;
    }

    /**
     * Write a list of 1Hz samples as a gzipped JSON-lines chunk appended to the output file.
     * Per-chunk approach: each flush creates a temp buffer, gzips it, and appends to the file.
     *
     * <p>Re-resolves the output directory against StorageManager.getTripsDir() on each
     * flush. The directory pointer can flip mid-trip when the SD card unmounts/remounts
     * (the watchdog rebinds tripsDir to internal storage and back). Without re-resolution,
     * we'd keep appending to a path under a now-absent volume, every flush would IOException,
     * and the trip would end with an empty file even though the recorder logs "ok".
     */
    private void writeGzippedChunk(List<TelemetrySample> samples) throws IOException {
        if (outputFile == null) return;

        // Build JSON-lines content
        StringBuilder sb = new StringBuilder();
        for (TelemetrySample sample : samples) {
            sb.append(sample.toJson().toString()).append('\n');
        }

        // Gzip the content
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(sb.toString().getBytes("UTF-8"));
        }

        // Re-resolve target file if its directory has gone away mid-trip.
        // We migrate any partial bytes from the stale path to the live one so
        // a chunk-by-chunk flush stays as one continuous file post-trip.
        File target = resolveOutputFileForFlush();

        // Append gzipped bytes to the (possibly relocated) output file.
        try (OutputStream fos = new BufferedOutputStream(
                new FileOutputStream(target, true))) {
            fos.write(baos.toByteArray());
        }

        logger.info("Flushed " + samples.size() + " 1Hz samples to " + target.getName() +
                " (" + baos.size() + " bytes gzipped)");
    }

    /**
     * Re-resolve the output file against the live {@link StorageManager#getTripsDir()}
     * before each flush. Two distinct cases trigger a migration:
     * <ol>
     *   <li>The original parent volume is gone (SD unmounted mid-trip).</li>
     *   <li>The user explicitly switched the trips storage type via the UI;
     *       the original volume may still be writable, but continuing on it
     *       silently ignores the user's intent. We honor the new selection
     *       on the next flush boundary.</li>
     * </ol>
     *
     * <p>When the live dir differs from the current parent, any prior chunk
     * bytes are copied to the new path before continuing, the source file is
     * removed (otherwise it would survive on the old volume as an orphan),
     * and {@link #outputFile} plus the cleanup-protection marker are
     * rebound to the new path so subsequent flushes and stopRecording's
     * path report see the live location.
     */
    private File resolveOutputFileForFlush() {
        if (outputFile == null) return outputFile;
        File parent = outputFile.getParentFile();
        File liveDir = StorageManager.getInstance().getTripsDir();
        if (liveDir == null) return outputFile;

        // Same volume: nothing to do. The canWrite() check covers the rare
        // case where the live dir == current parent but has gone read-only
        // (FUSE-bridged SD under heavy GL contention occasionally drops to
        // RO until vold catches up); we let the next flush retry.
        if (liveDir.equals(parent)) {
            return outputFile;
        }

        File newPath = new File(liveDir, outputFile.getName());
        if (newPath.equals(outputFile)) {
            return outputFile;
        }

        File oldPath = outputFile;
        boolean migrated = false;
        if (oldPath.exists() && oldPath.length() > 0
                && parent != null && parent.exists()) {
            try {
                java.nio.file.Files.copy(
                        oldPath.toPath(),
                        newPath.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                migrated = true;
                logger.info("Trip telemetry migrated: " + oldPath.getAbsolutePath()
                        + " -> " + newPath.getAbsolutePath());
            } catch (Exception e) {
                logger.warn("Trip telemetry migration failed: " + e.getMessage()
                        + " — continuing at " + newPath.getAbsolutePath());
            }
        } else {
            // Silent-skip cases (old volume unmounted, file not yet created).
            // Log so operators can correlate "trip ended on a different
            // path than it started" with a known volume event.
            logger.info("Trip telemetry rebinding without copy ("
                    + (oldPath.exists() ? "empty file" : "old parent gone")
                    + "): " + (parent == null ? "<no parent>" : parent.getAbsolutePath())
                    + " -> " + liveDir.getAbsolutePath());
        }

        // Update the cleanup-protection marker BEFORE rebinding outputFile.
        // Order matters: between assigning outputFile=newPath and calling
        // setActiveTripFile(newPath), a concurrent ensureTripsSpace would
        // see activeTripFilePath still pointing at oldPath, leaving newPath
        // unprotected. Cleanup runs from the 30s periodic tick so the race
        // window was sub-µs in practice, but the simpler invariant is to
        // mark the destination protected first; only after that commit do
        // we point outputFile at it.
        boolean markerUpdated = false;
        try {
            StorageManager.getInstance().setActiveTripFile(newPath);
            markerUpdated = true;
        } catch (Exception e) {
            logger.warn("Failed to update active trip file marker after migration: "
                    + e.getMessage());
        }
        outputFile = newPath;

        // Remove the source so the old volume doesn't accumulate an orphan
        // .jsonl.gz that would only get reaped when ensureTripsSpace next
        // walks the inactive volume. Skipped when:
        //   - copy failed → source is the only surviving copy of those bytes.
        //   - marker update failed → cleanup still sees oldPath as the
        //     protected file, so newPath is unprotected. Deleting the source
        //     here would trade one corruption window for another. Leave both
        //     files until the next flush retries the marker update.
        if (migrated && markerUpdated && oldPath.exists()) {
            if (!oldPath.delete()) {
                logger.warn("Failed to remove migrated source: " + oldPath.getAbsolutePath());
            }
        }

        return outputFile;
    }
}
