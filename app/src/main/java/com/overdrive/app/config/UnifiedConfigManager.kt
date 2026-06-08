package com.overdrive.app.config

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * SOTA Unified Configuration Manager
 * 
 * Solves the UID permission problem by using a world-accessible location
 * that both the app (via IPC) and shell daemon can read/write.
 * 
 * Architecture:
 * - Single JSON file at /data/local/tmp/overdrive_config.json
 * - App UI writes via IPC to daemon (daemon has shell UID 2000)
 * - Web UI/daemon writes directly (already has shell UID 2000)
 * - Both read from the same file
 * - Change listeners for real-time sync
 * 
 * Config sections:
 * - surveillance: Detection settings (minObjectSize, flashImmunity, etc.)
 * - recording: Recording settings (bitrate, codec, pre/post buffer)
 * - streaming: Streaming quality settings
 * - telegram: Telegram bot settings
 */
object UnifiedConfigManager {
    private const val TAG = "UnifiedConfig"
    
    // Single source of truth - world-readable location
    private const val CONFIG_PATH = "/data/local/tmp/overdrive_config.json"
    
    // Legacy paths for migration
    private const val LEGACY_SENTRY_CONFIG = "/data/local/tmp/sentry_config.json"
    private const val LEGACY_CAMERA_SETTINGS = "/data/local/tmp/camera_settings.json"
    private const val LEGACY_SYSTEM_CONFIG = "/data/data/com.android.providers.settings/sentry_config.json"
    
    // In-memory cache
    @Volatile
    private var cachedConfig: JSONObject? = null
    private val lastModified = AtomicLong(0)
    
    // Change listeners
    private val listeners = CopyOnWriteArrayList<ConfigChangeListener>()
    
    interface ConfigChangeListener {
        fun onConfigChanged(section: String, config: JSONObject)
    }
    
    /**
     * Initialize and migrate from legacy configs if needed.
     */
    @JvmStatic
    fun init() {
        val configFile = File(CONFIG_PATH)
        
        if (!configFile.exists()) {
            Log.i(TAG, "Unified config not found, migrating from legacy configs...")
            migrateFromLegacy()
        } else {
            Log.i(TAG, "Unified config exists at $CONFIG_PATH")
            loadConfig()
        }
    }
    
    /**
     * Migrate from legacy config files to unified config.
     */
    private fun migrateFromLegacy() {
        val unified = JSONObject()
        
        // Initialize sections
        unified.put("surveillance", JSONObject())
        unified.put("recording", JSONObject())
        unified.put("streaming", JSONObject())
        unified.put("telegram", JSONObject())
        unified.put("camera", JSONObject())
        unified.put("proximityGuard", JSONObject())
        unified.put("telemetryOverlay", JSONObject())
        unified.put("tripAnalytics", JSONObject())
        unified.put("oemDashcam", JSONObject())
        unified.put("version", 1)
        unified.put("lastModified", System.currentTimeMillis())
        
        // Try to migrate from legacy sentry config
        try {
            val legacySentry = File(LEGACY_SENTRY_CONFIG)
            if (legacySentry.exists()) {
                val legacy = JSONObject(legacySentry.readText())
                val surveillance = unified.getJSONObject("surveillance")
                
                // Copy surveillance settings
                copyIfExists(legacy, surveillance, "blockSize")
                copyIfExists(legacy, surveillance, "requiredBlocks")
                copyIfExists(legacy, surveillance, "sensitivity")
                copyIfExists(legacy, surveillance, "flashImmunity")
                copyIfExists(legacy, surveillance, "temporalFrames")
                copyIfExists(legacy, surveillance, "useChroma")
                copyIfExists(legacy, surveillance, "minDistanceM")
                copyIfExists(legacy, surveillance, "maxDistanceM")
                copyIfExists(legacy, surveillance, "cameraHeightM")
                copyIfExists(legacy, surveillance, "cameraTiltDeg")
                copyIfExists(legacy, surveillance, "verticalFovDeg")
                copyIfExists(legacy, surveillance, "aiConfidence")
                copyIfExists(legacy, surveillance, "minObjectSize")
                copyIfExists(legacy, surveillance, "detectPerson")
                copyIfExists(legacy, surveillance, "detectCar")
                copyIfExists(legacy, surveillance, "detectBike")
                copyIfExists(legacy, surveillance, "preRecordSeconds")
                copyIfExists(legacy, surveillance, "postRecordSeconds")
                
                Log.i(TAG, "Migrated surveillance settings from $LEGACY_SENTRY_CONFIG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from legacy sentry config: ${e.message}")
        }
        
        // Try to migrate from legacy camera settings
        try {
            val legacyCamera = File(LEGACY_CAMERA_SETTINGS)
            if (legacyCamera.exists()) {
                val legacy = JSONObject(legacyCamera.readText())
                val recording = unified.getJSONObject("recording")
                val streaming = unified.getJSONObject("streaming")
                
                // Copy recording settings
                copyIfExists(legacy, recording, "recordingBitrate", "bitrate")
                copyIfExists(legacy, recording, "recordingCodec", "codec")
                copyIfExists(legacy, recording, "recordingQuality", "quality")
                
                // Copy streaming settings
                copyIfExists(legacy, streaming, "streamingQuality", "quality")
                
                Log.i(TAG, "Migrated recording/streaming settings from $LEGACY_CAMERA_SETTINGS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from legacy camera settings: ${e.message}")
        }
        
        // Try system config as fallback
        try {
            val systemConfig = File(LEGACY_SYSTEM_CONFIG)
            if (systemConfig.exists()) {
                val legacy = JSONObject(systemConfig.readText())
                val surveillance = unified.getJSONObject("surveillance")
                
                // Only copy if not already set
                if (!surveillance.has("minObjectSize")) {
                    copyIfExists(legacy, surveillance, "minObjectSize")
                }
                if (!surveillance.has("flashImmunity")) {
                    copyIfExists(legacy, surveillance, "flashImmunity")
                }
                
                Log.i(TAG, "Migrated additional settings from $LEGACY_SYSTEM_CONFIG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from system config: ${e.message}")
        }
        
        // Apply defaults for missing values
        applyDefaults(unified)
        
        // Save unified config
        saveConfigInternal(unified)
        cachedConfig = unified
        
        Log.i(TAG, "Migration complete. Unified config saved to $CONFIG_PATH")
    }
    
    private fun copyIfExists(from: JSONObject, to: JSONObject, key: String, newKey: String = key) {
        if (from.has(key)) {
            to.put(newKey, from.get(key))
        }
    }
    
    private fun applyDefaults(config: JSONObject) {
        val surveillance = config.getJSONObject("surveillance")
        val recording = config.getJSONObject("recording")
        val streaming = config.getJSONObject("streaming")
        val camera = config.optJSONObject("camera") ?: JSONObject().also {
            config.put("camera", it)
        }
        val proximityGuard = config.optJSONObject("proximityGuard") ?: JSONObject().also {
            config.put("proximityGuard", it)
        }
        val blindspot = config.optJSONObject("blindspot") ?: JSONObject().also {
            config.put("blindspot", it)
        }
        
        // Surveillance defaults
        if (!surveillance.has("minObjectSize")) surveillance.put("minObjectSize", 0.08)
        if (!surveillance.has("aiConfidence")) surveillance.put("aiConfidence", 0.25)
        if (!surveillance.has("flashImmunity")) surveillance.put("flashImmunity", 2)
        if (!surveillance.has("detectPerson")) surveillance.put("detectPerson", true)
        if (!surveillance.has("detectCar")) surveillance.put("detectCar", true)
        if (!surveillance.has("detectBike")) surveillance.put("detectBike", false)
        if (!surveillance.has("preRecordSeconds")) surveillance.put("preRecordSeconds", 5)
        if (!surveillance.has("postRecordSeconds")) surveillance.put("postRecordSeconds", 10)
        if (!surveillance.has("blockSize")) surveillance.put("blockSize", 32)
        if (!surveillance.has("requiredBlocks")) surveillance.put("requiredBlocks", 3)
        if (!surveillance.has("sensitivity")) surveillance.put("sensitivity", 0.04)
        if (!surveillance.has("surveillanceEnabled")) surveillance.put("surveillanceEnabled", false)
        // ACC-OFF mode: "smart" runs the existing motion + YOLO event pipeline;
        // "continuous" records a plain rolling 4-cam mosaic with no filters and
        // no AI. Branched at SurveillanceEngineGpu.enable(). Default smart so
        // behaviour matches the prior single-mode build.
        if (!surveillance.has("accOffMode")) surveillance.put("accOffMode", "smart")
        if (!surveillance.has("deterrentAction")) surveillance.put("deterrentAction", "silent")
        if (!surveillance.has("deterrentCooldownSeconds")) surveillance.put("deterrentCooldownSeconds", 15)
        if (!surveillance.has("screenDeterrentEnabled")) surveillance.put("screenDeterrentEnabled", false)
        if (!surveillance.has("screenDeterrentDurationSeconds")) surveillance.put("screenDeterrentDurationSeconds", 8)
        if (!surveillance.has("screenDeterrentImagePath")) surveillance.put("screenDeterrentImagePath", "")
        if (!surveillance.has("screenDeterrentMessage")) surveillance.put("screenDeterrentMessage", "")
        
        // Recording defaults. The canonical key is `recordingQuality` (ECONOMY..MAX).
        // `quality` is the legacy mirror; `bitrate` (LOW/MEDIUM/HIGH) is no longer
        // seeded — it would drift from the active tier and confuse cross-channel
        // readers. Keep it only if a user actually has it from a pre-migration install.
        if (!recording.has("mode")) recording.put("mode", "NONE")  // Default: no recording
        if (!recording.has("recordingQuality")) recording.put("recordingQuality", "STANDARD")
        if (!recording.has("quality")) recording.put("quality", recording.optString("recordingQuality", "STANDARD"))
        if (!recording.has("codec")) recording.put("codec", "H264")
        // Recording-side dewarp strength (Fitzgibbon division model). 0..100,
        // 0 = off (default). Single source of truth for both ACC-on dashcam
        // and ACC-off surveillance flows — both pipelines read the same key
        // so one slider in the UI applies everywhere. Only effective on the
        // legacy 4-strip layout; dilink4 cars get a clean 2x2 from the HAL
        // already and the recorder's shader gates the dewarp accordingly.
        if (!recording.has("rectifyStrength")) recording.put("rectifyStrength", 0)
        
        // Streaming defaults
        if (!streaming.has("quality")) streaming.put("quality", "MEDIUM")

        // Camera defaults. cameraProfile=auto lets the runtime resolver infer
        // Tang vs legacy panoramic dims from ro.product.model. Existing
        // installs that only have probedCameraId continue to work unchanged.
        if (!camera.has("cameraProfile")) {
            camera.put("cameraProfile",
                com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO)
        }
        if (!camera.has("targetFps"))         camera.put("targetFps", 15)
        if (!camera.has("probedCameraId"))    camera.put("probedCameraId", -1)
        if (!camera.has("probedSurfaceMode")) camera.put("probedSurfaceMode", -1)
        if (!camera.has("roleMappings"))      camera.put("roleMappings", JSONObject())

        // OEM Dashcam (separate forward sensor on vehicles that ship a DVR).
        // -1 = unset; 0..5 = picked AVMCamera id. The resolver in
        // resolveOemDashcamId() falls back to (panoId XOR 1) when the manual
        // override is false, so a typical Seal install (pano=1) auto-picks
        // dashcam=0 without the user touching anything.
        if (!camera.has("oemDashcamCameraId"))      camera.put("oemDashcamCameraId", -1)
        if (!camera.has("oemDashcamManualOverride")) camera.put("oemDashcamManualOverride", false)
        // Sticky probe result. -1 = unprobed, 0 = single-client only,
        // 1 = both AVMCamera ids deliver frames concurrently. Until probed,
        // the daemon defaults to single-pipeline operation (yield protocol
        // between pano and OEM dashcam).
        if (!camera.has("concurrentAvmSupported"))   camera.put("concurrentAvmSupported", -1)

        // Proximity Guard defaults
        if (!proximityGuard.has("enabled")) proximityGuard.put("enabled", false)
        if (!proximityGuard.has("triggerLevel")) proximityGuard.put("triggerLevel", "RED")
        if (!proximityGuard.has("preRecordSeconds")) proximityGuard.put("preRecordSeconds", 5)
        if (!proximityGuard.has("postRecordSeconds")) proximityGuard.put("postRecordSeconds", 10)

        // Blind Spot overlay defaults. `enabled` gates the indicator-triggered
        // native overlay; the 6 numerics are the dialed-in stitch calibration
        // for this car (rear+side panorama). See BlindSpotOverlayService.
        if (!blindspot.has("enabled")) blindspot.put("enabled", false)
        if (!blindspot.has("rearFov")) blindspot.put("rearFov", 1.66)
        if (!blindspot.has("sideFov")) blindspot.put("sideFov", 1.98)
        if (!blindspot.has("yaw")) blindspot.put("yaw", 1.23)
        if (!blindspot.has("roll")) blindspot.put("roll", 0.25)
        if (!blindspot.has("pitch")) blindspot.put("pitch", -0.275)
        if (!blindspot.has("feather")) blindspot.put("feather", 0.38)
        // Additional opaque stitch-tuning scalars; defaults below = no change.
        if (!blindspot.has("projExp")) blindspot.put("projExp", 1.0)
        if (!blindspot.has("rearRoll")) blindspot.put("rearRoll", 0.0)
        if (!blindspot.has("rearPitch")) blindspot.put("rearPitch", 0.0)

        // Telemetry Overlay defaults.
        //
        // Schema:
        //   enabled            (legacy) — pano fallback when panoEnabled absent
        //   panoEnabled        explicit pano gate; if absent, falls back to enabled
        //   oemDashcamEnabled  explicit OEM Dashcam gate; default false (the OEM
        //                      front sensor doesn't need a stamp by default —
        //                      separate opt-in keeps pano clean while OEM is on)
        //
        // Resolver lives at isTelemetryOverlayEnabledFor(flow). Don't read
        // panoEnabled / oemDashcamEnabled directly from callers — the legacy
        // fallback is part of the contract.
        val telemetryOverlay = config.optJSONObject("telemetryOverlay") ?: JSONObject().also {
            config.put("telemetryOverlay", it)
        }
        if (!telemetryOverlay.has("enabled")) telemetryOverlay.put("enabled", false)

        // OEM Dashcam toggles (separate from camera.* because they're feature
        // gates, not HAL config). disableNativeDvr is sticky-applied on every
        // daemon boot if true (handles OTA / factory reset un-doing the
        // pm disable-user). bitrateBudget is the soft cap for the combined
        // pano+OEM bitrate when both pipelines run; UI uses it to size the
        // sliders. 10 Mbps matches the Adreno 610 H.264 ceiling under encoder
        // isolation.
        val oemDashcam = config.optJSONObject("oemDashcam") ?: JSONObject().also {
            config.put("oemDashcam", it)
        }
        if (!oemDashcam.has("enabled")) oemDashcam.put("enabled", false)
        // Note: legacy accOffMode key is intentionally NOT seeded here.
        // migrateOemDashcamModes nulls it on upgrades; fresh installs simply
        // don't have it. The modern schema is recordingMode + surveillanceMode.
        if (!oemDashcam.has("disableNativeDvr")) oemDashcam.put("disableNativeDvr", false)
        // Surveillance integration: when enabled, OEM dashcam pipeline records
        // dvr_*.mp4 clips in parallel with pano event_*.mp4 on every motion
        // trigger. Reuses the existing surveillance gating (SafeLocation,
        // schedule, per-camera enable) — no duplicate config.
        val oemSurv = oemDashcam.optJSONObject("surveillance") ?: JSONObject().also {
            oemDashcam.put("surveillance", it)
        }
        if (!oemSurv.has("enabled")) oemSurv.put("enabled", false)
        if (!oemDashcam.has("bitrateBudget"))    oemDashcam.put("bitrateBudget", 10_000_000)
        // Per-pipeline quality slot. Pano reads recording.recordingQuality;
        // OEM reads this. Without a separate slot, both pipelines pulled
        // from the same key and the budget-cap math double-counted pano's
        // tier (MAX→2 Mbps clamp regression). STANDARD default keeps OEM
        // clips well under the budget without user tuning.
        if (!oemDashcam.has("recordingQuality")) oemDashcam.put("recordingQuality", "STANDARD")
        if (!oemDashcam.has("codec")) oemDashcam.put("codec", "H264")
        if (!oemDashcam.has("fps")) oemDashcam.put("fps", 30)
        if (!oemDashcam.has("priorityWhenContended")) {
            // "pano" | "oemDashcam" — whichever pipeline holds the AVMCamera
            // when concurrentAvmSupported=0. Default pano because pano feeds
            // both surveillance and the existing dashcam mode.
            oemDashcam.put("priorityWhenContended", "pano")
        }
        if (!oemDashcam.has("segmentRotateOffsetMs")) {
            // Stagger OEM segment rotation so MediaMuxer.stop() bursts on the
            // two pipelines don't collide. 30s into the pano cycle.
            oemDashcam.put("segmentRotateOffsetMs", 30_000)
        }
        
        // Trip Analytics defaults
        val tripAnalytics = config.optJSONObject("tripAnalytics") ?: JSONObject().also {
            config.put("tripAnalytics", it)
        }
        if (!tripAnalytics.has("enabled")) tripAnalytics.put("enabled", false)

        // Floating status pill segment visibility. Independent of whether the
        // underlying feature (recording / trip analytics) is enabled — these
        // only gate the pill segments so users can hide either without
        // surrendering SYSTEM_ALERT_WINDOW or disabling the feature itself.
        val statusOverlay = config.optJSONObject("statusOverlay") ?: JSONObject().also {
            config.put("statusOverlay", it)
        }
        if (!statusOverlay.has("cameraVisible")) statusOverlay.put("cameraVisible", true)
        if (!statusOverlay.has("tripVisible")) statusOverlay.put("tripVisible", true)
        
        // BYD Cloud defaults
        val bydCloud = config.optJSONObject("bydCloud") ?: JSONObject().also {
            config.put("bydCloud", it)
        }
        if (!bydCloud.has("enabled")) bydCloud.put("enabled", false)

        // Vehicle appearance defaults — selected 3D model and body paint color.
        // Stored unified so AVN and remote (phone-over-tunnel) clients show the
        // same vehicle. modelId must match an entry in models/manifest.json; the
        // bundled default 'seal' is always available offline.
        val vehicle = config.optJSONObject("vehicle") ?: JSONObject().also {
            config.put("vehicle", it)
        }
        if (!vehicle.has("modelId")) vehicle.put("modelId", "seal")
        if (!vehicle.has("color")) vehicle.put("color", "#E8E8EC")  // Aurora White

        // Geocoding (place-name tagging) defaults — opt-in, fully offline by
        // default so the upgrade is non-surprising for existing users.
        // Per-flow split (recording / surveillance) so dashcam and sentry
        // clips can be tagged independently.
        //
        // Migration: if a pre-split config has top-level enabled / allowOnline
        // / customNominatimBase / nominatimCooldownUntilMs fields, fold them
        // into the new shape. The fold is one-shot and idempotent — once the
        // sub-objects exist applyDefaults is a no-op. Without this, users who
        // had the feature enabled under the old schema would silently lose
        // the toggle on first run after upgrade.
        val geocoding = config.optJSONObject("geocoding") ?: JSONObject().also {
            config.put("geocoding", it)
        }

        // Capture legacy values BEFORE constructing nested defaults so the
        // sub-objects pick up the old settings instead of defaulting to off.
        val legacyEnabled = geocoding.has("enabled") && geocoding.optBoolean("enabled", false)
        val legacyOnline  = geocoding.has("allowOnline") && geocoding.optBoolean("allowOnline", false)
        val legacyCustomUrl = geocoding.optString("customNominatimBase", "")
        val legacyCooldown = geocoding.optLong("nominatimCooldownUntilMs", 0L)

        val recordingGeo = geocoding.optJSONObject("recording") ?: JSONObject().also {
            geocoding.put("recording", it)
        }
        if (!recordingGeo.has("enabled")) recordingGeo.put("enabled", legacyEnabled)
        if (!recordingGeo.has("allowOnline")) recordingGeo.put("allowOnline", legacyOnline)

        val surveillanceGeo = geocoding.optJSONObject("surveillance") ?: JSONObject().also {
            geocoding.put("surveillance", it)
        }
        if (!surveillanceGeo.has("enabled")) surveillanceGeo.put("enabled", legacyEnabled)
        if (!surveillanceGeo.has("allowOnline")) surveillanceGeo.put("allowOnline", legacyOnline)

        val advancedGeo = geocoding.optJSONObject("advanced") ?: JSONObject().also {
            geocoding.put("advanced", it)
        }
        if (!advancedGeo.has("customNominatimBase")) {
            advancedGeo.put("customNominatimBase", legacyCustomUrl)
        }
        if (!advancedGeo.has("nominatimCooldownUntilMs")) {
            advancedGeo.put("nominatimCooldownUntilMs", legacyCooldown)
        }

        // Strip the legacy top-level keys after migration so future writers
        // don't accidentally re-introduce stale values.
        if (geocoding.has("enabled")) geocoding.remove("enabled")
        if (geocoding.has("allowOnline")) geocoding.remove("allowOnline")
        if (geocoding.has("customNominatimBase")) geocoding.remove("customNominatimBase")
        if (geocoding.has("nominatimCooldownUntilMs")) geocoding.remove("nominatimCooldownUntilMs")
    }
    
    /**
     * Load config from file (with caching).
     */
    @JvmStatic
    fun loadConfig(): JSONObject {
        val configFile = File(CONFIG_PATH)
        
        // Check if file changed since last load
        if (cachedConfig != null && configFile.exists()) {
            val fileModified = configFile.lastModified()
            if (fileModified <= lastModified.get()) {
                return cachedConfig!!
            }
        }
        
        return synchronized(this) {
            try {
                if (configFile.exists()) {
                    val content = configFile.readText()
                    val config = JSONObject(content)
                    // Run schema migration on every load. applyDefaults is
                    // idempotent — it only fills in absent fields and only
                    // strips legacy top-level geocoding keys that have
                    // already been folded into the nested shape. Without
                    // this call, a user with a pre-split flat geocoding
                    // schema on disk would silently lose their toggle —
                    // getGeocoding() would return false because the new
                    // nested keys wouldn't exist.
                    //
                    // Detect "needs migration" cheaply (legacy key at the
                    // top of geocoding) so non-migrating loads stay zero-
                    // overhead.
                    val migrationNeeded = run {
                        val geo = config.optJSONObject("geocoding") ?: return@run false
                        geo.has("enabled") || geo.has("allowOnline")
                            || geo.has("customNominatimBase")
                            || geo.has("nominatimCooldownUntilMs")
                    }
                    if (migrationNeeded) {
                        applyDefaults(config)
                        // Persist the migrated shape so subsequent loads
                        // skip the migration check entirely.
                        try {
                            saveConfigInternal(config)
                            Log.i(TAG, "Migrated legacy geocoding schema to nested form")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to persist migrated schema: ${e.message}")
                        }
                    }
                    cachedConfig = config
                    lastModified.set(configFile.lastModified())
                    Log.d(TAG, "Config loaded from $CONFIG_PATH")
                    config
                } else {
                    Log.w(TAG, "Config file not found, initializing...")
                    init()
                    cachedConfig ?: createDefaultConfig()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config: ${e.message}")
                cachedConfig ?: createDefaultConfig()
            }
        }
    }
    
    /**
     * Save entire config to file.
     */
    @JvmStatic
    fun saveConfig(config: JSONObject): Boolean {
        config.put("lastModified", System.currentTimeMillis())
        val success = saveConfigInternal(config)
        if (success) {
            cachedConfig = config
            // Track the file's actual mtime, NOT wall-clock — the cache
            // freshness check at loadConfig() compares fs mtime against
            // this value to detect cross-process writes. If we stored
            // System.currentTimeMillis() here, the saved mtime would
            // (almost always) be greater than the file's mtime, so the
            // fileModified <= lastModified check would never trip and
            // a cross-UID write would never invalidate the cache.
            lastModified.set(File(CONFIG_PATH).lastModified())
            notifyListeners("all", config)
        }
        return success
    }
    
    private fun saveConfigInternal(config: JSONObject): Boolean {
        val configFile = File(CONFIG_PATH)
        configFile.parentFile?.mkdirs()
        val payload = config.toString(2)

        // Atomic write: write to a sibling .tmp file, then rename. The rename
        // is a single inode swap on the filesystem, so power loss either
        // leaves the old file intact or fully promotes the new one — never
        // a half-written corrupt config that would wipe user settings.
        //
        // The catch matters: when the app UID (10xxx) writes here, it
        // can't always create new files in /data/local/tmp/ (the dir is
        // typically owned by shell:shell with the sticky bit). The tmp
        // create throws FileNotFoundException/EACCES. Without this catch,
        // every app-side write would fail, the cache would never be
        // updated, and `loadConfig` would re-enter `init()` →
        // `migrateFromLegacy()` on every subsequent call — producing the
        // ANR storm in the Connect-and-Test flow.
        val tmpFile = File(configFile.parentFile, configFile.name + ".tmp")
        try {
            FileWriter(tmpFile).use { it.write(payload) }
            tmpFile.setReadable(true, false)
            tmpFile.setWritable(true, false)
            if (tmpFile.renameTo(configFile)) {
                Log.i(TAG, "Config saved to $CONFIG_PATH (atomic)")
                return true
            }
            Log.w(TAG, "Atomic rename failed; falling back to direct write")
        } catch (e: Exception) {
            Log.w(TAG, "Tmp-write path unavailable (${e.message}); falling back to direct write")
        }

        // Fallback: write directly to the existing world-RW file. The
        // daemon (UID 2000) creates it on first boot with 0666, so the
        // app UID can open it for writing even though it can't create
        // new files in /data/local/tmp/. We lose atomicity here — a
        // crash mid-write corrupts the file — but for the cross-UID
        // case it's the only path that works, and corruption is
        // recoverable on next boot via the legacy-migration fallback.
        return try {
            if (!configFile.exists()) {
                Log.e(TAG, "Config file missing and tmp-create denied; cannot save")
                false
            } else {
                FileWriter(configFile).use { it.write(payload) }
                configFile.setReadable(true, false)
                configFile.setWritable(true, false)
                try { tmpFile.delete() } catch (_: Exception) {}
                Log.i(TAG, "Config saved to $CONFIG_PATH (direct)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config: ${e.message}")
            false
        }
    }
    
    // ==================== SECTION GETTERS ====================
    
    /**
     * Get surveillance config section.
     */
    @JvmStatic
    fun getSurveillance(): JSONObject {
        return loadConfig().optJSONObject("surveillance") ?: JSONObject()
    }
    
    /**
     * Get the surveillance schedule from config.
     * Returns a SurveillanceSchedule loaded from the surveillance section.
     */
    @JvmStatic
    fun getSurveillanceSchedule(): com.overdrive.app.surveillance.SurveillanceSchedule {
        val schedule = com.overdrive.app.surveillance.SurveillanceSchedule()
        schedule.loadFromJson(getSurveillance())
        return schedule
    }
    
    /**
     * Get recording config section.
     */
    @JvmStatic
    fun getRecording(): JSONObject {
        return loadConfig().optJSONObject("recording") ?: JSONObject()
    }
    
    /**
     * Get streaming config section.
     */
    @JvmStatic
    fun getStreaming(): JSONObject {
        return loadConfig().optJSONObject("streaming") ?: JSONObject()
    }

    /** Blind Spot overlay config section: {enabled, rearFov, sideFov, yaw, roll,
     *  pitch, feather}. mtime-gated loadConfig; callers needing cross-UID
     *  freshness (app reading a daemon/web write) should forceReload() first. */
    @JvmStatic
    fun getBlindSpot(): JSONObject {
        return loadConfig().optJSONObject("blindspot") ?: JSONObject()
    }

    /**
     * Recording-side dewarp strength (Fitzgibbon division model).
     *
     * Range: 0..100. 0 = off (passthrough — shader stays bit-exact to the
     * legacy mosaic). 100 = maximum rectification.
     *
     * Single shared key used by BOTH the recording (ACC-on dashcam) and
     * surveillance (ACC-off) flows. The UI exposes a slider in each section
     * but they both read/write this key — flipping it in one place applies
     * to the other automatically.
     *
     * Defaults to 0 if absent. Clamped on read so a corrupt config value
     * cannot push the shader out of its safe range.
     */
    @JvmStatic
    fun getRectifyStrength(): Int {
        val raw = getRecording().optInt("rectifyStrength", 0)
        return raw.coerceIn(0, 100)
    }

    /** Set the shared recording/surveillance dewarp strength (0..100). */
    @JvmStatic
    fun setRectifyStrength(strength: Int): Boolean {
        return updateValues("recording", mapOf(
            "rectifyStrength" to strength.coerceIn(0, 100)
        ))
    }
    
    /**
     * Get telegram config section.
     */
    @JvmStatic
    fun getTelegram(): JSONObject {
        return loadConfig().optJSONObject("telegram") ?: JSONObject()
    }
    
    /**
     * Get proximity guard config section.
     */
    @JvmStatic
    fun getProximityGuard(): JSONObject {
        return loadConfig().optJSONObject("proximityGuard") ?: JSONObject()
    }
    
    /**
     * Get telemetry overlay config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getTelemetryOverlay(): JSONObject {
        return loadConfig().optJSONObject("telemetryOverlay") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    // ==================== SECTION SETTERS ====================
    
    /**
     * Update surveillance config section.
     */
    @JvmStatic
    fun setSurveillance(surveillance: JSONObject): Boolean {
        return updateSection("surveillance", surveillance)
    }
    
    /**
     * Update recording config section.
     */
    @JvmStatic
    fun setRecording(recording: JSONObject): Boolean {
        return updateSection("recording", recording)
    }
    
    /**
     * Update streaming config section.
     */
    @JvmStatic
    fun setStreaming(streaming: JSONObject): Boolean {
        return updateSection("streaming", streaming)
    }
    
    /**
     * Update telegram config section.
     */
    @JvmStatic
    fun setTelegram(telegram: JSONObject): Boolean {
        return updateSection("telegram", telegram)
    }
    
    /**
     * Update proximity guard config section.
     */
    @JvmStatic
    fun setProximityGuard(proximityGuard: JSONObject): Boolean {
        return updateSection("proximityGuard", proximityGuard)
    }
    
    /**
     * Update telemetry overlay config section.
     */
    @JvmStatic
    fun setTelemetryOverlay(telemetryOverlay: JSONObject): Boolean {
        return updateSection("telemetryOverlay", telemetryOverlay)
    }

    /**
     * Resolve whether the telemetry overlay should burn-in for a given
     * recording flow ("pano" or "oemDashcam"). The legacy `enabled` key acts
     * as the pano fallback so pre-split installs keep their toggle. OEM
     * dashcam defaults to off — separate opt-in.
     */
    @JvmStatic
    fun isTelemetryOverlayEnabledFor(flow: String): Boolean {
        val tel = getTelemetryOverlay()
        return when (flow) {
            "oemDashcam" -> tel.optBoolean("oemDashcamEnabled", false)
            else /* "pano" */ -> {
                if (tel.has("panoEnabled")) tel.optBoolean("panoEnabled", false)
                else tel.optBoolean("enabled", false)
            }
        }
    }

    // ==================== OEM DASHCAM ====================

    /**
     * Get oemDashcam feature-gate section (separate from camera.* which is HAL
     * config). Read fields: disableNativeDvr, bitrateBudget,
     * priorityWhenContended, segmentRotateOffsetMs.
     */
    @JvmStatic
    fun getOemDashcam(): JSONObject {
        return loadConfig().optJSONObject("oemDashcam") ?: JSONObject().apply {
            put("disableNativeDvr", false)
            put("bitrateBudget", 10_000_000)
            put("priorityWhenContended", "pano")
            put("segmentRotateOffsetMs", 30_000)
            // Two independent modes — one per page:
            //   recordingMode      governs OEM behaviour during pano DASHCAM
            //                      recording (cam_*.mp4 flow). Off | Continuous
            //                      | Smart. Smart = follow whatever the pano
            //                      Recording Mode is doing (Continuous /
            //                      Drive Mode / Proximity Guard).
            //   surveillanceMode   governs OEM behaviour during pano
            //                      SURVEILLANCE (event_*.mp4 flow). Off |
            //                      Continuous | Smart. Smart = follow pano
            //                      surveillance motion detection.
            put("recordingMode", "off")
            put("surveillanceMode", "off")
        }
    }

    @JvmStatic
    fun setOemDashcam(oemDashcam: JSONObject): Boolean {
        return updateSection("oemDashcam", oemDashcam)
    }

    /**
     * True iff either OEM Dashcam mode is non-Off. Pipeline lifecycle is
     * OR-gated. Streaming alone does NOT activate recording — that's gated
     * separately by the daemon-side stream router, mirroring how pano
     * keeps its recording and stream encoders independent.
     */
    @JvmStatic
    fun isAnyOemDashcamTriggerEnabled(): Boolean {
        val oem = getOemDashcam()
        // Once either new key is present, it is the authoritative answer.
        // Legacy `enabled` / `surveillance.enabled` are read-only mirrors
        // post-migration and must NEVER override an explicit Off pick.
        if (oem.has("recordingMode") || oem.has("surveillanceMode")) {
            val rec = oem.optString("recordingMode", "off").lowercase()
            val surv = oem.optString("surveillanceMode", "off").lowercase()
            return rec != "off" || surv != "off"
        }
        // Pre-migration legacy schemas only.
        if (oem.optBoolean("enabled", false)) return true
        val legacySurv = oem.optJSONObject("surveillance")
        if (legacySurv != null && legacySurv.optBoolean("enabled", false)) return true
        return false
    }

    @JvmStatic
    fun getOemRecordingMode(): String {
        val oem = getOemDashcam()
        if (oem.has("recordingMode")) return oem.optString("recordingMode", "off").lowercase()
        // Legacy: enabled=true → smart (matched pano mode). enabled=false → off.
        return if (oem.optBoolean("enabled", false)) "smart" else "off"
    }

    @JvmStatic
    fun getOemSurveillanceMode(): String {
        val oem = getOemDashcam()
        if (oem.has("surveillanceMode")) return oem.optString("surveillanceMode", "off").lowercase()
        // Legacy: surveillance.enabled=true → smart (follow pano motion).
        val legacy = oem.optJSONObject("surveillance")
        return if (legacy != null && legacy.optBoolean("enabled", false)) "smart" else "off"
    }

    /**
     * Migrate legacy {@code enabled / surveillance.enabled / accOffMode} →
     * {@code recordingMode / surveillanceMode}. Idempotent — once one of the
     * new keys is present we treat the migration as done.
     */
    @JvmStatic
    fun migrateOemDashcamModes(): Boolean {
        val root = loadConfig()
        val oem = root.optJSONObject("oemDashcam") ?: return false
        if (oem.has("recordingMode") || oem.has("surveillanceMode")) return false
        val delta = JSONObject()
        val legacyEnabled = oem.optBoolean("enabled", false)
        val legacyMode = oem.optString("accOffMode", "off")
        val legacySurv = oem.optJSONObject("surveillance")
        val survOn = legacySurv?.optBoolean("enabled", false) == true
        // Pre-migration semantics: enabled=true recorded during the ACC ON
        // (driving) phase regardless of accOffMode; accOffMode then governed
        // what happened at ACC OFF (off=tear down, smart=event-trigger,
        // continuous=keep recording). Map recording-side to "continuous" for
        // any legacyEnabled=true install so the driving-phase recording
        // promise is preserved. Surveillance-side then independently captures
        // the parked-window intent from accOffMode + surveillance.enabled.
        if (legacyEnabled) {
            delta.put("recordingMode", "continuous")
            // accOffMode=continuous → user wanted parked-window recording too.
            // accOffMode=smart      → user wanted parked-window event clips.
            // accOffMode=off/unset  → user wanted clean ACC OFF teardown,
            //                          unless surveillance.enabled overrides.
            val survFromAccOff = when (legacyMode) {
                "continuous" -> "continuous"
                "smart" -> "smart"
                else -> if (survOn) "smart" else "off"
            }
            delta.put("surveillanceMode", survFromAccOff)
        } else {
            delta.put("recordingMode", "off")
            delta.put("surveillanceMode", if (survOn) "smart" else "off")
        }
        // R8-A #19: copy pano's codec/quality/fps into the oemDashcam
        // section so applyRecordingConfigFromUcm doesn't keep falling
        // back to pano's keys. The fallback was the pre-migration design
        // but now contradicts the doc comment that says OEM "deliberately
        // does NOT read pano's recording.* keys". Migrate once at upgrade
        // time; subsequent picks land directly into oemDashcam via
        // QualitySettingsApiHandler.
        val rec = root.optJSONObject("recording")
        if (rec != null) {
            if (!oem.has("codec") && rec.has("codec")) {
                delta.put("codec", rec.optString("codec"))
            }
            if (!oem.has("recordingQuality") && rec.has("recordingQuality")) {
                delta.put("recordingQuality", rec.optString("recordingQuality"))
            }
            if (!oem.has("fps") && rec.has("fps")) {
                delta.put("fps", rec.optInt("fps"))
            }
        }
        // Null out legacy keys so isAnyOemDashcamTriggerEnabled (and any
        // other reader that hasn't been switched to the new accessors)
        // can't resurrect a stale enabled=true from the pre-migration
        // schema. The nested surveillance.enabled stays as a one-way
        // mirror of surveillanceMode for readers that still consult it
        // (SurveillanceEngineGpu) — but we explicitly write its new
        // value so it can't disagree.
        delta.put("enabled", JSONObject.NULL)
        delta.put("accOffMode", JSONObject.NULL)
        // Mirror the resolved surveillanceMode (post-migration) into the
        // legacy nested boolean so readers that still consult it (e.g.
        // SurveillanceEngineGpu) can't disagree with the new accessor.
        val resolvedSurvMode = delta.optString("surveillanceMode", "off")
        // Clone existing surveillance object (if any) and only mutate `enabled`,
        // matching the POST handler's per-key merge semantics. Without the clone,
        // any future sub-key under `oem.surveillance` would be silently dropped
        // during migration.
        val existingSurv = oem.optJSONObject("surveillance")
        val sOut = if (existingSurv != null) JSONObject(existingSurv.toString()) else JSONObject()
        sOut.put("enabled", "off" != resolvedSurvMode)
        delta.put("surveillance", sOut)
        return updateSection("oemDashcam", delta)
    }

    /**
     * Resolve the effective OEM Dashcam AVMCamera id.
     *
     * Symmetric to pano's default: pano defaults to id=1 (see
     * {@code PanoramicCameraGpu.PHYSICAL_CAMERA_ID = 1}), so OEM defaults
     * to id=0. The two stay opposite by construction. Three rules in order:
     *
     *  1. **Manual override is honored verbatim.** When the user picked a
     *     specific id in the camera-mapping dialog (
     *     {@code oemDashcamManualOverride=true}), this returns that id —
     *     including -1 if they chose "Off". Manual is the authoritative answer.
     *  2. **Auto-infer from pano** when {@code oemDashcamManualOverride=false}:
     *     - pano=0 → OEM=1 (Tang-style, e.g. user manually set pano to 0)
     *     - pano=1 → OEM=0 (Seal/Han, the typical case)
     *  3. **Default to 0** otherwise (pano unprobed, or pano is some other
     *     id we don't have an XOR rule for). Symmetric to pano's id=1
     *     default — out-of-the-box install on Seal/Han works without
     *     forcing the user through the camera-mapping dialog. Tang users
     *     who manually set pano to 0 get OEM=1 via rule 2; if they ALSO
     *     don't set pano manually (pano stays at default 1, which is
     *     wrong for Tang anyway), the user has to fix the pano side first
     *     — no different from the pre-OEM situation.
     *
     * Safety net: even if this returns 0 on a vehicle where id=0 happens to
     * be the pano sensor, {@code OemDashcamPipeline.validateHalDimsOrReject}
     * checks BmmCameraInfo's declared dims and refuses to open anything with
     * an aspect ≥ 2.0 (panoramic strip). The applyLifecycle catch then rolls
     * UCM back to {@code enabled=false} with a {@code lastStartError} the UI
     * surfaces.
     *
     * Caller must ensure the camera section is fresh (forceReload from app UID).
     */
    @JvmStatic
    fun resolveOemDashcamId(): Int {
        val camera = loadConfig().optJSONObject("camera") ?: return 0
        if (camera.optBoolean("oemDashcamManualOverride", false)) {
            return camera.optInt("oemDashcamCameraId", -1)
        }
        val panoId = if (camera.optBoolean("manualOverride", false)) {
            camera.optInt("manualCameraId", -1)
        } else {
            camera.optInt("probedCameraId", -1)
        }
        return when (panoId) {
            0 -> 1                  // Tang-style: pano=0 → OEM=1
            1 -> 0                  // Seal/Han: pano=1 → OEM=0
            else -> 0               // Default symmetric to pano's id=1 default
        }
    }
    
    /**
     * Get trip analytics config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getTripAnalytics(): JSONObject {
        return loadConfig().optJSONObject("tripAnalytics") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    /**
     * Update trip analytics config section.
     */
    @JvmStatic
    fun setTripAnalytics(tripAnalytics: JSONObject): Boolean {
        return updateSection("tripAnalytics", tripAnalytics)
    }

    /**
     * Get status-overlay (floating pill) visibility section.
     * Each segment defaults to visible=true so installs that pre-date this
     * setting see no behavior change.
     */
    @JvmStatic
    fun getStatusOverlay(): JSONObject {
        return loadConfig().optJSONObject("statusOverlay") ?: JSONObject().apply {
            put("cameraVisible", true)
            put("tripVisible", true)
        }
    }

    /**
     * Update status-overlay (floating pill) visibility section.
     */
    @JvmStatic
    fun setStatusOverlay(statusOverlay: JSONObject): Boolean {
        return updateSection("statusOverlay", statusOverlay)
    }
    
    /**
     * Get BYD Cloud config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getBydCloud(): JSONObject {
        return loadConfig().optJSONObject("bydCloud") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    /**
     * Update BYD Cloud config section.
     */
    @JvmStatic
    fun setBydCloud(bydCloud: JSONObject): Boolean {
        return updateSection("bydCloud", bydCloud)
    }

    /**
     * Get vehicle appearance config section (selected 3D model + body color +
     * drive-side layout). `driveSide` is "lhd" or "rhd" and decides which
     * physical front door each BYD HAL door-area code maps to in
     * notifications. Default "rhd" because the field-tested L↔R swap in
     * DoorEventNotifier was calibrated against RHD Sealion/Atto/Seal trims.
     */
    @JvmStatic
    fun getVehicle(): JSONObject {
        val stored = loadConfig().optJSONObject("vehicle")
        if (stored != null) {
            // Backfill driveSide on configs written before this field existed
            // so call sites can read it unconditionally without a default.
            if (!stored.has("driveSide")) stored.put("driveSide", "rhd")
            return stored
        }
        return JSONObject().apply {
            put("modelId", "seal")
            put("color", "#E8E8EC")
            put("driveSide", "rhd")
        }
    }

    /**
     * Update vehicle appearance config section.
     */
    @JvmStatic
    fun setVehicle(vehicle: JSONObject): Boolean {
        return updateSection("vehicle", vehicle)
    }

    /**
     * Web-shell appearance preference (theme picker shipped in the WebView
     * pages). Stored separately from the Android-shell theme so a
     * Telegram-bot user accessing the tunnel can pick their own preference
     * without touching the Android side. Default: "dark".
     *
     * Schema:
     *   { "theme": "dark" | "light" | "auto",
     *     "locale": "en" | "de" | … | "auto" }
     *
     * `locale` is stored here (not in LocaleManager) so the web-side
     * language picker doesn't cross-pollinate the Android app's locale.
     * Survives tunnel-URL changes (each new zrok session is a fresh
     * origin, so localStorage alone is not enough). Default: "auto"
     * (the runtime falls back to navigator.language).
     */
    @JvmStatic
    fun getAppearance(): JSONObject {
        return loadConfig().optJSONObject("appearance") ?: JSONObject().apply {
            put("theme", "dark")
            put("locale", "auto")
        }
    }

    @JvmStatic
    fun setAppearance(appearance: JSONObject): Boolean {
        return updateSection("appearance", appearance)
    }

    /**
     * Geocoding (place-name tagging) section. Per-flow split so dashcam
     * and surveillance recordings can be tagged independently.
     *
     * Schema:
     *   recording: { enabled (bool, default false),
     *                allowOnline (bool, default false) }
     *   surveillance: { enabled (bool, default false),
     *                   allowOnline (bool, default false) }
     *   advanced: { customNominatimBase (string, default ""),
     *               nominatimCooldownUntilMs (long, default 0) }
     *
     * Why per-flow: a user driving a road-trip wants dashcam clips tagged
     * with the cities they passed through, but the same user parking at
     * home overnight may NOT want sentry clips tagged with their home
     * address (especially if shared via Telegram pushes). Per-flow toggles
     * give that distinction; a shared "advanced" sub-section keeps
     * power-user knobs (custom URL + cooldown state) singletons.
     *
     * The resolver picks {@code recording.allowOnline} for normal/proximity
     * recordings and {@code surveillance.allowOnline} for sentry events.
     * Cache + rate limiter are process-shared (one rate budget, one cache).
     */
    @JvmStatic
    fun getGeocoding(): JSONObject {
        return loadConfig().optJSONObject("geocoding") ?: JSONObject().apply {
            put("recording", JSONObject().apply {
                put("enabled", false)
                put("allowOnline", false)
            })
            put("surveillance", JSONObject().apply {
                put("enabled", false)
                put("allowOnline", false)
            })
            put("advanced", JSONObject().apply {
                put("customNominatimBase", "")
                put("nominatimCooldownUntilMs", 0L)
            })
        }
    }

    @JvmStatic
    fun setGeocoding(geocoding: JSONObject): Boolean {
        return updateSection("geocoding", geocoding)
    }

    /**
     * Convenience: query whether a given flow ("recording" or "surveillance")
     * is enabled. Falls back to {@code false} on any read failure so the
     * recorder's hot path is fail-closed.
     */
    @JvmStatic
    fun isGeocodingEnabledForFlow(flow: String): Boolean {
        try {
            val geo = loadConfig().optJSONObject("geocoding") ?: return false
            val sec = geo.optJSONObject(flow) ?: return false
            return sec.optBoolean("enabled", false)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Companion to [isGeocodingEnabledForFlow] for the online tier gate.
     */
    @JvmStatic
    fun isGeocodingOnlineAllowedForFlow(flow: String): Boolean {
        try {
            val geo = loadConfig().optJSONObject("geocoding") ?: return false
            val sec = geo.optJSONObject(flow) ?: return false
            return sec.optBoolean("allowOnline", false)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Native-shell preferences. Today this carries `locale` for the Android
     * UI's language picker — kept separate from `appearance.locale` (which
     * is the WebView-only locale) so a tunnel-side picker doesn't change
     * the in-car native shell's language and vice versa.
     *
     * Schema: { "locale": "<bcp47>" | "auto" }
     *
     * The legacy file at /data/local/tmp/.overdrive/locale was unreliable
     * because the app UID can't `mkdir` under /data/local/tmp/, so writes
     * from the picker silently failed and the language reverted on next
     * cold start.
     */
    @JvmStatic
    fun getNativeShell(): JSONObject {
        return loadConfig().optJSONObject("nativeShell") ?: JSONObject()
    }

    @JvmStatic
    fun setNativeShell(nativeShell: JSONObject): Boolean {
        return updateSection("nativeShell", nativeShell)
    }


    /**
     * Update a specific section of the config.
     */
    @JvmStatic
    fun updateSection(section: String, data: JSONObject): Boolean {
        synchronized(this) {
            val config = loadConfig()
            // Merge into existing section to preserve keys not present in data
            // (e.g. surveillanceEnabled is set separately from detection params)
            val existing = config.optJSONObject(section) ?: JSONObject()
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                existing.put(key, data.get(key))
            }
            config.put(section, existing)
            val success = saveConfig(config)
            if (success) {
                notifyListeners(section, existing)
            }
            return success
        }
    }
    
    /**
     * Update individual values within a section.
     */
    @JvmStatic
    fun updateValues(section: String, values: Map<String, Any>): Boolean {
        synchronized(this) {
            val config = loadConfig()
            val sectionObj = config.optJSONObject(section) ?: JSONObject()
            
            values.forEach { (key, value) ->
                sectionObj.put(key, value)
            }
            
            config.put(section, sectionObj)
            val success = saveConfig(config)
            if (success) {
                notifyListeners(section, sectionObj)
            }
            return success
        }
    }
    
    // ==================== CONVENIENCE METHODS ====================
    
    /**
     * Get a specific surveillance value.
     */
    @JvmStatic
    fun getSurveillanceValue(key: String, default: Any): Any {
        return getSurveillance().opt(key) ?: default
    }
    
    /**
     * Get a specific recording value.
     */
    @JvmStatic
    fun getRecordingValue(key: String, default: Any): Any {
        return getRecording().opt(key) ?: default
    }
    
    /**
     * Get a specific proximity guard value.
     */
    @JvmStatic
    fun getProximityGuardValue(key: String, default: Any): Any {
        return getProximityGuard().opt(key) ?: default
    }
    
    /**
     * Check if surveillance is enabled in config (user preference for ACC OFF auto-start).
     */
    @JvmStatic
    fun isSurveillanceEnabled(): Boolean {
        return getSurveillance().optBoolean("surveillanceEnabled", false)
    }
    
    /**
     * Set surveillance enabled state in config.
     */
    @JvmStatic
    fun setSurveillanceEnabled(enabled: Boolean): Boolean {
        return updateValues("surveillance", mapOf("surveillanceEnabled" to enabled))
    }
    
    // ==================== LISTENERS ====================
    
    @JvmStatic
    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }
    
    @JvmStatic
    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(section: String, config: JSONObject) {
        listeners.forEach { listener ->
            try {
                listener.onConfigChanged(section, config)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: ${e.message}")
            }
        }
    }
    
    // ==================== UTILITY ====================
    
    private fun createDefaultConfig(): JSONObject {
        val config = JSONObject()
        config.put("surveillance", JSONObject())
        config.put("recording", JSONObject())
        config.put("streaming", JSONObject())
        config.put("telegram", JSONObject())
        config.put("camera", JSONObject())
        config.put("proximityGuard", JSONObject())
        config.put("telemetryOverlay", JSONObject())
        config.put("tripAnalytics", JSONObject())
        config.put("oemDashcam", JSONObject())
        config.put("bydCloud", JSONObject())
        config.put("geocoding", JSONObject())
        config.put("version", 1)
        config.put("lastModified", System.currentTimeMillis())
        applyDefaults(config)
        return config
    }
    
    /**
     * Force reload from disk (bypasses cache).
     */
    @JvmStatic
    fun forceReload(): JSONObject {
        synchronized(this) {
            cachedConfig = null
            lastModified.set(0)
            return loadConfig()
        }
    }
    
    /**
     * Get the config file path (for debugging).
     */
    @JvmStatic
    fun getConfigPath(): String = CONFIG_PATH
    
    /**
     * Check if config file exists.
     */
    @JvmStatic
    fun configExists(): Boolean = File(CONFIG_PATH).exists()
    
    /**
     * Get last modified timestamp.
     */
    @JvmStatic
    fun getLastModified(): Long {
        return File(CONFIG_PATH).let { if (it.exists()) it.lastModified() else 0L }
    }
}
