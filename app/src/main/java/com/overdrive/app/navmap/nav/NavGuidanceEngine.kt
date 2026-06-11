package com.overdrive.app.navmap.nav

import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The turn-by-turn guidance state machine — our hand-rolled replacement for
 * ferrostar's core (rejected: Kotlin 2.3.0 / desugaring / JNA-Rust .so vs this
 * project's Kotlin 2.0.21).
 *
 * <p>It holds an active [NavRoute] and is fed raw GPS fixes by the Activity via
 * [update]. On each fix it snaps the position to the route, detects off-route,
 * resolves the current/next maneuver, and computes remaining distance + ETA and
 * arrival. It is PURE logic (only `android.util.Log` from the framework),
 * deterministic, and unit-testable.
 *
 * <p>Units: latitude/longitude are WGS-84 decimal degrees; all distances are
 * meters; all durations/ETAs are seconds. Threading: instances are NOT
 * thread-safe — call [start]/[update]/[stop] from a single thread (the
 * location-callback thread).
 */
class NavGuidanceEngine {

    companion object {
        private const val TAG = "NavGuidanceEngine"

        /** Earth mean radius in meters (for the haversine helper). */
        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Lateral distance from the route (meters) beyond which a fix counts as
         * a candidate off-route sample.
         */
        const val OFF_ROUTE_THRESHOLD_M = 50.0

        /**
         * Number of consecutive off-route-candidate fixes required before
         * [GuidanceState.offRoute] latches true (debounce against GPS jitter).
         */
        const val OFF_ROUTE_CONSECUTIVE = 3

        /**
         * Distance (meters) from the destination within which arrival is
         * declared.
         */
        const val ARRIVAL_RADIUS_M = 25.0
    }

    private var route: NavRoute? = null

    /** Count of consecutive fixes whose route distance exceeded the threshold. */
    private var consecutiveOffRoute = 0

    /**
     * Begin guidance along [route]. Resets the off-route debounce counter.
     *
     * @param route the active route to guide along
     */
    fun start(route: NavRoute) {
        this.route = route
        this.consecutiveOffRoute = 0
        Log.i(TAG, "guidance started: ${route.points.size} pts, ${route.maneuvers.size} maneuvers")
    }

    /** Stop guidance and clear all state. Subsequent [update] calls no-op. */
    fun stop() {
        this.route = null
        this.consecutiveOffRoute = 0
        Log.i(TAG, "guidance stopped")
    }

    /** Whether a route is currently loaded (between [start] and [stop]). */
    fun isActive(): Boolean = route != null

    /**
     * Process one GPS fix and return the derived [GuidanceState].
     *
     * <p>If no route is active (no [start] yet, or after [stop]) this returns a
     * neutral state echoing the input position with no maneuver. Otherwise it:
     * snaps the fix to the nearest route segment; updates the off-route latch;
     * resolves the next maneuver and the distance to it; and computes remaining
     * distance, ETA and arrival.
     *
     * @param lat fix latitude in decimal degrees
     * @param lng fix longitude in decimal degrees
     * @return the guidance state for this fix
     */
    fun update(lat: Double, lng: Double): GuidanceState {
        val r = route ?: return GuidanceState(
            snappedLat = lat,
            snappedLng = lng,
            offRoute = false,
            arrived = false,
            currentManeuver = null,
            distanceToManeuverM = 0.0,
            remainingDistanceM = 0.0,
            etaSeconds = 0.0
        )

        val pts = r.points
        if (pts.size < 2) {
            // Degenerate route — fall back to point-distance arrival on the
            // single vertex (if any).
            val arrived = pts.isNotEmpty() &&
                haversineMeters(lat, lng, pts[0].lat, pts[0].lng) <= ARRIVAL_RADIUS_M
            return GuidanceState(lat, lng, false, arrived, null, 0.0, 0.0, 0.0)
        }

        // 1) Snap to the nearest segment. Track which segment and how far along it.
        var bestDist = Double.MAX_VALUE
        var bestSegStart = 0
        var bestT = 0.0
        var bestLat = pts[0].lat
        var bestLng = pts[0].lng
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val snap = pointToSegment(lat, lng, a.lat, a.lng, b.lat, b.lng)
            if (snap.distMeters < bestDist) {
                bestDist = snap.distMeters
                bestSegStart = i
                bestT = snap.t
                bestLat = snap.lat
                bestLng = snap.lng
            }
        }

        // 2) Off-route debounce: latch true after N consecutive far fixes.
        if (bestDist > OFF_ROUTE_THRESHOLD_M) {
            consecutiveOffRoute++
        } else {
            consecutiveOffRoute = 0
        }
        val offRoute = consecutiveOffRoute >= OFF_ROUTE_CONSECUTIVE

        // 3) Remaining distance: partial remainder of the current segment plus
        //    every subsequent full segment.
        val segLen = haversineMeters(
            pts[bestSegStart].lat, pts[bestSegStart].lng,
            pts[bestSegStart + 1].lat, pts[bestSegStart + 1].lng
        )
        var remaining = segLen * (1.0 - bestT)
        for (i in bestSegStart + 1 until pts.size - 1) {
            remaining += haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
        }

        // 4) Arrival: within the radius of the final route vertex.
        val last = pts[pts.size - 1]
        val distToEnd = haversineMeters(lat, lng, last.lat, last.lng)
        val arrived = distToEnd <= ARRIVAL_RADIUS_M

        // 5) ETA: scale the route's total duration by the remaining fraction of
        //    its total distance (proportional model — robust when per-maneuver
        //    times are absent). Falls back to 0 when totals are missing.
        val etaSeconds = if (r.totalDistanceMeters > 0.0 && r.totalDurationSeconds > 0.0) {
            r.totalDurationSeconds * (remaining / r.totalDistanceMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        // 6) Next maneuver: the first maneuver whose begin vertex is at or ahead
        //    of where we are. "Ahead" = its begin index is past the current
        //    segment, or on the current segment but ahead of our progress.
        val maneuver = nextManeuver(r, bestSegStart, bestT)
        val distanceToManeuver = if (maneuver != null) {
            distanceAlongTo(pts, bestSegStart, bestT, bestLat, bestLng, maneuver.beginShapeIndex)
        } else {
            0.0
        }

        return GuidanceState(
            snappedLat = bestLat,
            snappedLng = bestLng,
            offRoute = offRoute,
            arrived = arrived,
            currentManeuver = maneuver,
            distanceToManeuverM = distanceToManeuver,
            remainingDistanceM = remaining,
            etaSeconds = etaSeconds
        )
    }

    /**
     * The next maneuver to announce: the first maneuver strictly ahead of the
     * current position. A maneuver at vertex `k` is "ahead" when `k` is beyond
     * the current segment start, or equal to it while we have not yet passed it
     * along the segment. The terminal destination maneuver is included.
     */
    private fun nextManeuver(r: NavRoute, segStart: Int, t: Double): RouteManeuver? {
        for (m in r.maneuvers) {
            val k = m.beginShapeIndex
            if (k > segStart) return m
            if (k == segStart + 1) return m
            // k <= segStart: behind us; or k == segStart with t already moving
            // off the vertex — treat as passed, keep scanning.
        }
        return null
    }

    /**
     * Distance (meters) along the route from the snapped position to the route
     * vertex at [targetIndex]: the remainder of the current segment up to its
     * end vertex, plus every full segment up to [targetIndex].
     */
    private fun distanceAlongTo(
        pts: List<GeoPoint>,
        segStart: Int,
        t: Double,
        snapLat: Double,
        snapLng: Double,
        targetIndex: Int
    ): Double {
        if (targetIndex <= segStart) {
            // Target is the current segment's start (already passed) — distance 0.
            return 0.0
        }
        // Remainder of the current segment from the snap point to vertex segStart+1.
        var dist = haversineMeters(snapLat, snapLng, pts[segStart + 1].lat, pts[segStart + 1].lng)
        var i = segStart + 1
        while (i < targetIndex && i < pts.size - 1) {
            dist += haversineMeters(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            i++
        }
        return dist
    }

    // ---- Geometry helpers (pure, unit-testable) ----

    /** Result of snapping a point onto a segment. */
    private data class SnapResult(
        val lat: Double,
        val lng: Double,
        /** Parametric position along the segment in [0,1] (0=A, 1=B). */
        val t: Double,
        /** Perpendicular/clamped distance from the input point, in meters. */
        val distMeters: Double
    )

    /**
     * Great-circle distance between two WGS-84 points, in meters (haversine).
     *
     * @return distance in meters
     */
    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Perpendicular distance (meters) from point P to segment A-B, clamped to
     * the segment endpoints. Uses an equirectangular projection (cos-lat
     * scaled local meters) which is accurate for the short segments of a route
     * polyline.
     *
     * @return distance in meters from P to the nearest point on segment A-B
     */
    fun pointToSegmentMeters(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Double = pointToSegment(pLat, pLng, aLat, aLng, bLat, bLng).distMeters

    /**
     * Full snap: nearest point on segment A-B to P, the parametric `t` in
     * [0,1], and the clamped distance in meters. Equirectangular local-meter
     * projection about A's latitude.
     */
    private fun pointToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): SnapResult {
        // Project to local meters: x = east, y = north, scaled by cos(lat).
        val latRad = Math.toRadians(aLat)
        val mPerDegLat = 111_320.0
        val mPerDegLng = 111_320.0 * cos(latRad)

        val ax = 0.0
        val ay = 0.0
        val bx = (bLng - aLng) * mPerDegLng
        val by = (bLat - aLat) * mPerDegLat
        val px = (pLng - aLng) * mPerDegLng
        val py = (pLat - aLat) * mPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy

        val t = if (segLenSq <= 1e-9) {
            0.0
        } else {
            (((px - ax) * dx + (py - ay) * dy) / segLenSq).coerceIn(0.0, 1.0)
        }

        val projX = ax + t * dx
        val projY = ay + t * dy
        val distMeters = sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))

        // Convert the projected local-meter point back to lat/lng.
        val snapLat = aLat + (projY / mPerDegLat)
        val snapLng = if (abs(mPerDegLng) < 1e-9) aLng else aLng + (projX / mPerDegLng)

        return SnapResult(
            lat = snapLat,
            lng = snapLng,
            t = t,
            distMeters = distMeters
        )
    }

    /**
     * Immutable per-fix guidance snapshot returned by [update].
     *
     * @property snappedLat snapped-to-route latitude in decimal degrees
     * @property snappedLng snapped-to-route longitude in decimal degrees
     * @property offRoute true once the fix has been off-route for
     *   [OFF_ROUTE_CONSECUTIVE] consecutive updates (the Activity reroutes)
     * @property arrived true when within [ARRIVAL_RADIUS_M] of the destination
     * @property currentManeuver the next maneuver to announce, or null if none
     *   remain / no route
     * @property distanceToManeuverM along-route distance to [currentManeuver],
     *   in meters
     * @property remainingDistanceM remaining along-route distance to the
     *   destination, in meters
     * @property etaSeconds estimated time remaining to the destination, in
     *   seconds
     */
    data class GuidanceState(
        val snappedLat: Double,
        val snappedLng: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
        val currentManeuver: RouteManeuver?,
        val distanceToManeuverM: Double,
        val remainingDistanceM: Double,
        val etaSeconds: Double
    )
}
