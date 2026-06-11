package com.overdrive.app.navmap

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client over the daemon's `/api/roadsense/hazards` surface, used
 * by [RoadSenseMapActivity].
 *
 * Mirrors [com.overdrive.app.ui.util.RecordingsApiClient] exactly: an
 * OkHttp-backed object, hardcoded loopback BASE_URL, all methods SYNC
 * (callers run them on a background executor) and NEVER throwing — failures
 * map to null/false so the map can keep its last good data and never crash
 * or block the UI thread.
 *
 * <p>The daemon (UID 2000) owns the RoadSense H2 store; the app (UID 10xxx)
 * cannot read it across UIDs, so the map fetches GeoJSON over HTTP. The
 * daemon's [com.overdrive.app.server.AuthMiddleware] trusts loopback
 * (127.0.0.1) requests with no tunnel-fingerprint headers (Tier-2), so a
 * plain unauthenticated localhost call succeeds — the same path
 * RecordingsApiClient relies on.
 *
 * <p>Daemon HTTP port = {@link com.overdrive.app.daemon.CameraDaemon#HTTP_PORT}
 * = 8080, hardcoded here (matching RecordingsApiClient) to keep this
 * package free of daemon-process imports.
 */
object RoadSenseHazardApiClient {
    private const val TAG = "RoadSenseHazardApi"

    // Confirmed via CameraDaemon.HTTP_PORT == 8080 (also Constants.HTTP_PORT).
    private const val BASE_URL = "http://127.0.0.1:8080"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Lazy so the OkHttpClient isn't built until the map is first opened.
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            // Let a single connect failure bubble straight up — the caller
            // keeps the last good data rather than waiting out a silent retry.
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * GET /api/roadsense/hazards?bbox=minLng,minLat,maxLng,maxLat.
     *
     * Returns the raw GeoJSON FeatureCollection string (ready to hand to
     * MapLibre's GeoJsonSource.setGeoJson(String)), or null on any transport
     * failure. The bbox is built by the Activity from the visible map bounds.
     */
    fun fetchHazardsGeoJson(minLng: Double, minLat: Double, maxLng: Double, maxLat: Double): String? {
        return try {
            val bbox = "$minLng,$minLat,$maxLng,$maxLat"
            val url = "$BASE_URL/api/roadsense/hazards?bbox=" + enc(bbox)
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET hazards -> HTTP ${resp.code}")
                    return null
                }
                resp.body?.string()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchHazardsGeoJson failed: ${t.message}")
            null
        }
    }

    /**
     * POST /api/roadsense/hazard/{id}/confirm with an optional
     * {"severity":1-3,"type":0-3} body when the user corrected either.
     * Returns true on HTTP 200 with {success:true}.
     *
     * @param severity 1-3 to send a correction, or null to leave unchanged
     * @param type     0-3 to send a correction, or null to leave unchanged
     */
    fun confirmHazard(id: String, severity: Int?, type: Int?): Boolean {
        val body = JSONObject()
        try {
            if (severity != null) body.put("severity", severity)
            if (type != null) body.put("type", type)
        } catch (_: Throwable) { /* keep an empty body */ }
        return postVerdict(id, "confirm", body.toString())
    }

    /**
     * POST /api/roadsense/hazard/{id}/reject — physically deletes the row.
     * Returns true on HTTP 200 with {success:true}.
     */
    fun rejectHazard(id: String): Boolean = postVerdict(id, "reject", "")

    /**
     * Current vehicle/device location, fetched from the daemon's GPS monitor
     * (GET /api/gps). Returns a [LatLngFix] (lat, lng, optional bearing/speed)
     * or null if the daemon has no fix yet / transport fails. The daemon
     * auto-starts GPS tracking on first read, so a freshly-opened map gets a
     * fix within a second or two of the first call.
     */
    fun fetchCurrentLocation(): LatLngFix? {
        return try {
            val url = "$BASE_URL/api/gps"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET gps -> HTTP ${resp.code}")
                    return null
                }
                val bodyStr = resp.body?.string() ?: return null
                val root = JSONObject(bodyStr)
                val loc = root.optJSONObject("location") ?: return null
                if (!loc.optBoolean("hasLocation", false)) return null
                val lat = loc.optDouble("lat", Double.NaN)
                val lng = loc.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) return null
                LatLngFix(
                    lat = lat,
                    lng = lng,
                    bearing = if (loc.has("bearing")) loc.optDouble("bearing", Double.NaN).takeUnless { it.isNaN() } else null,
                    speed = if (loc.has("speed")) loc.optDouble("speed", Double.NaN).takeUnless { it.isNaN() } else null
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchCurrentLocation failed: ${t.message}")
            null
        }
    }

    /** A single GPS fix from the daemon. bearing/speed may be null. */
    data class LatLngFix(
        val lat: Double,
        val lng: Double,
        val bearing: Double?,
        val speed: Double?
    )

    private fun postVerdict(id: String, action: String, jsonBody: String): Boolean {
        return try {
            if (id.isEmpty()) return false
            // {id} is path-encoded so an id with reserved chars routes correctly;
            // the daemon URL-decodes it back before the store lookup.
            val url = "$BASE_URL/api/roadsense/hazard/${enc(id)}/$action"
            val req = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST $action -> HTTP ${resp.code} for $id")
                    return false
                }
                val respBody = resp.body?.string() ?: return false
                JSONObject(respBody).optBoolean("success", false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$action hazard failed: ${t.message}")
            false
        }
    }

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
}
