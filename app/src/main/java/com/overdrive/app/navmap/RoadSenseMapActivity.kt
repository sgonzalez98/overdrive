package com.overdrive.app.navmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.overdrive.app.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import com.overdrive.app.navmap.nav.ForwardGeocoder
import com.overdrive.app.navmap.nav.NavGuidanceEngine
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.NavVoice
import com.overdrive.app.navmap.nav.SearchResult
import com.overdrive.app.navmap.nav.ValhallaRouteClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RoadSense Map — native MapLibre head-unit map surface (Phase 1+2).
 *
 * <p>MANIFEST: this Activity needs an <activity> entry registered by the
 * parent — e.g.
 * <pre>
 *   &lt;activity
 *       android:name="com.overdrive.app.navmap.RoadSenseMapActivity"
 *       android:exported="false"
 *       android:theme="@style/Theme.Overdrive.M3"
 *       android:configChanges="orientation|screenSize|keyboardHidden" /&gt;
 * </pre>
 * Nav-rail / launch wiring is intentionally NOT done here.
 *
 * <p>What it does:
 *   - Renders an OpenFreeMap "liberty" basemap (no API key) and overlays the
 *     device's crowdsourced road hazards as data-driven SymbolLayer markers.
 *   - Fetches hazards for the visible viewport from the daemon
 *     ({@code GET /api/roadsense/hazards?bbox=...}) on camera-idle, debounced.
 *   - Tap a hazard → M3 bottom sheet to Confirm (human-verify, optionally
 *     correcting severity/type) or Delete (reject) it.
 *
 * <p>Lifecycle: MapLibre.getInstance(this) is called BEFORE setContentView,
 * and every MapView lifecycle callback is forwarded. This is the #1 MapLibre
 * crash source, so it is kept exact.
 *
 * <p>Hazard property contract (from RoadSenseApiHandler): each GeoJSON
 * Feature carries {id, type 0=BREAKER/1=POTHOLE/2=UNKNOWN/3=ROUGH,
 * severity 1..3, confidence 0..1, status 0=candidate/1=local/2=cloud,
 * observations, humanVerified, heading, updatedMs}.
 */
open class RoadSenseMapActivity : AppCompatActivity() {

    // ---------------------------------------------------------------------
    // View / map state
    // ---------------------------------------------------------------------
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var hazardSource: GeoJsonSource? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Background executor for the daemon HTTP calls — never on the looper. */
    @Volatile private var ioExecutor: ExecutorService? = null

    /** Monotonic token so a stale in-flight fetch result is discarded. */
    @Volatile private var fetchToken: Long = 0L

    /** Debounce runnable for camera-idle viewport refetches. */
    private val refetchRunnable = Runnable { fetchViewportHazards() }

    /** Most recent GPS fix (for the puck + prefetch); null until first fix. */
    @Volatile private var lastFix: RoadSenseHazardApiClient.LatLngFix? = null

    /** True until the first successful auto-recenter, so we only auto-jump once. */
    private var didInitialRecenter = false

    /**
     * Guard so the map click / camera-idle listeners are wired exactly once even
     * if onStyleLoaded runs again (e.g. a future day/night style reload). Defensive
     * — a duplicate registration would fire onMapTap / scheduleRefetch twice.
     */
    private var listenersWired = false

    // --- Navigation (route search + guidance) state ---
    private var routeSource: GeoJsonSource? = null
    private val guidance = NavGuidanceEngine()
    private var navVoice: NavVoice? = null
    @Volatile private var navigating = false

    // --- Search autocomplete ---
    private val searchAdapter = NavSearchResultAdapter { result -> onSearchResultChosen(result) }
    @Volatile private var acToken: Long = 0L
    private var pendingAutocomplete: Runnable? = null

    // --- Alternate-route preview (before guidance starts) ---
    private val routeOptionAdapter = NavRouteOptionAdapter { idx -> onRouteOptionSelected(idx) }
    private var previewRoutes: List<NavRoute> = emptyList()
    private var previewDestLabel: String = ""

    // --- Multi-stop itinerary (Google-Maps-style waypoints) ---
    /**
     * The ORDERED itinerary AFTER the origin: [stop1, stop2, …, destination].
     * Convention (documented once here): the origin is ALWAYS the live GPS fix
     * ([lastFix]) and is NOT stored in this list; the LAST entry is the final
     * destination and every earlier entry is an intermediate via-stop the route
     * must pass through in order. So:
     *   - size 0 → no destination chosen yet (no preview).
     *   - size 1 → just a destination, no stops → use routesWithAlternates.
     *   - size >1 → destination + ≥1 stop → use routeVia([origin] + routeStops).
     */
    private val routeStops = ArrayList<SearchResult>()

    /**
     * When true, the NEXT chosen search result is INSERTED as an intermediate
     * stop (before the destination) instead of replacing the destination. Set by
     * the "Add stop" affordance, cleared after a pick or when the sheet resets.
     */
    @Volatile private var addingStop = false

    /** Index into [routeStops] being edited (re-searched), or -1 if none. */
    private var editingStopIndex = -1
    // Locked destination while navigating (for tap-to-switch + auto-reroute).
    private var lockedDestLat: Double = Double.NaN
    private var lockedDestLng: Double = Double.NaN
    @Volatile private var rerouting = false
    private var lastRerouteMs: Long = 0L
    private var previewSelectedIdx: Int = 0
    private var altRouteSource: GeoJsonSource? = null
    private var routeSheetBehavior:
        com.google.android.material.bottomsheet.BottomSheetBehavior<View>? = null

    // --- POI along route (EV charging / fuel) ---
    private var poiSource: GeoJsonSource? = null
    @Volatile private var poiEnabled = false

    // --- Itinerary markers (destination pin + numbered stop pins) ---
    private var markerSource: GeoJsonSource? = null
    /** Largest stop-ordinal bitmap registered so far (so we only build each once). */
    private var maxStopIconRegistered = 0

    // --- Hazard visibility filter ---
    private var hazardSymbolLayer: SymbolLayer? = null
    /** 0 = hidden, 1 = severe only, 2 = moderate+ (≥2), 3 = all (default). */
    private var hazardFilterMode = HAZARD_FILTER_ALL

    // --- 2D / 3D buildings ---
    /** Persisted 2D/3D choice; DEFAULT 2D (false) for both themes — 3D extrusion
     *  is GPU-heavy on the Adreno 610 and adds to the shared-GPU contention. */
    private var map3dEnabled = false

    /**
     * True when this Activity instance was launched onto the driver cluster
     * (am start --display N --ez cluster true). The cluster is a NON-TOUCH display,
     * so in this mode we strip every touch control, force a larger heading-up
     * immersive camera, and keep the view glanceable. Set once in onCreate.
     */
    private var clusterMode = false
    /** Periodic guidance tick: pulls a fresh GPS fix and advances guidance. */
    private val guidanceRunnable = object : Runnable {
        override fun run() {
            if (!navigating) return
            tickGuidance()
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // MUST precede setContentView — initializes the MapLibre runtime so
        // the inflated MapView can attach its render surface. Getting this
        // order wrong is the canonical MapLibre crash.
        MapLibre.getInstance(this)
        // Make MapLibre's own tile/style/glyph/sprite fetches PROXY-AWARE — must
        // run after getInstance() and before the first style load. Without it the
        // basemap would bypass sing-box/Tailscale on a proxied head unit while
        // search/routing (already proxy-aware) worked.
        com.overdrive.app.navmap.nav.MapNetworking.installMapLibreHttpClient()

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_roadsense_map)

        // Cluster mode: launched onto the non-touch driver-cluster display by the
        // daemon. Detected via the launching COMPONENT (the singleInstance alias
        // RoadSenseClusterMapActivity) OR the legacy `cluster` extra — component is
        // the robust signal (survives onNewIntent / can't be lost). Strip touch
        // chrome + go glanceable.
        // Cluster mode when we're the dedicated cluster subclass (robust — survives
        // onNewIntent / can't be lost) OR the legacy `cluster` extra is set.
        clusterMode = this is RoadSenseClusterMapActivity ||
            intent?.getBooleanExtra("cluster", false) == true

        // Safety: the cluster instance must NEVER end up on the default (head-unit)
        // display — if AMS placed it on display 0 (no cluster display present), it
        // would clobber the infotainment map. Finish immediately so the touch
        // instance is untouched.
        if (clusterMode && display?.displayId == android.view.Display.DEFAULT_DISPLAY) {
            android.util.Log.w("RoadSenseMap", "cluster instance landed on display 0 — finishing")
            finishAndRemoveTask()
            return
        }
        if (clusterMode) {
            applyClusterChrome()
            registerClusterDisplayWatch()
        }

        findViewById<MaterialToolbar>(R.id.mapToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        // Immersive-nav back FAB (the toolbar is hidden during immersive driving):
        // same affordance as the toolbar chevron — pop the Activity.
        findViewById<FloatingActionButton>(R.id.fabNavBack)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Edge-to-edge: pad the top chrome (toolbar/search/banner) down past the
        // status bar and lift the FAB stack above the system nav bar so nothing
        // is clipped. MapLibre's own chrome is offset separately in onMapReady.
        applyWindowInsets()

        // Responsive: on a wide (landscape) screen, cap the floating panels to a
        // column + center them (full-width edge-to-edge reads stretched on the
        // 1920px Seal). Re-applied on rotation via onConfigurationChanged.
        applyResponsiveLayout()

        findViewById<FloatingActionButton>(R.id.fabLocate).setOnClickListener {
            recenter()
        }

        // Destination search — Gmap-style: type-ahead autocomplete (debounced
        // Photon) drives the dropdown; submit (IME / glyph) is the on-submit
        // fallback. Wired in setupSearch().
        setupSearch()
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.setOnClickListener {
            stopGuidance()
            showSnackbar(getString(R.string.roadsense_map_nav_ended))
        }

        // Hazard visibility filter (toggle / severity) + POI (EV charging / fuel) toggle.
        setupHazardFilter()
        findViewById<FloatingActionButton>(R.id.fabPoi)?.setOnClickListener { togglePoi() }
        findViewById<FloatingActionButton>(R.id.fabMap3d)?.setOnClickListener { toggleMap3d() }

        // Explicit zoom +/- (MapLibre 11.x has no built-in zoom buttons; on a
        // head-unit an on-screen control is expected over pinch alone).
        findViewById<View>(R.id.fabZoomIn)?.setOnClickListener { zoomBy(1.0) }
        findViewById<View>(R.id.fabZoomOut)?.setOnClickListener { zoomBy(-1.0) }

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mlMap -> onMapReady(mlMap) }
    }

    /**
     * Apply system-bar insets so the edge-to-edge map UI isn't clipped: the
     * top app bar + search bar + maneuver banner are pushed below the status
     * bar, and the FAB stack is lifted above the navigation bar. The MapView
     * itself stays full-bleed (the basemap reaches every edge); only the
     * overlaid controls are inset. Also re-offsets MapLibre's own chrome once
     * the real inset is known.
     */
    private fun applyWindowInsets() {
        val appBar = findViewById<View>(R.id.mapAppBar)
        val searchColumn = findViewById<View>(R.id.navSearchColumn)
        val banner = findViewById<View>(R.id.navBanner)
        val fabLocate = findViewById<View>(R.id.fabLocate)
        val fabEnd = findViewById<View>(R.id.fabEndNav)
        val fabNavBack = findViewById<View>(R.id.fabNavBack)
        val zoomControls = findViewById<View>(R.id.zoomControls)
        val controlsTop = findViewById<View>(R.id.mapControlsTop)
        val routeSheet = findViewById<View>(R.id.routeOptionsSheet)
        val routeSheetContent = findViewById<View>(R.id.routeSheetContent)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.mapRoot)
        ) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            appBar?.setPadding(0, bars.top, 0, 0)
            // The search COLUMN (bar + dropdown) is pinned to the top, just below
            // the status bar + toolbar. Offset the whole column (NOT the inner
            // card — that double-offset was the misalignment).
            (searchColumn?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(56); searchColumn.layoutParams = it
            }
            (banner?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(56); banner.layoutParams = it
            }
            // Top-right map controls sit below the search bar.
            (controlsTop?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(128); controlsTop.layoutParams = it
            }
            // Immersive back FAB: just under the status bar, top-start.
            (fabNavBack?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(16); fabNavBack.layoutParams = it
            }
            (fabLocate?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(24); fabLocate.layoutParams = it
            }
            (fabEnd?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(24); fabEnd.layoutParams = it
            }
            (zoomControls?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(96); zoomControls.layoutParams = it
            }
            // Route-options sheet: pad its CONTENT above the nav bar so the Start
            // button + last row aren't hidden behind the system navigation bar.
            routeSheetContent?.setPadding(
                routeSheetContent.paddingLeft, routeSheetContent.paddingTop,
                routeSheetContent.paddingRight, bars.bottom + dp(12)
            )
            // Re-peek the sheet so the bottom inset is included in peekHeight.
            routeSheet?.let {
                routeSheetBehavior?.peekHeight = dp(260) + bars.bottom
            }
            // Re-offset MapLibre chrome now that we have the real inset.
            map?.let { applyMapChromeInsets(it) }
            insets
        }
    }

    /**
     * Cap the floating top panels (search column + maneuver banner) to a sensible
     * column width and center them on wide/landscape screens; on portrait/narrow
     * screens they fill the width. Without this, full-width panels stretch edge-to-
     * edge across the 1920px landscape display and look unbalanced. The route sheet
     * stays full-width (a bottom sheet is meant to span the bottom).
     */
    private fun applyResponsiveLayout() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val maxColW = (PANEL_MAX_WIDTH_DP * dm.density).toInt()
        val wide = screenW > maxColW + (32 * dm.density).toInt()
        val targetW = if (wide) maxColW else android.view.ViewGroup.LayoutParams.MATCH_PARENT

        fun setWidthCentered(id: Int) {
            val v = findViewById<View>(id) ?: return
            (v.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let { lp ->
                lp.width = targetW
                // Keep the existing top gravity; add horizontal centering when capped.
                lp.gravity = android.view.Gravity.TOP or
                    (if (wide) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START)
                v.layoutParams = lp
            }
        }
        setWidthCentered(R.id.navSearchColumn)
        setWidthCentered(R.id.navBanner)
    }

    /**
     * On the cluster, continuously follow the live GPS fix in the immersive
     * heading-up camera even when no route is active, so the projected map always
     * tracks the car. While navigating, the guidance tick already drives the
     * camera, so this only runs the idle (no-route) follow.
     */
    private val clusterFollowRunnable = object : Runnable {
        override fun run() {
            if (!clusterMode || isFinishing || isDestroyed) return
            if (!navigating) {   // guidance owns the camera while navigating
                ioExecutor().execute {
                    val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
                    if (fix != null) mainHandler.post {
                        if (isFinishing || isDestroyed || navigating) return@post
                        lastFix = fix
                        updateLocationPuck(fix)
                        val bearing = fix.bearing?.takeIf { (fix.speed ?: 0.0) > IMMERSIVE_MIN_SPEED_MPS } ?: lastBearing
                        lastBearing = bearing
                        // Dead-band: skip the glide when barely moved + barely turned
                        // (puck above still updates). Keyed off the live fix position.
                        if (!cameraWithinDeadband(fix.lat, fix.lng, bearing)) {
                            map?.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    org.maplibre.android.camera.CameraPosition.Builder()
                                        .target(LatLng(fix.lat, fix.lng))
                                        .zoom(IMMERSIVE_ZOOM).tilt(IMMERSIVE_TILT).bearing(bearing).build()
                                ), GUIDANCE_CAM_ANIM_MS
                            )
                            rememberAnimatedTarget(fix.lat, fix.lng, bearing)
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    private fun startClusterFollow() {
        mainHandler.removeCallbacks(clusterFollowRunnable)
        mainHandler.post(clusterFollowRunnable)
    }

    /**
     * Cluster-display chrome: the driver cluster is NON-TOUCH and glanceable, so
     * hide everything interactive (toolbar/back, search column, all FABs, zoom,
     * map-control stack). Only the map + the turn-by-turn maneuver banner remain.
     * Map gestures are also off (no input device on the cluster). The camera is
     * driven entirely by the guidance loop in cluster mode.
     */
    private var clusterDisplayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    /** Cluster-only: listens to the shared NavSession to mirror the infotainment route. */
    private var navSessionListener: NavSession.Listener? = null

    /**
     * On the cluster, watch for our own display being removed (the OEM projection
     * torn down on ACC-off / disable / stop) and finish() this Activity so its GL
     * surface + follow loop don't orphan. Because the cluster runs in a separate
     * task (singleInstance alias), finishing it leaves the infotainment instance
     * untouched.
     */
    private fun registerClusterDisplayWatch() {
        val myId = display?.displayId ?: return
        val dm = getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == myId) {
                    mainHandler.removeCallbacks(clusterFollowRunnable)
                    if (!isFinishing && !isDestroyed) finish()
                }
            }
        }
        clusterDisplayListener = l
        dm.registerDisplayListener(l, mainHandler)
    }

    private fun applyClusterChrome() {
        listOf(
            R.id.mapAppBar, R.id.navSearchColumn, R.id.zoomControls,
            R.id.fabLocate, R.id.fabEndNav, R.id.mapControlsTop, R.id.fabNavBack
        ).forEach { findViewById<View>(it)?.visibility = View.GONE }
        // Keep the maneuver banner and actually enlarge its text so it's legible at a
        // glance on the driver cluster (the previous alpha-only line was a no-op).
        findViewById<View>(R.id.navBanner)?.alpha = 1f
        findViewById<TextView>(R.id.navBannerPrimary)?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, CLUSTER_BANNER_PRIMARY_SP
        )
        findViewById<TextView>(R.id.navBannerSecondary)?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, CLUSTER_BANNER_SECONDARY_SP
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges keeps the GL surface alive across rotation (no recreate);
        // re-apply responsive widths + re-request insets so panels reflow cleanly.
        applyResponsiveLayout()
        findViewById<View>(R.id.mapRoot)?.requestApplyInsets()
    }

    // ---------------------------------------------------------------------
    // Map setup
    // ---------------------------------------------------------------------

    private fun onMapReady(mlMap: MapLibreMap) {
        map = mlMap

        // App-like feel: lock rotation + tilt, hide the compass (irrelevant
        // when north-locked) but ENABLE on-screen zoom +/- controls (no
        // pinch-only). Keep attribution/logo (OSM data license requires it).
        mlMap.uiSettings.apply {
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
            setCompassEnabled(false)
            // Cluster is non-touch → kill ALL gestures; head-unit keeps pinch/zoom.
            setZoomGesturesEnabled(!clusterMode)
            setQuickZoomGesturesEnabled(!clusterMode)
            setScrollGesturesEnabled(!clusterMode)
        }
        // MapLibre 11.x dropped the legacy on-screen zoom buttons, so we provide
        // our own +/- FABs (zoomInBy below). Pinch/quick-zoom gestures stay on
        // (head-unit only).
        if (clusterMode) {
            // On the cluster, hide MapLibre's own logo/attribution too (glanceable
            // surface; attribution shown on the head-unit instance which is the
            // user-facing one). Follow the live GPS continuously.
            mlMap.uiSettings.setLogoEnabled(false)
            mlMap.uiSettings.setAttributionEnabled(false)
            startClusterFollow()
        }

        // Lift MapLibre's own chrome (attribution, logo, zoom buttons) clear of
        // the system nav bar AND our FABs, so nothing is clipped at the bottom
        // edge. Margins are in px; convert from dp.
        applyMapChromeInsets(mlMap)

        // Initial camera: a sensible mid-zoom over the default region until
        // the first viewport fetch / recenter narrows it.
        mlMap.moveCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(DEFAULT_LAT, DEFAULT_LNG), DEFAULT_ZOOM)
        )

        // Theme-aware basemap: dark style in night mode, light otherwise, so the
        // map matches the rest of the app shell. Re-add all our sources/layers
        // in the onStyleLoaded callback (setStyle wipes them).
        mlMap.setStyle(Style.Builder().fromUri(styleUrlForTheme())) { style ->
            onStyleLoaded(style)
        }
    }

    /**
     * Push MapLibre's built-in chrome (zoom buttons, attribution, logo) in from
     * the screen edges so the system navigation bar and our FAB stack never clip
     * it. Uses the current window insets (bottom system bar) plus a base margin.
     */
    private fun applyMapChromeInsets(mlMap: MapLibreMap) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val navBottom = rootBottomInsetPx()
        mlMap.uiSettings.apply {
            // Attribution + logo: bottom-START, lifted clear of the nav bar
            // (required by the OSM data license, kept legible + unclipped).
            setAttributionMargins(dp(8), 0, 0, navBottom + dp(8))
            setLogoMargins(dp(40), 0, 0, navBottom + dp(8))
        }
    }

    /** Current bottom system-bar inset in px (0 if not yet laid out). */
    private fun rootBottomInsetPx(): Int {
        return try {
            val root = findViewById<View>(R.id.mapRoot)
            androidx.core.view.ViewCompat.getRootWindowInsets(root)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                ?.bottom ?: 0
        } catch (_: Throwable) { 0 }
    }

    /** OpenFreeMap style URL chosen by the active app theme (day/night). */
    private fun styleUrlForTheme(): String =
        if (isNightTheme()) STYLE_URL_DARK else STYLE_URL_LIGHT

    /**
     * Active day/night state — mirrors the source the rest of the app uses
     * (AppCompatDelegate runtime mode, then the Configuration uiMode fallback),
     * so the map follows the same theme as the WebView shell.
     */
    private fun isNightTheme(): Boolean {
        when (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> return true
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> return false
        }
        val ui = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun onStyleLoaded(style: Style) {
        // 0) Route line FIRST (added before hazards/clusters so the line draws
        //    UNDER the hazard markers). Two-layer stroke: a wide casing under a
        //    narrower bright main line. Empty until a route is computed.
        val routeColor = ContextCompat.getColor(this, R.color.md_sys_color_primary_light)
        val routeCasing = ContextCompat.getColor(this, R.color.md_sys_color_on_primary_container_light)
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(routeCasing),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        // Alternate routes — a SEPARATE source/layer drawn UNDER the selected
        // route, dimmed + thinner but tappable (tap selects that alternate).
        // Added before the bright route layers so the selected route sits on top.
        val altColor = ContextCompat.getColor(this, R.color.md_sys_color_outline_light)
        style.addSource(GeoJsonSource(ALT_ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(ALT_ROUTE_LAYER_ID, ALT_ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(altColor),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        altRouteSource = style.getSourceAs(ALT_ROUTE_SOURCE_ID)

        style.addLayer(
            LineLayer(ROUTE_MAIN_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(routeColor),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        routeSource = style.getSourceAs(ROUTE_SOURCE_ID)

        // 1) Register the four hazard marker icons (rasterized from the
        //    tintable vector drawables) once for this style.
        registerHazardIcons(style)

        // 2) Hazard source: empty FeatureCollection to start, clustered so
        //    dense corridors collapse into a count bubble at low zoom.
        val source = GeoJsonSource(
            HAZARD_SOURCE_ID,
            EMPTY_FEATURE_COLLECTION,
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(CLUSTER_RADIUS)
                .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
        )
        style.addSource(source)
        hazardSource = source

        // 3) Cluster bubble (CircleLayer) — only features carrying point_count.
        val clusterColor = ContextCompat.getColor(this, R.color.md_sys_color_primary_light)
        val clusterCircle = CircleLayer(CLUSTER_CIRCLE_LAYER_ID, HAZARD_SOURCE_ID).apply {
            setFilter(Expression.has(POINT_COUNT))
            setProperties(
                PropertyFactory.circleColor(clusterColor),
                // Bubble grows in steps with the cluster size.
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.toNumber(Expression.get(POINT_COUNT)),
                        Expression.literal(16f),
                        Expression.stop(10, 20f),
                        Expression.stop(50, 26f)
                    )
                ),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#ffffff")
            )
        }
        style.addLayer(clusterCircle)

        // 4) Cluster count text on top of the bubble.
        val onPrimary = ContextCompat.getColor(this, R.color.md_sys_color_on_primary_light)
        val clusterCount = SymbolLayer(CLUSTER_COUNT_LAYER_ID, HAZARD_SOURCE_ID).apply {
            setFilter(Expression.has(POINT_COUNT))
            setProperties(
                PropertyFactory.textField(Expression.get("point_count_abbreviated")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor(onPrimary),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true)
            )
        }
        style.addLayer(clusterCount)

        // 5) Un-clustered hazard markers — the data-driven SOTA core.
        val hazardSymbols = SymbolLayer(HAZARD_SYMBOL_LAYER_ID, HAZARD_SOURCE_ID).apply {
            setFilter(Expression.not(Expression.has(POINT_COUNT)))
            setProperties(
                // icon-image = match(type): 0->breaker, 1->pothole, 3->rough, default->unknown
                PropertyFactory.iconImage(
                    Expression.match(
                        Expression.toNumber(Expression.get(PROP_TYPE)),
                        Expression.literal(0L), Expression.literal(ICON_BREAKER),
                        Expression.literal(1L), Expression.literal(ICON_POTHOLE),
                        Expression.literal(3L), Expression.literal(ICON_ROUGH),
                        Expression.literal(ICON_UNKNOWN) // default (incl. type 2 = UNKNOWN)
                    )
                ),
                // Pin tip marks the location — anchor at the bottom of the icon.
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                // icon-size scales with severity (1..3). Pins are pre-rendered
                // large/crisp, so the on-map scale is modest.
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.toNumber(Expression.get(PROP_SEVERITY)),
                        Expression.stop(1, 0.5f),
                        Expression.stop(3, 0.78f)
                    )
                ),
                // icon-opacity by status: candidate(0) dimmed, local/cloud solid.
                PropertyFactory.iconOpacity(
                    Expression.match(
                        Expression.toNumber(Expression.get(PROP_STATUS)),
                        Expression.literal(0L), Expression.literal(0.6f),
                        Expression.literal(1.0f) // default (status 1 local / 2 cloud)
                    )
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }
        style.addLayer(hazardSymbols)
        hazardSymbolLayer = hazardSymbols
        // Apply the persisted hazard-filter mode to the fresh layer.
        applyHazardFilter(hazardFilterMode)

        // 5b) POI (EV charging / fuel) markers along the route — created empty,
        //     populated when a route is chosen + the POI toggle is on. Drawn above
        //     the route lines but below hazards.
        registerPoiIcons(style)
        style.addSource(GeoJsonSource(POI_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            SymbolLayer(POI_LAYER_ID, POI_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(
                    Expression.match(
                        Expression.get(POI_PROP_KIND),
                        Expression.literal("charging"), Expression.literal(ICON_POI_CHARGING),
                        Expression.literal(ICON_POI_FUEL) // default (fuel)
                    )
                ),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(0.6f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        poiSource = style.getSourceAs(POI_SOURCE_ID)

        // 5c) Itinerary markers (destination pin + numbered stop pins) — drawn LAST
        //     (top of the stack) so the trip endpoints stay legible over the route
        //     line, hazards and POIs. iconImage is data-driven from each feature's
        //     "img" property (rs_dest / rs_stop_<n>), bitmaps registered on demand.
        style.addSource(GeoJsonSource(MARKER_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            SymbolLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get(MARKER_PROP_IMG)),
                // Pin tip marks the exact coordinate.
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(0.82f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        markerSource = style.getSourceAs(MARKER_SOURCE_ID)

        // 5d) 3D buildings: add a fill-extrusion layer over the openmaptiles vector
        //     source (present in both the light + dark basemaps; only liberty ships
        //     its own extrusion, so we add ours either way) and set its visibility
        //     from the persisted 2D/3D choice. Drawn UNDER our route/markers (added
        //     before nothing here — added last among basemap layers, but symbol
        //     layers above still composite on top since they were added earlier with
        //     their own draw order). Default 2D (hidden) — opt-in, GPU-heavier.
        setup3dBuildings(style)

        // 5e) Language-aware basemap labels: rewrite every label layer's text-field
        //     to prefer the user's language name (name:<lang>) and fall back to the
        //     local/default name. So "München"/"慕尼黑" etc. render to match the app.
        localizeMapLabels(style)

        // 6+7) Tap-to-verify (query the hazard symbol layer at the tap point) and
        //      refetch on every camera-idle (debounced inside). Wired ONCE — a style
        //      reload re-runs onStyleLoaded but the map listeners persist, so a guard
        //      stops a duplicate registration firing each callback twice.
        if (!listenersWired) {
            map?.addOnMapClickListener { latLng -> onMapTap(latLng) }
            map?.addOnCameraIdleListener { scheduleRefetch() }
            listenersWired = true
        }

        // 8) First viewport fetch.
        fetchViewportHazards()

        // 9) Configure the offline ambient tile cache once, then auto-center on
        //    the live GPS fix the first time the map opens (so it lands on the
        //    user, not the default region). Subsequent recenters are manual (FAB).
        MapTilePrefetcher.configureAmbientCache(applicationContext)
        if (!didInitialRecenter) {
            didInitialRecenter = true
            recenter()
        }

        // 10) Cluster mirror: subscribe to the shared NavSession so a route the
        //     user sets on the infotainment map renders here in real time (the
        //     listener replays the current state immediately on add, so a cluster
        //     launched mid-trip picks up the in-progress route). Route layers exist
        //     now (added above), so it's safe to render on the callback.
        if (clusterMode) subscribeClusterToNavSession()
    }

    /**
     * Cluster-only: render whatever the infotainment instance publishes to
     * [NavSession]. On a route → draw the line + start the heading-up guidance
     * follow; on clear → wipe the line and fall back to the idle GPS follow.
     */
    private fun subscribeClusterToNavSession() {
        navSessionListener = NavSession.addListener { st ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val route = st.route
                if (route != null) {
                    renderRoute(route)
                    if (st.navigating && !navigating) {
                        // Drive the same guidance follow as the infotainment map,
                        // but WITHOUT re-publishing (clusterMode guards the publish).
                        guidance.start(route)
                        navigating = true
                        // Snap into the immersive follow view now (same reason as
                        // startGuidance) — renderRoute above left the cluster at the
                        // route-overview framing; without this the stationary cluster
                        // would stay zoomed-out until the car moves >3m.
                        moveCameraToImmersiveStart(route)
                        mainHandler.removeCallbacks(guidanceRunnable)
                        mainHandler.post(guidanceRunnable)
                    } else if (!st.navigating && navigating) {
                        navigating = false
                        mainHandler.removeCallbacks(guidanceRunnable)
                        guidance.stop()
                    }
                } else {
                    // Trip cleared on infotainment → wipe + resume idle follow.
                    navigating = false
                    mainHandler.removeCallbacks(guidanceRunnable)
                    guidance.stop()
                    routeSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
                    altRouteSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
                    clearItineraryMarkers()
                    startClusterFollow()
                }
            }
        }
    }

    /**
     * Register the four hazard markers as proper MAP PINS (not flat tinted
     * glyphs). Each is a composited teardrop: a soft drop shadow, a filled
     * type-colored body, a white circular glyph well, and the hazard symbol
     * drawn in the body color inside the well. This reads as a real map marker
     * at any zoom and encodes hazard type by color + glyph at a glance. The
     * SymbolLayer anchors these at "bottom" so the pin tip marks the location.
     */
    private fun registerHazardIcons(style: Style) {
        // Type colors — distinct, high-contrast, theme-stable (markers sit on
        // both light and dark basemaps so we use saturated fixed hues, not
        // theme-attr surface colors which would vanish on one theme).
        val potholeColor = 0xFFE53935.toInt()  // red   — pothole (most severe-looking)
        val breakerColor = 0xFFFB8C00.toInt()  // amber — speed breaker
        val roughColor   = 0xFF8E24AA.toInt()  // purple— rough section
        val unknownColor = 0xFF546E7A.toInt()  // slate — unknown

        // Idempotent: only build + register a bitmap the style doesn't already have,
        // so a future style reload doesn't needlessly re-rasterize each pin.
        if (style.getImage(ICON_POTHOLE) == null)
            style.addImage(ICON_POTHOLE, buildHazardPin(R.drawable.ic_hazard_pothole, potholeColor))
        if (style.getImage(ICON_BREAKER) == null)
            style.addImage(ICON_BREAKER, buildHazardPin(R.drawable.ic_hazard_breaker, breakerColor))
        if (style.getImage(ICON_ROUGH) == null)
            style.addImage(ICON_ROUGH, buildHazardPin(R.drawable.ic_hazard_rough, roughColor))
        if (style.getImage(ICON_UNKNOWN) == null)
            style.addImage(ICON_UNKNOWN, buildHazardPin(R.drawable.ic_hazard_unknown, unknownColor))
    }

    /**
     * Compose one SOTA map pin bitmap: teardrop body in [bodyColor] with a
     * drop shadow, a white inner disc, and the [glyphRes] hazard symbol tinted
     * [bodyColor] centered in the disc. Drawn at [PIN_PX] so it stays crisp on
     * the hi-dpi head-unit panel. The geometry leaves transparent margin for
     * the shadow so SymbolLayer's "bottom" anchor lands on the pin tip.
     */
    private fun buildHazardPin(glyphRes: Int, bodyColor: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f           // circular head radius
        val headCy = bodyR + w * 0.06f  // head center y (room for shadow at tip)
        val tipY = h - w * 0.06f        // teardrop tip near the bottom

        // NO blur drop-shadow: BlurMaskFilter renders as a hard dark RECTANGLE on
        // this head unit (HW-layer blur unsupported / clipped to the bitmap bounds),
        // which looks bad on the light basemap. The white rim below gives enough
        // separation on both light + dark basemaps.

        // Pin body (teardrop) + a thin white rim for separation.
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)

        // White inner disc (the glyph well).
        val discR = bodyR * 0.62f
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, discR, disc)

        // Hazard glyph, tinted in the body color, centered in the disc. Use a
        // SRC_IN color filter (not DrawableCompat.setTint) so it deterministically
        // overrides the vector's own baked android:tint across API levels.
        val glyph = ContextCompat.getDrawable(this, glyphRes)!!.mutate()
        glyph.colorFilter = android.graphics.PorterDuffColorFilter(
            bodyColor, android.graphics.PorterDuff.Mode.SRC_IN
        )
        val g = (discR * 1.7f).toInt()
        val gl = (cx - g / 2f).toInt()
        val gt = (headCy - g / 2f).toInt()
        glyph.setBounds(gl, gt, gl + g, gt + g)
        glyph.draw(c)
        return bmp
    }

    /** Build a teardrop/pin path: a circle head at (cx,cy) r=[r] tapering to [tipY]. */
    private fun teardropPath(cx: Float, cy: Float, r: Float, tipY: Float): android.graphics.Path {
        val p = android.graphics.Path()
        // Start at the tip, sweep up around the head, back to the tip.
        val k = r * 0.55f
        p.moveTo(cx, tipY)
        p.cubicTo(cx - k, cy + r * 0.9f, cx - r, cy + r * 0.5f, cx - r, cy)
        p.arcTo(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false)
        p.cubicTo(cx + r, cy + r * 0.5f, cx + k, cy + r * 0.9f, cx, tipY)
        p.close()
        return p
    }

    // ---------------------------------------------------------------------
    // Viewport-driven hazard fetch
    // ---------------------------------------------------------------------

    private fun scheduleRefetch() {
        mainHandler.removeCallbacks(refetchRunnable)
        mainHandler.postDelayed(refetchRunnable, REFETCH_DEBOUNCE_MS)
    }

    /**
     * Read the current visible bounds, fetch the hazards for that bbox on the
     * IO executor, and post the GeoJSON back to the source on the main thread.
     * A monotonic [fetchToken] discards a stale result if the camera moved
     * again before this one returned. Never blocks the UI thread; failures
     * keep the last good data.
     */
    private fun fetchViewportHazards() {
        val mlMap = map ?: return
        val bounds: LatLngBounds = mlMap.projection.visibleRegion.latLngBounds
        val minLng = bounds.getLonWest()
        val minLat = bounds.getLatSouth()
        val maxLng = bounds.getLonEast()
        val maxLat = bounds.getLatNorth()

        val token = ++fetchToken
        ioExecutor().execute {
            val geoJson = RoadSenseHazardApiClient.fetchHazardsGeoJson(minLng, minLat, maxLng, maxLat)
            if (geoJson == null) return@execute // keep last good data on failure
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (token != fetchToken) return@post // superseded by a newer fetch
                hazardSource?.setGeoJson(geoJson)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Tap-to-confirm / delete
    // ---------------------------------------------------------------------

    private fun onMapTap(latLng: LatLng): Boolean {
        val mlMap = map ?: return false
        val point: PointF = mlMap.projection.toScreenLocation(latLng)

        // Tapping the map dismisses the search dropdown + drops keyboard focus.
        if (findViewById<View>(R.id.navSearchDropdown)?.visibility == View.VISIBLE) {
            hideSearchDropdown()
            findViewById<android.widget.EditText>(R.id.navSearchInput)?.let {
                it.clearFocus(); hideKeyboard(it)
            }
        }

        // Hit-test PRECEDENCE matters: hazards + POIs sit ON TOP of the route line,
        // so the precise point markers MUST be tested BEFORE the route line —
        // otherwise tapping a hazard also hits the route box and wrongly "switches
        // route". Order: hazard → POI → route line (the big fallback target).
        val slop = TAP_SLOP_PX * resources.displayMetrics.density
        val box = android.graphics.RectF(
            point.x - slop, point.y - slop, point.x + slop, point.y + slop
        )

        // 1) Hazard markers (query a tolerance box too, so small pins are tappable).
        val hazardHit = mlMap.queryRenderedFeatures(box, HAZARD_SYMBOL_LAYER_ID)
            .firstOrNull { it.hasProperty(PROP_ID) }
        if (hazardHit != null) {
            val id = hazardHit.getStringProperty(PROP_ID) ?: return false
            val type = hazardHit.getNumberProperty(PROP_TYPE)?.toInt() ?: 2
            val severity = (hazardHit.getNumberProperty(PROP_SEVERITY)?.toInt() ?: 2).coerceIn(1, 3)
            val confidence = hazardHit.getNumberProperty(PROP_CONFIDENCE)?.toDouble() ?: 0.0
            val status = hazardHit.getNumberProperty(PROP_STATUS)?.toInt() ?: 0
            val observations = hazardHit.getNumberProperty(PROP_OBSERVATIONS)?.toInt() ?: 0
            showHazardSheet(id, type, severity, confidence, status, observations)
            return true
        }

        // 2) POI marker (charging / fuel) → add/remove-stop sheet.
        val poiHit = mlMap.queryRenderedFeatures(box, POI_LAYER_ID)
            .firstOrNull { it.hasProperty(POI_PROP_LAT) }
        if (poiHit != null) {
            val kind = poiHit.getStringProperty(POI_PROP_KIND) ?: "fuel"
            val name = poiHit.getStringProperty(POI_PROP_NAME).orEmpty()
            val lat = poiHit.getNumberProperty(POI_PROP_LAT).toDouble()
            val lng = poiHit.getNumberProperty(POI_PROP_LNG).toDouble()
            showPoiSheet(kind, name, lat, lng)
            return true
        }

        // 3) Route line LAST — only when no marker was hit. Tapping an alternate's
        //    line selects it (preview) / switches the active route (navigating).
        if (previewRoutes.isNotEmpty()) {
            val altHit = mlMap.queryRenderedFeatures(box, ALT_ROUTE_LAYER_ID)
                .firstOrNull { it.hasProperty("idx") }
            if (altHit != null) {
                val idx = altHit.getNumberProperty("idx").toInt()
                if (navigating) switchToRouteDuringNav(idx) else onRouteOptionSelected(idx)
                return true
            }
        }
        return false
    }

    private fun showHazardSheet(
        id: String,
        type: Int,
        severity: Int,
        confidence: Double,
        status: Int,
        observations: Int
    ) {
        val view = layoutInflater.inflate(R.layout.sheet_roadsense_hazard, null)
        val dialog = BottomSheetDialog(this, R.style.Theme_Overdrive_M3_BottomSheet).apply {
            setContentView(view)
            setCancelable(true)
        }
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        // Header
        view.findViewById<ImageView>(R.id.ivHazardIcon).setImageResource(iconForType(type))
        view.findViewById<TextView>(R.id.tvHazardType).setText(typeLabelRes(type))

        val statusChip = view.findViewById<Chip>(R.id.chipStatus)
        statusChip.setText(statusLabelRes(status))

        val confidencePct = (confidence * 100.0).toInt().coerceIn(0, 100)
        val reportsText = resources.getQuantityString(
            R.plurals.roadsense_map_reports, observations, observations
        )
        view.findViewById<TextView>(R.id.tvHazardMeta).text = getString(
            R.string.roadsense_map_meta_format,
            getString(severityLabelRes(severity)),
            confidencePct,
            reportsText
        )

        // Pre-check the correction chips at the hazard's current values.
        val sevGroup = view.findViewById<ChipGroup>(R.id.chipGroupSeverity)
        sevGroup.check(
            when (severity) {
                1 -> R.id.chipSevMinor
                3 -> R.id.chipSevSevere
                else -> R.id.chipSevModerate
            }
        )
        val typeGroup = view.findViewById<ChipGroup>(R.id.chipGroupType)
        typeGroup.check(
            when (type) {
                0 -> R.id.chipTypeBreaker
                1 -> R.id.chipTypePothole
                3 -> R.id.chipTypeRough
                else -> R.id.chipTypeUnknown
            }
        )

        view.findViewById<MaterialButton>(R.id.btnHazardConfirm).setOnClickListener {
            val newSeverity = severityFromChip(sevGroup.checkedChipId)
            val newType = typeFromChip(typeGroup.checkedChipId)
            // Only send a correction when the user actually changed a value.
            val sevArg = if (newSeverity != null && newSeverity != severity) newSeverity else null
            val typeArg = if (newType != null && newType != type) newType else null
            dialog.dismiss()
            submitVerdict(id, confirm = true, severity = sevArg, type = typeArg)
        }

        view.findViewById<MaterialButton>(R.id.btnHazardDelete).setOnClickListener {
            // Brief fade on the sheet content before dismiss for a polished delete.
            view.animate().alpha(0f).setDuration(140L).withEndAction {
                dialog.dismiss()
            }.start()
            submitVerdict(id, confirm = false, severity = null, type = null)
        }

        dialog.show()
    }

    /**
     * POST the verdict on the IO executor, then on success refetch the
     * viewport (so the pin updates / disappears) and toast confirmation.
     */
    private fun submitVerdict(id: String, confirm: Boolean, severity: Int?, type: Int?) {
        ioExecutor().execute {
            val ok = if (confirm) {
                RoadSenseHazardApiClient.confirmHazard(id, severity, type)
            } else {
                RoadSenseHazardApiClient.rejectHazard(id)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (ok) {
                    fetchViewportHazards()
                    val msg = if (confirm) R.string.roadsense_map_confirmed_toast
                    else R.string.roadsense_map_deleted_toast
                    showSnackbar(getString(msg))
                } else {
                    showSnackbar(getString(R.string.roadsense_map_action_failed))
                }
            }
        }
    }

    private fun showSnackbar(text: String) {
        val root = findViewById<View>(R.id.mapRoot)
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_SHORT)
        // Anchor to a VISIBLE FAB so the snackbar sits above it: fabLocate is GONE
        // during immersive nav (fabEndNav is the one shown then), so picking the
        // hidden one floated the snackbar at the wrong height. Pick whatever's up,
        // else leave it unanchored (bottom of the screen).
        val anchor = when {
            navigating -> findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }
            else -> findViewById<View>(R.id.fabLocate)?.takeIf { it.visibility == View.VISIBLE }
        }
        if (anchor != null) bar.anchorView = anchor
        bar.show()
    }

    // ---------------------------------------------------------------------
    // Hazard visibility filter (toggle / severity)
    // ---------------------------------------------------------------------

    /** Wire the hazard-filter FAB → an M3 popup menu of visibility modes. */
    private fun setupHazardFilter() {
        hazardFilterMode = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
            .getInt(KEY_HAZARD_FILTER, HAZARD_FILTER_ALL)
        findViewById<View>(R.id.fabHazardFilter)?.setOnClickListener { anchor ->
            val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
            menu.menu.add(0, HAZARD_FILTER_ALL, 0, getString(R.string.roadsense_map_hazards_all))
            menu.menu.add(0, HAZARD_FILTER_MODERATE, 1, getString(R.string.roadsense_map_hazards_moderate))
            menu.menu.add(0, HAZARD_FILTER_SEVERE, 2, getString(R.string.roadsense_map_hazards_severe))
            menu.menu.add(0, HAZARD_FILTER_HIDDEN, 3, getString(R.string.roadsense_map_hazards_hidden))
            menu.setOnMenuItemClickListener { item ->
                applyHazardFilter(item.itemId)
                getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE).edit()
                    .putInt(KEY_HAZARD_FILTER, item.itemId).apply()
                true
            }
            menu.show()
        }
    }

    /**
     * Filter the hazard SymbolLayer live by severity (no refetch) via a data-driven
     * filter expression. HIDDEN sets the layer invisible; the others gate on the
     * `severity` property (>= threshold). Cheap — just swaps the layer filter.
     */
    private fun applyHazardFilter(mode: Int) {
        hazardFilterMode = mode
        val layer = hazardSymbolLayer ?: return
        val base = Expression.not(Expression.has(POINT_COUNT)) // never the cluster aggregate
        when (mode) {
            HAZARD_FILTER_HIDDEN ->
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
            else -> {
                layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                val minSeverity = when (mode) {
                    HAZARD_FILTER_SEVERE -> 3L
                    HAZARD_FILTER_MODERATE -> 2L
                    else -> 1L
                }
                layer.setFilter(
                    Expression.all(
                        base,
                        Expression.gte(Expression.toNumber(Expression.get(PROP_SEVERITY)),
                            Expression.literal(minSeverity))
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 2D / 3D buildings
    // ---------------------------------------------------------------------

    /**
     * Wire 2D/3D buildings and apply the persisted choice.
     *
     * IMPORTANT: the light (liberty) style ALREADY SHIPS its own `building-3d`
     * fill-extrusion layer ([LIBERTY_3D_LAYER_ID]), so in 2D mode we must HIDE
     * that native layer — otherwise 3D always showed in light theme regardless of
     * the toggle. The dark style has NO extrusion layer, so for it (and any style
     * lacking one) we ADD our own [MAP_3D_LAYER_ID] over the shared openmaptiles
     * source. [applyMap3dVisibility] then drives BOTH (whichever exist) so the
     * toggle is authoritative on every theme.
     */
    private fun setup3dBuildings(style: Style) {
        map3dEnabled = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
            .getBoolean(KEY_MAP_3D, false)
        // Only ADD our own extrusion layer when the style doesn't already provide
        // one (i.e. dark). If a native building-3d exists (liberty), we just toggle
        // ITS visibility — no second layer needed. Idempotent across style reloads.
        val hasNative = style.getLayer(LIBERTY_3D_LAYER_ID) != null
        if (!hasNative && style.getLayer(MAP_3D_LAYER_ID) == null) {
            val color = if (isNightTheme()) MAP_3D_COLOR_DARK else MAP_3D_COLOR_LIGHT
            val ext = FillExtrusionLayer(MAP_3D_LAYER_ID, MAP_3D_SOURCE).apply {
                sourceLayer = MAP_3D_SOURCE_LAYER
                minZoom = MAP_3D_MIN_ZOOM
                setProperties(
                    PropertyFactory.fillExtrusionColor(color),
                    PropertyFactory.fillExtrusionHeight(
                        Expression.get("render_height")),
                    PropertyFactory.fillExtrusionBase(
                        Expression.get("render_min_height")),
                    PropertyFactory.fillExtrusionOpacity(0.8f),
                    PropertyFactory.visibility(
                        if (map3dEnabled) Property.VISIBLE else Property.NONE)
                )
            }
            // Place under our route casing (added first in onStyleLoaded) so the
            // extruded volumes never occlude the route line or markers. If the
            // casing isn't present yet for any reason, fall back to a plain add.
            try {
                style.addLayerBelow(ext, ROUTE_CASING_LAYER_ID)
            } catch (_: Throwable) {
                style.addLayer(ext)
            }
        }
        // Drive visibility of whatever 3D layer(s) exist (native and/or ours).
        applyMap3dVisibility(style)
        updateMap3dFab()
    }

    /** Flip 2D⇄3D, persist the choice, and apply it live (no style reload). */
    private fun toggleMap3d() {
        map3dEnabled = !map3dEnabled
        getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE).edit()
            .putBoolean(KEY_MAP_3D, map3dEnabled).apply()
        map?.style?.let { applyMap3dVisibility(it) }
        updateMap3dFab()
        showSnackbar(getString(
            if (map3dEnabled) R.string.roadsense_map_3d_on else R.string.roadsense_map_3d_off))
    }

    /** Set visibility on EVERY 3D building layer present — the style's native
     *  `building-3d` (liberty) AND our added `roadsense-building-3d` (dark) — so the
     *  toggle is authoritative regardless of which the active basemap carries. */
    private fun applyMap3dVisibility(style: Style) {
        val vis = if (map3dEnabled) Property.VISIBLE else Property.NONE
        style.getLayer(LIBERTY_3D_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MAP_3D_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
    }

    /**
     * Make the basemap's place labels language-aware. OpenFreeMap vector tiles
     * carry localized name fields (`name:en`, `name:de`, `name:zh`, …) alongside
     * the local `name`. For every SymbolLayer whose text-field is the plain
     * `{name}` (or `get name`), swap in `coalesce(get name:<lang>, get name)` so a
     * label shows the user's language when the tile has it and the local name
     * otherwise. English is the tile default, so skip the rewrite for `en` (and
     * for any layer that doesn't label by `name`, e.g. house numbers) to avoid
     * needless churn. Best-effort per layer — a failure on one never aborts the
     * rest or the style load.
     */
    private fun localizeMapLabels(style: Style) {
        val lang = com.overdrive.app.navmap.nav.MapNetworking.lang
        if (lang.isEmpty() || lang == "en") return  // tiles already default to name (latin/en)
        val localized = Expression.coalesce(
            Expression.get("name:$lang"),
            Expression.get("name")
        )
        try {
            for (layer in style.layers) {
                if (layer !is SymbolLayer) continue
                val tf = layer.textField ?: continue
                // Only retarget layers that actually label by the generic name. The
                // serialized expression mentions "name" for those (e.g. {name},
                // get name, name:latin); leave number/ref/icon-only layers alone.
                val asString = tf.toString()
                if (!asString.contains("name")) continue
                if (asString.contains("ref") && !asString.contains("name")) continue
                layer.setProperties(PropertyFactory.textField(localized))
            }
        } catch (_: Throwable) {
            // Style schema variance across basemaps — never break the map over labels.
        }
    }

    /** Tint the 3D FAB to reflect the active mode (accent when 3D is on). */
    private fun updateMap3dFab() {
        findViewById<FloatingActionButton>(R.id.fabMap3d)?.let { fab ->
            val attr = if (map3dEnabled) androidx.appcompat.R.attr.colorPrimary
                       else com.google.android.material.R.attr.colorSurfaceContainerHighest
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(fab, attr)
            )
        }
    }

    // ---------------------------------------------------------------------
    // POI along route (free OSM / Overpass)
    // ---------------------------------------------------------------------

    /** Register the EV-charging + fuel POI marker bitmaps for the POI SymbolLayer. */
    private fun registerPoiIcons(style: Style) {
        // Idempotent: build the bitmap only when the style lacks the image (skip the
        // allocation on a style reload that already carries it).
        if (style.getImage(ICON_POI_CHARGING) == null)
            style.addImage(ICON_POI_CHARGING,
                buildPoiPin(R.drawable.ic_poi_charging, 0xFF2E7D32.toInt())) // green
        if (style.getImage(ICON_POI_FUEL) == null)
            style.addImage(ICON_POI_FUEL,
                buildPoiPin(R.drawable.ic_poi_fuel, 0xFF1565C0.toInt()))     // blue
    }

    /** Small rounded POI marker (colored disc + white glyph), reusing the pin compositor style. */
    private fun buildPoiPin(glyphRes: Int, bodyColor: Int): Bitmap {
        val s = (PIN_PX * 0.8f).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val r = s * 0.42f
        // No blur shadow (renders as a hard dark box on this HW; bad on light theme).
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, cx, r, body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = s * 0.04f
        }
        c.drawCircle(cx, cx, r, rim)
        val glyph = ContextCompat.getDrawable(this, glyphRes)!!.mutate()
        glyph.colorFilter = android.graphics.PorterDuffColorFilter(
            0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        val g = (r * 1.2f).toInt()
        val off = (cx - g / 2f).toInt()
        glyph.setBounds(off, off, off + g, off + g)
        glyph.draw(c)
        return bmp
    }

    // ---------------------------------------------------------------------
    // Itinerary markers (destination pin + numbered stop pins on the map)
    // ---------------------------------------------------------------------

    /**
     * Paint the itinerary markers for [route] onto the marker source: one
     * numbered teardrop pin per intermediate via-stop (1..n) and a distinct
     * destination flag pin at the final point. Origin is the live puck, so it's
     * never marked here. [stops] is the ordered itinerary AFTER the origin
     * ([stop1, …, destination]); when empty (e.g. the cluster mirror has no
     * routeStops) the destination falls back to the route's last polyline point
     * so a pin still lands at the end. Idempotent registration of each bitmap.
     */
    private fun renderItineraryMarkers(route: NavRoute, stops: List<SearchResult>) {
        val style = map?.style ?: return
        if (route.points.isEmpty()) { markerSource?.setGeoJson(EMPTY_FEATURE_COLLECTION); return }

        // Destination color (route accent) + stop color (amber), theme-stable.
        val destColor = 0xFFE53935.toInt()   // red — the trip endpoint
        val stopColor = 0xFF1565C0.toInt()   // blue — intermediate via-stops

        // Destination pin (registered once).
        if (style.getImage(ICON_DEST) == null) {
            style.addImage(ICON_DEST, buildDestinationPin(destColor))
        }

        val features = StringBuilder("[")
        var first = true
        fun addFeature(lat: Double, lng: Double, img: String) {
            if (!first) features.append(","); first = false
            features.append(
                "{\"type\":\"Feature\",\"properties\":{\"$MARKER_PROP_IMG\":\"$img\"}," +
                    "\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lng,$lat]}}"
            )
        }

        if (stops.size >= 2) {
            // Numbered via-stops (all but the last entry), then the destination.
            val lastIdx = stops.size - 1
            for (i in 0 until lastIdx) {
                val ordinal = i + 1
                val img = ICON_STOP_PREFIX + ordinal
                if (style.getImage(img) == null) {
                    style.addImage(img, buildStopPin(stopColor, ordinal))
                    if (ordinal > maxStopIconRegistered) maxStopIconRegistered = ordinal
                }
                addFeature(stops[i].lat, stops[i].lng, img)
            }
            addFeature(stops[lastIdx].lat, stops[lastIdx].lng, ICON_DEST)
        } else if (stops.size == 1) {
            addFeature(stops[0].lat, stops[0].lng, ICON_DEST)
        } else {
            // No itinerary metadata (cluster mirror) — pin the polyline's end.
            val end = route.points.last()
            addFeature(end.lat, end.lng, ICON_DEST)
        }
        features.append("]")
        markerSource?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":$features}")
    }

    /** Clear all itinerary markers. */
    private fun clearItineraryMarkers() {
        markerSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    }

    /**
     * A SOTA destination marker: a teardrop pin (route-accent body + white rim)
     * with a checkered-flag glyph in a white inner disc — the universal "trip
     * end" affordance. Same teardrop geometry as the hazard pins so it reads as
     * part of the same marker family; tip at the bottom for "bottom" anchoring.
     */
    private fun buildDestinationPin(bodyColor: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f
        val headCy = bodyR + w * 0.06f
        val tipY = h - w * 0.06f

        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)

        // White glyph well.
        val discR = bodyR * 0.62f
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, discR, disc)

        // Checkered flag inside the well, drawn in the body color so it reads at a
        // glance. A 3x3 checker on a short pole — compact + unmistakable.
        drawCheckeredFlag(c, cx, headCy, discR * 1.25f, bodyColor)
        return bmp
    }

    /** Draw a small checkered flag centered on (cx,cy), fitting a [size] box, in [ink]. */
    private fun drawCheckeredFlag(c: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = android.graphics.Paint.Style.FILL
        }
        // Pole on the left.
        val poleW = size * 0.10f
        val poleLeft = cx - size * 0.42f
        val flagTop = cy - size * 0.40f
        val flagH = size * 0.52f
        c.drawRect(poleLeft, cy - size * 0.46f, poleLeft + poleW, cy + size * 0.50f, paint)
        // Checker grid (3 cols x 2 rows) to the right of the pole.
        val gridLeft = poleLeft + poleW
        val gridW = size * 0.74f
        val cols = 3; val rows = 2
        val cw = gridW / cols; val ch = flagH / rows
        // Outline the flag area faintly so the white cells read on the white disc.
        val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = android.graphics.Paint.Style.STROKE; strokeWidth = size * 0.04f
        }
        c.drawRect(gridLeft, flagTop, gridLeft + gridW, flagTop + flagH, outline)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if ((row + col) % 2 == 0) {
                    val l = gridLeft + col * cw
                    val t = flagTop + row * ch
                    c.drawRect(l, t, l + cw, t + ch, paint)
                }
            }
        }
    }

    /**
     * A numbered intermediate-stop pin: a teardrop body in [bodyColor] with a
     * white disc and the via-stop [ordinal] drawn in the body color. Matches the
     * destination/hazard marker family. Tip at the bottom for "bottom" anchoring.
     */
    private fun buildStopPin(bodyColor: Int, ordinal: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f
        val headCy = bodyR + w * 0.06f
        val tipY = h - w * 0.06f

        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)

        val discR = bodyR * 0.62f
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, discR, disc)

        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = discR * 1.5f
        }
        // Vertically center the digit baseline in the disc.
        val fm = text.fontMetrics
        val baseline = headCy - (fm.ascent + fm.descent) / 2f
        c.drawText(ordinal.toString(), cx, baseline, text)
        return bmp
    }

    /** Toggle the POI overlay; when turning on with an active route, load POIs along it. */
    private fun togglePoi() {
        poiEnabled = !poiEnabled
        findViewById<FloatingActionButton>(R.id.fabPoi)?.let { fab ->
            // Resolve via theme attrs (not fixed _light colors) so the active/idle
            // tint is correct in both day and night.
            val attr = if (poiEnabled) androidx.appcompat.R.attr.colorPrimary
                       else com.google.android.material.R.attr.colorSurfaceContainerHighest
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(fab, attr)
            )
        }
        if (!poiEnabled) { poiSource?.setGeoJson(EMPTY_FEATURE_COLLECTION); return }
        // If a route is active, search its corridor; otherwise search the current
        // map viewport so the user can browse charging/fuel near them with no route.
        val active = previewRoutes.getOrNull(previewSelectedIdx)
        if (active != null) loadPoisAlong(active.points)
        else loadPoisInViewport()
    }

    /** Load POIs across the current visible map bounds (no active route). */
    private fun loadPoisInViewport() {
        val mlMap = map ?: return
        val b = mlMap.projection.visibleRegion.latLngBounds
        // Corner points define the bbox; poisAlongRoute pads + bboxes internally.
        val corners = listOf(
            com.overdrive.app.navmap.nav.GeoPoint(b.getLatSouth(), b.getLonWest()),
            com.overdrive.app.navmap.nav.GeoPoint(b.getLatNorth(), b.getLonEast())
        )
        loadPoisAlong(corners)
    }

    /** Query OSM (Overpass) for charging+fuel near the given points' bbox; render as markers. */
    private fun loadPoisAlong(points: List<com.overdrive.app.navmap.nav.GeoPoint>) {
        showSnackbar(getString(R.string.roadsense_map_poi_loading))
        ioExecutor().execute {
            val pois = com.overdrive.app.navmap.nav.OverpassPoiClient.poisAlongRoute(
                points,
                setOf(com.overdrive.app.navmap.nav.PoiKind.CHARGING,
                    com.overdrive.app.navmap.nav.PoiKind.FUEL)
            )
            val fc = StringBuilder("[")
            pois.forEachIndexed { i, p ->
                if (i > 0) fc.append(",")
                val kind = if (p.kind == com.overdrive.app.navmap.nav.PoiKind.CHARGING) "charging" else "fuel"
                // Escape the name for JSON (quotes/backslashes) — it comes from OSM.
                val safeName = org.json.JSONObject.quote(p.name)
                fc.append("{\"type\":\"Feature\",\"properties\":{")
                fc.append("\"$POI_PROP_KIND\":\"$kind\",")
                fc.append("\"$POI_PROP_NAME\":$safeName,")
                fc.append("\"$POI_PROP_LAT\":${p.lat},\"$POI_PROP_LNG\":${p.lng}},")
                fc.append("\"geometry\":{\"type\":\"Point\",\"coordinates\":[${p.lng},${p.lat}]}}")
            }
            fc.append("]")
            mainHandler.post {
                if (isFinishing || isDestroyed || !poiEnabled) return@post
                poiSource?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":$fc}")
                if (pois.isEmpty()) showSnackbar(getString(R.string.roadsense_map_poi_none))
            }
        }
    }

    /**
     * Tapping a charging/fuel POI opens an M3 bottom sheet with the place name +
     * type and a single primary action: ADD it as a stop, or — if it's already in
     * the itinerary — REMOVE it. Adding inserts it as an intermediate stop (before
     * the destination) and recomputes; if there's no destination yet, it becomes
     * the destination. Tapping "Navigate here" sets it as the destination outright.
     */
    private fun showPoiSheet(kind: String, name: String, lat: Double, lng: Double) {
        val title = name.ifBlank {
            getString(if (kind == "charging") R.string.roadsense_map_poi_charging_generic
                      else R.string.roadsense_map_poi_fuel_generic)
        }
        val label = title
        val existingIdx = routeStops.indexOfFirst {
            kotlin.math.abs(it.lat - lat) < 1e-5 && kotlin.math.abs(it.lng - lng) < 1e-5
        }
        val isStop = existingIdx >= 0

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.Theme_Overdrive_M3_BottomSheet
        )
        val view = layoutInflater.inflate(R.layout.sheet_poi_action, null)
        view.findViewById<TextView>(R.id.tvPoiName).text = title
        view.findViewById<TextView>(R.id.tvPoiKind).setText(
            if (kind == "charging") R.string.roadsense_map_poi_kind_charging
            else R.string.roadsense_map_poi_kind_fuel
        )
        view.findViewById<ImageView>(R.id.ivPoiIcon).setImageResource(
            if (kind == "charging") R.drawable.ic_poi_charging else R.drawable.ic_poi_fuel
        )

        val btnStop = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPoiStop)
        btnStop.setText(if (isStop) R.string.roadsense_map_remove_stop_action
                        else R.string.roadsense_map_add_stop)
        btnStop.setIconResource(if (isStop) R.drawable.ic_clear else R.drawable.ic_add)
        btnStop.setOnClickListener {
            sheet.dismiss()
            if (isStop) removePoiStop(existingIdx)
            else addPoiAsStop(SearchResult(label, lat, lng))
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPoiNavigate)
            ?.setOnClickListener {
                sheet.dismiss()
                onSearchResultChosen(SearchResult(label, lat, lng)) // default → destination
            }

        sheet.setContentView(view)
        sheet.show()
    }

    /** Insert a POI as an intermediate stop (before the destination) + recompute. */
    private fun addPoiAsStop(result: SearchResult) {
        if (routeStops.size - 1 >= MAX_STOPS) { // already at the via-stop cap
            showSnackbar(getString(R.string.roadsense_map_max_stops, MAX_STOPS)); return
        }
        if (routeStops.isEmpty()) routeStops.add(result)            // becomes destination
        else routeStops.add(routeStops.size - 1, result)           // before destination
        RecentSearchStore.add(applicationContext, result)
        recomputeItinerary()
        showSnackbar(getString(R.string.roadsense_map_stop_added))
    }

    /** Remove a POI that's currently an itinerary stop + recompute (or clear). */
    private fun removePoiStop(index: Int) {
        if (index !in routeStops.indices) return
        routeStops.removeAt(index)
        showSnackbar(getString(R.string.roadsense_map_stop_removed))
        if (routeStops.isEmpty()) clearRoutePreview() else recomputeItinerary()
    }

    // ---------------------------------------------------------------------
    // Navigation — destination search, route fetch/render, turn-by-turn
    // ---------------------------------------------------------------------

    /**
     * Wire the search field: type-ahead autocomplete (debounced Photon) into the
     * dropdown RecyclerView, a clear (X) button, IME-submit fallback, and the
     * leading glyph as a submit affordance.
     */
    private fun setupSearch() {
        val input = findViewById<android.widget.EditText>(R.id.navSearchInput)
        val clear = findViewById<View>(R.id.navSearchClear)
        val results = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.navSearchResults)
        results?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        results?.adapter = searchAdapter

        input?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                clear?.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                scheduleAutocomplete(q)
            }
        })
        input?.setOnEditorActionListener { v, _, _ ->
            // Submit fallback: take the first autocomplete row if present, else
            // do a full (Photon→Nominatim) search and route to the top hit.
            submitSearch(v.text?.toString().orEmpty())
            hideKeyboard(v)
            true
        }
        // Gmaps-style: focusing the empty field reveals recent destinations;
        // losing focus hides the dropdown so it doesn't linger on top of the map.
        input?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (input.text.isNullOrEmpty()) showRecentSearches()
            } else {
                hideSearchDropdown()
            }
        }
        // Tapping the field (even when already focused) re-reveals recents/results.
        input?.setOnClickListener {
            if (input.text.isNullOrEmpty()) showRecentSearches()
        }
        findViewById<View>(R.id.navSearchButton)?.setOnClickListener {
            submitSearch(input?.text?.toString().orEmpty())
            input?.let { hideKeyboard(it) }
        }
        clear?.setOnClickListener {
            input?.setText("")
            hideSearchDropdown()
        }
    }

    /** Debounce per-keystroke autocomplete (~300ms); <3 chars hides the dropdown. */
    private fun scheduleAutocomplete(query: String) {
        pendingAutocomplete?.let { mainHandler.removeCallbacks(it) }
        val q = query.trim()
        if (q.length < AUTOCOMPLETE_MIN_CHARS) {
            hideSearchDropdown()
            return
        }
        val r = Runnable { runAutocomplete(q) }
        pendingAutocomplete = r
        mainHandler.postDelayed(r, AUTOCOMPLETE_DEBOUNCE_MS)
    }

    /** Photon typeahead off the looper; a monotonic token discards stale results. */
    private fun runAutocomplete(query: String) {
        val focus = lastFix
        val token = ++acToken
        ioExecutor().execute {
            val results = ForwardGeocoder.autocomplete(query, 6, focus?.lat, focus?.lng)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (token != acToken) return@post // superseded by a newer query
                if (results.isEmpty()) { hideSearchDropdown(); return@post }
                searchAdapter.submitList(results)
                showSearchDropdown()
            }
        }
    }

    private fun showSearchDropdown() {
        // Only show while the search field actually has focus — guards against a
        // late autocomplete/recents result re-revealing the dropdown after the
        // user already dismissed it (tap map / clear / chose a result).
        if (findViewById<android.widget.EditText>(R.id.navSearchInput)?.isFocused != true) return
        val dd = findViewById<View>(R.id.navSearchDropdown) ?: return
        dd.animate().cancel()
        if (dd.visibility != View.VISIBLE) {
            dd.alpha = 0f
            dd.visibility = View.VISIBLE
            dd.animate().alpha(1f).setDuration(140L).start()
        }
    }

    private fun hideSearchDropdown() {
        // Cancel any pending autocomplete + invalidate in-flight results so a
        // stale fetch can't re-show the dropdown after this dismissal (the token
        // bump makes already-dispatched IO fail the runAutocomplete token guard).
        pendingAutocomplete?.let { mainHandler.removeCallbacks(it) }
        pendingAutocomplete = null
        acToken++
        val dd = findViewById<View>(R.id.navSearchDropdown) ?: return
        dd.animate().cancel()
        if (dd.visibility == View.VISIBLE) {
            dd.animate().alpha(0f).setDuration(120L).withEndAction {
                dd.visibility = View.GONE
            }.start()
        }
    }

    /**
     * A row in the autocomplete dropdown was tapped. Three modes:
     *   - EDIT (editingStopIndex >= 0): replace that itinerary entry in place.
     *   - ADD  (addingStop): insert as an intermediate stop before the destination.
     *   - DEFAULT: set the destination (clears any existing itinerary), Gmaps-style.
     * All three then recompute + re-preview the trip.
     */
    private fun onSearchResultChosen(result: SearchResult) {
        findViewById<android.widget.EditText>(R.id.navSearchInput)?.let {
            it.setText(result.label)
            hideKeyboard(it)
            it.clearFocus()
        }
        hideSearchDropdown()
        // Remember this place for the recent-searches list.
        RecentSearchStore.add(applicationContext, result)

        val editIdx = editingStopIndex
        val adding = addingStop
        // Reset the entry modes regardless of which branch we take.
        editingStopIndex = -1
        addingStop = false

        when {
            editIdx in routeStops.indices -> {
                routeStops[editIdx] = result
            }
            adding -> {
                // Insert before the destination (the last entry). If there's no
                // destination yet, this becomes the destination.
                if (routeStops.isEmpty()) {
                    routeStops.add(result)
                } else {
                    routeStops.add(routeStops.size - 1, result)
                }
            }
            else -> {
                // Default: a brand-new destination replaces the whole itinerary.
                routeStops.clear()
                routeStops.add(result)
            }
        }
        recomputeItinerary()
    }

    /** Show recent destinations when the (empty) search field gains focus. */
    private fun showRecentSearches() {
        val recents = RecentSearchStore.getAll(applicationContext)
        if (recents.isEmpty()) { hideSearchDropdown(); return }
        searchAdapter.submitList(recents)
        showSearchDropdown()
    }

    /**
     * IME/glyph submit: full search, then treat the best hit exactly like a
     * tapped autocomplete row — so it honors add-stop / edit-stop mode (not just
     * "set destination"). [onSearchResultChosen] handles the routing + recompute.
     */
    private fun submitSearch(query: String) {
        if (query.isBlank()) return
        hideSearchDropdown()
        val focus = lastFix
        showSnackbar(getString(R.string.roadsense_map_searching))
        ioExecutor().execute {
            val results = ForwardGeocoder.search(query.trim(), 1, focus?.lat, focus?.lng)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val top = results.firstOrNull()
                if (top == null) { showSnackbar(getString(R.string.roadsense_map_no_results)); return@post }
                onSearchResultChosen(top)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Route preview (alternates) → choose → Start guidance
    // ---------------------------------------------------------------------

    /**
     * Set the chosen place as the trip DESTINATION (replacing any existing
     * itinerary, Gmaps-style) and preview. This is the default search entry
     * point (IME/submit + the autocomplete default branch route through here or
     * onSearchResultChosen). Delegates to [recomputeItinerary] once the
     * destination is the sole itinerary entry.
     */
    private fun previewRoutes(destLat: Double, destLng: Double, destLabel: String) {
        routeStops.clear()
        routeStops.add(SearchResult(destLabel, destLat, destLng))
        recomputeItinerary()
    }

    /**
     * Recompute + preview the current itinerary ([routeStops]) from the live GPS
     * origin. Picks the routing strategy by stop count:
     *   - exactly 1 entry (just a destination) → routesWithAlternates so the user
     *     still gets alternate routes for the simple case;
     *   - more than 1 entry (≥1 via-stop + destination) → routeVia through the
     *     ordered [origin, stop1, …, destination] (Valhalla returns one route, no
     *     alternates on multipoint — expected).
     * Renders the returned route(s) exactly like the single-destination flow.
     * Network runs on the IO executor; results post back guarded. Requires a GPS
     * fix (origin) and a configured routing key — both surfaced via snackbar.
     */
    private fun recomputeItinerary() {
        // The itinerary view should reflect the current stops immediately even
        // while the route is being (re)computed.
        if (routeStops.isEmpty()) {
            clearRoutePreview()
            return
        }
        val origin = lastFix
        if (origin == null) {
            showSnackbar(getString(R.string.roadsense_map_no_location))
            return
        }
        val destination = routeStops.last()
        val destLat = destination.lat
        val destLng = destination.lng
        val destLabel = destination.label
        // Snapshot the itinerary for the worker so a concurrent edit can't desync.
        val stopsSnapshot = ArrayList(routeStops)
        val useVia = stopsSnapshot.size > 1

        showSnackbar(getString(R.string.roadsense_map_routing))
        ioExecutor().execute {
            val routes = if (useVia) {
                val pts = ArrayList<com.overdrive.app.navmap.nav.GeoPoint>(stopsSnapshot.size + 1)
                pts.add(com.overdrive.app.navmap.nav.GeoPoint(origin.lat, origin.lng))
                stopsSnapshot.forEach { pts.add(com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng)) }
                ValhallaRouteClient.routeVia(pts)
            } else {
                ValhallaRouteClient.routesWithAlternates(
                    origin.lat, origin.lng, destLat, destLng, ROUTE_ALTERNATES
                )
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (routes.isEmpty()) {
                    val cfg = NavMapConfig.fromUnifiedConfig()
                    showSnackbar(
                        if (cfg.routingApiKey.isNullOrEmpty())
                            getString(R.string.roadsense_map_need_key)
                        else getString(R.string.roadsense_map_route_failed)
                    )
                    return@post
                }
                previewRoutes = routes
                previewDestLabel = destLabel
                previewSelectedIdx = 0
                // Lock the destination so off-route auto-reroute + in-nav route
                // switching always target the SAME place.
                lockedDestLat = destLat
                lockedDestLng = destLng
                drawRoutePreview(routes, 0)
                showRouteOptionsSheet(routes, destLabel)
                frameRoutes(routes)
                loadHazardCountsForRoutes(routes)
            }
        }
    }

    /**
     * Count RoadSense hazards along each candidate route, by severity, and feed
     * the route-options adapter so each row shows severe/moderate/minor pills.
     * Fetches hazards once for the routes' combined bbox (the same daemon GeoJSON
     * endpoint the map uses), then assigns each hazard to a route if it lies
     * within HAZARD_CORRIDOR_M of any of that route's segments. All off-thread.
     */
    private fun loadHazardCountsForRoutes(routes: List<NavRoute>) {
        if (routes.isEmpty()) return
        // Combined bbox over all routes (small padding).
        var minLat = Double.MAX_VALUE; var minLng = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        routes.forEach { r -> r.points.forEach { p ->
            if (p.lat < minLat) minLat = p.lat; if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng; if (p.lng > maxLng) maxLng = p.lng
        } }
        if (minLat > maxLat) return
        val pad = 0.01
        ioExecutor().execute {
            val geo = RoadSenseHazardApiClient.fetchHazardsGeoJson(
                minLng - pad, minLat - pad, maxLng + pad, maxLat + pad
            ) ?: return@execute
            // Parse hazard points (lng,lat,severity) from the GeoJSON. Cap the list
            // at HAZARD_COUNT_CAP so a pathologically dense bbox can't blow up the
            // O(hazards × segments) corridor scan below — the per-route pills only
            // need a representative count, not an exhaustive one.
            data class Hz(val lat: Double, val lng: Double, val sev: Int)
            val hazards = ArrayList<Hz>()
            try {
                val feats = org.json.JSONObject(geo).optJSONArray("features") ?: org.json.JSONArray()
                var i = 0
                while (i < feats.length() && hazards.size < HAZARD_COUNT_CAP) {
                    val f = feats.optJSONObject(i)
                    i++
                    if (f == null) continue
                    val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    val props = f.optJSONObject("properties") ?: continue
                    hazards.add(Hz(coords.optDouble(1), coords.optDouble(0), props.optInt("severity", 1)))
                }
            } catch (_: Throwable) { return@execute }
            if (hazards.isEmpty()) {
                // No RoadSense data anywhere in the area → every route is "not mapped yet".
                mainHandler.post {
                    routeOptionAdapter.setHazardCounts(routes.map {
                        NavRouteOptionAdapter.RouteHazardInfo(0, 0, 0, mapped = false)
                    })
                }
                return@execute
            }
            // Hazards exist in the area, so it IS surveyed: a route with 0 hits is
            // genuinely clear (mapped=true), not un-mapped.
            // Per-route bbox (padded by ~the corridor width in degrees) computed once,
            // so hazards far from a route skip the expensive point-to-segment scan.
            // Pure pre-reject: a hazard inside the corridor is always inside the bbox,
            // so the surviving counts are identical to scanning every hazard.
            val counts = routes.map { route ->
                var bMinLat = Double.MAX_VALUE; var bMinLng = Double.MAX_VALUE
                var bMaxLat = -Double.MAX_VALUE; var bMaxLng = -Double.MAX_VALUE
                route.points.forEach { p ->
                    if (p.lat < bMinLat) bMinLat = p.lat; if (p.lat > bMaxLat) bMaxLat = p.lat
                    if (p.lng < bMinLng) bMinLng = p.lng; if (p.lng > bMaxLng) bMaxLng = p.lng
                }
                bMinLat -= HAZARD_CORRIDOR_DEG; bMaxLat += HAZARD_CORRIDOR_DEG
                bMinLng -= HAZARD_CORRIDOR_DEG; bMaxLng += HAZARD_CORRIDOR_DEG
                var severe = 0; var moderate = 0; var minor = 0
                for (h in hazards) {
                    // Cheap bbox pre-reject before the per-segment distance loop.
                    if (h.lat < bMinLat || h.lat > bMaxLat || h.lng < bMinLng || h.lng > bMaxLng) continue
                    if (hazardNearRoute(h.lat, h.lng, route)) {
                        when (h.sev) { 3 -> severe++; 2 -> moderate++; else -> minor++ }
                    }
                }
                NavRouteOptionAdapter.RouteHazardInfo(severe, moderate, minor, mapped = true)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                routeOptionAdapter.setHazardCounts(counts)
            }
        }
    }

    /** True if (lat,lng) is within HAZARD_CORRIDOR_M of any segment of [route]. */
    private fun hazardNearRoute(lat: Double, lng: Double, route: NavRoute): Boolean {
        val pts = route.points
        for (i in 0 until pts.size - 1) {
            val d = guidance.pointToSegmentMeters(
                lat, lng, pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng
            )
            if (d <= HAZARD_CORRIDOR_M) return true
        }
        return false
    }

    /** Draw all candidate routes: alternates dimmed in one source, selected bright on top. */
    private fun drawRoutePreview(routes: List<NavRoute>, selectedIdx: Int) {
        // Alternates (everything except the selected) into the dimmed source,
        // each tagged with its original index for tap-to-select.
        val altFeatures = StringBuilder("[")
        var first = true
        routes.forEachIndexed { idx, r ->
            if (idx == selectedIdx) return@forEachIndexed
            if (!first) altFeatures.append(","); first = false
            altFeatures.append(routeFeature(r, idx))
        }
        altFeatures.append("]")
        altRouteSource?.setGeoJson(
            "{\"type\":\"FeatureCollection\",\"features\":$altFeatures}"
        )
        // Selected route into the bright (primary) source.
        routeSource?.setGeoJson(lineFeature(routes[selectedIdx]))
        // Destination + numbered stop pins for the selected route's itinerary.
        renderItineraryMarkers(routes[selectedIdx], routeStops)
    }

    /** A LineString Feature carrying its route index (for queryRenderedFeatures). */
    private fun routeFeature(route: NavRoute, idx: Int): String {
        return "{\"type\":\"Feature\",\"properties\":{\"idx\":$idx}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":${coordArray(route)}}}"
    }

    private fun lineFeature(route: NavRoute): String =
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":${coordArray(route)}}}"

    /**
     * Cache of serialized coordinate arrays keyed on the route instance. The preview
     * flow re-serializes the same routes repeatedly (select / redraw alternates), so
     * memoize per route reference. Cleared in [clearRoutePreview] when the routes are
     * discarded — the set of live routes is tiny (≤ ROUTE_ALTERNATES+1).
     */
    private val coordArrayCache = HashMap<NavRoute, String>()

    private fun coordArray(route: NavRoute): String {
        coordArrayCache[route]?.let { return it }
        val sb = StringBuilder("[")
        route.points.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("[").append(p.lng).append(",").append(p.lat).append("]")
        }
        val s = sb.append("]").toString()
        coordArrayCache[route] = s
        return s
    }

    private fun frameRoutes(routes: List<NavRoute>) {
        val b = LatLngBounds.Builder()
        var any = false
        routes.forEach { r -> r.points.forEach { b.include(LatLng(it.lat, it.lng)); any = true } }
        if (!any) return
        try {
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 140), RECENTER_ANIM_MS)
        } catch (_: Throwable) {}
    }

    private fun showRouteOptionsSheet(routes: List<NavRoute>, destLabel: String) {
        val sheet = findViewById<View>(R.id.routeOptionsSheet) ?: return
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.routeOptionsList)
        list?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        list?.adapter = routeOptionAdapter
        routeOptionAdapter.setRoutes(routes)

        findViewById<TextView>(R.id.tvRouteSheetTitle)?.text =
            getString(R.string.roadsense_map_routes_to, destLabel)

        findViewById<View>(R.id.btnRouteSheetClose)?.setOnClickListener { clearRoutePreview() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRouteStart)
            ?.setOnClickListener { startSelectedRoute() }

        // Build the ordered trip itinerary (origin → stops → destination + an
        // "Add stop" row) above the route candidates.
        rebuildStopsUi()

        sheet.visibility = View.VISIBLE
        if (routeSheetBehavior == null) {
            routeSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        }
        routeSheetBehavior?.apply {
            isHideable = true
            // Open EXPANDED so the Start CTA + all route rows are visible — a
            // collapsed peek hid the Start button below the fold.
            skipCollapsed = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /** A route-options row (or its map line) was selected → re-highlight + remember. */
    private fun onRouteOptionSelected(idx: Int) {
        if (idx < 0 || idx >= previewRoutes.size) return
        previewSelectedIdx = idx
        routeOptionAdapter.selectIndex(idx)
        drawRoutePreview(previewRoutes, idx)
    }

    // ---------------------------------------------------------------------
    // Stops / waypoints — itinerary UI (origin → stops → destination + add)
    // ---------------------------------------------------------------------

    /**
     * Rebuild the ordered itinerary list inside the route sheet from the current
     * [routeStops]. Renders, in order:
     *   - the ORIGIN row ("Your location", non-editable);
     *   - one row per itinerary entry: the last is the DESTINATION, earlier ones
     *     are intermediate STOPS. Each editable entry shows a remove (X) and, when
     *     there are ≥2 reorderable stops, up/down reorder controls;
     *   - an "Add stop" row (hidden once MAX_STOPS via-points are reached).
     * Rows are inflated/built in code into [R.id.routeStopsContainer]. The list is
     * tiny (≤ MAX_STOPS) so a code-populated LinearLayout is simpler than a
     * RecyclerView. Theme attrs only, so it's day/night-correct.
     */
    private fun rebuildStopsUi() {
        val container = findViewById<android.widget.LinearLayout>(R.id.routeStopsContainer) ?: return
        val divider = findViewById<View>(R.id.routeStopsDivider)
        container.removeAllViews()

        // No itinerary → hide the section entirely.
        if (routeStops.isEmpty()) {
            container.visibility = View.GONE
            divider?.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        divider?.visibility = View.VISIBLE

        // Origin (the live GPS fix) — always first, not part of routeStops.
        container.addView(buildOriginRow(container))

        val lastIdx = routeStops.size - 1
        // Reorder controls only make sense when there are ≥2 intermediate stops
        // (the destination is fixed as the last entry and isn't reordered).
        val stopCount = lastIdx // entries before the destination
        val reorderable = stopCount >= 2
        routeStops.forEachIndexed { idx, entry ->
            val isDestination = idx == lastIdx
            container.addView(buildStopRow(container, idx, entry, isDestination, reorderable))
        }

        // "Add stop" affordance — capped at MAX_STOPS intermediate stops (the
        // itinerary then holds MAX_STOPS stops + 1 destination).
        if (stopCount < MAX_STOPS) {
            container.addView(buildAddStopRow(container))
        }
    }

    /** Build the non-editable origin row ("Your location"). */
    private fun buildOriginRow(parent: android.view.ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_nav_stop, parent, false)
        row.findViewById<ImageView>(R.id.ivStopIcon).setImageResource(R.drawable.ic_my_location)
        row.findViewById<TextView>(R.id.tvStopLabel).text = getString(R.string.roadsense_map_your_location)
        row.findViewById<View>(R.id.tvStopOrdinal).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopRemove).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopUp).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopDown).visibility = View.GONE
        // Origin is not interactive.
        row.isClickable = false
        row.background = null
        return row
    }

    /**
     * Build one itinerary entry row. Stops/destination are tappable to EDIT
     * (re-search + replace); each carries a remove (X), and reorderable stops
     * also get up/down controls. The destination uses a distinct label + icon.
     */
    private fun buildStopRow(
        parent: android.view.ViewGroup,
        index: Int,
        entry: SearchResult,
        isDestination: Boolean,
        reorderable: Boolean
    ): View {
        val row = layoutInflater.inflate(R.layout.item_nav_stop, parent, false)
        val icon = row.findViewById<ImageView>(R.id.ivStopIcon)
        val ordinal = row.findViewById<TextView>(R.id.tvStopOrdinal)
        val label = row.findViewById<TextView>(R.id.tvStopLabel)
        val sub = row.findViewById<TextView>(R.id.tvStopSubtitle)
        val remove = row.findViewById<ImageView>(R.id.btnStopRemove)
        val up = row.findViewById<ImageView>(R.id.btnStopUp)
        val down = row.findViewById<ImageView>(R.id.btnStopDown)

        // Split "name, rest…" into a title + muted subtitle (mirrors search rows).
        val comma = entry.label.indexOf(',')
        if (comma > 0 && comma < entry.label.length - 1) {
            label.text = entry.label.substring(0, comma).trim()
            sub.text = entry.label.substring(comma + 1).trim()
            sub.visibility = View.VISIBLE
        } else {
            label.text = entry.label
            sub.visibility = View.GONE
        }

        if (isDestination) {
            icon.setImageResource(R.drawable.ic_location_pin)
            ordinal.visibility = View.GONE
        } else {
            // Intermediate stops show their 1-based via-stop ordinal.
            icon.setImageResource(R.drawable.ic_location_pin)
            ordinal.visibility = View.VISIBLE
            ordinal.text = (index + 1).toString()
        }

        // Tap the row → edit this entry (re-search + replace in place).
        row.setOnClickListener { beginEditStop(index) }

        // Remove (X) — collapses the itinerary; if the destination is removed the
        // previous stop becomes the new destination (handled in removeStop).
        remove.visibility = View.VISIBLE
        remove.contentDescription = getString(R.string.roadsense_map_remove_stop)
        remove.setOnClickListener { removeStop(index) }

        // Reorder controls only for intermediate stops, and only when ≥2 exist.
        if (reorderable && !isDestination) {
            up.visibility = View.VISIBLE
            down.visibility = View.VISIBLE
            up.contentDescription = getString(R.string.roadsense_map_move_stop_up)
            down.contentDescription = getString(R.string.roadsense_map_move_stop_down)
            // First stop can't move up; last STOP (index == lastIdx-1) can't move down.
            val lastStopIndex = routeStops.size - 2
            up.isEnabled = index > 0
            up.alpha = if (index > 0) 1f else 0.3f
            down.isEnabled = index < lastStopIndex
            down.alpha = if (index < lastStopIndex) 1f else 0.3f
            up.setOnClickListener { if (index > 0) moveStop(index, index - 1) }
            down.setOnClickListener { if (index < lastStopIndex) moveStop(index, index + 1) }
        } else {
            up.visibility = View.GONE
            down.visibility = View.GONE
        }
        return row
    }

    /** Build the "Add stop" row that puts the search into add-stop mode. */
    private fun buildAddStopRow(parent: android.view.ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_nav_stop, parent, false)
        row.findViewById<ImageView>(R.id.ivStopIcon).setImageResource(R.drawable.ic_add)
        row.findViewById<TextView>(R.id.tvStopLabel).text = getString(R.string.roadsense_map_add_stop)
        row.findViewById<View>(R.id.tvStopOrdinal).visibility = View.GONE
        row.findViewById<View>(R.id.tvStopSubtitle).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopRemove).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopUp).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopDown).visibility = View.GONE
        row.setOnClickListener { beginAddStop() }
        return row
    }

    /**
     * Enter "adding a stop" mode: reveal + focus the search field so the next
     * chosen result is inserted as an intermediate stop. Collapses the sheet so
     * the search column is reachable above it.
     */
    private fun beginAddStop() {
        if (routeStops.size - 1 >= MAX_STOPS) { // already at the via-stop cap
            showSnackbar(getString(R.string.roadsense_map_max_stops, MAX_STOPS))
            return
        }
        addingStop = true
        editingStopIndex = -1
        promptSearchForStop()
    }

    /**
     * Enter "editing a stop" mode: the next chosen result replaces the entry at
     * [index]. Implemented as a search-and-replace (simple + functional).
     */
    private fun beginEditStop(index: Int) {
        if (index !in routeStops.indices) return
        addingStop = false
        editingStopIndex = index
        promptSearchForStop()
    }

    /** Surface the search field (drop the sheet so the search column is usable). */
    private fun promptSearchForStop() {
        // Let the sheet peek so the search bar/dropdown sit clear above it.
        routeSheetBehavior?.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
        findViewById<View>(R.id.navSearchColumn)?.visibility = View.VISIBLE
        findViewById<android.widget.EditText>(R.id.navSearchInput)?.let { input ->
            input.setText("")
            input.requestFocus()
            try {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) {}
        }
        showSnackbar(getString(R.string.roadsense_map_add_stop_hint))
    }

    /**
     * Remove the itinerary entry at [index] and recompute. Removing the LAST
     * entry (the destination) promotes the previous stop to destination; removing
     * the only entry clears the whole preview.
     */
    private fun removeStop(index: Int) {
        if (index !in routeStops.indices) return
        // Any in-flight edit/add targeting indices is now invalid.
        editingStopIndex = -1
        addingStop = false
        routeStops.removeAt(index)
        showSnackbar(getString(R.string.roadsense_map_stop_removed))
        if (routeStops.isEmpty()) {
            clearRoutePreview()
        } else {
            recomputeItinerary()
        }
    }

    /** Reorder: move the stop at [from] to [to] (both must be via-stops), recompute. */
    private fun moveStop(from: Int, to: Int) {
        val lastStopIndex = routeStops.size - 2 // destination is fixed at the end
        if (from !in 0..lastStopIndex || to !in 0..lastStopIndex) return
        if (from == to) return
        val entry = routeStops.removeAt(from)
        routeStops.add(to, entry)
        recomputeItinerary()
    }

    /**
     * Start guidance on the selected preview route. The route options SHEET is
     * dismissed, but the candidate routes + their dimmed lines are KEPT so the
     * driver can still tap an alternate mid-trip to switch (Gmaps-style).
     */
    private fun startSelectedRoute() {
        val routes = previewRoutes
        if (routes.isEmpty()) return
        val chosen = routes.getOrElse(previewSelectedIdx) { routes[0] }
        val label = previewDestLabel
        hideRouteSheet()
        // Keep alternates dimmed + tappable during nav; draw the chosen bright.
        drawRoutePreview(routes, previewSelectedIdx)
        startGuidance(chosen, label)
    }

    /** Hide just the options sheet (keeps previewRoutes for in-nav switching). */
    private fun hideRouteSheet() {
        findViewById<View>(R.id.routeOptionsSheet)?.let { sheet ->
            routeSheetBehavior?.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
            sheet.visibility = View.GONE
        }
    }

    /** Fully clear the preview: routes, lines, sheet, locked destination, stops. */
    private fun clearRoutePreview(keepActive: Boolean = false) {
        previewRoutes = emptyList()
        coordArrayCache.clear()
        lockedDestLat = Double.NaN
        lockedDestLng = Double.NaN
        // Drop the whole itinerary + any pending add/edit mode so a fresh search
        // starts a brand-new trip.
        routeStops.clear()
        addingStop = false
        editingStopIndex = -1
        findViewById<android.widget.LinearLayout>(R.id.routeStopsContainer)?.removeAllViews()
        altRouteSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        if (!keepActive) {
            routeSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
            clearItineraryMarkers()
        }
        hideRouteSheet()
    }

    /**
     * Mid-navigation route switch: the driver tapped an alternate line. Re-arm the
     * guidance engine on the newly-selected route and redraw (selected bright,
     * others dimmed). Keeps the locked destination + immersive view.
     */
    private fun switchToRouteDuringNav(idx: Int) {
        if (idx < 0 || idx >= previewRoutes.size || idx == previewSelectedIdx) return
        previewSelectedIdx = idx
        val chosen = previewRoutes[idx]
        guidance.start(chosen)
        lastSpokenInstruction = null
        drawRoutePreview(previewRoutes, idx)
        showSnackbar(getString(R.string.roadsense_map_route_switched))
    }

    /** Paint the route polyline onto the route source + frame it on screen. */
    private fun renderRoute(route: NavRoute) {
        val coords = StringBuilder("[")
        route.points.forEachIndexed { i, p ->
            if (i > 0) coords.append(",")
            coords.append("[").append(p.lng).append(",").append(p.lat).append("]")
        }
        coords.append("]")
        val geoJson =
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\"," +
                "\"coordinates\":$coords}}"
        routeSource?.setGeoJson(geoJson)
        // Pin the destination at the route end (the cluster mirror has no
        // routeStops itinerary, so renderItineraryMarkers falls back to the
        // polyline's last point).
        renderItineraryMarkers(route, emptyList())

        // Frame the whole route with padding.
        if (route.points.size >= 2) {
            val b = LatLngBounds.Builder()
            route.points.forEach { b.include(LatLng(it.lat, it.lng)) }
            try {
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(b.build(), 120), RECENTER_ANIM_MS
                )
            } catch (_: Throwable) { /* degenerate bounds — ignore */ }
        }
    }

    /** Begin turn-by-turn: arm the engine, enter immersive view, start tick + voice. */
    private fun startGuidance(route: NavRoute, destLabel: String) {
        guidance.start(route)
        navigating = true
        // The infotainment (control) instance publishes the active route so the
        // view-only cluster mirror renders it in real time. The cluster instance
        // itself never publishes (it's the consumer).
        if (!clusterMode) NavSession.publishRoute(route, destLabel)
        if (navVoice == null) navVoice = NavVoice(applicationContext)
        enterImmersive()
        // Snap the camera into the immersive follow view IMMEDIATELY — do NOT wait
        // for the first GPS tick. The route-options preview left the camera at a
        // wide route-overview framing; if the car is stationary at the origin, the
        // per-tick follow's dead-band (keyed on lat/lng/bearing, not zoom/tilt)
        // would see "barely moved" and SKIP the zoom/tilt transition, leaving the
        // map stuck at the overview until the car physically moves >3m. Force the
        // transition here from the route origin + its initial heading.
        moveCameraToImmersiveStart(route)
        showManeuverBanner(getString(R.string.roadsense_map_nav_started, destLabel), route)
        findViewById<View>(R.id.navBanner)?.visibility = View.VISIBLE
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(guidanceRunnable)
        mainHandler.post(guidanceRunnable)
    }

    /**
     * Immediately glide the camera into the immersive driving view (close zoom, 3D
     * tilt, heading-up) at the route's origin, so navigation doesn't appear "stuck"
     * at the overview framing while the car is still stationary. Records the target
     * as the dead-band baseline so the per-tick follow doesn't redundantly re-animate
     * to the same spot, yet still animates once the car actually moves.
     */
    private fun moveCameraToImmersiveStart(route: NavRoute) {
        val m = map ?: return
        val pts = route.points
        // Prefer the live fix (where the car actually is); fall back to the route's
        // first vertex (the origin Valhalla routed from).
        val target = lastFix?.let { LatLng(it.lat, it.lng) }
            ?: pts.firstOrNull()?.let { LatLng(it.lat, it.lng) }
            ?: return
        // Heading-up bearing: aim down the first route segment so the view faces the
        // direction of travel even before the car moves (GPS bearing is noise at 0
        // speed). Fall back to the last camera bearing for a degenerate 1-pt route.
        val bearing = if (pts.size >= 2)
            bearingBetween(pts[0].lat, pts[0].lng, pts[1].lat, pts[1].lng) else lastBearing
        lastBearing = bearing
        val pos = org.maplibre.android.camera.CameraPosition.Builder()
            .target(target)
            .zoom(IMMERSIVE_ZOOM)
            .tilt(IMMERSIVE_TILT)
            .bearing(bearing)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), GUIDANCE_CAM_ANIM_MS)
        rememberAnimatedTarget(target.latitude, target.longitude, bearing)
    }

    /** Initial-bearing (forward azimuth) from (lat1,lng1) to (lat2,lng2), degrees 0..360. */
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = kotlin.math.sin(dLng) * kotlin.math.cos(p2)
        val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) -
            kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Enter the Waze/Gmaps-style immersive driving view: hide the search column,
     * toolbar, zoom + locate FABs, and the hazard-filter control so the map is
     * unobstructed; tilt the camera into a 3D perspective. The maneuver banner +
     * end-nav FAB stay. Heading-up rotation + close follow are applied per GPS
     * tick in tickGuidance().
     */
    private fun enterImmersive() {
        findViewById<View>(R.id.navSearchColumn)?.visibility = View.GONE
        findViewById<View>(R.id.mapAppBar)?.visibility = View.GONE
        findViewById<View>(R.id.zoomControls)?.visibility = View.GONE
        findViewById<View>(R.id.fabLocate)?.visibility = View.GONE
        // Hide the WHOLE top-control container (hazard-filter + POI), not just one
        // child — otherwise fabPoi stays floating in immersive nav.
        findViewById<View>(R.id.mapControlsTop)?.visibility = View.GONE
        hideSearchDropdown()
        // The toolbar (with its back chevron) is now hidden, so surface the
        // immersive back FAB in its place — but never on the non-touch cluster.
        findViewById<FloatingActionButton>(R.id.fabNavBack)?.visibility =
            if (clusterMode) View.GONE else View.VISIBLE
    }

    /** Restore the standard map chrome when navigation ends. */
    private fun exitImmersive() {
        // On the non-touch cluster, never restore touch chrome — re-apply the
        // glanceable cluster chrome and bail (e.g. after "arrived" ends guidance).
        if (clusterMode) { applyClusterChrome(); return }
        findViewById<View>(R.id.navSearchColumn)?.visibility = View.VISIBLE
        findViewById<View>(R.id.mapAppBar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.zoomControls)?.visibility = View.VISIBLE
        findViewById<View>(R.id.fabLocate)?.visibility = View.VISIBLE
        findViewById<View>(R.id.mapControlsTop)?.visibility = View.VISIBLE
        // The toolbar back chevron is back — retire the immersive back FAB.
        findViewById<View>(R.id.fabNavBack)?.visibility = View.GONE
        // Level the camera back to top-down north-up.
        map?.let { m ->
            val pos = org.maplibre.android.camera.CameraPosition.Builder()
                .target(m.cameraPosition.target)
                .tilt(0.0)
                .bearing(0.0)
                .zoom(DEFAULT_ZOOM)
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), RECENTER_ANIM_MS)
        }
    }

    /**
     * True when an animateCamera to ([lat],[lng],[bearing]) is close enough to the
     * last animated target to skip (within [CAM_DEADBAND_M] and [CAM_DEADBAND_DEG]).
     * Records the new target as the last animated one only when we DON'T skip
     * (callers gate the actual animate on the negation of this).
     */
    private fun cameraWithinDeadband(lat: Double, lng: Double, bearing: Double): Boolean {
        if (lastAnimatedLat.isNaN() || lastAnimatedLng.isNaN() || lastAnimatedBearing.isNaN()) {
            return false
        }
        val moved = guidance.haversineMeters(lastAnimatedLat, lastAnimatedLng, lat, lng)
        var dBearing = kotlin.math.abs(bearing - lastAnimatedBearing) % 360.0
        if (dBearing > 180.0) dBearing = 360.0 - dBearing
        return moved < CAM_DEADBAND_M && dBearing < CAM_DEADBAND_DEG
    }

    /** Record the target just handed to animateCamera (for the next dead-band check). */
    private fun rememberAnimatedTarget(lat: Double, lng: Double, bearing: Double) {
        lastAnimatedLat = lat
        lastAnimatedLng = lng
        lastAnimatedBearing = bearing
    }

    /** One guidance step: pull a fresh fix, advance the engine, update UI + voice. */
    private fun tickGuidance() {
        ioExecutor().execute {
            // Only the network GPS fetch runs on the IO thread.
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation() ?: return@execute
            mainHandler.post {
                if (isFinishing || isDestroyed || !navigating) return@post
                // NavGuidanceEngine is single-thread-contract — advance it on the
                // main thread (same thread that start()/stop() it), not the IO thread.
                val state = guidance.update(fix.lat, fix.lng)
                lastFix = fix
                updateLocationPuck(fix)
                // Immersive follow: 3D-tilted, zoomed-in, HEADING-UP — rotate the
                // map so travel direction points up (Waze/Gmaps style). Bearing
                // comes from the GPS fix when moving; falls back to the last
                // bearing when stationary (GPS bearing is noisy at 0 speed).
                val bearing = fix.bearing?.takeIf { (fix.speed ?: 0.0) > IMMERSIVE_MIN_SPEED_MPS }
                    ?: lastBearing
                lastBearing = bearing
                // Camera follows the ACTUAL vehicle position (the raw fix — same as
                // the puck), NOT the route-snapped point. The snapped position is for
                // maneuver/progress math only; using it as the camera target meant
                // that whenever the car drifted off the computed route corridor, the
                // snapped point could sit still (or crawl along the line) while the
                // real position — and the puck — moved away, so the dead-band kept
                // skipping the glide and the camera froze while the puck slid off.
                // Track the fix so the camera always stays under the puck.
                if (!cameraWithinDeadband(fix.lat, fix.lng, bearing)) {
                    map?.let { m ->
                        val pos = org.maplibre.android.camera.CameraPosition.Builder()
                            .target(LatLng(fix.lat, fix.lng))
                            .zoom(IMMERSIVE_ZOOM)
                            .tilt(IMMERSIVE_TILT)
                            .bearing(bearing)
                            .build()
                        m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), GUIDANCE_CAM_ANIM_MS)
                        rememberAnimatedTarget(fix.lat, fix.lng, bearing)
                    }
                }
                if (state.arrived) {
                    speakOnce(getString(R.string.roadsense_map_arrived))
                    showSnackbar(getString(R.string.roadsense_map_arrived))
                    stopGuidance()
                    return@post
                }
                if (state.offRoute) {
                    // Driver diverged → auto-recompute a route to the LOCKED
                    // destination from the current fix, then keep navigating.
                    maybeReroute(fix.lat, fix.lng)
                    return@post
                }
                val m = state.currentManeuver
                if (m != null) {
                    val dist = formatDistance(state.distanceToManeuverM)
                    updateBannerText("$dist • ${m.instruction}",
                        "${formatDistance(state.remainingDistanceM)} • ${formatEta(state.etaSeconds)}")
                    // Speak the instruction once when within the announce window.
                    if (state.distanceToManeuverM <= MANEUVER_ANNOUNCE_M && m.instruction != lastSpokenInstruction) {
                        speakOnce(m.instruction)
                        lastSpokenInstruction = m.instruction
                    }
                }
            }
        }
    }

    private var lastSpokenInstruction: String? = null
    /** Last camera bearing used in immersive follow (held when stationary). */
    private var lastBearing: Double = 0.0

    // Dead-band for the guidance/cluster camera follow: skip the animateCamera when
    // the new target is within ~3m of the last animated target AND |Δbearing| < ~2°,
    // so a near-stationary fix doesn't churn a 1s glide every tick. The puck + banner
    // /voice still update — only the camera animate is elided.
    private var lastAnimatedLat: Double = Double.NaN
    private var lastAnimatedLng: Double = Double.NaN
    private var lastAnimatedBearing: Double = Double.NaN

    /**
     * Auto-reroute: when the engine reports off-route, recompute a fresh route
     * from the current position to the LOCKED destination and resume guidance on
     * it. Debounced (REROUTE_MIN_INTERVAL_MS) and single-flight (`rerouting`) so a
     * burst of off-route ticks fires at most one recompute. No-op if the
     * destination isn't set (shouldn't happen while navigating).
     */
    private fun maybeReroute(fromLat: Double, fromLng: Double) {
        if (rerouting) return
        if (lockedDestLat.isNaN() || lockedDestLng.isNaN()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRerouteMs < REROUTE_MIN_INTERVAL_MS) return
        lastRerouteMs = now
        rerouting = true
        showSnackbar(getString(R.string.roadsense_map_rerouting))
        val dLat = lockedDestLat; val dLng = lockedDestLng
        ioExecutor().execute {
            val routes = ValhallaRouteClient.routesWithAlternates(
                fromLat, fromLng, dLat, dLng, ROUTE_ALTERNATES
            )
            mainHandler.post {
                rerouting = false
                if (isFinishing || isDestroyed || !navigating) return@post
                if (routes.isEmpty()) return@post // keep the old route; try again next divergence
                previewRoutes = routes
                previewSelectedIdx = 0
                guidance.start(routes[0])
                lastSpokenInstruction = null
                drawRoutePreview(routes, 0)
            }
        }
    }

    private fun stopGuidance() {
        navigating = false
        mainHandler.removeCallbacks(guidanceRunnable)
        guidance.stop()
        if (!clusterMode) NavSession.clear()   // tell the cluster mirror nav ended
        lastSpokenInstruction = null
        routeSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        altRouteSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        clearItineraryMarkers()
        findViewById<View>(R.id.navBanner)?.visibility = View.GONE
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.visibility = View.GONE
        exitImmersive()
        // Clear the navigation POIs (they were route-specific).
        poiSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    }

    private fun speakOnce(text: String) {
        try { navVoice?.speak(text) } catch (_: Throwable) {}
    }

    private fun showManeuverBanner(primary: String, route: NavRoute) {
        updateBannerText(primary,
            "${formatDistance(route.totalDistanceMeters)} • ${formatEta(route.totalDurationSeconds)}")
    }

    private fun updateBannerText(primary: String, secondary: String) {
        findViewById<TextView>(R.id.navBannerPrimary)?.text = primary
        findViewById<TextView>(R.id.navBannerSecondary)?.text = secondary
    }

    // Honours the user's Trips km/miles preference (shared formatter).
    private fun formatDistance(m: Double): String =
        com.overdrive.app.navmap.nav.MapNetworking.formatDistance(m)

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60.0).toInt()
        return if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
    }

    // ---------------------------------------------------------------------
    // Recenter
    // ---------------------------------------------------------------------

    /**
     * Recenter affordance — eases the camera to the live GPS fix from the
     * daemon ([RoadSenseHazardApiClient.fetchCurrentLocation], GET /api/gps,
     * which auto-starts tracking). Runs the fetch off the looper; on success
     * animates to the fix, drops/updates the location puck, and schedules an
     * offline prefetch around it. Falls back to the default region only when
     * no fix is available yet (toast so the tap isn't silent). A button press
     * is the one place a brief default-region ease is acceptable.
     */
    private fun recenter() {
        ioExecutor().execute {
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (fix != null) {
                    lastFix = fix
                    map?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), FOLLOW_ZOOM),
                        RECENTER_ANIM_MS
                    )
                    updateLocationPuck(fix)
                    MapTilePrefetcher.schedulePrefetch(
                        applicationContext, STYLE_URL, fix.lat, fix.lng, resources.displayMetrics.density
                    )
                } else {
                    showSnackbar(getString(R.string.roadsense_map_no_location))
                }
            }
        }
    }

    /**
     * Draw/move the location puck — a soft accuracy halo (CircleLayer) with a
     * DIRECTIONAL arrow on top (SymbolLayer) that rotates to the travel heading,
     * Gmaps-style. The feature carries a `bearing` property driving iconRotate;
     * when no bearing is known (stationary / no fix heading) it falls back to the
     * last camera bearing so the arrow doesn't snap to north. Created lazily on
     * the first fix; kept above the hazard layers so it's never occluded.
     */
    private fun updateLocationPuck(fix: RoadSenseHazardApiClient.LatLngFix) {
        val style = map?.style ?: return
        val bearing = fix.bearing?.takeIf { (fix.speed ?: 0.0) > IMMERSIVE_MIN_SPEED_MPS } ?: lastBearing
        val pointJson =
            "{\"type\":\"Feature\",\"properties\":{\"bearing\":$bearing}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${fix.lng},${fix.lat}]}}"
        val existing = style.getSourceAs<GeoJsonSource>(PUCK_SOURCE_ID)
        if (existing != null) {
            existing.setGeoJson(pointJson)
            return
        }
        val accent = ContextCompat.getColor(this, R.color.md_sys_color_primary_light)
        // Register the directional arrow bitmap once.
        if (style.getImage(ICON_PUCK_ARROW) == null) {
            style.addImage(ICON_PUCK_ARROW, buildDirectionArrow(accent))
        }
        style.addSource(GeoJsonSource(PUCK_SOURCE_ID, pointJson))
        // Soft accuracy halo underneath.
        style.addLayer(
            CircleLayer(PUCK_HALO_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleOpacity(0.18f)
            )
        )
        // Directional arrow, rotated to the heading property (rotation aligned to
        // the map so it points the travel direction regardless of map bearing).
        style.addLayer(
            SymbolLayer(PUCK_CORE_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(ICON_PUCK_ARROW),
                PropertyFactory.iconSize(0.85f),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    /**
     * Build a SOTA 3D-style navigation arrow puck (Gmaps/Waze look): a chevron
     * with a vertical light→dark gradient (lit front edge, shaded tail) for a
     * dimensional feel + a crisp white outline so it reads on any basemap. Points
     * "up" (north) at rotation 0 so iconRotate(bearing) aims it at the travel
     * heading. NO blur drop-shadow: BlurMaskFilter renders as a hard dark box on
     * this head unit (same reason the marker pins dropped theirs) — the white
     * outline provides the lift/separation instead.
     */
    private fun buildDirectionArrow(accent: Int): Bitmap {
        val s = (PIN_PX * 0.78f).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.40f

        // Chevron geometry: a sharp arrowhead with a concave tail notch.
        fun arrowPath(scale: Float, dy: Float) = android.graphics.Path().apply {
            moveTo(cx, cy - r * 0.78f * scale + dy)            // tip
            lineTo(cx + r * 0.62f * scale, cy + r * 0.66f * scale + dy) // back-right
            lineTo(cx, cy + r * 0.30f * scale + dy)            // tail notch
            lineTo(cx - r * 0.62f * scale, cy + r * 0.66f * scale + dy) // back-left
            close()
        }

        // No blur shadow: it renders as a hard dark box on this HW (bad on light
        // theme). The white outline (step 2) gives the lift/separation instead.

        // 2) White outline (slightly larger arrow drawn behind the fill).
        val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(arrowPath(1.18f, 0f), outline)

        // 3) Gradient-filled body: a lighter tint at the tip → the accent at the
        //    tail, giving the chevron a lit, dimensional 3D appearance.
        val lit = lightenColor(accent, 0.35f)
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                cx, cy - r * 0.78f, cx, cy + r * 0.66f,
                lit, accent, android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawPath(arrowPath(1.0f, 0f), body)
        return bmp
    }

    /** Blend [color] toward white by [amount] (0..1). */
    private fun lightenColor(color: Int, amount: Float): Int {
        val a = android.graphics.Color.alpha(color)
        val r = (android.graphics.Color.red(color) + (255 - android.graphics.Color.red(color)) * amount).toInt()
        val g = (android.graphics.Color.green(color) + (255 - android.graphics.Color.green(color)) * amount).toInt()
        val b = (android.graphics.Color.blue(color) + (255 - android.graphics.Color.blue(color)) * amount).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    // ---------------------------------------------------------------------
    // Label / icon resolution helpers
    // ---------------------------------------------------------------------

    private fun iconForType(type: Int): Int = when (type) {
        0 -> R.drawable.ic_hazard_breaker
        1 -> R.drawable.ic_hazard_pothole
        3 -> R.drawable.ic_hazard_rough
        else -> R.drawable.ic_hazard_unknown
    }

    private fun typeLabelRes(type: Int): Int = when (type) {
        0 -> R.string.roadsense_type_breaker
        1 -> R.string.roadsense_type_pothole
        3 -> R.string.roadsense_type_rough
        else -> R.string.roadsense_map_type_unknown
    }

    private fun severityLabelRes(severity: Int): Int = when (severity) {
        1 -> R.string.roadsense_sev_minor
        3 -> R.string.roadsense_sev_severe
        else -> R.string.roadsense_sev_moderate
    }

    private fun statusLabelRes(status: Int): Int = when (status) {
        1 -> R.string.roadsense_map_status_local
        2 -> R.string.roadsense_map_status_cloud
        else -> R.string.roadsense_map_status_candidate
    }

    private fun severityFromChip(checkedId: Int): Int? = when (checkedId) {
        R.id.chipSevMinor -> 1
        R.id.chipSevModerate -> 2
        R.id.chipSevSevere -> 3
        else -> null
    }

    private fun typeFromChip(checkedId: Int): Int? = when (checkedId) {
        R.id.chipTypeBreaker -> 0
        R.id.chipTypePothole -> 1
        R.id.chipTypeRough -> 3
        R.id.chipTypeUnknown -> 2
        else -> null
    }

    /**
     * Lazily create the IO executor. Synchronized (+ @Volatile field) so a burst of
     * concurrent callers — e.g. a guidance tick racing recenter() on first open —
     * can't each build a separate executor and leak all but one (the old
     * elvis-`.also` getter had that unsynchronized double-init race). Double-checked
     * so the common already-initialized path stays lock-free.
     */
    private fun ioExecutor(): ExecutorService {
        ioExecutor?.let { return it }
        return synchronized(this) {
            ioExecutor ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "RoadSenseMapIo").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.also { ioExecutor = it }
        }
    }

    // ---------------------------------------------------------------------
    // MapView lifecycle forwarding — every callback must reach the MapView,
    // or the render surface leaks / crashes. This is intentionally verbatim.
    // ---------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        // Resume the camera/guidance loops suspended in onStop (state was kept).
        if (navigating) { mainHandler.removeCallbacks(guidanceRunnable); mainHandler.post(guidanceRunnable) }
        if (clusterMode) startClusterFollow()   // idempotent (self-dedupes)
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Suspend the 1s guidance + cluster-follow loops while backgrounded — the
        // SurfaceView render is paused here, so continuing to fire network GETs +
        // animateCamera every second just drains the SoC / contends with the
        // daemon encoder for nothing. The `navigating`/`clusterMode` flags are KEPT
        // so onResume restarts seamlessly.
        mainHandler.removeCallbacks(guidanceRunnable)
        mainHandler.removeCallbacks(clusterFollowRunnable)
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    /** Animate the camera zoom by [delta] levels (+in / -out), clamped by the map. */
    private fun zoomBy(delta: Double) {
        val mlMap = map ?: return
        val target = mlMap.cameraPosition.zoom + delta
        mlMap.animateCamera(CameraUpdateFactory.zoomTo(target), 300)
    }

    private fun hideKeyboard(v: View) {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        navigating = false
        mainHandler.removeCallbacksAndMessages(null)
        clusterDisplayListener?.let { l ->
            try {
                (getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager)?.unregisterDisplayListener(l)
            } catch (_: Throwable) {}
        }
        clusterDisplayListener = null
        NavSession.removeListener(navSessionListener)
        navSessionListener = null
        try { navVoice?.shutdown() } catch (_: Throwable) {}
        navVoice = null
        ioExecutor?.shutdownNow()
        ioExecutor = null
        MapTilePrefetcher.cancelPending()
        hazardSource = null
        routeSource = null
        // Null symmetry: drop the remaining style-bound source/layer + sheet refs
        // so they don't pin a destroyed Style/MapView (no-op safety; map is gone).
        altRouteSource = null
        poiSource = null
        hazardSymbolLayer = null
        routeSheetBehavior = null
        map = null
        // Forward last so MapLibre releases its GL resources after we've
        // dropped our references.
        mapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "RoadSenseMapActivity"

        /**
         * OpenFreeMap hosted styles — always-current, no API key, no limits. The
         * map picks light vs dark by the active app theme (styleUrlForTheme).
         * "liberty" is the richer, more detailed light basemap (more premium than
         * the minimal "positron"); "dark" is its night counterpart. STYLE_URL
         * (light) is the prefetch reference (offline cache is theme-agnostic —
         * tiles are shared across styles; only the style JSON differs).
         */
        const val STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val STYLE_URL = STYLE_URL_LIGHT

        // Source / layer ids.
        const val HAZARD_SOURCE_ID = "roadsense-hazards"
        const val HAZARD_SYMBOL_LAYER_ID = "roadsense-hazard-markers"
        const val CLUSTER_CIRCLE_LAYER_ID = "roadsense-hazard-clusters"
        const val CLUSTER_COUNT_LAYER_ID = "roadsense-hazard-cluster-count"

        // Marker icon ids registered via style.addImage.
        const val ICON_BREAKER = "hz_breaker"
        const val ICON_POTHOLE = "hz_pothole"
        const val ICON_ROUGH = "hz_rough"
        const val ICON_UNKNOWN = "hz_unknown"

        // GeoJSON property keys (match RoadSenseApiHandler output).
        const val PROP_ID = "id"
        const val PROP_TYPE = "type"
        const val PROP_SEVERITY = "severity"
        const val PROP_CONFIDENCE = "confidence"
        const val PROP_STATUS = "status"
        const val PROP_OBSERVATIONS = "observations"
        const val POINT_COUNT = "point_count"

        // Clustering.
        const val CLUSTER_RADIUS = 50
        const val CLUSTER_MAX_ZOOM = 14

        // Marker bitmap resolution (px) for style.addImage.
        // Hazard pin bitmap width (px); height is 1.3x. Large for hi-dpi crispness.
        const val PIN_PX = 96

        const val REFETCH_DEBOUNCE_MS = 400L
        const val RECENTER_ANIM_MS = 900

        // Initial camera until a recenter / live fix narrows it.
        const val DEFAULT_LAT = 1.3521
        const val DEFAULT_LNG = 103.8198
        const val DEFAULT_ZOOM = 13.0

        // Zoom used when centering on the live GPS fix (closer than the
        // overview default so the user sees their immediate surroundings).
        const val FOLLOW_ZOOM = 16.0

        // Location puck source + layers (kept above the hazard layers).
        const val PUCK_SOURCE_ID = "roadsense-location"
        const val ICON_PUCK_ARROW = "puck_arrow"
        const val PUCK_HALO_LAYER_ID = "roadsense-location-halo"
        const val PUCK_CORE_LAYER_ID = "roadsense-location-core"

        // Route line source + two-layer stroke (added UNDER the hazard markers).
        const val ROUTE_SOURCE_ID = "roadsense-route"
        const val ROUTE_CASING_LAYER_ID = "roadsense-route-casing"
        const val ROUTE_MAIN_LAYER_ID = "roadsense-route-main"

        // Alternate routes (dimmed, tappable) — separate source under the selected route.
        const val ALT_ROUTE_SOURCE_ID = "roadsense-alt-routes"
        const val ALT_ROUTE_LAYER_ID = "roadsense-alt-routes-line"

        // Itinerary markers: a destination pin at the route end + numbered pins at
        // each intermediate stop. Drawn ABOVE the route line + hazards so the trip
        // endpoints are always legible. Each feature carries an "img" property =
        // the registered image id (rs_dest / rs_stop_<n>) selected via iconImage(get).
        const val MARKER_SOURCE_ID = "roadsense-route-markers"
        const val MARKER_LAYER_ID = "roadsense-route-markers-layer"
        const val MARKER_PROP_IMG = "img"
        const val ICON_DEST = "rs_dest"
        const val ICON_STOP_PREFIX = "rs_stop_"

        // How many ALTERNATE routes to request (Valhalla may return fewer).
        const val ROUTE_ALTERNATES = 2

        // Max INTERMEDIATE stops (via-points) the itinerary allows; the trip then
        // holds up to MAX_STOPS stops + 1 destination. Capped so the via route
        // request + the rebuilt LinearLayout stay small.
        const val MAX_STOPS = 8

        // Corridor (m) within which a hazard counts as "on this route".
        const val HAZARD_CORRIDOR_M = 40.0

        // Degree padding for the per-route bbox pre-reject in loadHazardCountsForRoutes.
        // A strict superset of HAZARD_CORRIDOR_M (~40m): 1° lat ≈ 111.3km so 40m ≈
        // 0.00036°; lng degrees are SHORTER in meters away from the equator, so the
        // same 0.0004° always covers ≥40m on the lng axis too — guaranteeing any
        // in-corridor hazard survives the bbox, so counts are unchanged.
        const val HAZARD_CORRIDOR_DEG = 0.0004

        // Max hazards parsed for the per-route corridor scan — caps the O(n×segments)
        // work on a pathologically dense bbox (the pills only need a representative count).
        const val HAZARD_COUNT_CAP = 2000

        // Tap tolerance (dp) for hitting a thin route line with a finger.
        const val TAP_SLOP_PX = 22f

        // Max width (dp) of the floating top panels on wide/landscape screens —
        // beyond this they're capped + centered (Gmaps-style column), not stretched.
        const val PANEL_MAX_WIDTH_DP = 560

        // Autocomplete debounce + minimum query length (don't spam the geocoder).
        const val AUTOCOMPLETE_DEBOUNCE_MS = 300L
        const val AUTOCOMPLETE_MIN_CHARS = 3

        // Guidance loop cadence + camera/announce thresholds. The camera
        // animation duration is matched to the tick interval so each glide ends
        // right as the next fix arrives — continuous motion, no animate-then-freeze
        // stutter (the old 2000ms tick / 900ms anim left the puck frozen ~1.1s).
        const val GUIDANCE_TICK_MS = 1000L
        const val GUIDANCE_CAM_ANIM_MS = 1000
        const val MANEUVER_ANNOUNCE_M = 180.0

        // Cluster maneuver-banner text sizes (sp) — larger than the head-unit's
        // TitleLarge/BodyMedium for glanceability on the driver cluster.
        const val CLUSTER_BANNER_PRIMARY_SP = 30f
        const val CLUSTER_BANNER_SECONDARY_SP = 18f

        // Immersive driving view: 3D tilt, close zoom, heading-up follow.
        const val IMMERSIVE_TILT = 55.0
        const val IMMERSIVE_ZOOM = 17.5
        const val IMMERSIVE_MIN_SPEED_MPS = 1.5 // below this, GPS bearing is noise → hold last
        const val REROUTE_MIN_INTERVAL_MS = 8000L // min gap between auto-reroutes

        // Camera follow dead-band: skip the per-tick animateCamera when the new
        // target is within this distance + heading delta of the last animated one.
        const val CAM_DEADBAND_M = 3.0
        const val CAM_DEADBAND_DEG = 2.0

        // POI (EV charging / fuel) along-route layer.
        const val POI_SOURCE_ID = "roadsense-poi"
        const val POI_LAYER_ID = "roadsense-poi-markers"
        const val ICON_POI_CHARGING = "poi_charging"
        const val ICON_POI_FUEL = "poi_fuel"
        const val POI_PROP_KIND = "kind"
        const val POI_PROP_NAME = "name"
        const val POI_PROP_LAT = "plat"
        const val POI_PROP_LNG = "plng"

        // Hazard filter modes (persisted in prefs).
        const val HAZARD_FILTER_HIDDEN = 0
        const val HAZARD_FILTER_SEVERE = 1   // severity == 3
        const val HAZARD_FILTER_MODERATE = 2 // severity >= 2
        const val HAZARD_FILTER_ALL = 3      // severity >= 1 (default)
        const val PREFS_NAVMAP = "navmap_ui"
        const val KEY_HAZARD_FILTER = "hazard_filter_mode"

        // 2D/3D buildings. The 3D layer is a runtime-added fill-extrusion over the
        // openmaptiles vector source (present in BOTH the light=liberty and the
        // dark style — only liberty ships the extrusion layer, so we add our own
        // in either case + toggle visibility). minzoom matches liberty's building-3d
        // so the volumes only appear when zoomed in (and never load tiles below it).
        const val MAP_3D_LAYER_ID = "roadsense-building-3d"
        // The liberty (light) basemap ships its OWN fill-extrusion layer with this
        // id — we toggle ITS visibility for 2D/3D rather than adding a duplicate.
        const val LIBERTY_3D_LAYER_ID = "building-3d"
        const val MAP_3D_SOURCE = "openmaptiles"
        const val MAP_3D_SOURCE_LAYER = "building"
        const val MAP_3D_MIN_ZOOM = 14f
        // Fixed extrusion hues (NOT theme attrs — these tint 3D building volumes on
        // the basemap, so they track the BASEMAP theme, not the app M3 palette).
        const val MAP_3D_COLOR_LIGHT = "hsl(35,8%,85%)" // liberty's own building tint
        const val MAP_3D_COLOR_DARK = "#2b2d36"          // muted slate for night
        const val KEY_MAP_3D = "map_3d_enabled" // persisted 2D/3D choice (default 2D)

        const val EMPTY_FEATURE_COLLECTION =
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
