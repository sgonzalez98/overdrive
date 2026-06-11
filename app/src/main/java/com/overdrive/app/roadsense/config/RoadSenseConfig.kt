package com.overdrive.app.roadsense.config

import com.overdrive.app.config.UnifiedConfigManager
import org.json.JSONObject

/**
 * Typed accessor for the `roadSense` section of [UnifiedConfigManager].
 *
 * Why a wrapper instead of adding getRoadSense()/setRoadSense() to UCM: keeps ALL
 * RoadSense config knowledge in a RoadSense-owned file (UCM is a large shared file
 * another session may be editing), and uses only UCM's PUBLIC generic API
 * (`loadConfig()` to read, `updateSection()` to merge-write) — so this never
 * touches UCM source. The section is file-backed, so both the app UID (web
 * settings page writes) and the daemon UID (controller reads) see the same values;
 * daemon-side reads should `forceReload()` first when they need the very latest
 * (cross-UID staleness — see feedback_unified_config_force_reload).
 *
 * All fields have safe defaults so a missing/partial section never crashes a read.
 * Defaults encode the product decisions: feature OFF until enabled; warn threshold
 * 0 = warn on everything (D-015); upload bar high (D-016); crowdsource up+down OFF
 * (R-CRD-6 on-device-first); calibration mode OFF (R-SET-6).
 */
object RoadSenseConfig {

    private const val SECTION = "roadSense"

    /**
     * Project-run SHARED crowdsource backend (D-009 + user decision 2026-06-05):
     * the out-of-box default so all users' confirmed hazards pool into ONE D1
     * instance and consensus works across the whole fleet. A fork can override
     * this on road-sense.html to run its own pool.
     *
     * DEPLOYED 2026-06-05: live Cloudflare Worker + D1 (region APAC), verified with
     * a full report→consensus→tiles round-trip against remote D1. Crowdsource is
     * still opt-in AND default-OFF (R-CRD-6) — this URL only matters once a user
     * enables upload/download.
     */
    const val DEFAULT_WORKER_URL = "https://roadsense-edge.yash321sri.workers.dev"

    // Keys (also the JSON field names the web settings page reads/writes).
    private const val K_ENABLED = "enabled"
    private const val K_WARN_ENABLED = "warnEnabled"
    private const val K_WARN_MODE = "warnMode"                 // "visual" | "audio" | "both"
    private const val K_WARN_LEAD_S = "warnLeadSeconds"        // t_w (D-010)
    private const val K_WARN_FLOOR_M = "warnFloorMeters"
    private const val K_WARN_CONF = "warnConfidenceThreshold"  // 0..1, default 0 (D-015)
    private const val K_SEV_MINOR = "warnSeverityMinor"        // per-severity chime gates (R-SET-4)
    private const val K_SEV_MODERATE = "warnSeverityModerate"
    private const val K_SEV_SEVERE = "warnSeveritySevere"
    private const val K_CALIBRATION_MODE = "calibrationMode"   // R-SET-6
    private const val K_UPLOAD = "crowdUpload"                 // R-SET-2 (independent)
    private const val K_DOWNLOAD = "crowdDownload"             // R-SET-2 (independent)
    private const val K_UPLOAD_CONF = "uploadConfidenceThreshold" // D-016
    private const val K_DEVICE_ID = "deviceId"                 // rotating anon UUID (R-CRD-7)
    private const val K_WORKER_URL = "syncWorkerUrl"           // user-configurable (D-009)
    private const val K_RAW_RECORD = "rawRecord"               // debug raw-IMU recorder (D-036)

    /** Warn delivery mode. */
    enum class WarnMode { VISUAL, AUDIO, BOTH;
        companion object {
            fun from(s: String?): WarnMode = when (s?.lowercase()) {
                "visual" -> VISUAL
                "audio" -> AUDIO
                else -> BOTH
            }
        }
    }

    /** Immutable snapshot of the whole section — read once per use so a mid-read
     *  config change can't yield an inconsistent mix of old/new fields. */
    data class Snapshot(
        val enabled: Boolean,
        val warnEnabled: Boolean,
        val warnMode: WarnMode,
        val warnLeadSeconds: Float,
        val warnFloorMeters: Float,
        val warnConfidenceThreshold: Float,
        val severityMinor: Boolean,
        val severityModerate: Boolean,
        val severitySevere: Boolean,
        val calibrationMode: Boolean,
        val crowdUpload: Boolean,
        val crowdDownload: Boolean,
        val uploadConfidenceThreshold: Float,
        val deviceId: String?,
        val syncWorkerUrl: String?,
        /** Debug raw-IMU recorder (D-036): when true the controller streams raw 100 Hz
         *  accel+gyro + derived features to a CSV for offline recall measurement + constant
         *  fitting. Default OFF (privacy + disk). Toggled via the unified config / adb. */
        val rawRecord: Boolean,
    ) {
        /** Should a hazard of [severityLevel] (1=minor,2=moderate,3=severe) with
         *  [confidence] produce a warning at all, per the gates? (Distance is the
         *  WarningCoordinator's separate job.) */
        fun warnsFor(severityLevel: Int, confidence: Float): Boolean {
            if (!enabled || !warnEnabled) return false
            if (confidence < warnConfidenceThreshold) return false
            return when (severityLevel) {
                1 -> severityMinor
                2 -> severityModerate
                else -> severitySevere
            }
        }
    }

    /**
     * Read the current section. [forceReload]=true forces a cross-UID disk re-read
     * (daemon reading app-written values) — use sparingly (it's a full reload), not
     * on the 100 Hz path; the controller should snapshot this per regime change /
     * periodically, not per sample.
     */
    fun snapshot(forceReload: Boolean = false): Snapshot {
        val root = if (forceReload) UnifiedConfigManager.forceReload()
        else UnifiedConfigManager.loadConfig()
        val s = root.optJSONObject(SECTION) ?: JSONObject()
        return Snapshot(
            enabled = s.optBoolean(K_ENABLED, false),
            warnEnabled = s.optBoolean(K_WARN_ENABLED, true),
            warnMode = WarnMode.from(s.optString(K_WARN_MODE, "both")),
            warnLeadSeconds = s.optDouble(K_WARN_LEAD_S, 4.0).toFloat(),
            warnFloorMeters = s.optDouble(K_WARN_FLOOR_M, 30.0).toFloat(),
            warnConfidenceThreshold = s.optDouble(K_WARN_CONF, 0.0).toFloat(),
            severityMinor = s.optBoolean(K_SEV_MINOR, true),
            severityModerate = s.optBoolean(K_SEV_MODERATE, true),
            severitySevere = s.optBoolean(K_SEV_SEVERE, true),
            calibrationMode = s.optBoolean(K_CALIBRATION_MODE, false),
            crowdUpload = s.optBoolean(K_UPLOAD, false),
            crowdDownload = s.optBoolean(K_DOWNLOAD, false),
            uploadConfidenceThreshold = s.optDouble(K_UPLOAD_CONF, 0.7).toFloat(),
            deviceId = s.optString(K_DEVICE_ID, "").ifEmpty { null },
            // Default to the project-run SHARED instance so every user's data
            // pools into ONE backend out of the box (crowdsourcing only works if
            // everyone reports to the same place). The field stays editable so a
            // fork can self-host its own pool (D-009). Empty/unset → use the
            // shared default; a user who blanks it disables sync.
            syncWorkerUrl = s.optString(K_WORKER_URL, "").ifEmpty { DEFAULT_WORKER_URL },
            rawRecord = s.optBoolean(K_RAW_RECORD, false),
        )
    }

    // NOTE: writes to the roadSense section go through UnifiedConfigManager.updateSection
    // directly (web settings page via RoadSenseApiHandler, daemon via RoadSenseController).
    // The old typed update()/setX() writers here had no callers and were removed.
}
