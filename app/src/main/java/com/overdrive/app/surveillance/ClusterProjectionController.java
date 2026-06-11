package com.overdrive.app.surveillance;

import android.content.Context;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Daemon-owned lifecycle controller for the OEM driver-cluster projection, used
 * ONLY when the blind-spot target is "cluster". It opens the OEM cluster
 * projection (so the cluster's {@code fission} PRESENTATION VirtualDisplay,
 * layerStack 1, materialises and our already-rendered blind-spot SurfaceControl
 * layer can be retargeted onto it), and — critically — guarantees the live gauges
 * are RESTORED on every exit path.
 *
 * <h3>Why this is safe to drive from here</h3>
 * The daemon runs as uid 2000 (shell), which is platform-signed. The OEM
 * container service's send-permission gate is a signature check
 * ({@code checkSignatures(selfUid, callingUid) == 0 → allowed}); a platform-signed
 * caller bypasses the package allowlist. Verified on-device: the projection
 * opcodes (size-profile / fullscreen-on / di4-mode / close / refresh) are accepted
 * from this uid (no SecurityException) and a uid-2000 SurfaceControl layer tagged
 * layerStack=1 composites onto the cluster.
 *
 * <h3>Projection is a FULL takeover</h3>
 * There is no partial/overlay cluster projection on this firmware — opening the
 * projection replaces the native gauges for its duration. To bound that, the
 * timing model is <b>lazy-open-on-first-signal + linger-close</b>: open on the
 * first turn-signal while armed for the cluster, stay open across the rapid blink
 * on/off phases, and close (restore gauges) after {@link #lingerCloseMs} of no
 * signal, or a hard {@link #maxProjectionMs} cap, or disarm/disable/shutdown.
 *
 * <h3>Gauge-restore guarantee (the whole point)</h3>
 * Every path that could leave the gauges blanked has a force-restore:
 * <ul>
 *   <li>blink-off → linger close (18→0)</li>
 *   <li>signal stuck-on → {@link #maxProjectionMs} cap force-closes</li>
 *   <li>ACC-off / disable / target→head-unit → {@link #forceClose}</li>
 *   <li>open-sequence error / ready-timeout → {@link #forceClose}</li>
 *   <li>daemon SIGTERM/normal exit → {@link #shutdown} fires 18→0 synchronously</li>
 *   <li>daemon SIGKILL → on respawn {@link #clearStaleGateAtBoot} reads the leaked
 *       UCM gate flag and blind-fires 18→0 (the opcode path is stateless)</li>
 * </ul>
 * NEVER sends opcode 1 (disconnects Qt entirely / destroys the display).
 *
 * <p>All opcode sends + sleeps run on a dedicated thread so the 250 ms blind-spot
 * turn loop and the GL thread are never blocked.
 */
public final class ClusterProjectionController {

    private static final String TAG = "ClusterProjection";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // ── Projection opcodes (sendInfo type = CLUSTER_TYPE, infoInt = opcode) ──────
    private static final int CLUSTER_TYPE     = 1000;
    // Cluster size profile: 29=8.8", 30=12.3", 31=10.25" native. 31 is correct for
    // this cluster (confirmed on-device — 30 gave a wrong/stretched aspect). UCM
    // surveillance.clusterSizeProfile overrides; 0 skips the size step (just 16→35).
    private static final int OP_SIZE_PROFILE  = 31;  // 10.25" native (was 30=12.3", wrong aspect)
    private static final int OP_FULLSCREEN_ON = 16;  // full-screen projection ON
    private static final int OP_DI4_MODE      = 35;  // Di4.0 mode → triggers VD creation
    private static final int OP_CLOSE         = 18;  // close projection
    private static final int OP_REFRESH       = 0;   // refresh/restore native gauge render
    // NOTE: opcode 1 (disconnect Qt entirely / destroy display) is deliberately
    // NOT defined and NEVER sent — it poisoned a prior teardown test.

    static final int CLUSTER_LAYER_STACK = 1;
    private static final int CLUSTER_DISPLAY_ID = 1;

    // ── Timing (UCM-tunable via surveillance.*) ─────────────────────────────────
    // The open sequence has TWO inter-step gaps with DIFFERENT requirements:
    //   • size→16 (sizeStepMs): the size-profile (31) just configures geometry, it
    //     settles fast — a short gap suffices.
    //   • 16→35 (openStepDelayMs): fullscreen-on (16) must actually establish the
    //     projection surface + wire it to the panel before Di4-mode (35) materializes
    //     the VirtualDisplay. ON-DEVICE PROVEN: 1000ms works (video shows, ~3s load);
    //     <=500ms regresses to NO VISIBLE VIDEO (display materializes + SF composites
    //     our layer, but Qt hasn't wired the fission surface to the panel yet).
    // Both UCM-tunable: surveillance.clusterSizeStepMs / surveillance.clusterOpenStepMs.
    private long sizeStepMs = DEFAULT_SIZE_STEP_MS;
    private long openStepDelayMs = DEFAULT_OPEN_STEP_DELAY_MS;
    // Both default 1000ms (the proven-working value). 200ms for the size step was
    // tried but suspected too short (the size profile may need to settle before 16);
    // keeping them equal at 1s avoids that risk. Total open ≈ 2×1s + settle ≈ 3s.
    private static final long DEFAULT_SIZE_STEP_MS = 1000;
    private static final long DEFAULT_OPEN_STEP_DELAY_MS = 1000;
    private static final long CLOSE_STEP_DELAY_MS = 1000;  // between 18→0
    private static final long READY_POLL_MS = 120;
    private static final long READY_TIMEOUT_MS = 8000;
    // The cluster VirtualDisplay materializes ~280ms after OP_DI4_MODE. We poll
    // DisplayManager as a fast early-exit, but the daemon's app_process Context has
    // a DisplayManagerGlobal cache that does NOT receive add-callbacks for the
    // foreign (uid-1000) fission display, so getDisplay(1) can return null forever
    // even though the display exists (confirmed on-device: dumpsys shows it, the
    // daemon's getDisplay(1) does not). So after this settle window we mark ready
    // UNCONDITIONALLY rather than timing out + force-closing (the latter caused an
    // open→timeout→close→reopen thrash that kept re-asserting the size-profile
    // opcode and reflowing the head-unit). Compositing onto layerStack 1 is the
    // proven path and is harmless even in the impossible case the display is absent
    // (the layer is simply invisible, never a crash).
    private static final long READY_SETTLE_MS = 900;   // past the ~280ms materialize + margin
    private static final long DEFAULT_LINGER_CLOSE_MS = 8000;
    private static final long DEFAULT_MAX_PROJECTION_MS = 90000;

    // UCM gate flags (under "surveillance") for SIGKILL recovery — mirror the
    // screenDeterrent* pattern so a respawned daemon can restore the gauges.
    private static final String GATE_FORCE_STOP = "clusterProjectionForceStop";
    private static final String GATE_ACTIVE_UNTIL = "clusterProjectionActiveUntilMs";

    private static final int ST_CLOSED  = 0;
    private static final int ST_OPENING = 1;
    private static final int ST_OPEN    = 2;
    private static final int ST_CLOSING = 3;

    private static volatile ClusterProjectionController instance;

    private final android.os.HandlerThread projThread;
    private final android.os.Handler projHandler;     // opcode sends + sleeps + ready-poll
    private final android.os.Handler watchdogHandler; // linger + max-cap timers

    private volatile int projState = ST_CLOSED;
    private volatile boolean ready = false;
    private volatile long openedAtMs = 0;
    private volatile long lastSignalMs = 0;
    // Sequence epoch: bumped (under the synchronized state-transition block) every
    // time an open or close sequence is started. Each posted opcode-step lambda
    // captures the epoch at post time and bails if it no longer matches — so a
    // superseded sequence (e.g. a close that started, then a re-open flipped the
    // state) can never land a trailing opcode (16/35 or 0) on the wire after the
    // flip. Without this, rapid signal/target toggling could interleave open/close
    // opcodes and strand the projection physically OPEN while projState==CLOSED
    // (disarming every restore path → gauges blanked with no recovery).
    private volatile int seqEpoch = 0;
    // Terminal latch: set once at shutdown() and NEVER cleared. The epoch guard only
    // invalidates already-posted steps of a SUPERSEDED sequence — it cannot stop a
    // brand-new requestOpen() (which is itself the newest epoch) from starting a
    // fresh open AFTER shutdown's restore already ran and cleared the recovery flags.
    // The BS turn loop keeps ticking during daemon teardown (it's stopped later, in
    // gpuPipeline.stop()), so without this a turn signal could re-enter projection
    // post-restore with boot-recovery disarmed. Once true, requestOpen is a no-op.
    private volatile boolean shuttingDown = false;

    private long lingerCloseMs = DEFAULT_LINGER_CLOSE_MS;
    private long maxProjectionMs = DEFAULT_MAX_PROJECTION_MS;
    // Size-profile opcode sent before 16→35. Selects the cluster projection size:
    // 29=8.8", 30=12.3", 31=10.25" native. The WRONG value gives a wrong-aspect /
    // stretched cluster projection. UCM-tunable (surveillance.clusterSizeProfile) so
    // the correct profile for this cluster can be dialled in live without a rebuild;
    // 0 = skip the size-profile step entirely (just 16→35).
    private int sizeProfileOpcode = OP_SIZE_PROFILE;   // default 30 (current behaviour)
    // Optional OVERRIDE for the on-close restore profile. Normally close re-sends
    // sizeProfileOpcode (the user's "Cluster layout" choice = the car's native size),
    // which automatically returns the gauges to the model's original layout. This
    // override is only for the rare case where the PROJECTION profile must differ
    // from the car's native gauge profile; 0 = use sizeProfileOpcode (the default).
    private int restoreProfileOpcode = 0;

    // When the 90s max cap fires, lock out re-open until the indicator GENUINELY
    // clears (a sustained/forgotten signal would otherwise re-open on the very next
    // 250ms tick — ST_CLOSING falls through requestOpen's guard — defeating the cap
    // and flashing the gauges every 90s instead of restoring them). Cleared on the
    // first indicator-off tick via notifySignalCleared().
    private volatile boolean maxCapLockout = false;
    private final Runnable maxCapTask = () -> { maxCapLockout = true; forceClose("max-cap"); };
    private final Runnable lingerTask = this::maybeLingerClose;

    // ── Sustained holder (map-on-cluster) ───────────────────────────────────────
    // The projection has TWO kinds of consumer:
    //   • TRANSIENT (blind-spot turn-signal): open on signal, linger-close after 8s,
    //     hard max-cap at 90s. This is the original, unchanged behaviour.
    //   • SUSTAINED (the nav map projected onto the cluster): held OPEN for the whole
    //     session until the user/ACC explicitly ends it. While a sustained holder is
    //     active, the two AUTO-close paths (linger + max-cap) are suppressed so the
    //     map doesn't get torn down mid-drive — but EVERY explicit/safety restore
    //     path (forceClose on ACC-off/disable, shutdown on SIGTERM, clearStaleGateAtBoot
    //     on SIGKILL) is UNCHANGED and still fires regardless of holders. The sustained
    //     flag is cleared by releaseSustained() (which then closes if no transient
    //     consumer wants it) and unconditionally by forceClose/shutdown.
    private volatile boolean sustainedHeld = false;

    private ClusterProjectionController() {
        projThread = new android.os.HandlerThread("ClusterProjection");
        projThread.start();
        projHandler = new android.os.Handler(projThread.getLooper());
        watchdogHandler = new android.os.Handler(projThread.getLooper());
    }

    public static ClusterProjectionController getInstance() {
        if (instance == null) {
            synchronized (ClusterProjectionController.class) {
                if (instance == null) instance = new ClusterProjectionController();
            }
        }
        return instance;
    }

    /** Shutdown-hook helper that does NOT construct the singleton. If instance is
     *  null, this daemon never opened a projection (the HandlerThread was never
     *  spawned), so there is nothing to restore — skip entirely. Avoids spawning
     *  the ClusterProjection thread on a head-unit-only daemon's exit. The
     *  defensive UCM flag-clear in the shutdown hook + boot-time
     *  clearStaleGateAtBoot still cover any leaked-flag case independently. */
    public static void shutdownIfActive() {
        ClusterProjectionController i = instance;
        if (i != null) i.shutdown();
    }

    // ── Public API (called from the BS turn loop / pipeline) ────────────────────

    /** Cheap volatile reads for the show-gate in the pipeline. */
    public boolean isOpen()  { return projState == ST_OPEN; }
    public boolean isReady() { return ready && projState == ST_OPEN; }

    /**
     * Request the projection be open (idempotent). Called from the cluster branch
     * of the BS turn loop on signal-on. Cheap from the 250 ms loop: a volatile
     * read + at most one post. Also refreshes the linger timer.
     */
    public void requestOpen() {
        // Terminal: once the daemon is shutting down, NEVER (re)enter projection —
        // shutdown() runs the authoritative restore and clears the recovery flags,
        // and a re-open here would undo it with boot-recovery disarmed.
        if (shuttingDown) return;
        lastSignalMs = System.currentTimeMillis();
        // After a max-cap close, refuse to re-open until the indicator has cycled
        // off at least once (notifySignalCleared). A normal blink reaches off
        // between flashes long before 90s, so this only bites a truly stuck signal.
        if (maxCapLockout) return;
        int st = projState;
        if (st == ST_OPEN || st == ST_OPENING) return;   // already up / coming up
        final int epoch;
        synchronized (this) {
            if (shuttingDown) return;   // re-check under the lock (shutdown may have just set it)
            // Only re-open from a FULLY-CLOSED state. Admitting ST_CLOSING here let a
            // racing reopen (BS turn-trigger / nav-map thread) supersede a safety
            // forceClose (retarget/disable/relayout) mid-close — bumping seqEpoch so
            // the close's OP_REFRESH(0) gauge-restore step bailed on the epoch guard,
            // leaving the gauges blanked until the linger backstop self-healed (~8s).
            // Requiring ST_CLOSED means a still-active turn signal simply re-opens on
            // the next 250ms tick AFTER the close genuinely reaches ST_CLOSED — the
            // restore always completes first. Strengthens the gauge-restore net.
            if (projState != ST_CLOSED) return;
            projState = ST_OPENING;
            ready = false;
            epoch = ++seqEpoch;   // supersede any in-flight close sequence
        }
        loadTuning();
        projHandler.post(() -> doOpenSequence(epoch));
    }

    /**
     * Acquire the projection as a SUSTAINED holder (the nav map). Opens it if not
     * already open (reusing the same open sequence as the transient path) and marks
     * it held so the linger + max-cap AUTO-close paths are suppressed for the
     * duration. Idempotent. Safety restores (forceClose/shutdown/boot) are unaffected
     * and will still tear it down + restore the gauges. No-op while shutting down.
     */
    public void acquireSustained() {
        if (shuttingDown) return;
        sustainedHeld = true;
        // Cancel any pending auto-close left over from a prior transient session.
        watchdogHandler.removeCallbacks(lingerTask);
        watchdogHandler.removeCallbacks(maxCapTask);
        maxCapLockout = false;
        requestOpen();   // opens if closed; no-op if already up
    }

    /**
     * Release the sustained hold (map projection ended). If a transient consumer
     * (blind-spot) still wants the projection right now it stays up and reverts to
     * the normal linger lifecycle; otherwise it force-closes + restores the gauges.
     */
    public void releaseSustained() {
        sustainedHeld = false;
        // If a turn signal is currently active (fresh within the linger window) keep
        // it up and hand back to the transient lifecycle; else close now.
        long sinceSignal = System.currentTimeMillis() - lastSignalMs;
        if (sinceSignal < lingerCloseMs) {
            requestCloseLingered();   // transient takes over; closes after linger
        } else {
            forceClose("sustained-release");
        }
    }

    /** True while the map holds the projection (used to gate BS coexistence). */
    public boolean isSustainedHeld() { return sustainedHeld; }

    /** Bump the signal timestamp (called every tick while a turn signal is active). */
    public void noteSignal() { lastSignalMs = System.currentTimeMillis(); }

    /** Called on the first indicator-off tick. Lifts a max-cap lockout so a fresh
     *  indicator after the cap can re-open normally. No-op if not locked out. */
    public void notifySignalCleared() { maxCapLockout = false; }

    /**
     * Schedule a lingered close: after {@link #lingerCloseMs} of no fresh signal,
     * restore the gauges. Re-arming is cheap (removeCallbacks + postDelayed).
     */
    public void requestCloseLingered() {
        watchdogHandler.removeCallbacks(lingerTask);
        watchdogHandler.postDelayed(lingerTask, lingerCloseMs);
    }

    private void maybeLingerClose() {
        // Sustained holder (map) keeps the projection open — never linger-close.
        if (sustainedHeld) return;
        long since = System.currentTimeMillis() - lastSignalMs;
        if (since >= lingerCloseMs - 50) {
            forceClose("linger");
        } else {
            // Signal came back inside the window — re-arm for the remainder.
            watchdogHandler.postDelayed(lingerTask, lingerCloseMs - since);
        }
    }

    /**
     * Hard close + gauge restore. Idempotent and harmless when already closed.
     * Clears the UCM gate flags FIRST so even if the close opcodes fail, a respawn
     * won't see a leaked "projection active" flag. Public so disable / disarm /
     * target-flip / errors can all force the gauges back.
     */
    public void forceClose(String reason) {
        // An explicit/safety close always drops the sustained hold — the map's
        // projection is being torn down; the holder must not linger and re-suppress.
        sustainedHeld = false;
        try { clearGateFlags(); } catch (Throwable ignored) {}
        watchdogHandler.removeCallbacks(maxCapTask);
        watchdogHandler.removeCallbacks(lingerTask);
        final int epoch;
        synchronized (this) {
            if (projState == ST_CLOSED) return;
            projState = ST_CLOSING;
            ready = false;
            epoch = ++seqEpoch;   // supersede any in-flight open sequence
        }
        logger.info("forceClose(" + reason + ")");
        // Tell the pipeline to hide the BS layer + drop the render gate NOW (every
        // close path goes through here: linger, max-cap, disarm, retarget). Without
        // this, PASS 1C keeps drawing into the layer after the projection's display
        // is gone → GPU pinned high after the turn signal stops.
        notifyPipelineClosed();
        projHandler.post(() -> doCloseSequence(epoch));
    }

    // ── Open / close sequences (run on projThread) ──────────────────────────────

    private void doOpenSequence(int epoch) {
        try {
            if (shuttingDown) return;        // terminal — never issue open opcodes during teardown
            if (epoch != seqEpoch) return;   // superseded before we started
            final int sizeOp = sizeProfileOpcode;
            logger.info("projection open: " + (sizeOp != 0 ? sizeOp + "→" : "") + OP_FULLSCREEN_ON + "→" + OP_DI4_MODE);
            // Size-profile step is optional (0 = skip). Wrong profile = wrong cluster aspect.
            if (sizeOp != 0 && !sendInfo(sizeOp)) { forceClose("open-size-failed"); return; }
            // First gap: short — size profile settles fast (0 if we skipped it).
            final long firstGap = (sizeOp != 0) ? sizeStepMs : 0;
            // Second gap: the critical one — 16 must establish the surface before 35.
            final long openGap = openStepDelayMs;
            projHandler.postDelayed(() -> {
                if (epoch != seqEpoch) return;   // a close/re-open superseded us
                if (!sendInfo(OP_FULLSCREEN_ON)) { forceClose("open-fullscreen-failed"); return; }
                projHandler.postDelayed(() -> {
                    if (epoch != seqEpoch) return;
                    if (!sendInfo(OP_DI4_MODE)) { forceClose("open-di4-failed"); return; }
                    pollReady(0, epoch);
                }, openGap);
            }, firstGap);
        } catch (Throwable t) {
            logger.warn("doOpenSequence error: " + t.getMessage());
            forceClose("open-exception");
        }
    }

    private void pollReady(long elapsed, int epoch) {
        if (shuttingDown) return;               // terminal — abandon a pending open
        if (epoch != seqEpoch) return;          // superseded by a newer sequence
        if (projState != ST_OPENING) return;    // cancelled / closed underneath us
        // Fast path: DisplayManager already shows the cluster display. (Often null
        // from the daemon's stale cache — see READY_SETTLE_MS — so this is a bonus,
        // not the contract.)
        if (displayPresent()) { commitReady(epoch, "display present"); return; }
        // Settle path: the display materializes ~280ms after OP_DI4_MODE but the
        // daemon's DisplayManager cache may never report it. Once past the settle
        // window, commit ready anyway (compositing is the proven path). NEVER
        // force-close here — a timeout-close caused the open/close thrash that
        // reflowed the head-unit. If the fission display is STILL absent at settle,
        // the model likely doesn't support cluster projection (e.g. a small
        // Atto-3-class non-fission cluster) — log it loudly so "nothing happens" on
        // those models is diagnosable; we still commit (harmless — the layer just
        // composites onto nothing).
        if (elapsed >= READY_SETTLE_MS) {
            boolean present = displayPresent();
            if (!present) {
                logger.warn("cluster projection: fission display NOT present at settle — "
                    + "model may not support projection (small/non-fission cluster?). "
                    + "Committing anyway; if no video, this trim is unsupported.");
            }
            commitReady(epoch, present ? "settle+present" : "settle+ABSENT");
            return;
        }
        projHandler.postDelayed(() -> pollReady(elapsed + READY_POLL_MS, epoch), READY_POLL_MS);
    }

    /** Atomically claim ST_OPEN (re-checking epoch+state under the lock so a racing
     *  forceClose wins cleanly), then arm the max-cap and notify the pipeline to
     *  retarget the layer onto the cluster. */
    private void commitReady(int epoch, String why) {
        final long activeUntil;
        synchronized (this) {
            if (epoch != seqEpoch || projState != ST_OPENING) return;
            openedAtMs = System.currentTimeMillis();
            ready = true;
            projState = ST_OPEN;
            activeUntil = openedAtMs + maxProjectionMs;
        }
        writeGateFlags(activeUntil);
        watchdogHandler.removeCallbacks(maxCapTask);
        // Suppress the hard max-cap auto-close while the map holds the projection;
        // the map is meant to stay up for the whole drive. The gate flag still
        // carries activeUntil for SIGKILL recovery, and every EXPLICIT restore
        // (ACC-off/disable/release/SIGTERM) still fires — only the timed auto-tear
        // is suppressed. Transient (blind-spot) sessions keep the cap.
        if (!sustainedHeld) {
            watchdogHandler.postDelayed(maxCapTask, maxProjectionMs);
        }
        logger.info("projection OPEN + ready (" + why + (sustainedHeld ? ", sustained" : "") + ")");
        notifyPipelineReady();
    }

    private void doCloseSequence(int epoch) {
        try {
            if (epoch != seqEpoch) return;   // superseded by a newer open before we started
            logger.info("projection close: " + OP_CLOSE + "→" + OP_REFRESH);
            sendInfo(OP_CLOSE);   // best-effort; even if it returns false we still refresh
            projHandler.postDelayed(() -> {
                // CRITICAL: the epoch re-check AND the projState write must be atomic
                // w.r.t. requestOpen (which transitions + bumps seqEpoch under the same
                // lock). Otherwise a re-open that wins between an unlocked check and the
                // ST_CLOSED write would be CLOBBERED back to CLOSED — stranding the
                // projection physically open with every restore path disarmed. Decide
                // superseded-ness and claim ST_CLOSED in ONE locked section.
                final boolean superseded;
                synchronized (this) {
                    superseded = (epoch != seqEpoch);
                    if (!superseded) { projState = ST_CLOSED; ready = false; }
                }
                if (superseded) return;   // a re-open won under the lock — leave its
                                          // ST_OPENING + fresh open sequence intact, and
                                          // do NOT send OP_REFRESH (it would land after
                                          // the open's opcodes and strand the projection).
                sendInfo(OP_REFRESH);
                // Re-assert the car's NATIVE size profile so the gauges return to the
                // model's ORIGINAL layout. The open's size-profile opcode is a
                // PERSISTENT layout switch that 18→0 does NOT undo; on a model whose
                // native cluster differs from a generic projection profile, the gauges
                // would otherwise come back in the wrong layout. The configured profile
                // (sizeProfileOpcode, = the user's "Cluster layout" dropdown choice) IS
                // the car's native size, so re-sending it on close is the automatic,
                // model-correct restore — no per-model opt-in needed. (clusterRestoreProfile
                // overrides only if a model's projection profile must differ from native.)
                final int rp = (restoreProfileOpcode != 0) ? restoreProfileOpcode : sizeProfileOpcode;
                if (rp != 0) {
                    projHandler.postDelayed(() -> sendInfo(rp), CLOSE_STEP_DELAY_MS);
                }
                watchdogHandler.removeCallbacks(maxCapTask);
                // Authoritative re-clear AFTER the restore. forceClose() clears the
                // gate flags up-front (SIGKILL-recovery contract), but a pollReady
                // success on another thread can re-assert them in the tiny window
                // after it dropped the lock and before its writeGateFlags — leaving
                // the recovery flag stuck SET with the projection actually closed.
                // This close runnable is serialized behind that pollReady on
                // projThread, so a clear here is guaranteed to win. Read-guarded, so
                // it's a free no-op when forceClose's up-front clear already stuck.
                try { clearGateFlags(); } catch (Throwable ignored) {}
                logger.info("projection CLOSED + gauges restore issued");
            }, CLOSE_STEP_DELAY_MS);
        } catch (Throwable t) {
            logger.warn("doCloseSequence error: " + t.getMessage());
            projState = ST_CLOSED;
            ready = false;
        }
    }

    // ── SIGKILL / SIGTERM recovery ──────────────────────────────────────────────

    /**
     * Called from {@code CameraDaemon.main()} top. If a prior daemon was SIGKILL'd
     * mid-projection it could not run its shutdown hook, leaving the gauges blanked.
     * The leaked UCM gate flag tells us to blind-fire the close sequence (18→0) on a
     * throwaway thread — the opcode path is stateless, so it restores the gauges
     * with no live display handle, and is harmless if the projection was already
     * closed. Static so it needs no live instance.
     */
    public static void clearStaleGateAtBoot() {
        boolean leaked;
        long activeUntil;
        try {
            org.json.JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            leaked = s != null && s.optBoolean(GATE_FORCE_STOP, false);
            activeUntil = s != null ? s.optLong(GATE_ACTIVE_UNTIL, 0L) : 0L;
        } catch (Throwable t) {
            return;
        }
        if (!leaked && activeUntil <= 0L) return;
        logger.warn("stale cluster-projection gate at boot (forceStop=" + leaked
                + ", activeUntil=" + activeUntil + ") — restoring gauges");
        new Thread(() -> {
            try {
                sendInfoStatic(OP_CLOSE);
                try { Thread.sleep(CLOSE_STEP_DELAY_MS); } catch (InterruptedException ignored) {}
                sendInfoStatic(OP_REFRESH);
            } catch (Throwable t) {
                logger.warn("clearStaleGateAtBoot restore error: " + t.getMessage());
            } finally {
                try { clearGateFlagsStatic(); } catch (Throwable ignored) {}
            }
        }, "ClusterProjBootRestore").start();
    }

    /**
     * Called from the daemon shutdown hook (SIGTERM / normal exit). Force-close
     * SYNCHRONOUSLY so 18→0 lands before the VM dies, blocking on a latch.
     *
     * <p>Two safety properties vs a naive close:
     * <ul>
     *   <li>Bumps seqEpoch + sets ST_CLOSING (like {@link #forceClose}) so any
     *       in-flight open-sequence step lambda bails on the epoch mismatch and
     *       cannot re-issue OP_FULLSCREEN_ON/OP_DI4_MODE AFTER our restore.</li>
     *   <li>Clears the UCM gate flags ONLY after the full 18→0 has actually been
     *       issued (inside the close runnable), NOT up-front. If the latch times
     *       out before the close completes (a wedged/busy projThread), the flags
     *       stay SET, so the next respawn's {@link #clearStaleGateAtBoot} re-fires
     *       18→0 — boot recovery is preserved as the backstop. (forceClose can
     *       safely clear up-front because its close runs on a healthy thread; a
     *       shutdown close may never land before VM death.)</li>
     * </ul>
     * The latch budget covers the worst case (2× ~2s shell waitFor + 1s sleep).
     */
    public void shutdown() {
        watchdogHandler.removeCallbacks(maxCapTask);
        watchdogHandler.removeCallbacks(lingerTask);
        sustainedHeld = false;   // teardown drops the hold; restore proceeds normally
        synchronized (this) {
            shuttingDown = true;   // terminal — blocks any future requestOpen re-entry
            if (projState == ST_CLOSED) {
                // Nothing open. Best-effort clear (read-guarded no-op if already clear).
                try { clearGateFlags(); } catch (Throwable ignored) {}
                return;
            }
            projState = ST_CLOSING;
            ready = false;
            ++seqEpoch;   // supersede any in-flight open sequence
        }
        final CountDownLatch latch = new CountDownLatch(1);
        projHandler.post(() -> {
            try {
                sendInfo(OP_CLOSE);
                try { Thread.sleep(CLOSE_STEP_DELAY_MS); } catch (InterruptedException ignored) {}
                sendInfo(OP_REFRESH);
                // Clear the recovery gate ONLY now that the full restore issued.
                try { clearGateFlags(); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {
            } finally {
                projState = ST_CLOSED;
                ready = false;
                latch.countDown();
            }
        });
        // Worst case: OP_CLOSE shell ~2s + 1s sleep + OP_REFRESH shell ~2s ≈ 5s.
        try { latch.await(5500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
    }

    // ── UCM gate flags ──────────────────────────────────────────────────────────

    private void writeGateFlags(long activeUntilMs) {
        Map<String, Object> m = new HashMap<>();
        m.put(GATE_FORCE_STOP, true);
        m.put(GATE_ACTIVE_UNTIL, activeUntilMs);
        try { UnifiedConfigManager.updateValues("surveillance", m); } catch (Throwable ignored) {}
    }

    private void clearGateFlags() { clearGateFlagsStatic(); }

    private static void clearGateFlagsStatic() {
        // Read-before-write: skip the full-config disk rewrite when the flags are
        // already clear. This makes the common cases free — the head-unit-default
        // disableBlindSpot() forceClose("bs-disabled") (projection never opened) and
        // any forceClose that early-returns at ST_CLOSED — and is the single
        // authoritative clear (forceClose runs it BEFORE the close opcodes for
        // SIGKILL recovery; doCloseSequence no longer re-clears).
        try {
            org.json.JSONObject s = UnifiedConfigManager.getSurveillance();
            if (s != null && !s.optBoolean(GATE_FORCE_STOP, false)
                    && s.optLong(GATE_ACTIVE_UNTIL, 0L) == 0L) {
                return;   // already clear — no disk write
            }
        } catch (Throwable ignored) {}
        Map<String, Object> m = new HashMap<>();
        m.put(GATE_FORCE_STOP, false);
        m.put(GATE_ACTIVE_UNTIL, 0L);
        try { UnifiedConfigManager.updateValues("surveillance", m); } catch (Throwable ignored) {}
    }

    private void loadTuning() {
        try {
            org.json.JSONObject s = UnifiedConfigManager.getSurveillance();
            if (s != null) {
                long l = s.optLong("clusterLingerMs", DEFAULT_LINGER_CLOSE_MS);
                long mx = s.optLong("clusterMaxProjectionMs", DEFAULT_MAX_PROJECTION_MS);
                lingerCloseMs = Math.max(2000, Math.min(l, 60000));
                maxProjectionMs = Math.max(10000, Math.min(mx, 300000));
                // Size-profile opcode (29=8.8" / 30=12.3" / 31=10.25" / 0=skip).
                // Primary source is the blindspot section (where the web UI writes it
                // via the layout dropdown, alongside target); fall back to the
                // surveillance key (live-tuning) then the default.
                int sizeFromSurv = s.optInt("clusterSizeProfile", OP_SIZE_PROFILE);
                org.json.JSONObject bs = UnifiedConfigManager.getBlindSpot();
                sizeProfileOpcode = (bs != null) ? bs.optInt("clusterSizeProfile", sizeFromSurv) : sizeFromSurv;
                // Native profile to restore on close (29/30/31), for models whose
                // native cluster size differs from the projection profile. 0 = none.
                int rpSurv = s.optInt("clusterRestoreProfile", 0);
                restoreProfileOpcode = (bs != null) ? bs.optInt("clusterRestoreProfile", rpSurv) : rpSurv;
                // Open-sequence inter-step delay (first-load latency). Clamp 100..3000.
                long os = s.optLong("clusterOpenStepMs", DEFAULT_OPEN_STEP_DELAY_MS);
                openStepDelayMs = Math.max(100, Math.min(os, 3000));
                long ss = s.optLong("clusterSizeStepMs", DEFAULT_SIZE_STEP_MS);
                sizeStepMs = Math.max(0, Math.min(ss, 3000));
            }
        } catch (Throwable ignored) {}
    }

    // ── Cluster display presence ────────────────────────────────────────────────

    private boolean displayPresent() {
        try {
            Context ctx = resolveContext();
            if (ctx != null) {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    if (dm.getDisplay(CLUSTER_DISPLAY_ID) != null) return true;
                    android.view.Display[] ds = dm.getDisplays();
                    if (ds != null) {
                        for (android.view.Display d : ds) {
                            String n = d.getName();
                            if (n != null && n.toLowerCase(java.util.Locale.US).contains("fission")) return true;
                        }
                    }
                }
            }
            // The daemon's DisplayManager cache is unreliable for the foreign uid-1000
            // fission display — fall back to the AUTHORITATIVE `dumpsys display` (the
            // same source clusterLayerStack uses; it reflects reality even when the
            // cache is stale). This also tells us, on a model where projection is
            // unsupported (e.g. a small non-fission cluster), that the display truly
            // never appears — see clusterDisplayPresentViaDumpsys + the ready logging.
            return clusterDisplayPresentViaDumpsys();
        } catch (Throwable ignored) {}
        return false;
    }

    /** Authoritative "is the fission cluster display live" via `dumpsys display`.
     *  Returns false when the model never materializes a fission display (e.g. an
     *  Atto-3-class small cluster that may not support projection at all). */
    private boolean clusterDisplayPresentViaDumpsys() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("fission") && line.contains("type VIRTUAL") && line.contains("state ON")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
        return false;
    }

    // ── Pipeline ready callback (reflection, avoids surveillance→pipeline import cycle) ──

    private void notifyPipelineReady() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Method getPipe = cd.getMethod("getGpuPipeline");
            Object pipe = getPipe.invoke(null);
            if (pipe != null) {
                Method m = pipe.getClass().getMethod("onClusterProjectionReady");
                m.invoke(pipe);
            }
        } catch (Throwable t) {
            logger.debug("notifyPipelineReady: " + t.getMessage());
        }
    }

    private void notifyPipelineClosed() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Method getPipe = cd.getMethod("getGpuPipeline");
            Object pipe = getPipe.invoke(null);
            if (pipe != null) {
                Method m = pipe.getClass().getMethod("onClusterProjectionClosed");
                m.invoke(pipe);
            }
        } catch (Throwable t) {
            logger.debug("notifyPipelineClosed: " + t.getMessage());
        }
    }

    // ── AutoContainer sendInfo ──────────────────────────────────────────────────

    /**
     * Send a projection opcode. Primary path: the IAutoContainer binder via
     * ServiceManager reflection. Fallback (the form proven on-device): the shell
     * {@code service call AutoContainer 2 i32 1000 i32 <op> s16 ''} (transaction
     * 2 = sendInfo). Returns false if BOTH fail (caller treats false as fatal).
     */
    private boolean sendInfo(int op) { return sendInfoStatic(op); }

    private static boolean sendInfoStatic(int op) {
        if (sendInfoBinder(op)) return true;
        return sendInfoShell(op);
    }

    private static boolean sendInfoBinder(int op) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "AutoContainer");
            if (binder == null) return false;
            Class<?> stub = Class.forName("android.os.IAutoContainer$Stub");
            Object iface = stub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binder);
            if (iface == null) return false;
            Method send = iface.getClass().getMethod("sendInfo", int.class, int.class, String.class);
            send.invoke(iface, CLUSTER_TYPE, op, "");
            return true;
        } catch (Throwable t) {
            logger.debug("sendInfoBinder(" + op + ") failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean sendInfoShell(int op) {
        Process p = null;
        try {
            // service call AutoContainer 2 (=TRANSACTION_sendInfo) i32 1000 i32 <op> s16 ""
            String[] cmd = {"service", "call", "AutoContainer", "2",
                    "i32", String.valueOf(CLUSTER_TYPE), "i32", String.valueOf(op), "s16", ""};
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            boolean done = p.waitFor(2, TimeUnit.SECONDS);
            if (!done) { p.destroy(); return false; }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("sendInfoShell(" + op + ") failed: " + t.getMessage());
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static Context resolveContext() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Object ctx = cd.getMethod("getAppContext").invoke(null);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {}
        return null;
    }
}
