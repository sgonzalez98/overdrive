package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Daemon-owned SurfaceControl buffer layer for the native blind-spot overlay.
 *
 * <p>The blind-spot stitched view (GpuStreamScaler view 7/8, libod) is rendered
 * by the daemon's GL pipeline (PanoramicCameraGpu PASS 1C) straight into this
 * layer's {@link Surface} via {@code EGLCore.createWindowSurface} — GPU → screen
 * with NO encoder, NO WebSocket, NO MediaCodec decoder. Everything here runs in
 * the daemon (UID 2000), which owns the GL context and the hidden-API
 * SurfaceControl reflection (validated on this firmware by BsSurfaceControlSpike:
 * non-fullscreen layers composite, EGL-on-SurfaceControl works, and
 * setGeometry/setPosition exist).
 *
 * <p>Reflection mirrors {@link ScreenDeterrent}'s proven path. SurfaceControl
 * layers have no InputChannel, so position/size come from config (UCM
 * blindspot.geometry) via {@link #setGeometry}, not finger drag.
 *
 * <p>Threading: {@link #create}/{@link #release} touch the SurfaceControl handle
 * (cheap, any thread — SurfaceFlinger serializes transactions). The returned
 * {@link Surface} must be wrapped in an EGLSurface ON THE GL THREAD by the caller
 * (EGL surfaces are GL-thread-bound). {@link #setGeometry} applies a transaction
 * (no re-render) and is safe from any thread.
 */
public final class BsNativeLayer {

    private static final String TAG = "BsNativeLayer";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Above all app windows / system chrome, so the safety overlay is never occluded
    // while driving — but ONE BELOW the cluster speed badge (ClusterSpeedOverlay.Z_ORDER
    // = Integer.MAX_VALUE) so the small centre-left speed readout stays visible on top of
    // the BS card on the cluster (both composite on the same cluster layerStack). Still
    // safely above the map/content. ScreenDeterrent uses Integer.MAX_VALUE too but never
    // contends: it only fires ACC-OFF (parked/sentry) on the head-unit stack, while the
    // blind-spot overlay is ACC-ON + signaling on the cluster stack.
    private static final int Z_ORDER = Integer.MAX_VALUE - 1;

    private final int bufferW;
    private final int bufferH;
    private Object surfaceControl;     // android.view.SurfaceControl (reflected)
    private Surface surface;           // wraps surfaceControl, fed to EGL
    private volatile boolean shown = false;

    // Target display's layerStack. 0 = head-unit (the shipping default — the layer
    // composites onto the primary/center screen exactly as before). 1 = the driver
    // cluster (the OEM "fission" PRESENTATION VirtualDisplay, layerStack 1), reached
    // only while an OEM cluster projection is open. CRITICAL: when this is 0 we emit
    // NO setLayerStack op at all, so the head-unit transaction is byte-for-byte the
    // pre-feature behaviour. setLayerStack(SurfaceControl,int) was validated live on
    // this firmware (a UID-2000 layer tagged layerStack=1 composited onto the cluster).
    private volatile int layerStack = 0;

    public BsNativeLayer(int bufferW, int bufferH) {
        this.bufferW = bufferW;
        this.bufferH = bufferH;
    }

    /**
     * Retarget the layer to a display by its layerStack (0 = head-unit, 1 = cluster).
     * Cheap no-op if unchanged. If the layer already exists and is shown, the stack
     * change is re-asserted immediately via a one-shot transaction so a mid-session
     * flip lands without waiting for the next setGeometry/show. The actual placement
     * (and where it composites) is still governed by setGeometry's dest rect.
     */
    public synchronized void setLayerStack(int stack) {
        // Defense-in-depth: a negative stack (e.g. the STACK_UNRESOLVED sentinel) must
        // NEVER be tagged onto the layer — setLayerStack(sc, -1) orphans it onto a
        // dead stack = black. Callers should gate on STACK_UNRESOLVED; ignore here too.
        if (stack < 0) {
            logger.warn("setLayerStack: ignoring negative stack " + stack);
            return;
        }
        if (stack == this.layerStack) return;
        this.layerStack = stack;
        if (surfaceControl != null) {
            // Re-assert stack on the live handle. Re-uses applyGeometry's transaction
            // path with the current visibility so we don't flash or move the card.
            applyLayerStack(surfaceControl, stack);
        }
    }

    /** Current target layerStack (0 head-unit / 1 cluster). */
    public synchronized int getLayerStack() { return layerStack; }

    /** Create the buffer layer (does NOT show it yet). Returns false on failure. */
    public synchronized boolean create() {
        if (surfaceControl != null) return true;
        surfaceControl = createBufferLayer("BlindSpot", bufferW, bufferH);
        if (surfaceControl == null) {
            logger.warn("create: SurfaceControl buffer layer creation failed");
            return false;
        }
        try {
            surface = new Surface((android.view.SurfaceControl) surfaceControl);
        } catch (Throwable t) {
            logger.warn("create: new Surface(SurfaceControl) failed: " + t.getMessage());
            releaseScOnly();
            return false;
        }
        logger.info("BS native layer created (" + bufferW + "x" + bufferH + ")");
        return true;
    }

    /** The Android Surface to render into (wrap in EGLSurface on the GL thread). */
    public synchronized Surface getSurface() { return surface; }

    public synchronized boolean isCreated() { return surfaceControl != null; }
    public boolean isShown() { return shown; }

    /**
     * Place the layer on screen at (x,y) sized w×h at the BS z-order, and show it.
     * The buffer is always {@link #bufferW}×{@link #bufferH}; setGeometry scales it
     * to the on-screen dest rect, so resize is a transaction — no GL re-init.
     */
    public synchronized void setGeometry(int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        applyGeometry(surfaceControl, x, y, w, h, Z_ORDER, true, bufferW, bufferH, layerStack);
        shown = true;
    }

    /** Position the layer WITHOUT showing it (single transaction, show=false).
     *  Used at enable to arm the geometry while the card is still hidden, avoiding
     *  a show-then-hide one-frame flash of an unrendered layer. */
    public synchronized void setGeometryHidden(int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        applyGeometry(surfaceControl, x, y, w, h, Z_ORDER, false, bufferW, bufferH, layerStack);
        // shown stays false
    }

    /** Hide the layer (keeps it allocated for a fast re-show). */
    public synchronized void hide() {
        if (surfaceControl == null || !shown) return;
        applyVisibility(surfaceControl, false);
        shown = false;
    }

    /** Re-show at the last geometry (cheap transaction). */
    public synchronized void show() {
        if (surfaceControl == null || shown) return;
        applyVisibility(surfaceControl, true);
        shown = true;
    }

    /** Hide, reparent-null, and release the layer + Surface. */
    public synchronized void release() {
        if (surface != null) {
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        if (surfaceControl != null) {
            releaseSurfaceControl(surfaceControl);
            surfaceControl = null;
        }
        shown = false;
    }

    private void releaseScOnly() {
        if (surfaceControl != null) {
            releaseSurfaceControl(surfaceControl);
            surfaceControl = null;
        }
    }

    /** Full-panel size for sizing/clamping geometry. */
    public static Point displaySize(Context ctx) {
        Point p = new Point(1920, 1080);
        try {
            android.view.WindowManager wm =
                (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealSize(p);
        } catch (Throwable t) {
            logger.debug("displaySize failed: " + t.getMessage());
        }
        return p;
    }

    // ── SurfaceControl reflection (mirrors ScreenDeterrent's proven path) ──────

    private static Object createBufferLayer(String name, int w, int h) {
        try {
            Class<?> b = Class.forName("android.view.SurfaceControl$Builder");
            Object builder = b.getDeclaredConstructor().newInstance();
            b.getMethod("setName", String.class).invoke(builder, name);
            b.getMethod("setBufferSize", int.class, int.class).invoke(builder, w, h);
            // CRITICAL: SurfaceControl.Builder defaults mFormat = PixelFormat.OPAQUE
            // on this firmware (Android 10), which builds an RGBx_8888 layer that
            // SurfaceFlinger composites with isOpaque=1 — DISCARDING the alpha
            // channel. Our card has TRANSPARENT rounded corners + a transparent
            // margin band + transparent regions the projection doesn't cover (the
            // shader emits premultiplied alpha=0 there), so with the opaque default
            // every alpha<1 pixel composites as solid BLACK — the "black rectangle"
            // around/below the video. PROVEN on-device: dumpsys SurfaceFlinger
            // showed `defaultPixelFormat=RGBx_8888, isOpaque=1` while the GPU buffer
            // was RGBA_8888. Fix: force the layer format to RGBA_8888 (==1) so the
            // alpha channel is honored. setOpaque(false) alone does NOT fix it
            // because isOpaque is derived from the opaque FORMAT.
            try { b.getMethod("setFormat", int.class).invoke(builder, android.graphics.PixelFormat.RGBA_8888); } catch (NoSuchMethodException ignored) {}
            // Belt-and-braces: also clear the opaque flag.
            try { b.getMethod("setOpaque", boolean.class).invoke(builder, false); } catch (NoSuchMethodException ignored) {}
            return b.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            logger.warn("createBufferLayer failed: " + t.getMessage());
            return null;
        }
    }

    private static void applyGeometry(Object sc, int x, int y, int w, int h, int z, boolean show,
                                      int bufW, int bufH, int layerStack) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sc, z); } catch (Throwable ignored) {}
            // Retarget to the cluster's layerStack ONLY when non-zero. When 0 (head-unit
            // default) we emit nothing here, so the transaction is byte-for-byte the
            // pre-feature head-unit path. setLayerStack(SurfaceControl,int) proven live.
            if (layerStack != 0) {
                try { txCls.getMethod("setLayerStack", scCls, int.class).invoke(tx, sc, layerStack); } catch (Throwable ignored) {}
            }
            try { txCls.getMethod("setAlpha", scCls, float.class).invoke(tx, sc, 1.0f); } catch (Throwable ignored) {}
            // setGeometry(sc, sourceCrop, destFrame, rotation) — validated present
            // on this firmware. Scales the fixed bufW×bufH buffer into the dest rect.
            boolean geom = false;
            try {
                Rect src = new Rect(0, 0, bufW, bufH);
                Rect dst = new Rect(x, y, x + w, y + h);
                txCls.getMethod("setGeometry", scCls, Rect.class, Rect.class, int.class)
                        .invoke(tx, sc, src, dst, 0);
                geom = true;
            } catch (Throwable ignored) {}
            if (!geom) {
                // Fallback: position + scale via setPosition + setMatrix.
                try {
                    txCls.getMethod("setPosition", scCls, float.class, float.class)
                            .invoke(tx, sc, (float) x, (float) y);
                } catch (Throwable ignored) {}
                try {
                    float sx = (float) w / (float) Math.max(1, bufW);
                    float sy = (float) h / (float) Math.max(1, bufH);
                    txCls.getMethod("setMatrix", scCls, float.class, float.class, float.class, float.class)
                            .invoke(tx, sc, sx, 0f, 0f, sy);
                } catch (Throwable ignored) {}
            }
            if (show) try { txCls.getMethod("show", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("applyGeometry failed: " + t.getMessage());
        }
    }

    private static void applyVisibility(Object sc, boolean show) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            String m = show ? "show" : "hide";
            try { txCls.getMethod(m, scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("applyVisibility failed: " + t.getMessage());
        }
    }

    /** One-shot transaction to (re)assign the layer's layerStack on the live handle.
     *  Used by {@link #setLayerStack} for a mid-session target flip. A no-op stack of
     *  0 still issues the call to MOVE the layer back to the head-unit if it was on
     *  the cluster — callers only invoke this when the value actually changed. */
    private static void applyLayerStack(Object sc, int layerStack) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayerStack", scCls, int.class).invoke(tx, sc, layerStack); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("applyLayerStack failed: " + t.getMessage());
        }
    }

    /** Real size of the driver-cluster display (the OEM "fission" PRESENTATION
     *  VirtualDisplay, layerStack 1, ~1920×720). Only valid while an OEM cluster
     *  projection is open — call AFTER projection-ready. Enumerates DisplayManager
     *  rather than WindowManager.getDefaultDisplay (which only ever returns the
     *  head-unit). Falls back to the known fixed 1920×720 if the display can't be
     *  read. Selection order: name contains "fission" → displayId==1 → a non-default
     *  PRESENTATION display. */
    public static Point clusterDisplaySize(Context ctx) {
        Point p = new Point(1920, 720);
        try {
            android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return p;
            android.view.Display chosen = null;
            android.view.Display[] displays = dm.getDisplays();
            if (displays != null) {
                // 1) name contains "fission"
                for (android.view.Display d : displays) {
                    String n = d.getName();
                    if (n != null && n.toLowerCase(java.util.Locale.US).contains("fission")) { chosen = d; break; }
                }
                // 2) displayId == 1
                if (chosen == null) {
                    for (android.view.Display d : displays) {
                        if (d.getDisplayId() == 1) { chosen = d; break; }
                    }
                }
                // 3) a non-default PRESENTATION display
                if (chosen == null) {
                    for (android.view.Display d : displays) {
                        if (d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY
                                && (d.getFlags() & android.view.Display.FLAG_PRESENTATION) != 0) { chosen = d; break; }
                    }
                }
            }
            if (chosen != null) {
                Point got = new Point();
                chosen.getRealSize(got);
                if (got.x > 0 && got.y > 0) return got;
                // Some A10 builds return 0×0 for getRealSize on a non-default Display
                // without a display-bound Context — retry via a display Context.
                try {
                    Context dctx = ctx.createDisplayContext(chosen);
                    android.view.WindowManager wm =
                        (android.view.WindowManager) dctx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        Point got2 = new Point();
                        wm.getDefaultDisplay().getRealSize(got2);
                        if (got2.x > 0 && got2.y > 0) return got2;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            logger.debug("clusterDisplaySize failed: " + t.getMessage());
        }
        // DisplayManager couldn't surface the fission display (its cache misses the
        // foreign uid-1000 display on many models). Before giving up to the fixed
        // 1920×720, parse the AUTHORITATIVE real W×H from the fission display's own
        // `dumpsys display` block (same source resolveFissionDisplay uses for the id /
        // layerStack — it reflects reality even when the DisplayManager cache is stale).
        // This is what stops a non-Seal cluster (real panel ≠ 1920×720) from silently
        // snapping to the fallback and mis-sizing / under-rendering the projection.
        Point fromDump = clusterDisplaySizeViaDumpsys();
        if (fromDump != null && fromDump.x > 0 && fromDump.y > 0) {
            logger.info("clusterDisplaySize: resolved " + fromDump.x + "x" + fromDump.y
                    + " from dumpsys (DisplayManager cache missed the fission display)");
            return fromDump;
        }
        // Only now fall back to the fixed 1920×720. Warn loudly: on a model whose real
        // cluster panel differs AND whose dumpsys layout we couldn't parse, this
        // mis-sizes the projection.
        logger.warn("clusterDisplaySize: fission panel not found via DisplayManager OR "
                + "dumpsys — using fixed 1920x720 fallback (may mis-size on non-Seal clusters)");
        return p;
    }

    /**
     * Parse the fission cluster display's REAL physical resolution from
     * {@code dumpsys display}, robust to per-model output layout. Returns null if no
     * fission block with a parseable {@code W x H} is found.
     *
     * <p>The logical-display DisplayInfo line for the fission display inlines its real
     * size, e.g.
     *   {@code DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", real 1920 x 720, ...}}
     * or {@code ... 1920 x 720, ...} / {@code ... 1920x720 ...}. We scan for a line
     * containing {@code "fission"} and extract the first {@code <W> x <H>} (or
     * {@code <W>x<H>}) integer pair on it. Same uid-2000/shell dumpsys access + same
     * "fission" keying as {@link #resolveFissionDisplay()}, so it works wherever that
     * does. Bounds-checked (1..8192) so a stray small pair (e.g. a density "1 x 1")
     * can't win. Best-effort: any failure returns null and the caller uses its fallback.
     */
    private static Point clusterDisplaySizeViaDumpsys() {
        Process proc = null;
        try {
            proc = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()));
            // <W> x <H> with optional spaces around the 'x' (e.g. "1920 x 720" or "1920x720").
            java.util.regex.Pattern dim =
                    java.util.regex.Pattern.compile("(\\d{3,4})\\s*[xX]\\s*(\\d{3,4})");
            String line;
            Point best = null;
            while ((line = r.readLine()) != null) {
                if (!line.toLowerCase(java.util.Locale.US).contains("fission")) continue;
                java.util.regex.Matcher mt = dim.matcher(line);
                while (mt.find()) {
                    int w = Integer.parseInt(mt.group(1));
                    int h = Integer.parseInt(mt.group(2));
                    // Plausible panel dimensions only; prefer the LARGEST pair on the
                    // line (the real resolution, not an inset/density artifact).
                    if (w >= 200 && w <= 8192 && h >= 200 && h <= 8192) {
                        if (best == null || (long) w * h > (long) best.x * best.y) {
                            best = new Point(w, h);
                        }
                    }
                }
                if (best != null) {
                    logger.info("clusterDisplaySizeViaDumpsys raw: " + line.trim());
                    return best;
                }
            }
        } catch (Throwable t) {
            logger.debug("clusterDisplaySizeViaDumpsys failed: " + t.getMessage());
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Resolved cluster (fission) display descriptor from {@code dumpsys display}.
     *  displayId / layerStack are -1 when not parsed. */
    public static final class FissionDisplay {
        public final int displayId;
        public final int layerStack;
        FissionDisplay(int displayId, int layerStack) {
            this.displayId = displayId;
            this.layerStack = layerStack;
        }
        /** True when we positively identified the fission display (id and/or stack). */
        public boolean present() { return displayId >= 0 || layerStack >= 0; }
    }

    /**
     * Resolve the cluster (fission) display's displayId AND layerStack from
     * {@code dumpsys display}, robust to per-model output layout.
     *
     * <p>WHY the old same-line grep was model-fragile (root cause of "BS cluster
     * black on some models"): it only captured a layerStack when the literal
     * substrings {@code "fission"} and {@code "layerStack"} appeared on the SAME
     * physical line. On the Seal the {@code mBaseDisplayInfo=DisplayInfo{...}} line
     * happens to inline both; on other trims {@code mLayerStack=N} sits on its OWN
     * line (the {@code "fission"} token is on the neighbouring
     * {@code mPrimaryDisplayDevice=} / DisplayInfo-name line), so the grep ALWAYS
     * missed and silently returned the hardcoded fallback (1). On a model whose
     * real cluster stack ≠ 1 the BS SurfaceControl layer was then tagged onto a
     * dead stack → composited to nothing = black, even though the gauges (opened
     * by independent opcodes) and the map (addressed by displayId) both worked.
     *
     * <p>This parses the Logical Displays section block-by-block (each block keyed
     * by an {@code mDisplayId=N} line), captures that block's {@code mLayerStack=}
     * (or a same-line {@code layerStack N/=N} from its DisplayInfo), and flags the
     * block as the fission one if ANY line in it contains {@code "fission"}. So the
     * id + stack always come from the SAME display and don't depend on the two
     * tokens sharing a line.
     *
     * <p>The daemon's DisplayManager cache is unreliable for the foreign uid-1000
     * fission display (it never gets the add-callback), so dumpsys is the
     * authoritative source (uid 2000 / shell can run it).
     */
    public static FissionDisplay resolveFissionDisplay() {
        Process p = null;
        try {
            p = new ProcessBuilder("dumpsys", "display").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            // AUTHORITATIVE source = a line that mentions "fission" AND carries a
            // same-line layerStack. That is the fission logical-display DisplayInfo
            // line (mBaseDisplayInfo/mOverrideDisplayInfo):
            //   ...DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", ...,
            //                  layerStack 1, ...}
            // Its "layerStack N" is the SF COMPOSITING stack — the value the BS
            // layer must be tagged with. PROVEN on the Seal across size profiles
            // (stacks 1/2/4). We do NOT trust the bare `mLayerStack=`/
            // `mCurrentLayerStack=` lines that sit a few lines ABOVE it: on the Seal
            // those read 0 (the device-level default), and a block parser that grabs
            // the first bare mLayerStack=0 then sees "fission" on a later line
            // returns 0 → the layer composites onto a dead stack → BLACK/no-video.
            // (That block-parser was a regression — this restores the same-line read.)
            // The displayId is on the SAME fission line ("displayId 1"), so it comes
            // for free. Prefer the LAST such line (Override after Base; identical N).
            java.util.regex.Pattern sameLineStack =
                java.util.regex.Pattern.compile("(?i)layerstack[ =]+(\\d+)");
            String line;
            int foundId = -1, foundStack = -1;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase(java.util.Locale.US);
                if (!low.contains("fission")) continue;
                // LIVENESS GATE (root cause of "BS card no video + layout fails to
                // restore" on a trim whose fission display never wires to the panel):
                // accept a fission line's displayId/layerStack ONLY when that SAME line
                // also reports the display is ON. The authoritative fission DisplayInfo
                // line on this firmware inlines all three tokens, e.g.
                //   ...DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", ...,
                //                  layerStack 1, ..., type VIRTUAL, state ON, ...}
                // so a same-line "state on" / "state=on" is the correct, model-robust
                // discriminator (no block parsing — same rationale as reading layerStack
                // same-line). WHY THIS MATTERS: SurfaceFlinger's layerStack counter is
                // monotonic and process-global (survives daemon restarts), so a fresh
                // daemon can read an already-high stack (observed: 5→6 on a just-restarted
                // daemon). The OLD lenient grep accepted ANY "fission"+layerStack line —
                // including a stale/transient/not-yet-wired entry — so the show path
                // tagged the BS SurfaceControl layer (and the speed badge) onto a stack
                // with NO live, panel-wired surface → SurfaceFlinger composited them onto
                // nothing = BLACK/no-video, and the projection-close gauge restore looked
                // "failed" because the takeover never had a healthy surface. Gating on
                // state-on means a non-live fission line yields (-1,-1) → present()=false
                // and clusterLayerStack()=STACK_UNRESOLVED → clusterShowWhenReady DEFERS
                // (the "trim may not support projection" path) instead of painting a dead
                // stack. On a model where the display IS live, its line carries state ON,
                // so this passes and behaviour is unchanged. Strictly safer: it can only
                // make resolution MORE conservative, never paint a card it wouldn't have.
                // (Liveness gate below is now fail-safe: reject only on a POSITIVE dead
                // signal, never require an affirmative live token — see the comment at
                // the gate. Word boundaries avoid matching "state offset"/etc.)
                // DIAGNOSTIC: log the RAW fission DisplayInfo line(s) we parse, so the
                // next on-car capture shows this trim's EXACT dumpsys token format
                // (layerStack / state) — the one datum missing from every prior log that
                // left the live-vs-fallback-stack question unanswerable.
                logger.info("resolveFissionDisplay raw: " + line.trim());
                // LIVENESS GATE — FAIL-SAFE (inverted from the over-strict v26.8 form).
                // v26.8 REQUIRED a same-line "state on"/"state=on" before accepting the
                // line; if THIS trim prints the state token in a different form (e.g.
                // "mState=ON", or state on a neighbouring line), that gate WRONGLY
                // rejected a genuinely live fission line → resolveFissionDisplay returned
                // nothing usable → clusterLayerStack fell through to the FALLBACK CONSTANT
                // 1 (a non-resolved stack) → BS card tagged onto the wrong stack = BLACK,
                // which matches the video(v25.4 lenient)→black(v26.8 gate) crossover on
                // byd-48eafd47. Invert to fail-safe: only REJECT a line that POSITIVELY
                // reports the display is NOT live (state off / state unknown). A line
                // with no parseable state token is ACCEPTED (v25.4 lenient behaviour),
                // so we never over-reject a trim whose format we don't recognise; we
                // still skip a genuinely dead/destroyed entry that says "state off".
                if (low.matches(".*\\bstate[ =]+(off|unknown)\\b.*")) continue;
                // Capture id + stack ATOMICALLY from the authoritative DisplayInfo line
                // (the one that actually carries layerStack), not as independent halves
                // accumulated across lines. A live fission display prints multiple
                // matching lines — DisplayDeviceInfo (state ON, NO layerStack token),
                // mBaseDisplayInfo + mOverrideDisplayInfo (state ON + "displayId N" +
                // "layerStack N") — all for the SAME display, so on this firmware the
                // halves agree. But pairing the layerStack with the displayId from the
                // SAME physical line makes the parse robust if a future/secondary fission
                // entry ever appears: we only adopt the stack together with that line's
                // own id, so a stack can never be mismatched to a different display's id.
                // The id-bearing layerStack line is the contract (DisplayInfo inlines
                // both); fall back to a same-line id with no stack only when no
                // layerStack line was seen yet. "Prefer LAST" still holds (Override after
                // Base; identical N).
                java.util.regex.Matcher m = sameLineStack.matcher(line);
                int id = extractDisplayIdOnLine(line);
                if (m.find()) {
                    foundStack = parseIntSafe(m.group(1));
                    if (id >= 0) foundId = id;          // id paired with THIS stack's line
                } else if (id >= 0 && foundStack < 0) {
                    foundId = id;                        // id-only line, no stack seen yet
                }
            }
            // DIAGNOSTIC: log the RESOLVED result so the next on-car log shows whether
            // this trim yielded a parsed displayId/layerStack at all (vs nothing usable
            // → clusterLayerStack falls to the FALLBACK CONSTANT). This + the per-line
            // "resolveFissionDisplay raw:" above are the two datums every prior log
            // lacked, which left code-vs-environment unanswerable.
            logger.info("resolveFissionDisplay result: displayId=" + foundId
                    + " layerStack=" + foundStack);
            return new FissionDisplay(foundId, foundStack);
        } catch (Throwable t) {
            logger.warn("resolveFissionDisplay parse failed: " + t.getMessage());
            return new FissionDisplay(-1, -1);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** Pull the integer after "displayid" (followed by ' ' or '=') on a single line,
     *  or -1. Mirrors ClusterMapProjector.extractDisplayIdOnLine — the displayId is
     *  embedded in the fission DisplayInfo name string ("fission..., displayId 1"). */
    private static int extractDisplayIdOnLine(String line) {
        String low = line.toLowerCase(java.util.Locale.US);
        int idx = low.indexOf("displayid");
        while (idx >= 0) {
            int i = idx + "displayid".length();
            while (i < low.length() && (low.charAt(i) == '=' || low.charAt(i) == ' ')) i++;
            int start = i;
            while (i < low.length() && Character.isDigit(low.charAt(i))) i++;
            if (i > start) {
                int v = parseIntSafe(low.substring(start, i));
                if (v >= 0) return v;
            }
            idx = low.indexOf("displayid", idx + 1);
        }
        return -1;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    /**
     * Discover the CURRENT layerStack of the cluster (fission) display.
     *
     * <p>Returns the live parsed layerStack, or {@code fallback} ONLY when the
     * fission block was found but carried no parseable stack. When the fission
     * display can't be identified at all (e.g. it hasn't materialised yet), returns
     * {@link #STACK_UNRESOLVED} (-1) so the caller can DECLINE to show rather than
     * blindly tag the layer onto the fallback stack (the old behaviour that went
     * black on models where the real stack ≠ fallback).
     */
    public static final int STACK_UNRESOLVED = -1;

    public static int clusterLayerStack(int fallback) {
        FissionDisplay fd = resolveFissionDisplay();
        // DIAGNOSTIC: log WHICH branch we return so the next on-car log says definitively
        // whether the card's stack is the PARSED-LIVE value (branch=live) or the
        // FALLBACK CONSTANT (branch=fallback, meaning the real stack was never parsed —
        // a non-resolved stack the card gets wrongly tagged onto = the suspected black).
        // This is the one fact that separates a code bug (fallback) from environment
        // (live stack but OEM never panel-composites it).
        if (fd.layerStack >= 0) {
            logger.info("clusterLayerStack: branch=live stack=" + fd.layerStack);
            return fd.layerStack;   // live, authoritative
        }
        if (fd.displayId >= 0) {
            logger.info("clusterLayerStack: branch=FALLBACK stack=" + fallback
                    + " (displayId=" + fd.displayId + " but layerStack unparsed)");
            return fallback;          // fission seen, stack unparsed → last-known-good
        }
        logger.info("clusterLayerStack: branch=UNRESOLVED (no fission display)");
        return STACK_UNRESOLVED;                          // no fission display → don't show
    }

    private static void releaseSurfaceControl(Object sc) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("hide", scCls).invoke(tx, sc); } catch (Throwable ignored) {}
            try { txCls.getMethod("reparent", scCls, scCls).invoke(tx, sc, null); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { scCls.getMethod("release").invoke(sc); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("releaseSurfaceControl failed: " + t.getMessage());
        }
    }
}
