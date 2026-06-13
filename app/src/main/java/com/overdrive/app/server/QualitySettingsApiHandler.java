package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;

/**
 * Quality Settings API Handler - manages recording and streaming quality settings.
 * 
 * SOTA: Also handles storage limit settings via StorageManager.
 * 
 * Endpoints:
 * - GET /api/settings/quality - Get current quality settings
 * - POST /api/settings/quality - Update quality settings
 * - GET /api/settings/storage - Get storage limit settings
 * - POST /api/settings/storage - Update storage limit settings
 */
public class QualitySettingsApiHandler {
    
    // Stored quality settings
    // Single user-facing recording quality tier (ECONOMY/STANDARD/HIGH/PREMIUM/MAX).
    // Persisted in UnifiedConfigManager under recording.recordingQuality.
    // Default STANDARD on first load — legacy values reset per migration policy.
    private static String recordingQuality = "STANDARD";
    /** @deprecated mirrors recordingQuality; kept until persistence migration completes. */
    @Deprecated
    private static String recordingBitrate = "STANDARD";
    private static String recordingCodec = "H264";      // H264 or H265
    
    private static final String UNIFIED_CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    private static final String LEGACY_SETTINGS_FILE = "/data/local/tmp/camera_settings.json";
    
    /**
     * Handle quality settings API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/settings/quality") && method.equals("GET")) {
            sendQualitySettings(out);
            return true;
        }
        if (path.equals("/api/settings/quality") && method.equals("POST")) {
            handleQualitySettingsPost(out, body);
            return true;
        }
        // SOTA: Storage limit settings
        if (path.equals("/api/settings/storage") && method.equals("GET")) {
            sendStorageSettings(out);
            return true;
        }
        if (path.equals("/api/settings/storage") && method.equals("POST")) {
            handleStorageSettingsPost(out, body);
            return true;
        }
        // SOTA: Unified config endpoint for cross-UID sync (proximityGuard, recording, streaming)
        if (path.equals("/api/settings/unified") && method.equals("GET")) {
            sendUnifiedConfig(out);
            return true;
        }
        if (path.equals("/api/settings/unified") && method.equals("POST")) {
            handleUnifiedConfigPost(out, body);
            return true;
        }
        // Telemetry overlay settings
        if (path.equals("/api/settings/telemetry-overlay") && method.equals("GET")) {
            sendTelemetryOverlaySettings(out);
            return true;
        }
        if (path.equals("/api/settings/telemetry-overlay") && method.equals("POST")) {
            handleTelemetryOverlayPost(out, body);
            return true;
        }
        // Audio recording toggle (mic capture in app process, muxed into clips)
        if (path.equals("/api/settings/audio-recording") && method.equals("GET")) {
            sendAudioRecordingSettings(out);
            return true;
        }
        if (path.equals("/api/settings/audio-recording") && method.equals("POST")) {
            handleAudioRecordingPost(out, body);
            return true;
        }
        // Place tagging (reverse geocoding) — per-flow split so dashcam and
        // surveillance can be enabled independently. GET returns the full
        // schema; POST accepts a flow-keyed delta (recording / surveillance
        // / advanced sub-objects).
        if (path.equals("/api/settings/geocoding") && method.equals("GET")) {
            sendGeocodingSettings(out);
            return true;
        }
        if (path.equals("/api/settings/geocoding") && method.equals("POST")) {
            handleGeocodingPost(out, body);
            return true;
        }
        // Recording composition layout (standard 360 mosaic / dashcam)
        if (path.equals("/api/settings/recording-layout") && method.equals("GET")) {
            sendRecordingLayoutSettings(out);
            return true;
        }
        if (path.equals("/api/settings/recording-layout") && method.equals("POST")) {
            handleRecordingLayoutPost(out, body);
            return true;
        }
        // Sentry (surveillance) composition layout — independent of the
        // dashcam layout above so sentry and dashcam can use different layouts.
        if (path.equals("/api/settings/surveillance-layout") && method.equals("GET")) {
            sendSurveillanceLayoutSettings(out);
            return true;
        }
        if (path.equals("/api/settings/surveillance-layout") && method.equals("POST")) {
            handleSurveillanceLayoutPost(out, body);
            return true;
        }
        // Web-shell appearance (theme picker shipped on every page).
        // Same UnifiedConfigManager-backed pattern as the rest of /api/settings.
        if (path.equals("/api/settings/appearance") && method.equals("GET")) {
            sendAppearance(out);
            return true;
        }
        if (path.equals("/api/settings/appearance") && method.equals("POST")) {
            handleAppearancePost(out, body);
            return true;
        }
        // Telegram bot status — used by the surveillance settings UI to
        // grey-out the per-tier filter toggles when the bot isn\'t paired,
        // so the user understands why the toggles do nothing instead of
        // silently configuring a feature that can never fire.
        if (path.equals("/api/settings/telegram-status") && method.equals("GET")) {
            sendTelegramStatus(out);
            return true;
        }
        return false;
    }

    /**
     * GET /api/settings/telegram-status — read /data/local/tmp/telegram_config.properties
     * and report whether the bot is configured (token present) and paired
     * (owner_chat_id > 0). Both must be true for any Telegram message to
     * actually leave the device. The web UI uses this to disable the tier
     * filter toggles + show a "pair Telegram first" hint.
     */
    private static void sendTelegramStatus(OutputStream out) throws Exception {
        boolean configured = false;
        boolean paired = false;
        try {
            configured = com.overdrive.app.telegram.config.UnifiedTelegramConfig.hasBotToken();
            paired = configured
                    && com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId() > 0;
        } catch (Exception e) {
            // Treat any read failure as "not configured" — the UI will
            // grey out the toggles and the runtime gate (NotificationGate
            // → daemon "Owner not set") still backstops the user.
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("configured", configured);
        response.put("paired", paired);
        // `enabled` = the gate condition the engine effectively uses (token
        // present AND owner paired). Surface as a single field so the UI
        // doesn\'t have to re-compute the same logic.
        response.put("enabled", configured && paired);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/settings/appearance — return the saved theme + locale
     * preferences for the WEB UI (not the Android app). Defaults to
     * theme=dark / locale=auto so first-load matches the design system.
     *
     * Note: `locale` here is the web-only language pick. The Android
     * app's locale lives in LocaleManager and is round-tripped through
     * /api/i18n/lang. Keeping these endpoints separate is what stops
     * picking Hindi on the tunnel from also flipping the in-car app.
     */
    private static void sendAppearance(OutputStream out) throws Exception {
        JSONObject app = com.overdrive.app.config.UnifiedConfigManager.getAppearance();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("theme", app.optString("theme", "dark"));
        response.put("locale", app.optString("locale", "auto"));
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/settings/appearance — body: { "theme": "dark"|"light"|"auto",
     *                                          "locale": "<bcp47>"|"auto" }.
     * Either field may be omitted (partial update). theme is validated to
     * one of three strings; locale is validated against LocaleManager
     * SUPPORTED set (with "auto" sentinel allowed). Persists into the
     * appearance section of the unified config, NOT into LocaleManager.
     */
    private static void handleAppearancePost(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            JSONObject app = new JSONObject();
            String theme = req.optString("theme", null);
            if (theme != null) {
                if (!"dark".equals(theme) && !"light".equals(theme) && !"auto".equals(theme)) {
                    response.put("success", false);
                    response.put("error", "theme must be one of: dark, light, auto");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                app.put("theme", theme);
            }
            String locale = req.optString("locale", null);
            if (locale != null) {
                if (!"auto".equals(locale) && !com.overdrive.app.server.LocaleManager.isSupported(locale)) {
                    response.put("success", false);
                    response.put("error", "locale must be 'auto' or one of the supported tags");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                app.put("locale", locale);
            }
            boolean ok = com.overdrive.app.config.UnifiedConfigManager.setAppearance(app);
            response.put("success", ok);
            if (theme != null)  response.put("theme", theme);
            if (locale != null) response.put("locale", locale);
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Send storage limit settings.
     */
    /** Throttle for the auto-refresh in sendStorageSettings. Without this,
     * a USB-not-inserted user (extremely common — most installs are SD-only)
     * triggers a full discoverVolumes cycle on every poll of /api/settings/storage,
     * which the UI fires every ~10s. The refresh runs `sm list-volumes` and a
     * `/proc/mounts` parse + StatFs probes — cheap individually but spammy
     * in the log and competing with the storage path under heavy load.
     * 30s is well below the time scale of physical insert/remove events. */
    private static volatile long lastAutoRefreshMs = 0L;
    private static final long AUTO_REFRESH_MIN_INTERVAL_MS = 30_000L;

    private static void sendStorageSettings(OutputStream out) throws Exception {
        StorageManager storage = StorageManager.getInstance();

        // Refresh SD/USB detection only when BOTH are missing (handles
        // post-boot inserts) AND not too recently. Previously this fired
        // on every poll for any user who didn't have a USB stick inserted —
        // since `!isUsbAvailable()` is true forever in that config — driving
        // a discoverVolumes cycle every ~10s. Narrowing the trigger to
        // genuinely-degraded state + a 30s throttle eliminates the spam.
        boolean sdMissing = !storage.isSdCardAvailable();
        boolean usbMissing = !storage.isUsbAvailable();
        long now = System.currentTimeMillis();
        if ((sdMissing || usbMissing) && (now - lastAutoRefreshMs) > AUTO_REFRESH_MIN_INTERVAL_MS) {
            lastAutoRefreshMs = now;
            storage.refreshSdCard();  // refreshes both SD and USB
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
        response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
        response.put("minLimitMb", StorageManager.getMinLimitMb());

        // Dynamic per-volume max from live StatFs (clamped to per-category share).
        response.put("maxLimitMb", storage.getEffectiveMaxLimitMb(StorageManager.StorageType.INTERNAL));
        response.put("maxLimitMbSdCard", storage.getEffectiveMaxLimitMb(StorageManager.StorageType.SD_CARD));
        response.put("maxLimitMbUsb", storage.getEffectiveMaxLimitMb(StorageManager.StorageType.USB));

        response.put("recordingsPath", storage.getRecordingsPath());
        response.put("surveillancePath", storage.getSurveillancePath());
        response.put("recordingsSize", storage.getRecordingsSize());
        response.put("surveillanceSize", storage.getSurveillanceSize());
        response.put("recordingsCount", storage.getRecordingsCount());
        response.put("surveillanceCount", storage.getSurveillanceCount());

        // Configured = what the user picked (persisted). Active = what we
        // actually write to right now (INTERNAL when the configured external
        // volume isn't currently mounted). UI picker should bind to the
        // configured value; status chips / "currently writing to X" copy
        // should bind to the Active variant.
        response.put("recordingsStorageType", storage.getRecordingsStorageType().name());
        response.put("surveillanceStorageType", storage.getSurveillanceStorageType().name());
        response.put("recordingsStorageTypeActive", storage.getActiveRecordingsStorageType().name());
        response.put("surveillanceStorageTypeActive", storage.getActiveSurveillanceStorageType().name());

        // SD card info
        response.put("sdCardAvailable", storage.isSdCardAvailable());
        response.put("sdCardPath", storage.getSdCardPath());
        if (storage.isSdCardAvailable()) {
            response.put("sdCardFreeSpace", storage.getSdCardFreeSpace());
            response.put("sdCardTotalSpace", storage.getSdCardTotalSpace());
            response.put("sdCardFreeFormatted", StorageManager.formatSize(storage.getSdCardFreeSpace()));
            response.put("sdCardTotalFormatted", StorageManager.formatSize(storage.getSdCardTotalSpace()));
        }

        // USB info
        response.put("usbAvailable", storage.isUsbAvailable());
        response.put("usbPath", storage.getUsbPath());
        if (storage.isUsbAvailable()) {
            response.put("usbFreeSpace", storage.getUsbFreeSpace());
            response.put("usbTotalSpace", storage.getUsbTotalSpace());
            response.put("usbFreeFormatted", StorageManager.formatSize(storage.getUsbFreeSpace()));
            response.put("usbTotalFormatted", StorageManager.formatSize(storage.getUsbTotalSpace()));
        }

        // Internal storage info
        response.put("internalFreeSpace", storage.getInternalFreeSpace());
        response.put("internalTotalSpace", storage.getInternalTotalSpace());
        response.put("internalFreeFormatted", StorageManager.formatSize(storage.getInternalFreeSpace()));
        response.put("internalTotalFormatted", StorageManager.formatSize(storage.getInternalTotalSpace()));

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Decode storage-type string from the API. Anything that isn't a known
     * external-volume label falls back to INTERNAL — that includes empty
     * strings and legacy "SDCARD" without the underscore.
     */
    private static StorageManager.StorageType parseStorageType(String s) {
        if (s == null) return StorageManager.StorageType.INTERNAL;
        switch (s.toUpperCase()) {
            case "SD_CARD": return StorageManager.StorageType.SD_CARD;
            case "USB":     return StorageManager.StorageType.USB;
            default:        return StorageManager.StorageType.INTERNAL;
        }
    }
    
    /**
     * Handle storage settings POST.
     */
    private static void handleStorageSettingsPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            StorageManager storage = StorageManager.getInstance();
            
            // Handle storage type changes first (before limit changes)
            boolean storageTypeChanged = false;
            
            if (settings.has("recordingsStorageType")) {
                StorageManager.StorageType type = parseStorageType(settings.getString("recordingsStorageType"));
                boolean success = storage.setRecordingsStorageType(type);
                if (success) {
                    storageTypeChanged = true;
                    CameraDaemon.log("Recordings storage type set to: " + type);
                } else {
                    CameraDaemon.log("Failed to set recordings storage type to " + type + " - not available");
                }
            }

            if (settings.has("surveillanceStorageType")) {
                StorageManager.StorageType type = parseStorageType(settings.getString("surveillanceStorageType"));
                boolean success = storage.setSurveillanceStorageType(type);
                if (success) {
                    storageTypeChanged = true;
                    CameraDaemon.log("Surveillance storage type set to: " + type);

                    try {
                        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                            CameraDaemon.getGpuPipeline();
                        if (pipeline != null && pipeline.getSentry() != null) {
                            pipeline.getSentry().setEventOutputDir(storage.getSurveillanceDir());
                            CameraDaemon.log("Updated sentry output dir: " +
                                storage.getSurveillanceDir().getAbsolutePath());
                        }
                    } catch (Exception e) {
                        CameraDaemon.log("Warning: could not update sentry output dir: " + e.getMessage());
                    }
                } else {
                    CameraDaemon.log("Failed to set surveillance storage type to " + type + " - not available");
                }
            }
            
            // Calculate how much will be deleted before applying changes
            long recordingsToDelete = 0;
            long surveillanceToDelete = 0;
            int recordingsFilesToDelete = 0;
            int surveillanceFilesToDelete = 0;
            
            if (settings.has("recordingsLimitMb")) {
                long newLimit = settings.getLong("recordingsLimitMb");
                long currentSize = storage.getRecordingsSize();
                long newLimitBytes = newLimit * 1024 * 1024;
                if (currentSize > newLimitBytes) {
                    recordingsToDelete = currentSize - newLimitBytes;
                    // Estimate files to delete (rough estimate based on average file size)
                    int count = storage.getRecordingsCount();
                    if (count > 0) {
                        long avgSize = currentSize / count;
                        recordingsFilesToDelete = (int) Math.ceil((double) recordingsToDelete / avgSize);
                    }
                }
                storage.setRecordingsLimitMb(newLimit);
                CameraDaemon.log("Recordings limit set to: " + newLimit + " MB");
            }
            
            if (settings.has("surveillanceLimitMb")) {
                long newLimit = settings.getLong("surveillanceLimitMb");
                long currentSize = storage.getSurveillanceSize();
                long newLimitBytes = newLimit * 1024 * 1024;
                if (currentSize > newLimitBytes) {
                    surveillanceToDelete = currentSize - newLimitBytes;
                    // Estimate files to delete
                    int count = storage.getSurveillanceCount();
                    if (count > 0) {
                        long avgSize = currentSize / count;
                        surveillanceFilesToDelete = (int) Math.ceil((double) surveillanceToDelete / avgSize);
                    }
                }
                storage.setSurveillanceLimitMb(newLimit);
                CameraDaemon.log("Surveillance limit set to: " + newLimit + " MB");
            }
            
            // Run cleanup async to not block HTTP response
            new Thread(() -> {
                storage.runCleanup();
                CameraDaemon.log("Storage cleanup completed after limit change");
            }, "StorageLimitCleanup").start();
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
            response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
            response.put("recordingsStorageType", storage.getRecordingsStorageType().name());
            response.put("surveillanceStorageType", storage.getSurveillanceStorageType().name());
            response.put("recordingsStorageTypeActive", storage.getActiveRecordingsStorageType().name());
            response.put("surveillanceStorageTypeActive", storage.getActiveSurveillanceStorageType().name());
            response.put("recordingsPath", storage.getRecordingsPath());
            response.put("surveillancePath", storage.getSurveillancePath());
            
            // Include cleanup info in response
            if (recordingsToDelete > 0 || surveillanceToDelete > 0) {
                JSONObject cleanup = new JSONObject();
                if (recordingsToDelete > 0) {
                    cleanup.put("recordingsToDelete", StorageManager.formatSize(recordingsToDelete));
                    cleanup.put("recordingsFilesEstimate", recordingsFilesToDelete);
                }
                if (surveillanceToDelete > 0) {
                    cleanup.put("surveillanceToDelete", StorageManager.formatSize(surveillanceToDelete));
                    cleanup.put("surveillanceFilesEstimate", surveillanceFilesToDelete);
                }
                response.put("cleanup", cleanup);
                response.put("message", Messages.get("messages.quality_storage_settings_updated_cleanup"));
            } else if (storageTypeChanged) {
                response.put("message", Messages.get("messages.quality_storage_location_changed"));
            } else {
                response.put("message", Messages.get("messages.quality_storage_settings_updated"));
            }

            // Re-arm RecordingsIndex FileObservers against the new active dir
            // set, AND walk the new active dir to populate the index. The
            // refresh-only call would only catch live writes going forward —
            // existing files on the new volume would be invisible to the
            // index until the 1-hour periodic reconcile. Reconcile here fills
            // them immediately. Run on a background thread so the HTTP
            // response doesn't block on the FUSE walk.
            if (storageTypeChanged) {
                try {
                    com.overdrive.app.daemon.RecordingsIndexFileWatcher.getInstance().refresh();
                } catch (Throwable t) {
                    CameraDaemon.log("RecordingsIndexFileWatcher refresh failed: " + t.getMessage());
                }
                new Thread(() -> {
                    try {
                        com.overdrive.app.server.RecordingsIndex.getInstance().reconcile();
                    } catch (Throwable t) {
                        CameraDaemon.log("Post-storage-switch reconcile failed: " + t.getMessage());
                    }
                }, "RecordingsIndexStorageSwitchReconcile").start();
            }
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error setting storage limits: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    /**
     * Send full unified config for cross-UID sync.
     * Returns the entire config including proximityGuard, recording, streaming sections.
     */
    private static void sendUnifiedConfig(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                response.put("config", config);
                response.put("lastModified", unifiedFile.lastModified());
            } else {
                // Return default config structure
                JSONObject config = new JSONObject();
                config.put("version", 1);
                
                JSONObject recording = new JSONObject();
                recording.put("recordingQuality", recordingQuality);
                recording.put("quality", recordingQuality);  // legacy mirror
                recording.put("codec", recordingCodec);
                config.put("recording", recording);
                
                JSONObject streaming = new JSONObject();
                streaming.put("quality", StreamingApiHandler.getStreamingQuality());
                config.put("streaming", streaming);
                
                JSONObject proximityGuard = new JSONObject();
                proximityGuard.put("triggerLevel", "RED");
                proximityGuard.put("preRecordSeconds", 5);
                proximityGuard.put("postRecordSeconds", 10);
                config.put("proximityGuard", proximityGuard);
                
                response.put("config", config);
                response.put("lastModified", System.currentTimeMillis());
            }
        } catch (Exception e) {
            CameraDaemon.log("sendUnifiedConfig: Error reading config: " + e.getMessage());
            // Return minimal default
            JSONObject config = new JSONObject();
            JSONObject proximityGuard = new JSONObject();
            proximityGuard.put("triggerLevel", "RED");
            proximityGuard.put("preRecordSeconds", 5);
            proximityGuard.put("postRecordSeconds", 10);
            config.put("proximityGuard", proximityGuard);
            response.put("config", config);
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Handle unified config POST - updates a specific section.
     * Body format: { "section": "proximityGuard", "data": { ... } }
     */
    private static void handleUnifiedConfigPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject request = new JSONObject(body);
            String section = request.optString("section", "");
            JSONObject data = request.optJSONObject("data");

            if (section.isEmpty() || data == null) {
                HttpResponse.sendJsonError(out, Messages.get("errors.quality_missing_section_or_data"));
                return;
            }

            // Route through UnifiedConfigManager so the in-memory cache stays
            // consistent and registered listeners fire. The previous direct
            // file-read/merge/write bypassed the cache: any other writer
            // (StorageManager, ExternalStorageCleaner) within the same mtime
            // second could merge into a stale cache and clobber this section.
            boolean ok = com.overdrive.app.config.UnifiedConfigManager.updateSection(section, data);
            if (!ok) {
                HttpResponse.sendJsonError(out, "updateSection returned false");
                return;
            }

            // Push live changes to the running subsystems for sections that
            // have stateful runtime consumers. UnifiedConfigManager.updateSection
            // persists the value but doesn't itself notify in-process consumers
            // — without this dispatch the user's slider sits in the file while
            // the running encoder/controller keeps using the old value until
            // the next ACC cycle.
            if ("proximityGuard".equals(section)) {
                try {
                    com.overdrive.app.recording.RecordingModeManager rmm =
                        CameraDaemon.getRecordingModeManager();
                    if (rmm != null) {
                        rmm.reloadConfig();
                    }
                } catch (Exception e) {
                    CameraDaemon.log("proximityGuard reloadConfig dispatch failed: " + e.getMessage());
                }
            }

            // Blind-spot display-target flip arrives here via the web UI's unified
            // POST (section "blindspot", {target}). Without a live retarget the new
            // target only takes effect on the next enable — and a cluster→head_unit
            // flip would leave the OEM projection (gauges blanked) up until then.
            // retargetBlindSpot() forceReloads, re-reads target, and force-closes any
            // open cluster projection when leaving the cluster (restoring gauges
            // immediately). This covers browser/tunnel clients that have no
            // AndroidBridge and never hit /api/bs/target. No-op when target absent.
            if ("blindspot".equals(section) && data.has("target")) {
                try {
                    com.overdrive.app.surveillance.GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
                    if (p != null && p.isBlindSpotEnabled()) {
                        p.retargetBlindSpot();
                    }
                } catch (Exception e) {
                    CameraDaemon.log("blindspot target retarget dispatch failed: " + e.getMessage());
                }
            }
            // A cluster-layout (size-profile) change must take effect live: force the
            // open projection closed so the next turn signal reopens with the new
            // profile (the daemon re-reads it on open; onClusterProjectionReady then
            // re-resolves the layerStack + geometry for the new layout).
            if ("blindspot".equals(section) && data.has("clusterSizeProfile")) {
                try {
                    com.overdrive.app.surveillance.GpuSurveillancePipeline p = CameraDaemon.getGpuPipeline();
                    if (p != null) p.relayoutCluster();
                } catch (Exception e) {
                    CameraDaemon.log("blindspot relayout dispatch failed: " + e.getMessage());
                }
            }

            CameraDaemon.log("Unified config section '" + section + "' updated");

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("section", section);
            response.put("message", Messages.get("messages.quality_config_section_updated"));

            HttpResponse.sendJson(out, response.toString());

        } catch (Exception e) {
            CameraDaemon.log("Error updating unified config: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    private static void sendQualitySettings(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        // Read from unified config for cross-UID sync
        String currentBitrate = recordingBitrate;
        String currentCodec = recordingCodec;
        String currentRecQuality = recordingQuality;
        String currentStreamQuality = StreamingApiHandler.getStreamingQuality();
        long lastModified = System.currentTimeMillis();
        
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                lastModified = unifiedFile.lastModified();
                
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject unified = new JSONObject(sb.toString());
                
                JSONObject recording = unified.optJSONObject("recording");
                if (recording != null) {
                    String fileCodec = recording.optString("codec", "");
                    if (fileCodec.equals("H264") || fileCodec.equals("H265")) {
                        currentCodec = fileCodec;
                        recordingCodec = fileCodec;
                    }

                    // Canonical tier first (recordingQuality → quality), then
                    // migrate legacy `bitrate` LOW/MEDIUM/HIGH as a final fallback.
                    // Old `quality` values (LOW/REDUCED/NORMAL) collapse to STANDARD.
                    String fileTier = recording.optString("recordingQuality",
                        recording.optString("quality", ""));
                    if (isKnownTier(fileTier)) {
                        currentRecQuality = fileTier;
                        recordingQuality = fileTier;
                        recordingBitrate = fileTier;
                    } else if (recording.has("bitrate")) {
                        String fileBitrate = recording.optString("bitrate", "").toUpperCase();
                        String tier;
                        switch (fileBitrate) {
                            case "LOW":    tier = "ECONOMY"; break;
                            case "MEDIUM": tier = "STANDARD"; break;
                            case "HIGH":   tier = "HIGH"; break;
                            default:       tier = ""; break;
                        }
                        if (!tier.isEmpty()) {
                            currentRecQuality = tier;
                            recordingQuality = tier;
                            recordingBitrate = tier;
                        }
                    } else if (!fileTier.isEmpty()) {
                        currentRecQuality = "STANDARD";
                        recordingQuality = "STANDARD";
                        recordingBitrate = "STANDARD";
                    }
                    currentBitrate = recordingBitrate;
                }
                
                JSONObject streaming = unified.optJSONObject("streaming");
                if (streaming != null) {
                    String fileStreamQuality = streaming.optString("quality", "");
                    if (!fileStreamQuality.isEmpty()) {
                        currentStreamQuality = fileStreamQuality;
                        StreamingApiHandler.setStreamingQuality(fileStreamQuality);
                    }
                }
            }
        } catch (Exception e) {
            CameraDaemon.log("sendQualitySettings: Could not read unified config: " + e.getMessage());
        }
        
        // Single user-facing recording quality tier. Bundles bitrate +
        // perceptual expectation. FPS and codec stay independent.
        // Migrate any legacy LOW/REDUCED/NORMAL value silently to STANDARD.
        String tierFromConfig;
        try {
            org.json.JSONObject recCfg = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("recording");
            tierFromConfig = recCfg != null ? recCfg.optString("recordingQuality", null) : null;
        } catch (Exception e) {
            tierFromConfig = null;
        }
        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality activeTier =
            com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.fromString(tierFromConfig);

        response.put("recordingQuality", activeTier.name());
        response.put("streamingQuality", currentStreamQuality);
        response.put("recordingCodec", currentCodec);
        response.put("lastModified", lastModified);
        
        // Camera FPS setting
        int currentFps = 15;
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraConfig != null) {
                currentFps = cameraConfig.optInt("targetFps", 15);
            }
        } catch (Exception e) { /* use default */ }
        response.put("cameraFps", currentFps);

        // Surface measured FPS so the UI can show actualFps when HAL clamps
        // below requested (e.g., user picks 30, HAL emits ~26 panoramic on
        // this device). 0 means "not measured yet" — the renderLoop only
        // updates this every 2 minutes.
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            float measured = (pipeline != null && pipeline.getCamera() != null)
                ? pipeline.getCamera().getMeasuredFps() : 0f;
            if (measured > 0f) {
                response.put("cameraFpsActual", Math.round(measured * 10) / 10.0);
                if (Math.abs(measured - currentFps) > 1.5f) {
                    response.put("cameraFpsClampNote",
                        "HAL emitting at ~" + Math.round(measured)
                            + " fps (requested " + currentFps + ")");
                }
            }
        } catch (Exception ignored) {}

        // Recording quality tiers — single user-facing knob.
        // Includes per-tier bitrate (resolved against current codec) and
        // size estimate so the UI can show "X MB/min, ~Y GB/hour".
        // Note: bitrate is bandwidth-per-second, FPS does not change file
        // size at fixed bitrate (higher fps just spreads bits over more
        // frames, reducing per-frame detail).
        com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec codecForEstimate =
            "H265".equalsIgnoreCase(currentCodec)
                ? com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265
                : com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;

        JSONObject qualityInfo = new JSONObject();
        for (com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality q :
                com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.values()) {
            JSONObject entry = new JSONObject();
            int br = q.getBitrateForCodec(codecForEstimate);
            entry.put("displayName", q.displayName);
            entry.put("bitrateBps", br);
            entry.put("bitrateMbps", Math.round(br / 100_000.0) / 10.0);
            entry.put("mbPerMinute", Math.round(q.estimateMbPerMinute(codecForEstimate) * 10) / 10.0);
            entry.put("gbPerHour", Math.round(q.estimateMbPerHour(codecForEstimate) / 102.4) / 10.0);
            // Perceptual equivalent at the user's current fps. Drops one tier
            // at 30 fps vs 15 fps because the encoder spreads bits over more
            // frames. UI should label this as approximate — native resolution
            // is fixed at 2560×1920 regardless of tier.
            entry.put("qualityEquivalent", q.getQualityEquivalent(codecForEstimate, currentFps));
            qualityInfo.put(q.name(), entry);
        }
        response.put("recordingQualityOptions", qualityInfo);
        response.put("nativeResolution", "2560×1920 mosaic · 4 × 1280×960 cameras");

        // Currently-active size estimate so the UI can render
        // "uses ~X GB/hour at your current settings" without iterating the
        // options dict. Recomputed from active tier + active codec each call.
        JSONObject activeEstimate = new JSONObject();
        double mbPerMin = activeTier.estimateMbPerMinute(codecForEstimate);
        activeEstimate.put("bitrateMbps", Math.round(activeTier.getBitrateForCodec(codecForEstimate) / 100_000.0) / 10.0);
        activeEstimate.put("mbPerMinute", Math.round(mbPerMin * 10) / 10.0);
        activeEstimate.put("mbPer2Min", Math.round(mbPerMin * 2 * 10) / 10.0);
        activeEstimate.put("gbPerHour", Math.round(mbPerMin * 60 / 102.4) / 10.0);
        // Minutes of recording per 1 GB of storage — easier to reason about
        // for a parked surveillance session than fractional GB/hr numbers.
        if (mbPerMin > 0) {
            activeEstimate.put("minutesPerGb", Math.round(1024.0 / mbPerMin));
        }
        activeEstimate.put("qualityEquivalent", activeTier.getQualityEquivalent(codecForEstimate, currentFps));
        response.put("activeRecordingEstimate", activeEstimate);

        // OEM Dashcam concurrency surface — gives the UI everything it needs
        // to render the simultaneous-recording capability badge + bitrate
        // budget slider:
        //
        //   oemDashcamCameraId       resolved AVMCamera id (-1 = unconfigured)
        //   concurrentAvmSupported   -1 unprobed / 0 single-client / 1 dual
        //   bitrateBudget            current combined cap (bps)
        //   panoBitrateBps           pano's resolved bitrate at active tier+codec
        //   oemHeadroomBps           remaining for OEM after pano (≥2M floor)
        try {
            JSONObject oem = new JSONObject();
            int oemId = com.overdrive.app.config.UnifiedConfigManager.resolveOemDashcamId();
            JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            int concurrent = cam == null ? -1 : cam.optInt("concurrentAvmSupported", -1);
            int budget = com.overdrive.app.config.UnifiedConfigManager
                .getOemDashcam().optInt("bitrateBudget", 10_000_000);
            int panoBps = activeTier.getBitrateForCodec(codecForEstimate);
            int headroom = Math.max(2_000_000, budget - panoBps);
            oem.put("oemDashcamCameraId", oemId);
            oem.put("concurrentAvmSupported", concurrent);
            oem.put("bitrateBudget", budget);
            oem.put("panoBitrateBps", panoBps);
            oem.put("oemHeadroomBps", headroom);
            // Echo the OEM-specific quality slot so a future OEM picker can
            // round-trip its own state (Round-3 audit: GET was missing these).
            // Falls back to legacy recording.* keys if the OEM slot is empty
            // — same precedence as OemDashcamPipeline.applyRecordingConfigFromUcm.
            JSONObject oemCfg = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
            JSONObject recCfg = com.overdrive.app.config.UnifiedConfigManager.getRecording();
            oem.put("recordingQuality", oemCfg.has("recordingQuality")
                ? oemCfg.optString("recordingQuality", "STANDARD")
                : recCfg.optString("recordingQuality", "STANDARD"));
            oem.put("codec", oemCfg.has("codec")
                ? oemCfg.optString("codec", "H264")
                : recCfg.optString("codec", "H264"));
            oem.put("fps", oemCfg.has("fps")
                ? oemCfg.optInt("fps", 30)
                : recCfg.optInt("fps", 30));
            // Surface whether the OEM slot DIVERGES from pano. The R2 mirror
            // writes populate oemDashcam.* on every pano POST, so a bare
            // `oemCfg.has("recordingQuality")` returns true after the user's
            // first quality save even when OEM matches pano — the UI would
            // show a "Custom" badge for an install that's actually
            // pano-linked. Compare values to render the badge correctly.
            String oemQ = oemCfg.optString("recordingQuality", "");
            String panoQ = recCfg.optString("recordingQuality", "");
            String oemC = oemCfg.optString("codec", "");
            String panoC = recCfg.optString("codec", "");
            int oemF = oemCfg.optInt("fps", -1);
            int panoF = recCfg.optInt("fps", -1);
            boolean diverged = (oemCfg.has("recordingQuality") && !oemQ.equalsIgnoreCase(panoQ))
                || (oemCfg.has("codec") && !oemC.equalsIgnoreCase(panoC))
                || (oemCfg.has("fps") && oemF != panoF);
            oem.put("hasOwnQuality", diverged);
            response.put("oemDashcam", oem);
        } catch (Exception ignored) {}
        
        // Add codec info for UI
        JSONObject codecInfo = new JSONObject();
        codecInfo.put("H264", "H.264/AVC (Compatible)");
        codecInfo.put("H265", "H.265/HEVC (50% smaller)");
        response.put("codecOptions", codecInfo);
        
        // Add FPS options for UI. Range 10..30 — clamped server-side by
        // GpuSurveillancePipeline.applyFpsChange. The HAL on this device
        // tops out around 26 fps panoramic, so 30 will clamp gracefully.
        JSONObject fpsInfo = new JSONObject();
        fpsInfo.put("10", "10 FPS (Low power)");
        fpsInfo.put("15", "15 FPS (Balanced)");
        fpsInfo.put("20", "20 FPS (Smooth)");
        fpsInfo.put("25", "25 FPS (High motion)");
        fpsInfo.put("30", "30 FPS (Max — HAL ceiling ~26)");
        response.put("fpsOptions", fpsInfo);
        
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleQualitySettingsPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);

            // Tracks per-field rejections so the UI can surface "we kept the
            // old value for X" instead of silently treating an invalid input
            // as a successful save. Empty when everything was accepted.
            org.json.JSONArray rejected = new org.json.JSONArray();

            // ── Resolve & validate the three encoder-reconfig knobs first ────
            // (quality, codec, fps). They are routed through a single batched
            // pipeline call so the encoder reinits at most once and the
            // recording-resume runs at most once. Calling the per-knob
            // setters in sequence used to leave recording dead in the
            // multi-setting case: the second/third call observed
            // isRecording()==false (deferred start window after the first)
            // and skipped its restart.
            String pendingQuality = null;
            if (settings.has("recordingQuality")) {
                String tier = settings.getString("recordingQuality").toUpperCase();
                if (tier.equals("ECONOMY") || tier.equals("STANDARD")
                        || tier.equals("HIGH") || tier.equals("PREMIUM")
                        || tier.equals("MAX")) {
                    pendingQuality = tier;
                    recordingQuality = tier;
                    CameraDaemon.log("Recording quality set to: " + tier);
                } else {
                    CameraDaemon.log("Rejecting recordingQuality=" + tier
                        + " — must be one of ECONOMY/STANDARD/HIGH/PREMIUM/MAX");
                    rejected.put(new JSONObject()
                        .put("field", "recordingQuality").put("value", tier)
                        .put("reason", "invalid tier"));
                }
            }

            // Legacy `recordingBitrate` key (LOW/MEDIUM/HIGH) — translate
            // to the new tier system. UI should send `recordingQuality`
            // directly going forward; this branch only catches old clients.
            if (pendingQuality == null && settings.has("recordingBitrate")) {
                String legacy = settings.getString("recordingBitrate").toUpperCase();
                String tier;
                switch (legacy) {
                    case "LOW":    tier = "ECONOMY"; break;
                    case "MEDIUM": tier = "STANDARD"; break;
                    case "HIGH":   tier = "HIGH"; break;
                    default:       tier = "STANDARD"; break;
                }
                CameraDaemon.log("Legacy recordingBitrate=" + legacy + " → recordingQuality=" + tier);
                pendingQuality = tier;
                recordingQuality = tier;
            }

            String pendingCodec = null;
            if (settings.has("recordingCodec")) {
                String codec = settings.getString("recordingCodec").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    pendingCodec = codec;
                    recordingCodec = codec;
                    CameraDaemon.log("Recording codec set to: " + codec);
                } else {
                    CameraDaemon.log("Rejecting recordingCodec=" + codec + " — must be H264 or H265");
                    rejected.put(new JSONObject()
                        .put("field", "recordingCodec").put("value", codec)
                        .put("reason", "must be H264 or H265"));
                }
            }

            Integer pendingFps = null;
            if (settings.has("cameraFps")) {
                int fps = settings.getInt("cameraFps");
                if (fps < 10 || fps > 30) {
                    CameraDaemon.log("Rejecting cameraFps=" + fps + " — out of range [10..30]");
                    rejected.put(new JSONObject()
                        .put("field", "cameraFps").put("value", fps)
                        .put("reason", "out of range [10..30]"));
                } else {
                    pendingFps = fps;
                }
            }

            // Mirror recording quality / codec / fps into the OEM Dashcam
            // section. Without this, the new oem.recordingQuality slot is
            // never written by the existing recording.html UI and OEM falls
            // back to legacy `recording.*` keys via applyRecordingConfigFromUcm
            // — which defeats the budget-cap fix that depends on the two
            // pipelines having independent quality values.
            //
            // OEM-specific overrides (when web UI lands a dedicated picker)
            // can use settings.oemDashcam.* keys; for now we just mirror the
            // pano picks.
            JSONObject oemDelta = new JSONObject();
            if (pendingQuality != null) oemDelta.put("recordingQuality", pendingQuality);
            if (pendingCodec != null)   oemDelta.put("codec", pendingCodec);
            if (pendingFps != null)     oemDelta.put("fps", pendingFps);
            // Also accept explicit oemDashcam.* overrides from a future UI.
            JSONObject oemBlock = settings.optJSONObject("oemDashcam");
            if (oemBlock != null) {
                if (oemBlock.has("recordingQuality")) {
                    String t = oemBlock.optString("recordingQuality", "").toUpperCase();
                    if (t.equals("ECONOMY") || t.equals("STANDARD")
                            || t.equals("HIGH") || t.equals("PREMIUM") || t.equals("MAX")) {
                        oemDelta.put("recordingQuality", t);
                    }
                }
                if (oemBlock.has("codec")) {
                    String c = oemBlock.optString("codec", "").toUpperCase();
                    if (c.equals("H264") || c.equals("H265")) oemDelta.put("codec", c);
                }
                if (oemBlock.has("fps")) {
                    int f = oemBlock.optInt("fps", -1);
                    if (f >= 10 && f <= 30) oemDelta.put("fps", f);
                }
            }
            if (oemDelta.length() > 0) {
                com.overdrive.app.config.UnifiedConfigManager.setOemDashcam(oemDelta);
                // R8-A #16: forceReload so the upcoming OEM restart on
                // a different thread sees the new mtime-cached values.
                // Without this, the restart's applyRecordingConfigFromUcm
                // can race the mtime invalidation and read stale codec /
                // bitrate / fps. Cheap explicit signal.
                com.overdrive.app.config.UnifiedConfigManager.forceReload();
                CameraDaemon.log("OEM dashcam recording config mirrored: " + oemDelta.toString());
                // If the OEM pipeline is currently running, the mirrored
                // values only take effect at the next start — without an
                // explicit restart, UCM and pipeline state diverge until the
                // user toggles OEM off+on. Stop+start asynchronously to
                // pick up the new bitrate / codec / fps without making the
                // HTTP worker block on encoder teardown.
                com.overdrive.app.camera.OemDashcamPipeline live =
                    CameraDaemon.getOemDashcamPipeline();
                if (live != null && live.isRunning()) {
                    // Route through LIFECYCLE_EXEC so the restart serializes against picker
                    // applies. Use applyTriggerLifecycleFromUcm instead of applyLifecycle(true)
                    // so the desired-state computation reads the user's current
                    // oemRecordingMode + oemSurveillanceMode rather than hardcoding
                    // recordingDesired=true. Without this, a user with rec=off + surv=smart
                    // would silently start recording a dvr_*.mp4 every time they change
                    // quality/codec/fps. Mirrors the OemDashcamApiHandler.handlePost
                    // restart:true path (OemDashcamApiHandler.java around line 618-625).
                    com.overdrive.app.server.OemDashcamApiHandler.LIFECYCLE_EXEC.execute(() -> {
                        try {
                            com.overdrive.app.server.OemDashcamApiHandler.applyLifecycle(false);
                            com.overdrive.app.server.OemDashcamApiHandler.applyTriggerLifecycleFromUcm();
                        } catch (Exception e) {
                            // Best-effort — quality apply itself already succeeded; restart
                            // failure leaves the user with their old encoder settings until
                            // the next mode change or ACC cycle.
                            android.util.Log.w("QualitySettings",
                                "OEM dashcam quality-mirror restart failed: " + e.getMessage());
                        }
                    });
                }
            }

            // OEM Dashcam bitrate budget — soft cap on combined pano+OEM
            // encoder bitrate when both pipelines run. The cap is advisory
            // here (we just persist); the OEM pipeline reads the budget at
            // start time and clamps its own requested bitrate against
            // (budget - panoTier) with a 2 Mbps floor. Range 4..15 Mbps —
            // below 4 the system has no headroom for both encoders, above
            // 15 the Adreno 610 H.264 path fails under sustained load.
            if (settings.has("oemDashcamBitrateBudget")) {
                int budget = settings.optInt("oemDashcamBitrateBudget", 10_000_000);
                if (budget < 4_000_000 || budget > 15_000_000) {
                    CameraDaemon.log("Rejecting oemDashcamBitrateBudget=" + budget
                        + " — out of [4..15] Mbps");
                    rejected.put(new JSONObject()
                        .put("field", "oemDashcamBitrateBudget")
                        .put("value", budget)
                        .put("reason", "out of [4..15] Mbps"));
                } else {
                    JSONObject delta = new JSONObject();
                    delta.put("bitrateBudget", budget);
                    com.overdrive.app.config.UnifiedConfigManager.setOemDashcam(delta);
                    CameraDaemon.log("OEM Dashcam bitrate budget set to: " + budget);
                }
            }

            // Streaming quality is a separate encoder; it doesn't share the
            // recording reinit cycle, so route it through its own setter.
            if (settings.has("streamingQuality")) {
                String streamQuality = settings.getString("streamingQuality").toUpperCase();
                if (streamQuality.equals("ULTRA_LOW") || streamQuality.equals("LOW")
                        || streamQuality.equals("MEDIUM") || streamQuality.equals("HIGH")
                        || streamQuality.equals("ULTRA_HIGH") || streamQuality.equals("SMOOTH")
                        || streamQuality.equals("MAX")
                        || streamQuality.equals("LQ") || streamQuality.equals("HQ")) {
                    StreamingApiHandler.setStreamingQuality(streamQuality);
                    CameraDaemon.log("Streaming quality set to: " + streamQuality);
                    CameraDaemon.setStreamingQuality(streamQuality);
                }
            }

            // ── Apply the recording-encoder knobs in a single batched call ───
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null) {
                com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality qualityEnum = null;
                if (pendingQuality != null) {
                    qualityEnum = com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality
                        .fromString(pendingQuality);
                }
                com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec codecEnum = null;
                if (pendingCodec != null) {
                    codecEnum = "H265".equals(pendingCodec)
                        ? com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265
                        : com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                }
                if (qualityEnum != null || codecEnum != null || pendingFps != null) {
                    pipeline.applyBatchedChange(qualityEnum, codecEnum, pendingFps);
                }
            } else if (pendingFps != null) {
                // Pipeline not yet created — persist FPS so init() picks it up.
                try {
                    org.json.JSONObject camCfg = com.overdrive.app.config.UnifiedConfigManager
                        .loadConfig().optJSONObject("camera");
                    if (camCfg == null) camCfg = new org.json.JSONObject();
                    camCfg.put("targetFps", pendingFps);
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    CameraDaemon.log("Camera FPS saved (pipeline not ready): " + pendingFps);
                } catch (Exception e) {
                    CameraDaemon.log("Failed to save camera FPS: " + e.getMessage());
                }
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("recordingBitrate", recordingBitrate);
            response.put("recordingCodec", recordingCodec);
            response.put("note", recordingCodec.equals("H265") ?
                Messages.get("messages.quality_h265_note") : null);
            // UI distinguishes silent rejections (kept old value) from a
            // genuine save by checking response.rejected.length > 0.
            if (rejected.length() > 0) {
                response.put("rejected", rejected);
            }

            persistSettings();
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error setting quality: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    /**
     * Loads persisted settings from unified config file.
     * Called during HttpServer initialization.
     */
    public static void loadPersistedSettings() {
        // Try unified config first
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject unified = new JSONObject(sb.toString());
                
                JSONObject recording = unified.optJSONObject("recording");
                if (recording != null) {
                    // Canonical tier: recordingQuality (ECONOMY..MAX). Fall back to
                    // legacy `quality` and finally legacy `bitrate` (LOW/MEDIUM/HIGH).
                    String tier = recording.optString("recordingQuality",
                            recording.optString("quality", ""));
                    if (isKnownTier(tier)) {
                        recordingQuality = tier;
                        recordingBitrate = tier;  // mirror — keep in sync
                        CameraDaemon.log("Restored recording tier from unified: " + tier);
                    } else if (recording.has("bitrate")) {
                        String legacyBitrate = recording.getString("bitrate").toUpperCase();
                        switch (legacyBitrate) {
                            case "LOW":    tier = "ECONOMY"; break;
                            case "MEDIUM": tier = "STANDARD"; break;
                            case "HIGH":   tier = "HIGH"; break;
                            default:       tier = ""; break;
                        }
                        if (!tier.isEmpty()) {
                            recordingQuality = tier;
                            recordingBitrate = tier;
                            CameraDaemon.log("Migrated legacy bitrate=" + legacyBitrate
                                    + " → recordingQuality=" + tier);
                        }
                    }
                    if (recording.has("codec")) {
                        String codec = recording.getString("codec");
                        if (codec.equals("H264") || codec.equals("H265")) {
                            recordingCodec = codec;
                            CameraDaemon.log("Restored recording codec from unified: " + codec);
                        }
                    }
                }
                
                JSONObject streaming = unified.optJSONObject("streaming");
                if (streaming != null && streaming.has("quality")) {
                    String quality = streaming.getString("quality");
                    StreamingApiHandler.setStreamingQuality(quality);
                    CameraDaemon.log("Restored streaming quality from unified: " + quality);
                }
                
                CameraDaemon.log("Settings loaded from unified config: " + UNIFIED_CONFIG_FILE);
                return;
            }
        } catch (Exception e) {
            CameraDaemon.log("Could not load from unified config: " + e.getMessage());
        }
        
        // Fallback to legacy settings file
        loadLegacySettings();
    }

    private static void loadLegacySettings() {
        try {
            File file = new File(LEGACY_SETTINGS_FILE);
            CameraDaemon.log("Loading settings from legacy: " + LEGACY_SETTINGS_FILE + " (exists=" + file.exists() + ")");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject settings = new JSONObject(sb.toString());
                
                // Canonical recordingQuality first; fall back to legacy bitrate.
                if (settings.has("recordingQuality")) {
                    String tier = settings.getString("recordingQuality").toUpperCase();
                    if (isKnownTier(tier)) {
                        recordingQuality = tier;
                        recordingBitrate = tier;
                    }
                } else if (settings.has("recordingBitrate")) {
                    String bitrate = settings.getString("recordingBitrate").toUpperCase();
                    String tier;
                    switch (bitrate) {
                        case "LOW":    tier = "ECONOMY"; break;
                        case "MEDIUM": tier = "STANDARD"; break;
                        case "HIGH":   tier = "HIGH"; break;
                        default:       tier = ""; break;
                    }
                    if (!tier.isEmpty()) {
                        recordingQuality = tier;
                        recordingBitrate = tier;
                    }
                }
                if (settings.has("recordingCodec")) {
                    String codec = settings.getString("recordingCodec");
                    if (codec.equals("H264") || codec.equals("H265")) {
                        recordingCodec = codec;
                    }
                }
                if (settings.has("streamingQuality")) {
                    String quality = settings.getString("streamingQuality");
                    StreamingApiHandler.setStreamingQuality(quality);
                }
                
                CameraDaemon.log("Settings loaded from legacy " + LEGACY_SETTINGS_FILE);
                // Migrate to unified config
                persistSettings();
            }
        } catch (Exception e) {
            CameraDaemon.log("Could not load legacy settings: " + e.getMessage());
        }
    }
    
    /**
     * Persists current settings to unified config file via UnifiedConfigManager.
     *
     * Routing through UCM (instead of doing direct file I/O) acquires the
     * UCM lock, gets the atomic-rename write semantics, and prevents this
     * write from racing with concurrent updateSection calls (e.g. a camera
     * probe persisting its findings at the same time the user clicks Save).
     */
    public static void persistSettings() {
        try {
            org.json.JSONObject recording = new org.json.JSONObject();
            // Canonical tier; `quality` is the legacy mirror. We deliberately
            // do NOT write `bitrate` (LOW/MEDIUM/HIGH) — that's the field
            // that historically drifted out of sync with the active tier.
            recording.put("recordingQuality", recordingQuality);
            recording.put("quality", recordingQuality);
            recording.put("codec", recordingCodec);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("recording", recording);

            org.json.JSONObject streaming = new org.json.JSONObject();
            streaming.put("quality", StreamingApiHandler.getStreamingQuality());
            com.overdrive.app.config.UnifiedConfigManager.updateSection("streaming", streaming);

            CameraDaemon.log("Settings persisted via UnifiedConfigManager");
        } catch (Exception e) {
            CameraDaemon.log("Could not persist settings: " + e.getMessage());
        }
    }

    // Static getters for cross-component access
    public static String getRecordingQuality() { return recordingQuality; }
    public static String getRecordingBitrate() { return recordingBitrate; }
    public static String getRecordingCodec() { return recordingCodec; }
    
    // Static setters for app UI and IPC. recordingQuality accepts the new
    // tier names (ECONOMY..MAX); legacy names are migrated to STANDARD per
    // the migration policy so old IPC clients don't get silently swallowed.
    public static void setRecordingQuality(String quality) {
        if (quality == null) return;
        String tier;
        if (isKnownTier(quality)) {
            tier = quality.toUpperCase();
        } else {
            // Legacy LOW/REDUCED/NORMAL → STANDARD.
            tier = "STANDARD";
        }
        recordingQuality = tier;
        CameraDaemon.setRecordingQuality(tier);
        persistSettings();
    }

    /** @deprecated use setRecordingQuality with ECONOMY..MAX. */
    @Deprecated
    public static void setRecordingBitrate(String bitrate) {
        if (bitrate == null) return;
        String tier;
        switch (bitrate.toUpperCase()) {
            case "LOW":    tier = "ECONOMY"; break;
            case "MEDIUM": tier = "STANDARD"; break;
            case "HIGH":   tier = "HIGH"; break;
            default:       tier = "STANDARD"; break;
        }
        setRecordingQuality(tier);
    }

    /** Validates a tier name without depending on the enum class (this
     *  handler runs in the daemon process before the surveillance pipeline
     *  is built — keep the check string-based and cheap). */
    private static boolean isKnownTier(String s) {
        if (s == null) return false;
        switch (s.toUpperCase()) {
            case "ECONOMY":
            case "STANDARD":
            case "HIGH":
            case "PREMIUM":
            case "MAX":
                return true;
            default:
                return false;
        }
    }
    
    public static void setRecordingCodec(String codec) {
        if (codec.equals("H264") || codec.equals("H265")) {
            recordingCodec = codec;
            CameraDaemon.setRecordingCodec(codec);
            persistSettings();
        }
    }
    
    // Static setters for IPC server (updates variable only, no CameraDaemon call).
    // Accepts both canonical tier names (ECONOMY..MAX) and legacy LOW/MEDIUM/HIGH.
    public static void setRecordingBitrateStatic(String value) {
        if (value == null) return;
        String v = value.toUpperCase();
        String tier;
        switch (v) {
            case "LOW":    tier = "ECONOMY"; break;
            case "MEDIUM": tier = "STANDARD"; break;
            case "HIGH":
            case "ECONOMY":
            case "STANDARD":
            case "PREMIUM":
            case "MAX":    tier = v; break;
            default:       return;
        }
        recordingBitrate = tier;
        recordingQuality = tier;
    }
    
    public static void setRecordingCodecStatic(String codec) {
        if (codec.equals("H264") || codec.equals("H265")) {
            recordingCodec = codec;
        }
    }

    /**
     * Send telemetry overlay settings.
     *
     * <p>Returns the full per-flow shape:
     * <pre>
     * {
     *   enabled:           bool,    // legacy, pano fallback
     *   panoEnabled:       bool,    // resolved pano gate (legacy if absent)
     *   oemDashcamEnabled: bool     // OEM Dashcam gate (default false)
     * }
     * </pre>
     * Older clients that only read {@code enabled} continue to work because
     * the legacy field is the pano fallback. Newer clients can render two
     * separate switches (one per pipeline).
     */
    private static void sendTelemetryOverlaySettings(OutputStream out) throws Exception {
        boolean panoEffective = com.overdrive.app.config.UnifiedConfigManager
            .isTelemetryOverlayEnabledFor("pano");
        boolean oemEffective = com.overdrive.app.config.UnifiedConfigManager
            .isTelemetryOverlayEnabledFor("oemDashcam");
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("enabled", panoEffective);              // legacy alias = pano
        response.put("panoEnabled", panoEffective);
        response.put("oemDashcamEnabled", oemEffective);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/settings/audio-recording — read audioEnabled flag from the
     * recording section. Default off; the toggle is opt-in for legal/privacy
     * reasons and only takes effect for ACC-on recording modes (CONTINUOUS /
     * DRIVE_MODE / PROXIMITY_GUARD), never for surveillance recordings.
     */
    private static void sendAudioRecordingSettings(OutputStream out) throws Exception {
        JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("enabled", recording.optBoolean("audioEnabled", false));
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/settings/recording-layout — read the recording composition
     * layout ("standard" 360 mosaic, default, or "dashcam": forward view on
     * top with 360 left/rear/right below). Recordings only.
     */
    private static void sendRecordingLayoutSettings(OutputStream out) throws Exception {
        JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
        com.overdrive.app.camera.ResolvedCameraConfig camera =
            com.overdrive.app.camera.CameraConfigResolver.resolve();
        int windshieldCameraId = camera.getDirectCameraIdForRole(
            com.overdrive.app.camera.CameraRole.WINDSHIELD);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("layout", recording.optString("recordingLayout", "standard"));
        response.put("dashcamUseWindshield", recording.optBoolean("dashcamUseWindshield", false));
        response.put("windshieldAvailable", windshieldCameraId >= 0);
        response.put("windshieldCameraId", windshieldCameraId);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/settings/recording-layout — set the recording layout.
     * Hot-applies to the live recorder via the pipeline (gated GPU uniform,
     * no recompile) and persists for daemon restarts / later recorders.
     */
    private static void handleRecordingLayoutPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            String layout = "dashcam".equals(settings.optString("layout", "standard"))
                ? "dashcam" : "standard";
            com.overdrive.app.camera.ResolvedCameraConfig camera =
                com.overdrive.app.camera.CameraConfigResolver.resolve();
            int windshieldCameraId = camera.getDirectCameraIdForRole(
                com.overdrive.app.camera.CameraRole.WINDSHIELD);
            boolean windshieldAvailable = windshieldCameraId >= 0;
            boolean useWindshield = settings.optBoolean("dashcamUseWindshield", false)
                && windshieldAvailable;

            JSONObject delta = new JSONObject();
            delta.put("recordingLayout", layout);
            delta.put("dashcamUseWindshield", useWindshield);
            com.overdrive.app.config.UnifiedConfigManager.setRecording(delta);

            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            if (pipeline != null) {
                pipeline.setRecordingLayout("dashcam".equals(layout) ? 1 : 0);
                pipeline.setDashcamUseWindshield(useWindshield);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("layout", layout);
            response.put("dashcamUseWindshield", useWindshield);
            response.put("windshieldAvailable", windshieldAvailable);
            response.put("windshieldCameraId", windshieldCameraId);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting recording layout: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    /**
     * GET /api/settings/surveillance-layout — read the SENTRY composition
     * layout ("standard" 360 mosaic, default, or "dashcam"). Independent of the
     * dashcam recording-layout. When the sentry keys are unset we fall back to
     * the dashcam values so existing installs keep their current sentry
     * appearance until the user explicitly diverges.
     */
    private static void sendSurveillanceLayoutSettings(OutputStream out) throws Exception {
        JSONObject surveillance = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
        JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
        com.overdrive.app.camera.ResolvedCameraConfig camera =
            com.overdrive.app.camera.CameraConfigResolver.resolve();
        int windshieldCameraId = camera.getDirectCameraIdForRole(
            com.overdrive.app.camera.CameraRole.WINDSHIELD);
        String layout = surveillance.optString("recordingLayout",
            recording.optString("recordingLayout", "standard"));
        boolean useWindshield = surveillance.has("useWindshield")
            ? surveillance.optBoolean("useWindshield", false)
            : recording.optBoolean("dashcamUseWindshield", false);
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("layout", layout);
        response.put("dashcamUseWindshield", useWindshield);
        response.put("windshieldAvailable", windshieldCameraId >= 0);
        response.put("windshieldCameraId", windshieldCameraId);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/settings/surveillance-layout — set the sentry layout. Hot-
     * applies to the live recorder (only while in surveillance mode) via the
     * pipeline and persists under surveillance.* for daemon restarts / later
     * recorders. Mirrors {@link #handleRecordingLayoutPost} for the dashcam
     * flow; the request/response use the same {@code dashcamUseWindshield}
     * key so the web layer can reuse the recording-layout client code.
     */
    private static void handleSurveillanceLayoutPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            String layout = "dashcam".equals(settings.optString("layout", "standard"))
                ? "dashcam" : "standard";
            com.overdrive.app.camera.ResolvedCameraConfig camera =
                com.overdrive.app.camera.CameraConfigResolver.resolve();
            int windshieldCameraId = camera.getDirectCameraIdForRole(
                com.overdrive.app.camera.CameraRole.WINDSHIELD);
            boolean windshieldAvailable = windshieldCameraId >= 0;
            boolean useWindshield = settings.optBoolean("dashcamUseWindshield", false)
                && windshieldAvailable;

            JSONObject delta = new JSONObject();
            delta.put("recordingLayout", layout);
            delta.put("useWindshield", useWindshield);
            com.overdrive.app.config.UnifiedConfigManager.setSurveillance(delta);

            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            if (pipeline != null) {
                pipeline.setSurveillanceRecordingLayout("dashcam".equals(layout) ? 1 : 0);
                pipeline.setSurveillanceUseWindshield(useWindshield);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("layout", layout);
            response.put("dashcamUseWindshield", useWindshield);
            response.put("windshieldAvailable", windshieldAvailable);
            response.put("windshieldCameraId", windshieldCameraId);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting surveillance layout: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    /**
     * POST /api/settings/audio-recording — set audioEnabled. Hot-reload
     * applies at the next segment rotation (~2 min) or next event trigger;
     * we don't try to add audio tracks to a live MediaMuxer because
     * MediaMuxer rejects addTrack post-start. On an OFF→ON transition
     * we force-rotate the live segment so the user sees audio in the
     * very next clip they review instead of waiting up to ~2 min.
     */
    private static void handleAudioRecordingPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            boolean enabled = settings.optBoolean("enabled", false);

            // Read current value BEFORE writing so we can detect an OFF→ON
            // transition. getRecording() returns the live shared JSONObject
            // reference — read-only access is fine, but we deliberately
            // avoid mutating it (see delta-write below).
            JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
            boolean wasEnabled = recording.optBoolean("audioEnabled", false);
            boolean transitioning = !wasEnabled && enabled;

            // Write only the delta. setRecording → updateSection performs a
            // synchronized merge (existing.put for each key in data), so a
            // single-key delta updates audioEnabled without touching other
            // recording keys. Mutating the shared reference returned by
            // getRecording() would race with concurrent POSTs (e.g. quality
            // change) — JSONObject is not thread-safe under concurrent put().
            // Mirrors the convention in handleTelemetryOverlayPost.
            JSONObject delta = new JSONObject();
            delta.put("audioEnabled", enabled);
            com.overdrive.app.config.UnifiedConfigManager.setRecording(delta);

            // OFF→ON: force-rotate the live segment so the next clip the
            // user reviews actually has audio. MediaMuxer can't add tracks
            // mid-stream so the running segment will remain video-only —
            // closing it now and opening a fresh one is the only way to
            // avoid up-to-2-min staleness.
            // OFF→ON force-rotate is DEFERRED, not immediate. If we
            // rotated now, the new segment opens before the app process
            // has reconnected to AacIngestServer and re-applied its
            // CONFIG, so the new segment gets audioTrackIndex=-1 and
            // is video-only — exactly the bug the rotation was meant to
            // avoid. Wait up to 5 s in a background thread for
            // hasAudioConfig() to flip true (i.e. the app reconnected,
            // CONFIG was applied), THEN rotate. If audio never arrives
            // (app crash, mic claimed) we skip the rotation; the next
            // natural rotation at 2 min picks up audio if it's back.
            boolean rotateScheduled = false;
            if (transitioning) {
                final com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    CameraDaemon.getGpuPipeline();
                if (pipeline != null) {
                    final com.overdrive.app.surveillance.HardwareEventRecorderGpu enc =
                        pipeline.getEncoder();
                    if (enc != null && enc.isWritingToFile()) {
                        rotateScheduled = true;
                        new Thread(() -> {
                            // Poll up to 5 s at 100 ms cadence for the app
                            // to ship its CONFIG. AppAudioCaptureController
                            // start() takes ~30-100 ms once a poll fires;
                            // StatusOverlayService polls every 1 s in the
                            // fast-poll window after ACC ON, every 3 s
                            // otherwise. 5 s covers both.
                            long deadline = System.currentTimeMillis() + 5000;
                            while (System.currentTimeMillis() < deadline) {
                                if (enc.hasAudioConfig()) break;
                                try { Thread.sleep(100); }
                                catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            // Re-resolve the encoder at fire time. A
                            // recording-mode switch (CONTINUOUS → DRIVE_MODE,
                            // user-triggered restart, etc.) can tear down
                            // the encoder + create a fresh one during the
                            // 5 s wait above; the captured `enc` reference
                            // would then point at a dead-but-not-yet-released
                            // instance. forceSegmentRotation on that would
                            // either no-op silently or throw inside the
                            // muxerLock. Identity-check against the live
                            // pipeline encoder before firing.
                            com.overdrive.app.surveillance.HardwareEventRecorderGpu liveEnc =
                                pipeline.getEncoder();
                            if (liveEnc != enc) {
                                CameraDaemon.log("audio-recording: deferred rotation skipped — "
                                    + "encoder identity changed");
                                return;
                            }
                            if (!liveEnc.isWritingToFile()) return;  // mode changed
                            if (!liveEnc.hasAudioConfig()) {
                                // Either the audio config never arrived
                                // within 5 s, or it landed and was
                                // subsequently reverted (app stopped,
                                // mic claimed). Skip — natural rotation
                                // at the next 2 min boundary will pick
                                // up audio if it comes back.
                                CameraDaemon.log("audio-recording: deferred rotation skipped — "
                                    + "audio config not present at fire time");
                                return;
                            }
                            try {
                                liveEnc.forceSegmentRotation();
                                CameraDaemon.log("audio-recording: deferred rotation fired "
                                    + "after audio config landed");
                            } catch (Exception e) {
                                CameraDaemon.log("audio-recording: deferred rotation failed: "
                                    + e.getMessage());
                            }
                        }, "AudioRecordingRotate").start();
                    }
                }
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("enabled", enabled);
            // UI hint. "scheduled" means we'll force-rotate the segment
            // once the app's audio config lands (within ~5 s); the next
            // clip after that has audio. "next-event" means there's no
            // active recording so audio just applies to the next event
            // trigger naturally.
            response.put("appliesAt", rotateScheduled ? "next-clip" : "next-event");
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting audio-recording: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    /**
     * Handle telemetry overlay settings POST. Accepts a per-flow body:
     * <pre>{ enabled?: bool, panoEnabled?: bool, oemDashcamEnabled?: bool }</pre>
     * All three are optional; only present keys are written. {@code enabled}
     * is treated as the legacy pano alias and writes the legacy key + the
     * canonical {@code panoEnabled} so a downgrade-then-upgrade resolves
     * consistently.
     */
    private static void handleTelemetryOverlayPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body == null ? "{}" : body);

            JSONObject delta = new JSONObject();
            // Legacy alias — write both legacy + panoEnabled so a future
            // read with the new resolver picks up the user's intent.
            if (settings.has("enabled")) {
                boolean v = settings.optBoolean("enabled", false);
                delta.put("enabled", v);
                if (!settings.has("panoEnabled")) delta.put("panoEnabled", v);
            }
            if (settings.has("panoEnabled")) {
                delta.put("panoEnabled", settings.optBoolean("panoEnabled", false));
            }
            if (settings.has("oemDashcamEnabled")) {
                delta.put("oemDashcamEnabled", settings.optBoolean("oemDashcamEnabled", false));
            }
            if (delta.length() > 0) {
                com.overdrive.app.config.UnifiedConfigManager.setTelemetryOverlay(delta);
            }

            // Notify pano pipeline of the resolved pano state.
            boolean panoEffective = com.overdrive.app.config.UnifiedConfigManager
                .isTelemetryOverlayEnabledFor("pano");
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null) {
                pipeline.setOverlayEnabled(panoEffective);
            }

            // Notify OEM Dashcam pipeline of its resolved state. The pipeline
            // holds the bit via setOverlayEnabled; its own GL draw pass
            // composites the OverlayBitmapRenderer output onto the encoder
            // surface during drawPassthrough (gated on overlayEnabled &&
            // recording so the overlay only burns into actively-recording
            // dvr_*.mp4 clips, not stream-only output).
            boolean oemEffective = com.overdrive.app.config.UnifiedConfigManager
                .isTelemetryOverlayEnabledFor("oemDashcam");
            com.overdrive.app.camera.OemDashcamPipeline oem = CameraDaemon.getOemDashcamPipeline();
            if (oem != null) {
                oem.setOverlayEnabled(oemEffective);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("enabled", panoEffective);              // legacy alias
            response.put("panoEnabled", panoEffective);
            response.put("oemDashcamEnabled", oemEffective);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting telemetry overlay: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    // ======================================================================
    // Geocoding (place-tagging) settings
    // ======================================================================

    /**
     * GET /api/settings/geocoding — return the full geocoding schema:
     * {@code { recording:{enabled,allowOnline}, surveillance:{enabled,allowOnline},
     * advanced:{customNominatimBase} } }.
     *
     * <p>The persisted {@code nominatimCooldownUntilMs} is intentionally
     * NOT exposed to the web — it's purely an internal rate-limiter
     * signal, surfacing it would invite users to clear it manually and
     * defeat the purpose of the persistent backoff.
     */
    private static void sendGeocodingSettings(OutputStream out) throws Exception {
        JSONObject geo = com.overdrive.app.config.UnifiedConfigManager.getGeocoding();
        JSONObject rec = geo.optJSONObject("recording");
        JSONObject sur = geo.optJSONObject("surveillance");
        JSONObject adv = geo.optJSONObject("advanced");

        JSONObject response = new JSONObject();
        response.put("success", true);

        JSONObject recOut = new JSONObject();
        recOut.put("enabled", rec != null && rec.optBoolean("enabled", false));
        recOut.put("allowOnline", rec != null && rec.optBoolean("allowOnline", false));
        response.put("recording", recOut);

        JSONObject surOut = new JSONObject();
        surOut.put("enabled", sur != null && sur.optBoolean("enabled", false));
        surOut.put("allowOnline", sur != null && sur.optBoolean("allowOnline", false));
        response.put("surveillance", surOut);

        JSONObject advOut = new JSONObject();
        advOut.put("customNominatimBase",
                adv != null ? adv.optString("customNominatimBase", "") : "");
        response.put("advanced", advOut);

        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/settings/geocoding — accept a partial delta. Body:
     * <pre>{
     *   recording?: { enabled?: bool, allowOnline?: bool },
     *   surveillance?: { enabled?: bool, allowOnline?: bool },
     *   advanced?: { customNominatimBase?: string }
     * }</pre>
     *
     * <p>Each sub-object is optional; the handler only writes the fields
     * the caller actually included. No mid-recording invalidation — the
     * recorder's per-call read of UnifiedConfigManager picks up the
     * change at the next rotation/start naturally.
     */
    private static void handleGeocodingPost(OutputStream out, String body) throws Exception {
        try {
            if (body == null || body.isEmpty()) {
                HttpResponse.sendJsonError(out, "Empty body");
                return;
            }
            JSONObject delta = new JSONObject(body);

            // Lightweight URL validation — reject obvious garbage so a user
            // typo doesn't drive NominatimRateLimiter into a 6-hour backoff
            // by triggering MalformedURLException on every resolve attempt.
            // Empty string is allowed (means "use default OSM endpoint").
            // Scheme comparison is case-insensitive; URL parsers accept
            // HTTP:// / HTTPS:// uppercase forms.
            JSONObject inAdvCheck = delta.optJSONObject("advanced");
            if (inAdvCheck != null && inAdvCheck.has("customNominatimBase")) {
                String url = inAdvCheck.optString("customNominatimBase", "").trim();
                if (!url.isEmpty()) {
                    String urlLower = url.toLowerCase();
                    if (!urlLower.startsWith("http://")
                            && !urlLower.startsWith("https://")) {
                        HttpResponse.sendJsonError(out,
                                "customNominatimBase must start with http:// or https://");
                        return;
                    }
                }
            }

            // The read-merge-write below MUST be atomic w.r.t.
            // NominatimRateLimiter.writeCooldownUntilMs running on a
            // worker thread. UnifiedConfigManager.updateSection serializes
            // disk writes on `synchronized(this)` — Kotlin object's `this`
            // is the singleton INSTANCE field, NOT the Class object. We
            // MUST lock on UnifiedConfigManager.INSTANCE to share the
            // same monitor as the internal critical section. Locking on
            // .class would create a separate monitor and the TOCTOU race
            // would still be open.
            JSONObject merged = new JSONObject();
            boolean ok;
            JSONObject readBack;
            synchronized (com.overdrive.app.config.UnifiedConfigManager.INSTANCE) {
                JSONObject current = com.overdrive.app.config.UnifiedConfigManager.getGeocoding();

                if (delta.has("recording") || current.has("recording")) {
                    JSONObject curRec = current.optJSONObject("recording");
                    if (curRec == null) curRec = new JSONObject();
                    JSONObject inRec = delta.optJSONObject("recording");
                    JSONObject outRec = new JSONObject();
                    outRec.put("enabled",
                            inRec != null && inRec.has("enabled")
                                    ? inRec.optBoolean("enabled", false)
                                    : curRec.optBoolean("enabled", false));
                    outRec.put("allowOnline",
                            inRec != null && inRec.has("allowOnline")
                                    ? inRec.optBoolean("allowOnline", false)
                                    : curRec.optBoolean("allowOnline", false));
                    merged.put("recording", outRec);
                }

                if (delta.has("surveillance") || current.has("surveillance")) {
                    JSONObject curSur = current.optJSONObject("surveillance");
                    if (curSur == null) curSur = new JSONObject();
                    JSONObject inSur = delta.optJSONObject("surveillance");
                    JSONObject outSur = new JSONObject();
                    outSur.put("enabled",
                            inSur != null && inSur.has("enabled")
                                    ? inSur.optBoolean("enabled", false)
                                    : curSur.optBoolean("enabled", false));
                    outSur.put("allowOnline",
                            inSur != null && inSur.has("allowOnline")
                                    ? inSur.optBoolean("allowOnline", false)
                                    : curSur.optBoolean("allowOnline", false));
                    merged.put("surveillance", outSur);
                }

                // Advanced sub-object — preserve cooldown when the caller
                // writes a new customNominatimBase. Cooldown is set
                // internally by NominatimRateLimiter; the web caller has
                // no business clearing it, and now the lock above
                // guarantees we read the latest cooldown value.
                if (delta.has("advanced") || current.has("advanced")) {
                    JSONObject curAdv = current.optJSONObject("advanced");
                    if (curAdv == null) curAdv = new JSONObject();
                    JSONObject inAdv = delta.optJSONObject("advanced");
                    JSONObject outAdv = new JSONObject();
                    if (inAdv != null && inAdv.has("customNominatimBase")) {
                        String url = inAdv.optString("customNominatimBase", "").trim();
                        outAdv.put("customNominatimBase", url);
                    } else {
                        outAdv.put("customNominatimBase",
                                curAdv.optString("customNominatimBase", ""));
                    }
                    outAdv.put("nominatimCooldownUntilMs",
                            curAdv.optLong("nominatimCooldownUntilMs", 0L));
                    merged.put("advanced", outAdv);
                }

                ok = com.overdrive.app.config.UnifiedConfigManager
                        .setGeocoding(merged);
                readBack = com.overdrive.app.config.UnifiedConfigManager.getGeocoding();
            }

            JSONObject response = new JSONObject();
            response.put("success", ok);
            JSONObject readRec = readBack.optJSONObject("recording");
            JSONObject readSur = readBack.optJSONObject("surveillance");
            JSONObject readAdv = readBack.optJSONObject("advanced");
            JSONObject echoRec = new JSONObject();
            echoRec.put("enabled", readRec != null && readRec.optBoolean("enabled", false));
            echoRec.put("allowOnline", readRec != null && readRec.optBoolean("allowOnline", false));
            JSONObject echoSur = new JSONObject();
            echoSur.put("enabled", readSur != null && readSur.optBoolean("enabled", false));
            echoSur.put("allowOnline", readSur != null && readSur.optBoolean("allowOnline", false));
            JSONObject echoAdv = new JSONObject();
            echoAdv.put("customNominatimBase",
                    readAdv != null ? readAdv.optString("customNominatimBase", "") : "");
            response.put("recording", echoRec);
            response.put("surveillance", echoSur);
            response.put("advanced", echoAdv);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting geocoding: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
}
