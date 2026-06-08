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

    public BsNativeLayer(int bufferW, int bufferH) {
        this.bufferW = bufferW;
        this.bufferH = bufferH;
    }

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
        applyGeometry(surfaceControl, x, y, w, h, Z_ORDER, true, bufferW, bufferH);
        shown = true;
    }

    /** Position the layer WITHOUT showing it (single transaction, show=false).
     *  Used at enable to arm the geometry while the card is still hidden, avoiding
     *  a show-then-hide one-frame flash of an unrendered layer. */
    public synchronized void setGeometryHidden(int x, int y, int w, int h) {
        if (surfaceControl == null) return;
        applyGeometry(surfaceControl, x, y, w, h, Z_ORDER, false, bufferW, bufferH);
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
                                      int bufW, int bufH) {
        try {
            Class<?> scCls = Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayer", scCls, int.class).invoke(tx, sc, z); } catch (Throwable ignored) {}
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
