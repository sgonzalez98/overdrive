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

    // Topmost — above all app windows / system chrome, so the safety overlay is
    // never occluded while driving. Same z as ScreenDeterrent (Integer.MAX_VALUE),
    // but they never actually contend: the deterrent only fires ACC-OFF (parked /
    // sentry) and the blind-spot overlay only ACC-ON + signaling.
    private static final int Z_ORDER = Integer.MAX_VALUE;

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
        // Reached only when DisplayManager couldn't surface the fission display (its
        // cache misses the foreign uid-1000 display on many models) — we return the
        // fixed 1920×720. Warn loudly: on a model whose real cluster panel differs,
        // this mis-sizes the BS card. Pair with resolveFissionDisplay() for the id;
        // a future pass can parse "real W x H" from that display's dumpsys block.
        logger.warn("clusterDisplaySize: fission panel not found via DisplayManager — "
                + "using fixed 1920x720 fallback (may mis-size on non-Seal clusters)");
        return p;
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
            java.util.regex.Pattern idLine =
                java.util.regex.Pattern.compile("mDisplayId\\s*=\\s*(\\d+)");
            java.util.regex.Pattern stackLine =
                java.util.regex.Pattern.compile("(?:m)?[Ll]ayerStack[ =]+(\\d+)");
            String line;
            // Current logical-display block being accumulated.
            int curId = -1, curStack = -1;
            boolean curFission = false;
            int foundId = -1, foundStack = -1;
            while ((line = r.readLine()) != null) {
                java.util.regex.Matcher mid = idLine.matcher(line);
                if (mid.find()) {
                    // New block boundary — finalize the previous block first.
                    if (curFission) {
                        if (foundId < 0) foundId = curId;
                        if (foundStack < 0) foundStack = curStack;
                    }
                    curId = parseIntSafe(mid.group(1));
                    curStack = -1;
                    curFission = false;
                    continue;
                }
                java.util.regex.Matcher mst = stackLine.matcher(line);
                if (mst.find() && curStack < 0) curStack = parseIntSafe(mst.group(1));
                if (line.toLowerCase(java.util.Locale.US).contains("fission")) {
                    curFission = true;
                    // The fission DisplayInfo line may also inline displayId/layerStack
                    // (Seal layout) — prefer those when the block didn't carry them.
                    int idHere = extractDisplayIdOnLine(line);
                    if (idHere >= 0 && curId < 0) curId = idHere;
                    java.util.regex.Matcher inl = stackLine.matcher(line);
                    if (inl.find() && curStack < 0) curStack = parseIntSafe(inl.group(1));
                }
            }
            // Flush the trailing block.
            if (curFission) {
                if (foundId < 0) foundId = curId;
                if (foundStack < 0) foundStack = curStack;
            }
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
        if (fd.layerStack >= 0) return fd.layerStack;   // live, authoritative
        if (fd.displayId >= 0) return fallback;          // fission seen, stack unparsed → last-known-good
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
