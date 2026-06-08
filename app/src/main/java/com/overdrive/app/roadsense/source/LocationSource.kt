package com.overdrive.app.roadsense.source

import com.overdrive.app.monitor.GpsMonitor
import com.overdrive.app.roadsense.detect.ALTITUDE_UNKNOWN
import com.overdrive.app.roadsense.detect.Pose

/**
 * Adapts the daemon's already-running [GpsMonitor] singleton into the RoadSense
 * [Pose] contract (D-020).
 *
 * RoadSense does NOT register its own `LocationManager` — `LocationSidecarService`
 * (app side) already streams GPS to the daemon, and `GpsMonitor` (daemon side)
 * holds the latest fix with staleness metadata. RoadSense, running in the daemon
 * (D-023), reads that singleton. The `GpsRingBuffer` (separate stage) keeps a
 * short history of these [Pose]s for back-projection with fix-latency compensation
 * (R-EXT-2) — this adapter just exposes the current fix.
 *
 * GpsMonitor accessors are simple volatile reads (lat/lng/speed/heading/accuracy
 * + lastUpdate), safe from any thread.
 *
 * Pure read-through, no state, thread-safe. `nowMs` injected for testability.
 */
class LocationSource(
    private val monitor: () -> GpsMonitor = { GpsMonitor.getInstance() },
) {

    /** Whether GPS is live (sidecar feeding the monitor). */
    fun isRunning(): Boolean = monitor().isRunning

    /**
     * Latest pose, or null if we have no usable fix yet. A 0/0 location or an
     * absurdly stale fix returns null rather than a bogus Pose, so the pipeline
     * never localizes a hazard at null-island or kilometres from reality.
     *
     * @param nowMs       current wall-clock ms (injected for testability).
     * @param maxAgeMs    fixes older than this are treated as no-fix. Default
     *                    aligns with "a few seconds" — older than that and a
     *                    100 Hz event can't be back-projected accurately anyway.
     */
    fun latest(nowMs: Long, maxAgeMs: Long = DEFAULT_MAX_FIX_AGE_MS): Pose? {
        val m = monitor()
        val lat = m.latitude
        val lng = m.longitude
        // Reject the "no fix" sentinel (0,0) — RoadSense must never map a hazard
        // at null-island. A real fix in the Gulf of Guinea is not our problem.
        if (lat == 0.0 && lng == 0.0) return null
        val age = (nowMs - m.lastUpdate).coerceAtLeast(0L)
        if (m.lastUpdate <= 0L || age > maxAgeMs) return null
        return Pose(
            tMs = m.lastUpdate,
            lat = lat,
            lng = lng,
            speedMps = m.speed,         // GpsMonitor.getSpeed() is m/s (Location.getSpeed)
            bearingDeg = m.heading,
            accuracyM = m.accuracy,
            // GpsMonitor stores 0.0 when the fix had no altitude (Location.hasAltitude
            // false) — indistinguishable from a real sea-level fix, so map 0.0 to
            // UNKNOWN and stay fail-open rather than risk a bogus level mismatch.
            altitudeM = m.altitude.let { if (it == 0.0) ALTITUDE_UNKNOWN else it },
        )
    }

    companion object {
        /** Fixes older than ~5 s are not useful for back-projecting a fast event. */
        const val DEFAULT_MAX_FIX_AGE_MS = 5_000L
    }
}
