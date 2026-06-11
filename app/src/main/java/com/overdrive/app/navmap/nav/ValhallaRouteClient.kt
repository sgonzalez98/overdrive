package com.overdrive.app.navmap.nav

import android.util.Log
import com.overdrive.app.navmap.NavMapConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches a driving route from the BYOK (bring-your-own-key) Valhalla endpoint
 * configured in [NavMapConfig].
 *
 * <p>Style mirrors [com.overdrive.app.navmap.RoadSenseHazardApiClient]: lazy
 * OkHttp client, SYNC method (the Activity runs it off the UI thread), and
 * NEVER throwing — any failure (no key, transport error, malformed response)
 * returns `null`. The caller treats `null` as "no route" and, when the key is
 * blank, prompts the user to add a routing key.
 *
 * <p>Routing can be slower than the hazard surface, so the read timeout is
 * raised to 10s (connect stays at 4s).
 */
object ValhallaRouteClient {

    private const val TAG = "ValhallaRouteClient"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Lazy so the OkHttpClient isn't built until the first route request.
    // Proxy-aware via MapNetworking (the BYOK endpoint is on the public internet,
    // so it must follow sing-box / Tailscale when present).
    private val http: OkHttpClient by lazy {
        MapNetworking.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Compute an `auto` (driving) route from the start to the end coordinate.
     *
     * <p>Reads [NavMapConfig.fromUnifiedConfig] for the endpoint + BYOK key.
     * If the key is blank, returns `null` immediately (the caller should ask
     * the user to add a routing key). Otherwise POSTs a Valhalla `/route`
     * request and parses the first leg.
     *
     * @param startLat origin latitude in decimal degrees
     * @param startLng origin longitude in decimal degrees
     * @param endLat destination latitude in decimal degrees
     * @param endLng destination longitude in decimal degrees
     * @return a decoded [NavRoute] (polyline, maneuvers, totals), or `null` on
     *   any failure / missing key
     */
    fun route(startLat: Double, startLng: Double, endLat: Double, endLng: Double): NavRoute? =
        routesWithAlternates(startLat, startLng, endLat, endLng, 0).firstOrNull()

    /**
     * Compute a route through an ORDERED list of locations: origin, optional
     * intermediate stops (waypoints), then destination. The first point is the
     * start and the last is the final destination; everything between is a
     * via-stop the route must pass through in order.
     *
     * Valhalla does NOT support `alternates` on multipoint routes, so this always
     * returns a single route (wrapped in a list for caller symmetry). Empty list
     * on failure / missing key / fewer than 2 points.
     *
     * @param points ordered [origin, stop1, stop2, …, destination]; ≥2 required
     */
    fun routeVia(points: List<GeoPoint>): List<NavRoute> {
        if (points.size < 2) return emptyList()
        return try {
            val cfg = NavMapConfig.fromUnifiedConfig()
            val key = cfg.routingApiKey
            if (key.isEmpty()) {
                Log.w(TAG, "routeVia skipped: no routing API key configured")
                return emptyList()
            }
            val url = buildRouteUrl(cfg.routingEndpoint, key)
            val body = buildRequestBodyVia(points)
            val req = Request.Builder().url(url).post(body.toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST routeVia -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parseRoutes(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "routeVia failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Compute the primary `auto` route PLUS up to [alternates] alternate routes
     * between the start and end coordinate. The returned list is ordered
     * [primary, alt1, alt2, …]; it is empty on any failure / missing key.
     * Valhalla may return fewer alternates than requested (or none) — that's
     * normal, the caller just gets what's available.
     *
     * @param alternates how many ALTERNATE routes to request (0 = primary only)
     * @return ordered routes (primary first), or empty list on failure
     */
    fun routesWithAlternates(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, alternates: Int
    ): List<NavRoute> {
        return try {
            val cfg = NavMapConfig.fromUnifiedConfig()
            val key = cfg.routingApiKey
            if (key.isEmpty()) {
                Log.w(TAG, "route skipped: no routing API key configured")
                return emptyList()
            }

            val url = buildRouteUrl(cfg.routingEndpoint, key)
            val body = buildRequestBody(startLat, startLng, endLat, endLng, alternates)

            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "POST route -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parseRoutes(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "route failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Append the BYOK key to the endpoint as a query param per the Valhalla
     * cloud-provider convention (`?api_key=<key>`). Preserves any existing
     * query string on the endpoint. Pure function — exposed for unit testing.
     *
     * @param endpoint the configured routing endpoint (already includes the
     *   `/route` path per [NavMapConfig.DEFAULT_ROUTING_ENDPOINT])
     * @param apiKey the decrypted BYOK key
     */
    internal fun buildRouteUrl(endpoint: String, apiKey: String): String {
        val sep = if (endpoint.contains('?')) '&' else '?'
        return "$endpoint${sep}api_key=$apiKey"
    }

    /**
     * Build the Valhalla `/route` request body JSON. Pure function — exposed
     * for unit testing.
     *
     * @param startLat origin latitude (decimal degrees)
     * @param startLng origin longitude (decimal degrees)
     * @param endLat destination latitude (decimal degrees)
     * @param endLng destination longitude (decimal degrees)
     */
    internal fun buildRequestBody(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, alternates: Int = 0
    ): String {
        val locations = JSONArray()
        locations.put(JSONObject().put("lat", startLat).put("lon", startLng))
        locations.put(JSONObject().put("lat", endLat).put("lon", endLng))
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            // Language- + unit-aware: maneuver instructions in the user's app
            // language (Valhalla falls back to English for unsupported tags) and
            // distances in the user's Trips km/miles preference.
            .put("directions_options", JSONObject()
                .put("units", MapNetworking.valhallaUnits)
                .put("language", MapNetworking.valhallaLanguage))
        // "alternates" = how many ALTERNATE routes to also return (primary is
        // always returned separately). Only set when > 0.
        if (alternates > 0) body.put("alternates", alternates)
        return body.toString()
    }

    /**
     * Build a multipoint Valhalla `/route` body from an ordered point list
     * (origin … via-stops … destination). Intermediate stops use
     * `type:"through"`-style break points (Valhalla defaults each location to a
     * break, which is what we want — the route stops/continues at each). Pure
     * function — exposed for unit testing.
     */
    internal fun buildRequestBodyVia(points: List<GeoPoint>): String {
        val locations = JSONArray()
        points.forEach { p -> locations.put(JSONObject().put("lat", p.lat).put("lon", p.lng)) }
        return JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("directions_options", JSONObject()
                .put("units", MapNetworking.valhallaUnits)
                .put("language", MapNetworking.valhallaLanguage))
            .toString()
    }

    /**
     * Parse a Valhalla `/route` response into a [NavRoute].
     *
     * <p>Shape: `trip.legs[0].shape` is a precision-6 encoded polyline (decoded
     * via [PolylineCodec]); `trip.legs[0].maneuvers[]` carry `instruction`,
     * `type`, `begin_shape_index`, `length` (km -> *1000 m), `time` (s); the
     * maneuver lat/lng is taken from `points[begin_shape_index]`. Trip totals
     * come from `trip.summary.length` (km -> *1000 m) and `trip.summary.time`
     * (s). Returns `null` if the response has no usable leg. Pure function —
     * exposed for unit testing.
     *
     * @param json the raw Valhalla response body
     */
    internal fun parseRoute(json: String): NavRoute? =
        parseRoutes(json).firstOrNull()

    /**
     * Parse a Valhalla response into an ordered list of [NavRoute]s: the primary
     * `trip` first, then each `alternates[i].trip` (Valhalla returns alternates
     * as a top-level array, each entry wrapping a `trip` object with the same
     * shape as the primary). Routes that fail to parse are skipped. Pure
     * function — exposed for unit testing.
     *
     * @param json the raw Valhalla response body
     */
    internal fun parseRoutes(json: String): List<NavRoute> {
        return try {
            val root = JSONObject(json)
            val out = ArrayList<NavRoute>()
            root.optJSONObject("trip")?.let { parseTrip(it)?.let(out::add) }
            val alts = root.optJSONArray("alternates")
            if (alts != null) {
                for (i in 0 until alts.length()) {
                    val altTrip = alts.optJSONObject(i)?.optJSONObject("trip") ?: continue
                    parseTrip(altTrip)?.let(out::add)
                }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "parseRoutes failed: ${t.message}")
            emptyList()
        }
    }

    /** Parse a single Valhalla `trip` object into a [NavRoute], or null if unusable. */
    private fun parseTrip(trip: JSONObject): NavRoute? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null
        val leg = legs.optJSONObject(0) ?: return null

        val shape = leg.optString("shape", "")
        val points = PolylineCodec.decode(shape, 1e6)
        if (points.isEmpty()) return null

        val maneuvers = ArrayList<RouteManeuver>()
        val mArr = leg.optJSONArray("maneuvers")
        if (mArr != null) {
            for (i in 0 until mArr.length()) {
                val m = mArr.optJSONObject(i) ?: continue
                val beginIdx = m.optInt("begin_shape_index", 0)
                // Clamp the index so a malformed/out-of-range value can't
                // throw; pin to the nearest valid route vertex.
                val safeIdx = beginIdx.coerceIn(0, points.size - 1)
                val mp = points[safeIdx]
                maneuvers.add(
                    RouteManeuver(
                        instruction = m.optString("instruction", ""),
                        type = m.optInt("type", 0),
                        beginShapeIndex = safeIdx,
                        lengthMeters = m.optDouble("length", 0.0) * 1000.0,
                        timeSeconds = m.optDouble("time", 0.0),
                        lat = mp.lat,
                        lng = mp.lng
                    )
                )
            }
        }

        val summary = trip.optJSONObject("summary")
        val totalDistanceMeters = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0
        val totalDurationSeconds = summary?.optDouble("time", 0.0) ?: 0.0

        return NavRoute(
            points = points,
            maneuvers = maneuvers,
            totalDistanceMeters = totalDistanceMeters,
            totalDurationSeconds = totalDurationSeconds
        )
    }
}
