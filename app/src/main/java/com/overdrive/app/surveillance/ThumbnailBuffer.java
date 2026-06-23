package com.overdrive.app.surveillance;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ThumbnailBuffer — Captures the highest-severity frame per Actor over the life
 * of a recording, then writes JPEG thumbnails next to the MP4 when the recording
 * closes.
 *
 * Score tuple per slot: (severity ordinal, confidence, proximity rank).
 * Higher tuple wins; new observations only overwrite the slot when their tuple
 * beats the existing one. This guarantees the saved JPEG is the peak-threat
 * moment, not the first or last detection.
 *
 * Memory bound: one slot per active actorId, one 640×640 RGB byte[] each
 * (~1.2 MB). Worst case at MAX_TRACKS=32 ≈ 38 MB; in practice 1–4 actors so
 * ~5 MB during a recording. All slots are dropped when the recording closes.
 */
public final class ThumbnailBuffer {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ThumbBuf");

    /** Output JPEG side-length. The crop is resized to this from whatever the
     *  source dimensions were (typically 640×640 foveated or 320×240 mosaic). */
    private static final int OUT_SIDE = 640;
    private static final int JPEG_QUALITY = 85;

    /** Package-visible so the caller-driven per-actor writer (see
     *  {@link #writePerActorJpeg}) can iterate a snapshot of slots after
     *  the buffer has been cleared synchronously. */
    static final class Slot {
        byte[] rgb;
        int srcW;
        int srcH;
        int bboxX, bboxY, bboxW, bboxH;
        Actor.Severity severity;
        float confidence;
        Actor.Proximity proximity;
        long wallMs;
        // Wall-clock of the actor's PEAK-severity moment (the frame this crop
        // depicts), as opposed to wallMs which is when the slot was last touched.
        // Used to reject a hero whose peak predates the recorded MP4 window
        // (the pre-record ring is bounded, so a peak captured before the clip
        // starts was evicted and is not in the video).
        long peakWallMs;
        Actor.ClassGroup classGroup;
        long actorId;
        int camera;
    }

    private final Map<Long, Slot> slots = new HashMap<>();

    // Pooled scratch buffer for ARGB conversion in writeJpeg. Hero JPEGs are
    // written sequentially during stopRecording, all from foveated crops of
    // identical dimension. Without pooling, each writeJpeg allocates a fresh
    // int[srcW*srcH] (~1.6 MB per 640×640 thumb) and discards it, churning
    // 6-16 MB per recording-stop and triggering GC pauses on the main thread.
    // Held by class because flushToDisk is single-threaded (synchronized).
    private int[] argbScratch = null;

    /**
     * Score tuple for ranking observations. Higher wins.
     *
     * Order of importance:
     *  1. Severity ordinal (NOTICE < ALERT < CRITICAL).
     *  2. Class group rank — person > bike > vehicle > animal > unknown.
     *     Reason: when two actors hit the same severity tier (e.g. an approaching
     *     car and a walking person both reach ALERT), the *person* is what the
     *     user actually wants the thumbnail to depict. Without this, a high-
     *     confidence vehicle bbox can mask the lower-confidence but more
     *     relevant person.
     *  3. Proximity (closer wins).
     *  4. Confidence — high-resolution tie-breaker only.
     */
    private static long score(Actor.Severity sev, float conf, Actor.Proximity p,
                              Actor.ClassGroup g) {
        int sevOrd = sev != null ? sev.ordinal() : 0;
        int classRank = classRank(g);                    // 0..4
        int proxRank = (p == null) ? 0 : (Actor.Proximity.values().length - 1 - p.ordinal());
        int confMilli = Math.max(0, Math.min(1000, Math.round(conf * 1000f)));
        // Pack: [sev:4][class:4][prox:4][confMilli:14]
        return ((long) sevOrd  << 32)
             | ((long) classRank << 28)
             | ((long) proxRank  << 24)
             | ((long) confMilli);
    }

    private static int classRank(Actor.ClassGroup g) {
        if (g == null) return 0;
        switch (g) {
            case PERSON:  return 4;
            case BIKE:    return 3;
            case VEHICLE: return 2;
            case ANIMAL:  return 1;
            default:      return 0;
        }
    }

    /**
     * Observe a frame: examine each Actor in the snapshot and update its slot
     * iff the new tuple beats the existing one.
     *
     * The {@code rgb} buffer is COPIED into the slot — the caller is free to
     * recycle their own buffer immediately.
     *
     * @param actors  Snapshot from {@link ActorTracker#update(java.util.List, int, int, int, long, long)}
     * @param rgb     RGB byte[] (length = w*h*3) of the YOLO crop the actors were detected in
     * @param w       Width of the rgb buffer (e.g. 320 for mosaic, 640 for foveated)
     * @param h       Height of the rgb buffer
     * @param camera  Quadrant index
     */
    public synchronized void observe(List<Actor> actors, byte[] rgb, int w, int h, int camera) {
        if (actors == null || actors.isEmpty() || rgb == null || w <= 0 || h <= 0) return;
        long now = System.currentTimeMillis();
        for (Actor a : actors) {
            // Only consider actors that hit at least NOTICE in this frame's quadrant
            if (a.peakCamera != camera) continue;
            // Skip background scenery: a static non-person actor that never
            // escalated past NOTICE is almost always a parked car or a tree
            // briefly uncovered by motion. Including them in the slot pool
            // means a far parked vehicle wins the hero score on otherwise-empty
            // events — the user sees a thumbnail with a green bbox over a
            // static car in the distance and assumes the system flagged it as
            // a threat. EventTimelineCollector's peakProximity aggregation
            // already excludes these (RecordingsApiHandler honours the result
            // for the distance chip filter). Mirror the same gate here so the
            // hero / per-actor JPEGs agree with the recording-level summary.
            if (a.isStatic
                    && a.classGroup != Actor.ClassGroup.PERSON
                    && a.peakSeverity == Actor.Severity.NOTICE) {
                continue;
            }
            long incoming = score(a.peakSeverity, a.peakConfidence, a.peakProximity, a.classGroup);
            Slot existing = slots.get(a.actorId);
            long existingScore = existing != null
                    ? score(existing.severity, existing.confidence, existing.proximity, existing.classGroup) : -1L;
            if (incoming <= existingScore) continue;

            // CRITICAL: bbox alignment guard. The actor's peakBbox lives in
            // peakBboxQuadW × peakBboxQuadH coords (the crop space at the
            // frame peak severity was hit). The rgb we'd store is in THIS
            // frame's w × h. The pipeline alternates between mosaic (320×240,
            // full quadrant downscaled) and foveated (640×640, a high-res
            // window centered on motion centroid) — these are NOT
            // proportionally related geometries. Naive rescaling would draw
            // the bbox on the wrong physical region.
            //
            // Skip the update unless this frame's crop matches the peak's
            // crop. The score gate above already returned for non-improving
            // observations, so the only path that lands here is a real
            // improvement — but if it lands during an incompatible crop
            // mode, we'd rather keep the prior matching (rgb, bbox) pair
            // than overwrite with mismatched ones. The peak frame itself
            // (when peakSeverityWallMs == this frame's wallMs) is always
            // compatible because peakBboxQuad{W,H} were just set to (w, h).
            //
            // Defensive fallback: if peakBboxQuadW/H are zero (Actor
            // produced before this field existed in storage / very early
            // frames), trust the current crop dims.
            int bboxQuadW = a.peakBboxQuadW > 0 ? a.peakBboxQuadW : w;
            int bboxQuadH = a.peakBboxQuadH > 0 ? a.peakBboxQuadH : h;
            if (bboxQuadW != w || bboxQuadH != h) {
                // Wait for a frame whose crop matches the peak's crop. The
                // existing slot (if any) already has a coherent (rgb, bbox)
                // pair captured when the dims did match — better than
                // overwriting with a mismatched pair.
                continue;
            }

            Slot s = existing != null ? existing : new Slot();
            // Re-allocate only if size changed (or first capture) — avoids per-frame churn
            int needBytes = w * h * 3;
            if (s.rgb == null || s.rgb.length != needBytes) {
                s.rgb = new byte[needBytes];
            }
            System.arraycopy(rgb, 0, s.rgb, 0, needBytes);
            s.srcW = w;
            s.srcH = h;
            s.bboxX = a.peakBboxX;
            s.bboxY = a.peakBboxY;
            s.bboxW = a.peakBboxW;
            s.bboxH = a.peakBboxH;
            s.severity = a.peakSeverity;
            s.confidence = a.peakConfidence;
            s.proximity = a.peakProximity;
            s.wallMs = now;
            s.peakWallMs = a.peakSeverityWallMs;
            s.classGroup = a.classGroup;
            s.actorId = a.actorId;
            s.camera = a.peakCamera;
            slots.put(a.actorId, s);
        }
    }

    /**
     * Pick the highest-score slot from a snapshot. Pure helper used by the
     * sync (engine-stop) and async (segment-rotation) hero writers so both
     * compute the hero off the same snapshot the caller already drained.
     * Doing the snapshot AT DRAIN TIME (rather than later inside the
     * executor) avoids a cross-segment race: rotation listener and
     * stopRecording can both call into here for different segments — each
     * captures its own snapshot synchronously, so the second segment isn't
     * left with an empty buffer because the first already drained.
     */
    static Slot pickHero(List<Slot> snap) {
        return pickHero(snap, 0L, 0L);
    }

    /**
     * Pick the highest-score slot whose peak frame lies within the recorded
     * window [windowStartMs, windowEndMs]. A slot whose peakWallMs predates the
     * window depicts a moment evicted from the bounded pre-record ring — i.e. a
     * frame the user will NOT find when scrubbing the MP4 — so it is excluded.
     *
     * windowStartMs<=0 disables the gate (legacy behavior). windowEndMs<=0 means
     * "no upper bound" (open-ended, e.g. the still-growing current segment).
     *
     * Fallback: if NO slot is in-window (e.g. every peak predates a very short
     * pre-record window), return the best slot anyway — a slightly-stale hero
     * beats no hero, and the caption/sidecar still describe the event.
     */
    static Slot pickHero(List<Slot> snap, long windowStartMs, long windowEndMs) {
        Slot hero = null, heroAny = null;
        long heroScore = -1L, heroAnyScore = -1L;
        for (Slot s : snap) {
            long sc = score(s.severity, s.confidence, s.proximity, s.classGroup);
            if (sc > heroAnyScore) { heroAnyScore = sc; heroAny = s; }
            boolean inWindow = windowStartMs <= 0
                    || (s.peakWallMs >= windowStartMs
                        && (windowEndMs <= 0 || s.peakWallMs <= windowEndMs));
            if (inWindow && sc > heroScore) { heroScore = sc; hero = s; }
        }
        return hero != null ? hero : heroAny;
    }

    /**
     * Write the hero JPEG from a pre-drained snapshot. Caller decides whether
     * this runs on the engine thread (sync hero, publish path needs it
     * deterministic) or on the executor (rotation path, no publish dep).
     *
     * @param snap     Pre-drained snapshot from {@link #drainSnapshotForAsync()}.
     * @param mp4File  Recording the hero accompanies; named
     *                 {@code <basename>.jpg} in the same directory.
     * @return Hero JPEG file on disk, or null if no slots / write failed.
     */
    public synchronized File writeHeroFromSnapshot(List<Slot> snap, File mp4File) {
        return writeHeroFromSnapshot(snap, mp4File, 0L, 0L);
    }

    /**
     * Window-gated variant: only a slot whose peak frame lies within
     * [windowStartMs, windowEndMs] is eligible as the hero (with a best-effort
     * fallback if none qualify — see {@link #pickHero(List, long, long)}). This
     * stops the hero JPEG from depicting a peak moment that was evicted from the
     * bounded pre-record ring and therefore is not present anywhere in the MP4.
     */
    public synchronized File writeHeroFromSnapshot(List<Slot> snap, File mp4File,
                                                   long windowStartMs, long windowEndMs) {
        if (snap == null || snap.isEmpty() || mp4File == null) return null;
        File parent = mp4File.getParentFile();
        if (parent == null) return null;
        Slot hero = pickHero(snap, windowStartMs, windowEndMs);
        if (hero == null) return null;
        String base = mp4File.getName();
        if (base.endsWith(".mp4")) base = base.substring(0, base.length() - 4);
        File heroFile = new File(parent, base + ".jpg");
        try {
            // Synchronized: writeJpeg uses the instance argbScratch field;
            // engine-thread (stopRecording publish) and executor-thread
            // (segment-rotation listener) can BOTH call this for different
            // segments at the same time. Lock the buffer instance so the
            // scratch buffer isn't torn between callers. JPEG compress is
            // ~50ms — short enough that serializing rotation+stop heroes
            // doesn't matter; correctness wins over minor parallelism.
            writeJpeg(hero, heroFile);
            return heroFile;
        } catch (Exception e) {
            logger.warn("Hero thumb write failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detach the current per-actor slots into a snapshot list and clear the
     * internal map. Caller drives the actual JPEG writes on a background
     * executor via {@link #writePerActorJpeg}, one slot at a time.
     *
     * <p>Returning a snapshot rather than draining inside the writer lets
     * the caller decide which executor to use AND lets the buffer be
     * cleared synchronously here — so the next recording's
     * {@link #observe(java.util.List, byte[], int, int, int)} starts with a
     * clean slate even if per-actor writes are still pending.
     *
     * <p>The returned slots reference the SAME {@code byte[] rgb} arrays the
     * buffer was holding — caller must NOT mutate them. After this call the
     * buffer no longer holds them, so they're safe to use until the
     * background executor finishes JPEG compression.
     *
     * @return list of slots (empty if nothing captured). Never null.
     */
    public synchronized List<Slot> drainSnapshotForAsync() {
        if (slots.isEmpty()) return java.util.Collections.emptyList();
        List<Slot> snap = new ArrayList<>(slots.values());
        slots.clear();
        return snap;
    }

    /**
     * Write a single per-actor JPEG. Caller-driven: invoked from the
     * background executor inside SurveillanceEngineGpu's per-segment
     * publish path. Allocates a fresh scratch int[] every call — cold path
     * (≤ a few JPEGs per recording) so the allocation cost is negligible
     * compared to the JPEG compress itself.
     *
     * <p>Does NOT touch the buffer-instance {@link #argbScratch} field, so
     * the engine-thread sync hero writer (which uses that field) and this
     * async per-actor writer are safe to run concurrently for different
     * segments.
     */
    public void writePerActorJpeg(Slot s, File outFile) throws Exception {
        writeJpegWithScratch(s, outFile, null);
    }

    /**
     * @return list of actorIds for which a thumbnail has been captured during
     *         the current recording.
     */
    public synchronized List<Long> capturedActorIds() {
        return new ArrayList<>(slots.keySet());
    }

    /**
     * Returns the recording-relative time (wall-ms) the slot was last updated,
     * for slot's owning actorId, or -1 if no slot exists.
     */
    public synchronized long lastUpdateWallMs(long actorId) {
        Slot s = slots.get(actorId);
        return s != null ? s.wallMs : -1L;
    }

    /** Drop everything (e.g. when recording aborted). */
    public synchronized void clear() {
        slots.clear();
    }

    // ---------- writer ------------------------------------------------------

    /**
     * Sync-path JPEG writer; uses the buffer's pooled {@link #argbScratch}
     * which is owned by the surveillance (caller) thread. Must NOT be
     * called from the async per-actor executor — use
     * {@link #writeJpegWithScratch} there.
     */
    private void writeJpeg(Slot s, File outFile) throws Exception {
        argbScratch = writeJpegImpl(s, outFile, argbScratch);
    }

    /**
     * Async-path JPEG writer; takes a caller-owned scratch buffer (or null
     * for first call) and returns the (possibly grown) buffer for reuse on
     * the next iteration. Lets the per-actor lambda pool ARGB allocation
     * across slots without sharing state with the sync hero path.
     */
    private static int[] writeJpegWithScratch(Slot s, File outFile, int[] scratch)
            throws Exception {
        return writeJpegImpl(s, outFile, scratch);
    }

    /** Shared implementation. Returns the (possibly grown) scratch buffer. */
    private static int[] writeJpegImpl(Slot s, File outFile, int[] scratchIn) throws Exception {
        Bitmap bmp = null;
        Bitmap out = null;
        int[] argbScratchLocal = scratchIn;
        try {
            bmp = Bitmap.createBitmap(s.srcW, s.srcH, Bitmap.Config.ARGB_8888);
            // Convert RGB byte[] → ARGB pixel array, reusing a pooled scratch
            // buffer when possible. Realloc only when the size grows.
            int needPixels = s.srcW * s.srcH;
            if (argbScratchLocal == null || argbScratchLocal.length < needPixels) {
                argbScratchLocal = new int[needPixels];
            }
            int[] pixels = argbScratchLocal;
            for (int i = 0, p = 0; i < s.rgb.length; i += 3, p++) {
                int r = s.rgb[i] & 0xFF;
                int g = s.rgb[i + 1] & 0xFF;
                int b = s.rgb[i + 2] & 0xFF;
                pixels[p] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(pixels, 0, s.srcW, 0, 0, s.srcW, s.srcH);

            // Resize to OUT_SIDE if needed
            if (s.srcW != OUT_SIDE || s.srcH != OUT_SIDE) {
                out = Bitmap.createScaledBitmap(bmp, OUT_SIDE, OUT_SIDE, true);
                // bmp is now redundant — recycle eagerly (and null it so the
                // finally block doesn't double-recycle). createScaledBitmap
                // can also return the same bitmap if dims happened to match;
                // guard by identity.
                if (out != bmp) {
                    bmp.recycle();
                    bmp = null;
                }
            } else {
                out = bmp;
                bmp = null;  // ownership transferred to `out`
            }

            // Draw bbox + label
            Canvas canvas = new Canvas(out);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(4f);
            stroke.setColor(severityColor(s.severity));

            float scaleX = (float) OUT_SIDE / s.srcW;
            float scaleY = (float) OUT_SIDE / s.srcH;
            Rect r = new Rect(
                    Math.round(s.bboxX * scaleX),
                    Math.round(s.bboxY * scaleY),
                    Math.round((s.bboxX + s.bboxW) * scaleX),
                    Math.round((s.bboxY + s.bboxH) * scaleY));
            canvas.drawRect(r, stroke);

            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(Color.WHITE);
            label.setTextSize(28f);
            label.setShadowLayer(3f, 0f, 0f, Color.BLACK);
            String text = Actor.severityLabel(s.severity) + " · "
                    + Actor.groupLabel(s.classGroup) + " · "
                    + Actor.proximityLabel(s.proximity);
            canvas.drawText(text, Math.max(8, r.left), Math.max(32, r.top - 8), label);

            // Atomic write: compress to <name>.tmp, fsync, rename to <name>.
            // A process kill mid-compress would otherwise leave a truncated
            // .jpg at the final filename — and the hero JPEG is now
            // load-bearing for both PWA push and Telegram sendPhoto, with
            // no regeneration path once the sidecar names it as heroThumbnail.
            // Same discipline EventTimelineCollector uses for the JSON sidecar.
            File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
                try { fos.getFD().sync(); } catch (Throwable ignored) {}
            }
            // World-readable so the Telegram daemon (separate UID, typically
            // shell/2000) can read the JPEG with sendPhoto. Set on tmp BEFORE
            // rename so the readable bit lands atomically with the file move.
            try { tmpFile.setReadable(true, /*ownerOnly=*/false); } catch (Throwable ignored) {}
            if (!tmpFile.renameTo(outFile)) {
                // Rename failed (e.g. cross-volume on weird mounts). Best-effort
                // direct copy as a fallback so we don't lose the hero entirely.
                outFile.delete();
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.delete();
                    throw new java.io.IOException("Failed to atomically rename " + tmpFile + " → " + outFile);
                }
            }
        } finally {
            // Recycle whichever Bitmaps are still live. setPixels / createScaledBitmap /
            // FileOutputStream can all throw, and previously these paths leaked
            // 1.6 MB of native pixels per failure. Identity-guard against
            // double-recycle when out==bmp.
            if (out != null) out.recycle();
            if (bmp != null && bmp != out) bmp.recycle();
        }
        return argbScratchLocal;
    }

    private static int severityColor(Actor.Severity sev) {
        if (sev == Actor.Severity.CRITICAL) return Color.RED;
        if (sev == Actor.Severity.ALERT)    return 0xFFFF8800; // orange
        return 0xFFAAAAAA; // grey for NOTICE
    }
}
