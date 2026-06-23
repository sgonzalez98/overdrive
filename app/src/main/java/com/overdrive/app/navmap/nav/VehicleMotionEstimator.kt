package com.overdrive.app.navmap.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smooth, continuous vehicle motion from SPARSE, jittery GPS fixes.
 *
 * <p>The daemon's `/api/gps` delivers a new fix only every ~1-2s (and re-sends the
 * SAME fix in between), so rendering straight off it makes the puck/camera TELEPORT
 * every couple seconds. This estimator decouples the two cadences:
 *
 * <ul>
 *   <li>{@link #onTruthPoint} — called when a genuinely NEW fix arrives. It rejects
 *       bad-accuracy / out-of-order fixes, derives a stable bearing (from the
 *       position delta when moving, since raw GPS bearing is noisy), and EMA-blends
 *       the fix into a filtered state with a DISTANCE-ADAPTIVE alpha (snap hard on a
 *       small confident jump, ease gently on a big/uncertain one so GPS noise is
 *       absorbed). Speed and bearing are smoothed on their own factors.</li>
 *   <li>{@link #estimate} — called every RENDER FRAME (~12 fps micro-tick). Between
 *       fixes it dead-reckons the position FORWARD from the last filtered state along
 *       the smoothed bearing by {@code speed × elapsed}, capped at
 *       {@link #MAX_DEAD_RECKON_S}. So the puck glides continuously; it never
 *       outruns reality by more than ~1s of travel, and it FREEZES when stationary
 *       (raw GPS wanders when parked).</li>
 * </ul>
 *
 * <p>Pure Kotlin, framework-free (JVM-testable) — mirrors the rest of {@code nav/}.
 * NOT thread-safe; call from a single thread (the map's main/guidance thread).
 *
 * <p>The constants are tuned for a ~1-2s GPS cadence on a head unit; they are the
 * generic technique (accuracy-gated complementary filter + bounded dead-reckoning),
 * not specific to any one app.
 */
class VehicleMotionEstimator {

    /** One filtered motion sample. lat/lng degrees, speed m/s, bearing 0..360°. */
    data class Motion(
        val lat: Double,
        val lng: Double,
        val speedMps: Double,
        val bearingDeg: Double,
        val timestampMs: Long,
    )

    private var filtered: Motion? = null
    private var prevTruth: Motion? = null
    /** Timestamp of the last truth fix we ACCEPTED — to drop identical re-polls. */
    private var lastAcceptedTs: Long = 0L
    /** Last CAN brake-pedal position (0..100) from a truth fix, or null if the CAN bus
     *  didn't report it. Drives the dead-reckon deceleration model in [estimate]. */
    private var lastBrakePercent: Int? = null

    /** True once a first fix has been accepted (so callers can gate rendering). */
    fun hasFix(): Boolean = filtered != null

    /** Reset all state (call on nav start / stop so a new trip starts clean). */
    fun reset() {
        filtered = null
        prevTruth = null
        lastAcceptedTs = 0L
        lastBrakePercent = null
    }

    /**
     * Ingest a raw GPS fix. [accuracyM] and [rawBearingDeg] may be null (then we
     * derive bearing from motion / hold the last). [tsMs] is the fix's wall-clock
     * time; an identical re-poll (same or older ts) is ignored so we advance once
     * per real fix. Returns the new filtered [Motion] (or the unchanged current one).
     */
    fun onTruthPoint(
        lat: Double, lng: Double, speedMps: Double,
        rawBearingDeg: Double?, accuracyM: Double?, tsMs: Long,
        brakePercent: Int? = null,
    ): Motion {
        val acc = accuracyM ?: DEFAULT_ACCURACY_M
        val cur = filtered
        // Remember the latest CAN brake position for the dead-reckon decel model.
        // Held across re-polls (a duplicate fix returns early below without clearing it).
        if (brakePercent != null) lastBrakePercent = brakePercent

        // First fix → seed directly.
        if (cur == null) {
            val seed = Motion(lat, lng, speedMps, normalize(rawBearingDeg ?: 0.0), tsMs)
            filtered = seed; prevTruth = seed; lastAcceptedTs = tsMs
            return seed
        }
        // Reject obviously-bad accuracy + out-of-order / duplicate re-polls.
        if (acc > MAX_ACCEPTED_ACCURACY_M) return cur
        if (tsMs <= lastAcceptedTs) return cur
        if (tsMs + MAX_OUT_OF_ORDER_FIX_AGE_MS < cur.timestampMs) return cur

        val prev = prevTruth
        val moved = haversine(cur.lat, cur.lng, lat, lng)

        // --- Bearing source: prefer position-delta when clearly moving (stabler than
        //     raw GPS bearing); else raw when fast; else hold the last filtered. ---
        val bearing: Double = when {
            speedMps > 2.2 && rawBearingDeg != null -> rawBearingDeg
            speedMps > 1.2 && prev != null &&
                haversine(prev.lat, prev.lng, lat, lng) > 3.0 ->
                bearingBetween(prev.lat, prev.lng, lat, lng)
            else -> cur.bearingDeg
        }

        // Stationary on both sides → just refresh timestamp, freeze position.
        if (speedMps < STATIONARY_SPEED_MPS && cur.speedMps < STATIONARY_SPEED_MPS) {
            val held = cur.copy(timestampMs = tsMs)
            filtered = held; prevTruth = Motion(lat, lng, speedMps, bearing, tsMs)
            lastAcceptedTs = tsMs
            return held
        }

        val goodAcc = acc <= GOOD_ACCURACY_M
        val speedDelta = speedMps - cur.speedMps
        // Distance-adaptive position alpha: confident small jump → snap (0.68);
        // far/uncertain jump → ease (0.18) to swallow multipath noise.
        val posAlpha = when {
            goodAcc && abs(speedDelta) >= 2.0 && moved <= 30.0 -> 0.68
            goodAcc && moved <= 8.0 -> 0.62
            !goodAcc || moved > 8.0 -> when {
                moved > 35.0 -> if (goodAcc) 0.18 else 0.10
                moved > 12.0 -> if (goodAcc) 0.38 else 0.22
                else -> if (goodAcc) 0.52 else 0.36
            }
            else -> 0.52
        }
        // Speed alpha: respond faster to braking than to mild changes.
        val speedAlpha = when {
            speedDelta < -2.0 -> 0.72
            speedDelta > 3.0 -> 0.55
            else -> 0.35
        }
        // Bearing alpha: snap on a big heading change (turn), ease otherwise.
        val bearingAlpha = when {
            abs(shortestArc(cur.bearingDeg, bearing)) > 80.0 -> 0.10
            speedMps < 3.0 -> 0.08
            else -> 0.22
        }

        val (nlat, nlng) = interpolate(cur.lat, cur.lng, lat, lng, posAlpha)
        var nspeed = cur.speedMps + (speedMps - cur.speedMps) * speedAlpha
        if (speedMps < STATIONARY_SPEED_MPS && nspeed < STATIONARY_SPEED_MPS) nspeed = 0.0
        val nbearing = normalize(cur.bearingDeg + shortestArc(cur.bearingDeg, bearing) * bearingAlpha)

        val next = Motion(nlat, nlng, nspeed, nbearing, tsMs)
        filtered = next
        prevTruth = Motion(lat, lng, speedMps, bearing, tsMs)
        lastAcceptedTs = tsMs
        return next
    }

    /**
     * Predict the motion at [nowMs] by dead-reckoning forward from the last filtered
     * fix. Returns null until the first fix. When stationary, returns the held
     * position (no creep). The predicted distance is capped at {@link #MAX_DEAD_RECKON_S}
     * of travel so a dropped fix can't send the puck flying.
     */
    fun estimate(nowMs: Long): Motion? {
        val m = filtered ?: return null
        if (m.speedMps < STATIONARY_SPEED_MPS) return m.copy(timestampMs = nowMs)
        val dtS = ((nowMs - m.timestampMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(MAX_DEAD_RECKON_S)
        if (dtS <= 0.0) return m
        // Brake-aware dead-reckoning: at constant speed the puck over-travels when the
        // driver is braking (the next fix lands SHORTER than predicted → the puck
        // snaps back). When the CAN bus reports the brake pedal pressed, shed predicted
        // speed over the window with a constant-decel model so the puck eases toward a
        // stop. The decel rate scales with pedal travel up to BRAKE_MAX_DECEL_MPS2
        // (a firm-but-not-emergency stop); a light/zero brake leaves motion unchanged.
        // dist = integral of v(t) over [0,dtS] with v(t)=v0 - a*t, clamped at the stop.
        val brake = lastBrakePercent ?: 0
        val dist: Double
        if (brake > BRAKE_MIN_PERCENT) {
            val a = BRAKE_MAX_DECEL_MPS2 * (brake.coerceAtMost(100) / 100.0)
            val tStop = m.speedMps / a                       // time to reach 0 speed
            val tEff = minOf(dtS, tStop)                     // don't integrate past the stop
            dist = m.speedMps * tEff - 0.5 * a * tEff * tEff // ∫(v0 - a t) dt
        } else {
            dist = m.speedMps * dtS
        }
        val (plat, plng) = destinationPoint(m.lat, m.lng, m.bearingDeg, dist)
        return m.copy(lat = plat, lng = plng, timestampMs = nowMs)
    }

    // ── geo helpers (self-contained; degrees in, meters where noted) ──────────────

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLng)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** Point [distM] meters from (lat,lng) along [bearingDeg]. Returns (lat,lng). */
    private fun destinationPoint(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6371000.0
        val d = distM / r
        val br = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lng)
        val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Linear interpolation between two coords by [t] (0=a, 1=b). Fine at these distances. */
    private fun interpolate(lat1: Double, lng1: Double, lat2: Double, lng2: Double, t: Double): Pair<Double, Double> =
        (lat1 + (lat2 - lat1) * t) to (lng1 + (lng2 - lng1) * t)

    /** Shortest signed angular delta from→to, in -180..180. */
    private fun shortestArc(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    private fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    companion object {
        private const val GOOD_ACCURACY_M = 18.0
        private const val MAX_ACCEPTED_ACCURACY_M = 55.0
        private const val DEFAULT_ACCURACY_M = 20.0
        private const val MAX_OUT_OF_ORDER_FIX_AGE_MS = 1500L
        // Max forward dead-reckon (s). MUST cover the real inter-fix gap or the puck
        // FREEZES at the cap and then JUMPS when the next fix lands — the "laggy /
        // not smooth" symptom. The daemon's /api/gps re-sends the SAME fix for ~2s
        // between genuinely-new fixes, so a 1.2s cap stranded the puck for ~0.8s
        // every cycle. 3.0s comfortably bridges a ~2s cadence (plus a missed poll)
        // while still bounding a fully-dropped fix to ~3s of travel.
        private const val MAX_DEAD_RECKON_S = 3.0
        private const val STATIONARY_SPEED_MPS = 1.4
        // Brake-anticipation dead-reckon (CAN brakePercent). Below BRAKE_MIN_PERCENT the
        // pedal is effectively released (sensor noise / light coast) → constant-speed
        // extrapolation. At full travel the model decelerates at BRAKE_MAX_DECEL_MPS2
        // (~3 m/s² — a firm comfortable stop, well under an ABS emergency ~8 m/s²), so a
        // hard brake eases the puck to a halt over the ~1-2s inter-fix gap instead of
        // sailing past the stop line and snapping back when the next fix lands short.
        private const val BRAKE_MIN_PERCENT = 8
        private const val BRAKE_MAX_DECEL_MPS2 = 3.0
    }
}
