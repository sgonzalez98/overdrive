package com.overdrive.app.roadsense.warn

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.detect.Pose
import com.overdrive.app.roadsense.detect.Severity
import com.overdrive.app.roadsense.store.ApproachEngine
import com.overdrive.app.roadsense.store.RoadSenseStore

/**
 * Decides WHEN to warn the driver about an upcoming hazard and HOW (audio chime /
 * visual cue / both), honoring every gate (D-010 distance, D-015 confidence,
 * R-SET-4 per-severity, R-EXT-4 direction).
 *
 * ## Where it sits
 * Driven by the daemon controller's periodic tick (NOT the 100 Hz IMU path). On
 * each tick the controller hands it the live [Pose] + the current config snapshot;
 * this queries the store for hazards ahead (via [ApproachEngine]), picks the most
 * imminent one that passes the gates, and fires at most one warning per hazard.
 *
 * ## The gates (all must pass)
 *  1. feature + warnings enabled, and severity/confidence pass [RoadSenseConfig.Snapshot.warnsFor]
 *  2. hazard is AHEAD within the forward cone (ApproachEngine already filtered this)
 *  3. range ≤ dynamic alert distance `d = max(v·t_w, floor)` (D-010)
 *  4. we haven't already warned for THIS hazard id on this approach (dedupe)
 *
 * ## Audio
 * Uses [ToneGenerator] on STREAM_MUSIC — the same mechanism the project's
 * AudioTestApiHandler uses for beeps. Severity picks the tone so the driver can
 * tell a minor bump from a severe pothole without looking. Visual cues are
 * delegated to a [VisualSink] the overlay implements, keeping this class
 * overlay-agnostic + unit-testable.
 *
 * Not thread-safe; called from the single controller tick thread.
 */
class WarningCoordinator(
    private val store: RoadSenseStore,
    private val approach: ApproachEngine = ApproachEngine(),
    private val visualSink: VisualSink? = null,
    private val audio: AudioCue = ToneAudioCue(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Overlay-implemented visual cue. Kept minimal so the overlay owns rendering. */
    interface VisualSink {
        /** Show/refresh the approach cue for the zone led by [hazardId] at [rangeM]
         *  with the given [relativeBearingDeg] (−180..+180, 0=ahead) and [severity]
         *  (the zone's WORST). [typeOrdinal] is the lead hazard's type (for the
         *  icon). [zoneCount]/[zoneLengthM]/[zoneRough] describe the cluster (D-032):
         *  count=1 length=0 for a singleton; count>1 / rough=true for a cluster the
         *  overlay renders as "N bumps ahead" / "rough section, N m". */
        fun showApproach(
            hazardId: String,
            rangeM: Double,
            relativeBearingDeg: Double,
            severity: Severity,
            typeOrdinal: Int,
            zoneCount: Int,
            zoneLengthM: Int,
            zoneRough: Boolean,
        )
        /** Clear any visible approach cue (no hazard ahead). */
        fun clearApproach()
    }

    /** Pluggable audio so tests don't need a real ToneGenerator. */
    interface AudioCue {
        fun chime(severity: Severity)
        fun release()
    }

    // Dedupe: the id we last warned for, and when, so we warn once per approach
    // and re-arm only after the hazard leaves range / a cooldown passes.
    private var lastWarnedId: String? = null
    private var lastWarnedMs = 0L
    // GLOBAL last-audio-chime time — a single gate across ALL hazards/zones so a
    // burst of distinct bumps within the cool-off window yields ONE chime (issue C).
    // Set ONLY when a chime actually fires, independent of the per-zone dedupe above.
    private var lastAudioChimeMs = 0L

    /**
     * Evaluate one tick. [pose] is the live (or back-projected current) vehicle
     * pose; [cfg] the current config snapshot; [headingReliable] false at crawl /
     * no-fix (ApproachEngine then suppresses entirely — no direction-blind warnings).
     */
    fun onTick(pose: Pose, cfg: RoadSenseConfig.Snapshot, headingReliable: Boolean) {
        if (!cfg.enabled || !cfg.warnEnabled) {
            visualSink?.clearApproach()
            return
        }

        // Pull nearby hazards (tile-scoped); ApproachEngine consumes StoredHazard
        // directly (queryAhead's return type) — no adapter needed.
        val nearby = store.queryAhead(pose.lat, pose.lng, pose.bearingDeg.toDouble(), MAX_CANDIDATES)
        if (nearby.isEmpty()) { visualSink?.clearApproach(); return }

        // Group ahead into zones (D-032). ApproachEngine.rank already bounded these to
        // [minRangeM .. maxRangeM] inside the forward cone + same-direction road match,
        // so EVERY zone here is a hazard genuinely AHEAD on our road and not yet passed.
        val zones = approach.zonesAhead(pose, nearby, headingReliable)

        // VISUAL target — the nearest zone within the WIDE, persistent visual band that
        // passes the severity/confidence gate. Deliberately INDEPENDENT of speed: it
        // persists from first detection until the hazard is passed (ApproachEngine drops
        // it once range < minRangeM), so braking on approach can't make it vanish early
        // (issue A). Previously this used the speed-shrinking alertDist, which cleared
        // the cue before reaching the hazard.
        val visualTarget = zones.firstOrNull { z ->
            z.lead.rangeM <= VISUAL_MAX_RANGE_M &&
                z.members.any { cfg.warnsFor(it.stored.hazard.severity.level, it.stored.hazard.confidence) }
        }

        if (visualTarget == null) {
            // Nothing ahead in the visual band → clear. A hazard now under the car was
            // already dropped by ApproachEngine (range < minRangeM), so a passed hazard
            // lands here and the cue clears.
            visualSink?.clearApproach()
            if (lastWarnedId != null && zones.none { it.lead.stored.id == lastWarnedId }) {
                lastWarnedId = null
            }
            return
        }

        val lead = visualTarget.lead
        // The zone chimes/colours at its WORST member, not its nearest (D-032).
        val sev = severityFromLevel(visualTarget.maxSeverityLevel)
        val now = clock()

        // (A) VISUAL: refresh every tick while the hazard is ahead in the interest band
        // — NOT gated on the dynamic alertDist. Shown continuously, distance counting
        // down, until passed. (visual modes = VISUAL or BOTH)
        if (cfg.warnMode != RoadSenseConfig.WarnMode.AUDIO) {
            visualSink?.showApproach(
                lead.stored.id, lead.rangeM, lead.relativeBearingDeg, sev,
                lead.stored.hazard.type.ordinal,
                visualTarget.count, visualTarget.lengthM.toInt(), visualTarget.isRoughSection,
            )
        } else {
            // AUDIO-only: the user wants no hazard CARD, but the overlay still needs a
            // heartbeat or the app-side card goes stale ("Idle"/disconnected) after its
            // 4 s window — for the WHOLE approach, precisely while we're actively
            // chiming. Publishing idle here keeps the daemon-alive heartbeat + the
            // calibration dot flowing without rendering a hazard card (the right visual
            // for audio-only mode). Was previously a no-op → overlay stall.
            visualSink?.clearApproach()
        }

        // (B)(C) AUDIO: fire ONCE when a not-yet-chimed zone crosses into the NARROW,
        // speed-based alert distance, then a GLOBAL cool-off silences every chime for
        // AUDIO_COOLOFF_MS — so a burst of 3-4 bumps gets one chime while the visual
        // keeps updating. Two independent gates compose: per-zone dedupe (lastWarnedId)
        // + global quiet window (lastAudioChimeMs).
        val alertDist = maxOf(pose.speedMps * cfg.warnLeadSeconds, cfg.warnFloorMeters).toDouble()
        val withinAlert = lead.rangeM <= alertDist
        // newZone: a zone we haven't CHIMED for yet (different id), or the same zone
        // we're still approaching past the per-zone re-warn window.
        val newZone = lead.stored.id != lastWarnedId || (now - lastWarnedMs) >= REWARN_COOLDOWN_MS
        val cooledOff = (now - lastAudioChimeMs) >= AUDIO_COOLOFF_MS

        if (withinAlert && newZone && cfg.warnMode != RoadSenseConfig.WarnMode.VISUAL && cooledOff) {
            // Chime exactly once per zone, AND only when the global burst cool-off has
            // elapsed. We mark the zone announced (lastWarnedId/Ms) ONLY here — when a
            // chime actually fires — NOT unconditionally on withinAlert&&newZone. The
            // old unconditional mark "spent" a distinct later hazard's audio slot when
            // that hazard first appeared mid-cool-off: it was flagged announced without
            // ever chiming, so once the cool-off expired newZone was already false and
            // it stayed silent forever (the visual still showed). Marking inside the
            // chime keeps the burst collapse (cool-off still silences the rest of a
            // burst) while letting a genuinely separate hazard chime once the window
            // clears, and still prevents the SAME zone re-triggering the instant the
            // cool-off ends (it's now lastWarnedId until REWARN_COOLDOWN_MS).
            audio.chime(sev)
            lastAudioChimeMs = now   // global gate — silences the rest of the burst (C)
            lastWarnedId = lead.stored.id
            lastWarnedMs = now
            Log.i(TAG, "warn $sev zone lead=${lead.stored.id} n=${visualTarget.count} " +
                "len=${"%.0f".format(visualTarget.lengthM)}m rough=${visualTarget.isRoughSection} " +
                "@${"%.0f".format(lead.rangeM)}m (alertDist=${"%.0f".format(alertDist)}m " +
                "mode=${cfg.warnMode})")
        }
    }

    /** Map a stored severity level (1..3) back to [Severity], clamped. */
    private fun severityFromLevel(level: Int): Severity = when {
        level >= Severity.SEVERE.level -> Severity.SEVERE
        level == Severity.MODERATE.level -> Severity.MODERATE
        else -> Severity.MINOR
    }

    fun release() = audio.release()

    companion object {
        private const val TAG = "RoadSense/Warn"
        private const val MAX_CANDIDATES = 16
        /** Don't re-chime for the same zone lead within this window (one chime/approach). */
        private const val REWARN_COOLDOWN_MS = 20_000L
        /** GLOBAL audio quiet window: after ANY chime, suppress ALL chimes for this long
         *  regardless of how many distinct hazards/zones appear — collapses a burst of
         *  3-4 close bumps into a single chime while the visual keeps updating (issue C).
         *  12 s sits between a cluster traversal (~seconds) and REWARN_COOLDOWN_MS, so a
         *  genuinely separate hazard a block later still chimes. PROVISIONAL. */
        private const val AUDIO_COOLOFF_MS = 12_000L
        /** Wide, speed-INDEPENDENT band the VISUAL cue persists within — from first
         *  detection until the hazard is passed (issue A). Must stay below
         *  ApproachEngine.DEFAULT_MAX_RANGE_M (300m) so the cue clears with margin
         *  before the candidate even leaves the store query. PROVISIONAL. */
        private const val VISUAL_MAX_RANGE_M = 200.0
    }
}

/**
 * Default [WarningCoordinator.AudioCue] backed by [ToneGenerator] on STREAM_MUSIC
 * — same approach as the project's AudioTestApiHandler. Severity → distinct tone
 * so the chime itself conveys urgency. The generator is created lazily and reused;
 * [release] frees it when the feature stops.
 */
class ToneAudioCue : WarningCoordinator.AudioCue {
    private var tg: ToneGenerator? = null

    private fun gen(): ToneGenerator? {
        if (tg == null) {
            tg = try { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) } catch (_: Throwable) { null }
        }
        return tg
    }

    override fun chime(severity: Severity) {
        val tone = when (severity) {
            Severity.MINOR -> ToneGenerator.TONE_PROP_BEEP          // single soft beep
            Severity.MODERATE -> ToneGenerator.TONE_PROP_BEEP2      // double beep
            Severity.SEVERE -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD // urgent
        }
        val ms = if (severity == Severity.SEVERE) 600 else 300
        try { gen()?.startTone(tone, ms) } catch (_: Throwable) { /* never crash on a chime */ }
    }

    override fun release() {
        try { tg?.release() } catch (_: Throwable) {}
        tg = null
    }

    companion object { private const val VOLUME = 90 }
}
