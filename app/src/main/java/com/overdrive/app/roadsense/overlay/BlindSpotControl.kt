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
                post(if (enabled || preview) "/api/bs/enable" else "/api/bs/disable")
            } catch (t: Throwable) {
                Log.w(TAG, "sync failed: ${t.message}")
            }
        }, "BlindSpotControl-sync").start()
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
