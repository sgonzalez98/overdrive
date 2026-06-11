package com.overdrive.app.roadsense.overlay

import android.content.Context
import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin app-side control for the NATIVE blind-spot lane.
 *
 * The blind-spot visual is rendered entirely in the daemon (UID 2000) onto a
 * SurfaceControl layer — there is NO app-process overlay/decoder/WebSocket. The
 * app's only job is to tell the daemon to ARM or DISARM the lane when the feature
 * toggle changes, via the daemon's loopback HTTP control surface. The daemon then
 * owns show/hide (turn-trigger) and positioning (config-driven geometry).
 *
 * Replaces the deleted BlindSpotOverlayService (app-process decoder/WS overlay).
 */
object BlindSpotControl {

    private const val TAG = "BlindSpot/Control"
    private const val BASE_URL = "http://127.0.0.1:8080"

    /** Hard cap on the re-arm loop so it always converges (covers daemon cold-start + pano cold-start). */
    private const val REARM_DEADLINE_MS = 30_000L
    /** Backoff ceiling between re-arm polls. */
    private const val REARM_MAX_BACKOFF_MS = 2_000L

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Arm or disarm the daemon BS lane to match the `blindspot.enabled` flag
     * (or debugPreview, which also needs the lane up). Runs the loopback POST on a
     * background thread so it never blocks the caller (UI thread / receiver).
     * Idempotent daemon-side: /api/bs/enable no-ops once the lane is armed.
     */
    @JvmStatic
    fun sync(context: Context) {
        Thread({
            try {
                val bs = UnifiedConfigManager.forceReload().optJSONObject("blindspot")
                val enabled = bs?.optBoolean("enabled", false) ?: false
                val preview = bs?.optBoolean("debugPreview", false) ?: false
                if (enabled || preview) {
                    // ARM path: a single POST is not enough. On cold ACC-on the daemon's
                    // HTTP port (8080) may not be listening yet, or the pano pipeline may
                    // still be cold-starting (/api/bs/enable returns starting:true), so the
                    // lane is not armed by one shot. Drive a bounded re-arm loop that keeps
                    // re-POSTing /api/bs/enable and polling /api/bs/status until the lane
                    // reports enabled:true (or we hit the deadline). Idempotent daemon-side.
                    armWithRetry()
                } else {
                    post("/api/bs/disable")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sync failed: ${t.message}")
            }
        }, "BlindSpotControl-sync").start()
    }

    /**
     * Bounded, self-converging re-arm loop. POSTs /api/bs/enable, then polls
     * /api/bs/status with 500ms→2s backoff, re-POSTing enable each pass, until the
     * lane reports enabled:true or [REARM_DEADLINE_MS] elapses. Survives the initial
     * sync() call by running on its own daemon thread; converges (no infinite loop —
     * hard deadline) and never flaps the lane (only ever POSTs enable, never disable).
     */
    private fun armWithRetry() {
        Thread({
            val deadline = System.currentTimeMillis() + REARM_DEADLINE_MS
            var delayMs = 500L
            var attempts = 0
            while (System.currentTimeMillis() < deadline) {
                attempts++
                post("/api/bs/enable")
                if (isLaneEnabled()) {
                    return@Thread
                }
                try {
                    Thread.sleep(delayMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                delayMs = (delayMs * 2).coerceAtMost(REARM_MAX_BACKOFF_MS)
            }
            Log.w(TAG, "armWithRetry: lane not enabled after $attempts attempts (${REARM_DEADLINE_MS}ms); giving up")
        }, "BlindSpotRetry").apply { isDaemon = true }.start()
    }

    /** GET /api/bs/status and return true iff it reports enabled:true. */
    private fun isLaneEnabled(): Boolean {
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/api/bs/status")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return false
                org.json.JSONObject(body).optBoolean("enabled", false)
            }
        } catch (t: Throwable) {
            // Connection refused / timeout while daemon still cold-starting: not enabled yet.
            false
        }
    }

    private fun post(path: String) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL$path")
                .post(RequestBody.create(null, ByteArray(0)))
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        } catch (t: Throwable) {
            Log.w(TAG, "post $path failed: ${t.message}")
        }
    }
}
