package com.overdrive.app.navmap.nav

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Forward geocoder (free-text place name -> coordinates) for the RoadSense map
 * search box.
 *
 * <p>Primary provider is **Photon** (Komoot, OSM-backed, free, autocomplete-
 * friendly); on empty/error it falls back to **Nominatim**. Both are public
 * OSM services reached over the open internet from the app process — via
 * [MapNetworking] so the calls are PROXY-AWARE (sing-box / Tailscale) and
 * LANGUAGE-AWARE (results in the user's chosen app language). Per OSM usage
 * policy a descriptive `User-Agent` is sent (added by [MapNetworking.builder]).
 *
 * <p>Semantics mirror [com.overdrive.app.navmap.RoadSenseHazardApiClient]:
 * lazy OkHttp client, all methods SYNC (the Activity runs them off the UI
 * thread), and NEVER throwing — any failure returns an empty list so the
 * search box degrades gracefully. Search-on-submit is the intended usage
 * (this is not wired to per-keystroke calls).
 */
object ForwardGeocoder {

    private const val TAG = "ForwardGeocoder"

    private const val PHOTON_BASE = "https://photon.komoot.io/api/"
    private const val NOMINATIM_BASE = "https://nominatim.openstreetmap.org/search"

    /** Required by OSM usage policy (identifies the client). */
    private const val USER_AGENT = MapNetworking.USER_AGENT

    // Lazy so the OkHttpClient isn't built until search is first used. Proxy-aware
    // (dynamic proxy selector) + User-Agent come from MapNetworking.builder().
    private val http: OkHttpClient by lazy {
        MapNetworking.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Forward-geocode [query], returning up to [limit] results best-first.
     *
     * <p>Tries Photon first; if Photon yields no results (or errors), falls
     * back to Nominatim. Never throws — returns an empty list on any failure
     * or for a blank query.
     *
     * @param query the free-text place/address to search (e.g. "Eiffel Tower")
     * @param limit maximum number of results to return (default 5)
     * @param focusLat optional latitude (decimal degrees) to bias results
     *   toward (e.g. the current vehicle location); pass with [focusLng]
     * @param focusLng optional longitude (decimal degrees) focus bias
     * @return up to [limit] [SearchResult]s, or an empty list
     */
    fun search(
        query: String,
        limit: Int = 5,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val n = if (limit < 1) 1 else limit

        val photon = searchPhoton(q, n, focusLat, focusLng)
        if (photon.isNotEmpty()) return photon

        return searchNominatim(q, n)
    }

    /**
     * Type-ahead autocomplete for the search box — Photon ONLY (never Nominatim).
     * Photon is purpose-built for "search as you type" with typo tolerance; the
     * public Nominatim instance forbids per-keystroke querying, so the autocomplete
     * path must not touch it. The Activity calls this on a debounce (~300ms) with
     * in-flight cancellation; this method stays SYNC + never-throws like the rest.
     *
     * @param query partial text typed so far
     * @param limit max suggestions (default 6 — a Gmap-style short list)
     * @param focusLat/[focusLng] optional location bias (current vehicle position)
     */
    fun autocomplete(
        query: String,
        limit: Int = 6,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchPhoton(q, if (limit < 1) 1 else limit, focusLat, focusLng)
    }

    /**
     * Query Photon directly. Exposed for callers that want to skip the
     * Nominatim fallback. Never throws — returns an empty list on failure.
     *
     * @param query free-text place/address
     * @param limit max results
     * @param focusLat optional latitude (decimal degrees) focus bias
     * @param focusLng optional longitude (decimal degrees) focus bias
     */
    fun searchPhoton(
        query: String,
        limit: Int = 5,
        focusLat: Double? = null,
        focusLng: Double? = null
    ): List<SearchResult> {
        return try {
            val sb = StringBuilder(PHOTON_BASE)
                .append("?q=").append(enc(query))
                .append("&limit=").append(limit)
                // Language-aware: return place names in the user's app language.
                .append("&lang=").append(enc(MapNetworking.lang))
            if (focusLat != null && focusLng != null) {
                sb.append("&lat=").append(focusLat).append("&lon=").append(focusLng)
            }
            val req = Request.Builder()
                .url(sb.toString())
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", MapNetworking.acceptLanguage)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Photon -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parsePhoton(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchPhoton failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Query Nominatim (fallback). Never throws — returns an empty list on
     * failure.
     *
     * @param query free-text place/address
     * @param limit max results
     */
    fun searchNominatim(query: String, limit: Int = 5): List<SearchResult> {
        return try {
            val url = StringBuilder(NOMINATIM_BASE)
                .append("?format=jsonv2")
                .append("&q=").append(enc(query))
                .append("&limit=").append(limit)
                // Language-aware: bias result names to the user's app language.
                .append("&accept-language=").append(enc(MapNetworking.acceptLanguage))
                .toString()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", MapNetworking.acceptLanguage)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Nominatim -> HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyStr = resp.body?.string() ?: return emptyList()
                parseNominatim(bodyStr)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchNominatim failed: ${t.message}")
            emptyList()
        }
    }

    /**
     * Parse a Photon GeoJSON FeatureCollection. Each `feature.geometry`
     * is a Point with `coordinates = [lng, lat]`; `feature.properties` carries
     * name/street/city/state/country which we assemble into a readable label.
     * Pure function — exposed for unit testing.
     */
    internal fun parsePhoton(json: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val features = JSONObject(json).optJSONArray("features") ?: return out
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geom = feature.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lng = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue

                val props = feature.optJSONObject("properties") ?: JSONObject()
                val label = buildPhotonLabel(props)
                if (label.isEmpty()) continue
                out.add(SearchResult(label, lat, lng))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parsePhoton failed: ${t.message}")
        }
        return out
    }

    /**
     * Build a readable label from Photon `properties`, e.g.
     * "name, city, country", skipping blank/duplicate parts. Falls back to
     * street when there's no name. Pure function — exposed for unit testing.
     */
    internal fun buildPhotonLabel(props: JSONObject): String {
        val name = props.optString("name", "").trim()
        val street = props.optString("street", "").trim()
        val city = props.optString("city", "").trim()
        val state = props.optString("state", "").trim()
        val country = props.optString("country", "").trim()

        val parts = ArrayList<String>(4)
        val head = if (name.isNotEmpty()) name else street
        if (head.isNotEmpty()) parts.add(head)
        if (city.isNotEmpty() && city != head) parts.add(city)
        if (state.isNotEmpty() && state != city && state != head) parts.add(state)
        if (country.isNotEmpty()) parts.add(country)

        return parts.joinToString(", ")
    }

    /**
     * Parse a Nominatim `jsonv2` response (a JSON array). Each element has
     * `lat`, `lon` (strings) and `display_name`. Pure function — exposed for
     * unit testing.
     */
    internal fun parseNominatim(json: String): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                // Nominatim returns lat/lon as strings.
                val lat = item.optString("lat", "").toDoubleOrNull() ?: continue
                val lng = item.optString("lon", "").toDoubleOrNull() ?: continue
                val label = item.optString("display_name", "").trim()
                if (label.isEmpty()) continue
                out.add(SearchResult(label, lat, lng))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseNominatim failed: ${t.message}")
        }
        return out
    }

    private fun enc(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }
}
