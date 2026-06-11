package com.overdrive.app.navmap;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.ClusterProjectionController;

/**
 * Projects the RoadSense map Activity onto the BYD driver-cluster (fission)
 * display, as a SUSTAINED holder of {@link ClusterProjectionController}.
 *
 * <p>Flow (daemon process, UID 2000):
 * <ol>
 *   <li>{@link #start()} acquires the projection as a sustained holder (opens
 *       the OEM cluster projection if not already up, and suppresses the linger
 *       / max-cap auto-close so the map stays up for the drive).</li>
 *   <li>Once the fission display has materialised, launch {@code RoadSenseMapActivity}
 *       onto it via {@code am start --display N} (the same uid-2000 launch path
 *       AvcHalWarmup already uses on-car — no self-ADB needed). The Activity is
 *       told via an intent extra that it's on the cluster, so it renders the
 *       non-touch cluster view.</li>
 *   <li>{@link #stop()} releases the sustained hold; the controller restores the
 *       gauges (18→0) when no transient consumer still wants the projection.</li>
 * </ol>
 *
 * <p>Blind-spot coexistence: BS composites its own SurfaceControl layer at z=MAX
 * onto the SAME projection (it doesn't own the lifecycle while the map holds it),
 * so a turn signal always paints on top of the map. See the BS pipeline.
 *
 * <p>Safety: this class never bypasses the controller's gauge-restore net. ACC-off
 * / disable / SIGTERM / SIGKILL recovery all still fire through the controller
 * regardless of this holder. Default OFF — only runs when the user opts in.
 */
public final class ClusterMapProjector {

    private static final String TAG = "ClusterMapProjector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String APP_PKG = "com.overdrive.app";
    // Launch the CLUSTER ALIAS (own taskAffinity + singleInstance) so the cluster
    // map lives in a separate task from the interactive infotainment instance and
    // the two coexist on two displays. (Was the bare Activity, which relied on the
    // OEM-inconsistent MULTIPLE_TASK flag to avoid stealing the display-0 instance.)
    private static final String MAP_ACTIVITY = APP_PKG + "/.navmap.RoadSenseClusterMapActivity";

    // am start flag: NEW_TASK only (0x10000000). The alias's singleInstance +
    // distinct taskAffinity already isolate the task, so MULTIPLE_TASK (0x08000000)
    // is redundant and contradicts singleInstance.
    private static final String LAUNCH_FLAGS = "0x10000000";

    // windowingMode for the cluster launch. 1 = WINDOWING_MODE_FULLSCREEN — the
    // Activity fills the whole fission panel (1920x720). The earlier value 5
    // (WINDOWING_MODE_FREEFORM) was WRONG: it placed the map in a tiny floating
    // window (on-device: mLastNonFullscreenBounds ~= Rect(825,0-1095,720), a
    // ~270px-wide box centred on the panel) — the "small projection" symptom.
    // Verified on-car via `am start --display 1 --windowingMode 1`: the window
    // resolves to mBounds=[0,0][1920,720] / mWindowingMode=fullscreen, matching
    // the panel exactly. Fullscreen is also what blind-spot uses on this display.
    private static final String WINDOWING_MODE_FULLSCREEN = "1";

    // Poll budget for the fission display to appear after the projection opens.
    private static final int READY_POLL_MS = 250;
    private static final int READY_TIMEOUT_MS = 8000;

    private static volatile boolean active = false;
    private static Thread launchThread;

    private ClusterMapProjector() {}

    /** True while the map is (being) projected onto the cluster. */
    public static boolean isActive() { return active; }

    /**
     * Begin projecting the map onto the cluster. Idempotent. Acquires the
     * sustained projection hold, waits for the fission display, then launches the
     * map Activity onto it. Runs the wait+launch off the caller's thread.
     */
    public static synchronized void start() {
        if (active) return;
        active = true;
        logger.info("cluster map projection: start");
        try {
            ClusterProjectionController.getInstance().acquireSustained();
        } catch (Throwable t) {
            logger.warn("acquireSustained failed: " + t.getMessage());
        }
        launchThread = new Thread(ClusterMapProjector::waitAndLaunch, "ClusterMapLaunch");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    /** Stop projecting: release the sustained hold; the controller restores gauges. */
    public static synchronized void stop() {
        if (!active) return;
        active = false;
        logger.info("cluster map projection: stop");
        try {
            ClusterProjectionController.getInstance().releaseSustained();
        } catch (Throwable t) {
            logger.warn("releaseSustained failed: " + t.getMessage());
        }
    }

    private static void waitAndLaunch() {
        // Wait for the OEM projection to actually establish the fission display.
        int waited = 0;
        int displayId = -1;
        // Keep polling until a POSITIVE fission displayId appears. The fission
        // VirtualDisplay materialises ~1-3s AFTER the open opcodes (31→16→35), so
        // an early resolve transiently returns -1; and it's NEVER display 0 (that's
        // the built-in head unit). Requiring >0 means we wait for the real cluster
        // display instead of aborting on a transient/misparse 0.
        while (active && waited < READY_TIMEOUT_MS) {
            int id = resolveFissionDisplayId();
            if (id > 0) { displayId = id; break; }
            try { Thread.sleep(READY_POLL_MS); } catch (InterruptedException e) { return; }
            waited += READY_POLL_MS;
        }
        if (!active) return;
        if (displayId <= 0) {
            // No fission display ever materialised within the budget (non-fission /
            // Atto-class cluster, or the OEM projection never established). Do NOT
            // blind-fall back to displayId 1 — on such a model display 1 may be the
            // HEAD UNIT, so launching there would clobber the infotainment screen.
            // Abort + RELEASE the sustained hold so the controller restores gauges.
            logger.warn("fission display not resolved (>0) in " + READY_TIMEOUT_MS
                    + "ms — aborting cluster map projection (no clobber of display 0)");
            active = false;
            try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().releaseSustained(); }
            catch (Throwable ignored) {}
            return;
        }
        // Final race guard: a stop() may have fired during the resolve above. Don't
        // launch the Activity if the projection was torn down in the meantime.
        if (!active) {
            logger.info("stop() raced the display resolve — skipping cluster map launch");
            return;
        }
        launchMapOnDisplay(displayId);
    }

    /**
     * Resolve the current fission cluster displayId from {@code dumpsys display}.
     * SurfaceFlinger assigns the id per projection-open, so it must be read live
     * (the daemon's DisplayManager cache doesn't see the foreign uid-1000 display).
     * Returns the id of the display whose name contains "fission", or -1.
     */
    private static int resolveFissionDisplayId() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "display"});
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            int found = -1;
            while ((line = r.readLine()) != null) {
                if (!line.toLowerCase(java.util.Locale.US).contains("fission")) continue;
                // The authoritative line is the LOGICAL display info, e.g.
                //   DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", ...}
                // The id appears ON THE SAME LINE as "fission" as either
                // `displayId 1` (logical info string) or `displayId=1`. The old
                // code paired "fission" with the LAST displayId from a PRIOR line
                // (the built-in viewport's displayId=0) → wrongly returned 0.
                // Extract the id from THIS fission line, supporting both forms.
                int id = extractDisplayIdOnLine(line);
                if (id >= 0) { found = id; if (id > 0) break; } // prefer a non-zero logical id
            }
            r.close();
            return found;
        } catch (Throwable t) {
            logger.warn("resolveFissionDisplayId failed: " + t.getMessage());
        }
        return -1;
    }

    /** Pull the integer after "displayid" (followed by ' ' or '=') on a single line, or -1. */
    private static int extractDisplayIdOnLine(String line) {
        String low = line.toLowerCase(java.util.Locale.US);
        int idx = low.indexOf("displayid");
        while (idx >= 0) {
            int i = idx + "displayid".length();
            // Skip a single '=' or whitespace separator.
            while (i < low.length() && (low.charAt(i) == '=' || low.charAt(i) == ' ')) i++;
            int start = i;
            while (i < low.length() && Character.isDigit(low.charAt(i))) i++;
            if (i > start) {
                try { return Integer.parseInt(low.substring(start, i)); } catch (Throwable ignored) {}
            }
            idx = low.indexOf("displayid", idx + 1);
        }
        return -1;
    }

    /** am start --display N the map Activity, telling it it's on the cluster. */
    private static void launchMapOnDisplay(int displayId) {
        try {
            String[] cmd = {
                "am", "start",
                "--display", String.valueOf(displayId),
                "--windowingMode", WINDOWING_MODE_FULLSCREEN,  // full panel, not freeform
                "-f", LAUNCH_FLAGS,
                "--ez", "cluster", "true",   // RoadSenseMapActivity reads this → cluster view
                "-n", MAP_ACTIVITY
            };
            logger.info("launching map onto displayId " + displayId);
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
            if (proc.exitValue() != 0) {
                logger.warn("am start --display " + displayId + " exited " + proc.exitValue());
            }
        } catch (Throwable t) {
            logger.warn("launchMapOnDisplay failed: " + t.getMessage());
        }
    }
}
