/**
 * Overdrive — RoadSense Settings Module
 *
 * Mirrors recording.js / surveillance.js:
 *   - loadConfig() reads the current state from the daemon (GET
 *     /api/settings/unified -> config.roadSense).
 *   - each control persists immediately on change via fetch() POST
 *     /api/settings/unified { section: "roadSense", data: { ... } }.
 *     (XHR POST bodies are dropped in the in-app WebView — always fetch().)
 *
 * Config keys (the `roadSense` UCM section; RoadSenseConfig.kt reads these
 * exact names):
 *   enabled, warnEnabled, warnMode ("visual"|"audio"|"both"),
 *   warnLeadSeconds (default 4, 2..8), warnConfidenceThreshold (0..1, default 0),
 *   warnSeverityMinor / warnSeverityModerate / warnSeveritySevere,
 *   calibrationMode, crowdUpload, crowdDownload, syncWorkerUrl.
 *
 * The two delete actions hit live daemon endpoints (RoadSenseApiHandler, routed
 * from HttpServer):
 *   POST /api/roadsense/delete-local  — wipe on-device calibrations/detections
 *   POST /api/roadsense/delete-cloud  — wipe this device's uploaded rows
 */

window.BYD = window.BYD || {};

BYD.roadSense = {
    config: {
        enabled: false,
        warnEnabled: true,
        warnMode: 'both',
        warnLeadSeconds: 4,
        // Stored 0..1 in the config; the slider works in whole percent (0..100).
        warnConfidenceThreshold: 0,
        warnSeverityMinor: true,
        warnSeverityModerate: true,
        warnSeveritySevere: true,
        calibrationMode: false,
        crowdUpload: false,
        crowdDownload: false,
        syncWorkerUrl: '',
        // Blind Spot (separate UCM 'blindspot' section). enabled gates the
        // native indicator overlay; the 6 numerics are the stitch calibration.
        bsEnabled: false,
        bsRearFov: 1.66,
        bsSideFov: 1.98,
        bsYaw: 1.23,
        bsRoll: 0.25,
        bsPitch: -0.275,
        bsFeather: 0.38,
        // Additional opaque stitch-tuning scalars; defaults = no change.
        bsProjExp: 1.0,
        bsRearRoll: 0.0,
        bsRearPitch: 0.0,
        // On-screen card size (% of panel width) + corner. Persisted as a preset
        // (not absolute px) so it stays correct across portrait/landscape rotation.
        bsSizePct: 40,
        bsCorner: 'tr'
    },

    async init() {
        await this.loadConfig();
        this.updateUI();

        // Re-read config when the user switches back to the tab (unless a
        // write is mid-flight). Cheap and keeps the page in sync with the
        // native settings UI / daemon-side changes.
        const self = this;
        document.addEventListener('visibilitychange', function () {
            if (document.visibilityState === 'hidden') {
                // CRITICAL: tearing the page away (tab switch / background) while
                // a debug preview is active would otherwise leave debugPreview=true
                // pinned in UCM — the native service keeps the HW decoder warm and
                // the global stream hijacked to view 7/8 indefinitely, surviving
                // ACC-off and app/daemon restart. Stop it on hide. visibilitychange
                // 'hidden' fires reliably on background where unload may not.
                if (self._bsPreviewActive) self.bsPreviewStop();
                return;
            }
            if (document.visibilityState === 'visible' && !self._writing) {
                self.reload();
            }
        });
        // pagehide/beforeunload cover hard navigation away from the page (the
        // visibilitychange('hidden') above covers background/tab-switch).
        window.addEventListener('pagehide', function () { if (self._bsPreviewActive) self.bsPreviewStop(); });
        window.addEventListener('beforeunload', function () { if (self._bsPreviewActive) self.bsPreviewStop(); });
    },

    async reload() {
        await this.loadConfig();
        this.updateUI();
    },

    async loadConfig() {
        try {
            const resp = await fetch('/api/settings/unified');
            const data = await resp.json();
            if (data && data.success && data.config && data.config.roadSense) {
                const rs = data.config.roadSense;
                const c = this.config;
                if (typeof rs.enabled === 'boolean') c.enabled = rs.enabled;
                if (typeof rs.warnEnabled === 'boolean') c.warnEnabled = rs.warnEnabled;
                if (rs.warnMode) c.warnMode = String(rs.warnMode).toLowerCase();

                if (typeof rs.warnLeadSeconds === 'number') {
                    let v = Math.round(rs.warnLeadSeconds);
                    if (v < 2) v = 2; if (v > 8) v = 8;
                    c.warnLeadSeconds = v;
                }
                if (typeof rs.warnConfidenceThreshold === 'number') {
                    let t = rs.warnConfidenceThreshold;
                    if (t < 0) t = 0; if (t > 1) t = 1;
                    c.warnConfidenceThreshold = t;
                }
                if (typeof rs.warnSeverityMinor === 'boolean') c.warnSeverityMinor = rs.warnSeverityMinor;
                if (typeof rs.warnSeverityModerate === 'boolean') c.warnSeverityModerate = rs.warnSeverityModerate;
                if (typeof rs.warnSeveritySevere === 'boolean') c.warnSeveritySevere = rs.warnSeveritySevere;
                if (typeof rs.calibrationMode === 'boolean') c.calibrationMode = rs.calibrationMode;
                if (typeof rs.crowdUpload === 'boolean') c.crowdUpload = rs.crowdUpload;
                if (typeof rs.crowdDownload === 'boolean') c.crowdDownload = rs.crowdDownload;
                if (typeof rs.syncWorkerUrl === 'string') c.syncWorkerUrl = rs.syncWorkerUrl;
            }
            // Blind Spot lives in its own top-level section.
            if (data && data.success && data.config && data.config.blindspot) {
                const bs = data.config.blindspot;
                const c = this.config;
                if (typeof bs.enabled === 'boolean') c.bsEnabled = bs.enabled;
                if (typeof bs.rearFov === 'number') c.bsRearFov = this._clamp(bs.rearFov, 1.0, 2.2);
                if (typeof bs.sideFov === 'number') c.bsSideFov = this._clamp(bs.sideFov, 1.0, 2.2);
                if (typeof bs.yaw === 'number') c.bsYaw = this._clamp(bs.yaw, 0, 1.4);
                if (typeof bs.roll === 'number') c.bsRoll = this._clamp(bs.roll, -0.4, 0.4);
                if (typeof bs.pitch === 'number') c.bsPitch = this._clamp(bs.pitch, -0.4, 0.4);
                if (typeof bs.feather === 'number') c.bsFeather = this._clamp(bs.feather, 0, 1.0);
                if (typeof bs.projExp === 'number') c.bsProjExp = this._clamp(bs.projExp, 0.4, 1.6);
                if (typeof bs.rearRoll === 'number') c.bsRearRoll = this._clamp(bs.rearRoll, -0.4, 0.4);
                if (typeof bs.rearPitch === 'number') c.bsRearPitch = this._clamp(bs.rearPitch, -0.4, 0.4);
                // On-screen size/position preset (orientation-safe — daemon
                // recomputes px from the live panel). geometry.sizePct + .corner.
                var geo = bs.geometry || {};
                if (typeof geo.sizePct === 'number') c.bsSizePct = this._clamp(geo.sizePct, 15, 90);
                if (typeof geo.corner === 'string') c.bsCorner = geo.corner;
            }
        } catch (e) {
            console.warn('RoadSense: failed to load config:', e);
        }
    },

    _clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); },

    /**
     * Merge-write one or more keys into the roadSense UCM section. fetch()
     * (never XHR) so the WebView doesn't drop the POST body. Returns true on
     * a successful write so callers can revert the control on failure.
     */
    async _save(delta) {
        this._writing = true;
        try {
            const resp = await fetch('/api/settings/unified', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ section: 'roadSense', data: delta })
            });
            const data = await resp.json();
            return !!(data && data.success);
        } catch (e) {
            console.warn('RoadSense: save failed:', e);
            return false;
        } finally {
            this._writing = false;
        }
    },

    // ==================== UI sync ====================

    updateUI() {
        const c = this.config;

        this._setChecked('rsEnabled', c.enabled);
        this._setBadge('rsStatusBadge', c.enabled);

        this._setChecked('rsCalibrationMode', c.calibrationMode);

        this._setChecked('rsWarnEnabled', c.warnEnabled);
        this._setBadge('rsWarnBadge', c.enabled && c.warnEnabled);

        // Master gate: every other card only takes effect while RoadSense is on
        // (master_enable_desc). When it's off, dim + disable the dependent cards so
        // their toggles don't read as live next to an OFF badge — the contradiction
        // a first-run user hits (defaults show warnEnabled/severities ON, but the
        // Warnings badge is OFF because the master is off).
        this._applyMasterGate(c.enabled);

        // Warn mode button group.
        document.querySelectorAll('#rsWarnModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === c.warnMode));

        // Lead-time slider.
        const leadSlider = document.getElementById('rsWarnLeadSlider');
        if (leadSlider) leadSlider.value = c.warnLeadSeconds;
        this._setLeadLabel(c.warnLeadSeconds);

        // Confidence slider (config 0..1 -> percent 0..100).
        const confPct = Math.round(c.warnConfidenceThreshold * 100);
        const confSlider = document.getElementById('rsWarnConfSlider');
        if (confSlider) confSlider.value = confPct;
        this._setConfLabel(confPct);

        // Per-severity chimes.
        this._setChecked('rsSeverityMinor', c.warnSeverityMinor);
        this._setChecked('rsSeverityModerate', c.warnSeverityModerate);
        this._setChecked('rsSeveritySevere', c.warnSeveritySevere);

        // Crowdsource.
        this._setChecked('rsCrowdUpload', c.crowdUpload);
        this._setChecked('rsCrowdDownload', c.crowdDownload);
        const urlInput = document.getElementById('rsSyncWorkerUrl');
        if (urlInput) urlInput.value = c.syncWorkerUrl || '';

        // Blind Spot.
        this._setChecked('bsEnabled', c.bsEnabled);
        this._setBadge('bsStatusBadge', c.bsEnabled);
        // Live preview is a NATIVE on-car window — only meaningful in the in-app
        // WebView. Hide the preview controls on a tunnel/browser (no AndroidBridge),
        // where tapping them would do nothing. Sliders + Apply still work remotely
        // (they tune/persist via HTTP), so only the preview row is gated.
        var inApp = (typeof window.AndroidBridge !== 'undefined');
        var pc = document.getElementById('bsPreviewControls');
        if (pc) pc.style.display = inApp ? '' : 'none';
        this._bsSetSlider('bsRearFov', 'bsRearFovVal', c.bsRearFov);
        this._bsSetSlider('bsSideFov', 'bsSideFovVal', c.bsSideFov);
        this._bsSetSlider('bsYaw', 'bsYawVal', c.bsYaw);
        this._bsSetSlider('bsRoll', 'bsRollVal', c.bsRoll);
        this._bsSetSlider('bsPitch', 'bsPitchVal', c.bsPitch);
        this._bsSetSlider('bsFeather', 'bsFeatherVal', c.bsFeather);
        this._bsSetSlider('bsProjExp', 'bsProjExpVal', c.bsProjExp);
        this._bsSetSlider('bsRearRoll', 'bsRearRollVal', c.bsRearRoll);
        this._bsSetSlider('bsRearPitch', 'bsRearPitchVal', c.bsRearPitch);
        // Reflect saved size%/corner into the size+position control.
        var szEl = document.getElementById('bsSize');
        var szVal = document.getElementById('bsSizeVal');
        if (szEl) szEl.value = String(c.bsSizePct);
        if (szVal) szVal.textContent = c.bsSizePct + '%';
        this._bsHighlightCorner(c.bsCorner || 'tr');
    },

    _bsSetSlider(sliderId, labelId, value) {
        const s = document.getElementById(sliderId);
        if (s) s.value = value;
        const l = document.getElementById(labelId);
        if (l) l.textContent = String(value);
    },

    _setChecked(id, on) {
        const el = document.getElementById(id);
        if (el) el.checked = !!on;
    },

    /**
     * Visually gate the dependent cards on the master `enabled` flag. The General
     * card holding the master switch stays fully live; every OTHER card (Warnings,
     * Crowdsource, Data) is dimmed + made non-interactive while the master is off,
     * so a first-run user doesn't see live-looking toggles next to an OFF badge.
     * Their checked STATE is preserved (so flipping master back on reveals the
     * saved selection) — we only block interaction, we don't change values.
     */
    _applyMasterGate(masterOn) {
        document.querySelectorAll('.card').forEach(card => {
            // Leave the master switch's own card always interactive.
            if (card.querySelector('#rsEnabled')) return;
            card.classList.toggle('rs-gated', !masterOn);
            // Block pointer + keyboard interaction on the controls when gated,
            // without touching their checked/value (so state survives a toggle).
            card.querySelectorAll('input, button, .btn-toggle').forEach(ctrl => {
                if (!masterOn) {
                    ctrl.setAttribute('disabled', 'disabled');
                    ctrl.setAttribute('aria-disabled', 'true');
                } else {
                    ctrl.removeAttribute('disabled');
                    ctrl.removeAttribute('aria-disabled');
                }
            });
        });
    },

    _setBadge(id, on) {
        const badge = document.getElementById(id);
        if (!badge) return;
        badge.textContent = on ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
        badge.className = 'status-badge ' + (on ? 'active' : 'inactive');
    },

    _setLeadLabel(seconds) {
        const el = document.getElementById('rsWarnLeadValue');
        if (!el) return;
        const tmpl = BYD.i18n.t('road_sense.unit_seconds', { n: seconds });
        el.textContent = (tmpl && tmpl !== 'road_sense.unit_seconds') ? tmpl : (seconds + 's');
    },

    _setConfLabel(pct) {
        const el = document.getElementById('rsWarnConfValue');
        if (el) el.textContent = pct + '%';
    },

    // ==================== Control handlers ====================

    async toggleEnabled() {
        const el = document.getElementById('rsEnabled');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ enabled: on });
        if (ok) {
            this.config.enabled = on;
            this._setBadge('rsStatusBadge', on);
            this._setBadge('rsWarnBadge', on && this.config.warnEnabled);
            // Live-update the dependent-card gate so toggling the master on/off
            // immediately enables/dims Warnings/Crowdsource/Data.
            this._applyMasterGate(on);
            this._toastSaved();
        } else {
            el.checked = !on;
            this._toastFailed();
        }
    },

    async toggleCalibrationMode() {
        const el = document.getElementById('rsCalibrationMode');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ calibrationMode: on });
        if (ok) { this.config.calibrationMode = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async toggleWarnEnabled() {
        const el = document.getElementById('rsWarnEnabled');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ warnEnabled: on });
        if (ok) {
            this.config.warnEnabled = on;
            this._setBadge('rsWarnBadge', this.config.enabled && on);
            this._toastSaved();
        } else { el.checked = !on; this._toastFailed(); }
    },

    async setWarnMode(mode) {
        if (mode !== 'visual' && mode !== 'audio' && mode !== 'both') return;
        const prev = this.config.warnMode;
        // Optimistic UI — reflect immediately, revert if the write fails.
        document.querySelectorAll('#rsWarnModeBtns .btn-toggle').forEach(btn =>
            btn.classList.toggle('active', btn.dataset.value === mode));
        const ok = await this._save({ warnMode: mode });
        if (ok) { this.config.warnMode = mode; this._toastSaved(); }
        else {
            document.querySelectorAll('#rsWarnModeBtns .btn-toggle').forEach(btn =>
                btn.classList.toggle('active', btn.dataset.value === prev));
            this._toastFailed();
        }
    },

    // Slider live-label is updated on every input; the durable write is
    // debounced so dragging doesn't hammer the daemon.
    updateWarnLead(value) {
        let v = parseInt(value, 10);
        if (isNaN(v)) v = 4;
        if (v < 2) v = 2; if (v > 8) v = 8;
        this.config.warnLeadSeconds = v;
        this._setLeadLabel(v);
        this._debounceSave('warnLeadSeconds', { warnLeadSeconds: v });
    },

    updateWarnConf(value) {
        let pct = parseInt(value, 10);
        if (isNaN(pct)) pct = 0;
        if (pct < 0) pct = 0; if (pct > 100) pct = 100;
        const t = pct / 100;
        this.config.warnConfidenceThreshold = t;
        this._setConfLabel(pct);
        this._debounceSave('warnConfidenceThreshold', { warnConfidenceThreshold: t });
    },

    _debounceSave(key, delta) {
        this._saveTimers = this._saveTimers || {};
        if (this._saveTimers[key]) clearTimeout(this._saveTimers[key]);
        const self = this;
        this._saveTimers[key] = setTimeout(function () {
            self._saveTimers[key] = null;
            self._save(delta).then(function (ok) {
                if (!ok) self._toastFailed();
            });
        }, 250);
    },

    async toggleSeverity(level) {
        const map = {
            minor: { id: 'rsSeverityMinor', key: 'warnSeverityMinor' },
            moderate: { id: 'rsSeverityModerate', key: 'warnSeverityModerate' },
            severe: { id: 'rsSeveritySevere', key: 'warnSeveritySevere' }
        };
        const m = map[level];
        if (!m) return;
        const el = document.getElementById(m.id);
        if (!el) return;
        const on = el.checked;
        const delta = {}; delta[m.key] = on;
        const ok = await this._save(delta);
        if (ok) { this.config[m.key] = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async toggleCrowdUpload() {
        const el = document.getElementById('rsCrowdUpload');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ crowdUpload: on });
        if (ok) { this.config.crowdUpload = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async toggleCrowdDownload() {
        const el = document.getElementById('rsCrowdDownload');
        if (!el) return;
        const on = el.checked;
        const ok = await this._save({ crowdDownload: on });
        if (ok) { this.config.crowdDownload = on; this._toastSaved(); }
        else { el.checked = !on; this._toastFailed(); }
    },

    async saveWorkerUrl() {
        const input = document.getElementById('rsSyncWorkerUrl');
        if (!input) return;
        const url = (input.value || '').trim();
        const ok = await this._save({ syncWorkerUrl: url });
        if (ok) { this.config.syncWorkerUrl = url; this._toastSaved(); }
        else { this._toastFailed(); }
    },

    // ==================== Destructive actions ====================
    //
    // Two SEPARATE deletes with distinct confirms, both backed by live handlers
    // (RoadSenseApiHandler):
    //   POST /api/roadsense/delete-local  — wipe on-device calibrations + labels.
    //   POST /api/roadsense/delete-cloud  — wipe this device's uploaded rows
    //                                        (server scopes by the rotating
    //                                        roadSense.deviceId).

    async deleteLocal() {
        const msg = BYD.i18n.t('road_sense.confirm_delete_local');
        const prompt = (msg && msg !== 'road_sense.confirm_delete_local')
            ? msg
            : 'Delete all RoadSense calibrations stored on this device? This cannot be undone.';
        if (!confirm(prompt)) return;
        const btn = document.getElementById('rsDeleteLocalBtn');
        if (btn) btn.disabled = true;
        try {
            const resp = await fetch('/api/roadsense/delete-local', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data && data.success) {
                this._toast('road_sense.delete_local_done', 'Local calibrations deleted', 'success');
            } else {
                this._toast('road_sense.delete_failed', 'Delete failed', 'error');
            }
        } catch (e) {
            console.warn('RoadSense: delete-local failed:', e);
            this._toast('road_sense.delete_failed', 'Delete failed', 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    async deleteCloud() {
        const msg = BYD.i18n.t('road_sense.confirm_delete_cloud');
        const prompt = (msg && msg !== 'road_sense.confirm_delete_cloud')
            ? msg
            : 'Delete the RoadSense detections you uploaded from the shared cloud map? This cannot be undone.';
        if (!confirm(prompt)) return;
        const btn = document.getElementById('rsDeleteCloudBtn');
        if (btn) btn.disabled = true;
        try {
            const resp = await fetch('/api/roadsense/delete-cloud', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data && data.success) {
                this._toast('road_sense.delete_cloud_done', 'Cloud calibrations deleted', 'success');
            } else {
                this._toast('road_sense.delete_failed', 'Delete failed', 'error');
            }
        } catch (e) {
            console.warn('RoadSense: delete-cloud failed:', e);
            this._toast('road_sense.delete_failed', 'Delete failed', 'error');
        } finally {
            if (btn) btn.disabled = false;
        }
    },

    // ==================== Toast helpers ====================

    _toast(key, fallback, type) {
        if (!(BYD.utils && BYD.utils.toast)) return;
        const t = BYD.i18n.t(key);
        BYD.utils.toast((t && t !== key) ? t : fallback, type);
    },

    _toastSaved() {
        this._toast('road_sense.saved', 'Saved', 'success');
    },

    _toastFailed() {
        this._toast('road_sense.save_failed', 'Save failed', 'error');
    },

    // ==================== Blind Spot ====================

    /** Kick the native overlay service to react to the just-saved enabled/preview
     *  flag immediately. AndroidBridge is only present inside the in-app WebView;
     *  on a tunnel/browser there's no native overlay so this is a harmless no-op. */
    _bsSyncNative() {
        try {
            if (window.AndroidBridge && typeof AndroidBridge.syncBlindSpotOverlay === 'function') {
                var r = AndroidBridge.syncBlindSpotOverlay();
                // M4: the overlay needs "draw over other apps" permission; if it's
                // off the feature silently no-ops. The bridge opens the grant
                // screen and returns this marker — tell the user why.
                if (r === 'needs_overlay_permission') {
                    this._toast('road_sense.bs_needs_overlay', 'Allow "display over other apps" to show the blind-spot view', 'error');
                }
            }
        } catch (e) { /* no bridge (browser/tunnel) — service polls the flag anyway */ }
    },

    /** Persist only the blindspot section (NOT roadSense — separate top-level). */
    async _bsSave(delta) {
        this._writing = true;
        try {
            const resp = await fetch('/api/settings/unified', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ section: 'blindspot', data: delta })
            });
            const data = await resp.json();
            return !!(data && data.success);
        } catch (e) {
            console.warn('BlindSpot: save failed:', e);
            return false;
        } finally {
            this._writing = false;
        }
    },

    async bsToggleEnabled() {
        const on = document.getElementById('bsEnabled').checked;
        this.config.bsEnabled = on;
        this._setBadge('bsStatusBadge', on);
        // Also signal the native MainActivity to start/stop the overlay service.
        // The service polls blindspot.enabled itself, but a kick makes it instant.
        const ok = await this._bsSave({ enabled: on });
        if (ok) { this._bsSyncNative(); this._toastSaved(); }
        else { document.getElementById('bsEnabled').checked = !on; this.config.bsEnabled = !on; this._setBadge('bsStatusBadge', !on); this._toastFailed(); }
    },

    /** Read the sliders into config + reflect labels. */
    _bsReadSliders() {
        const c = this.config;
        c.bsRearFov = parseFloat(document.getElementById('bsRearFov').value);
        c.bsSideFov = parseFloat(document.getElementById('bsSideFov').value);
        c.bsYaw = parseFloat(document.getElementById('bsYaw').value);
        c.bsRoll = parseFloat(document.getElementById('bsRoll').value);
        c.bsPitch = parseFloat(document.getElementById('bsPitch').value);
        c.bsFeather = parseFloat(document.getElementById('bsFeather').value);
        var pe = document.getElementById('bsProjExp');
        if (pe) c.bsProjExp = parseFloat(pe.value);
        var rr = document.getElementById('bsRearRoll');
        if (rr) c.bsRearRoll = parseFloat(rr.value);
        var rp = document.getElementById('bsRearPitch');
        if (rp) c.bsRearPitch = parseFloat(rp.value);
        document.getElementById('bsRearFovVal').textContent = String(c.bsRearFov);
        document.getElementById('bsSideFovVal').textContent = String(c.bsSideFov);
        document.getElementById('bsYawVal').textContent = String(c.bsYaw);
        document.getElementById('bsRollVal').textContent = String(c.bsRoll);
        document.getElementById('bsPitchVal').textContent = String(c.bsPitch);
        document.getElementById('bsFeatherVal').textContent = String(c.bsFeather);
        var pev = document.getElementById('bsProjExpVal');
        if (pev) pev.textContent = String(c.bsProjExp);
        var rrv = document.getElementById('bsRearRollVal');
        if (rrv) rrv.textContent = String(c.bsRearRoll);
        var rpv = document.getElementById('bsRearPitchVal');
        if (rpv) rpv.textContent = String(c.bsRearPitch);
    },

    /** Live-tune the in-car stitch via /api/stream/bs (in-memory, debounced).
     *  Order: hfov/sideHFov/yaw/roll/feather/projExp/vscale/pitch/rearRoll/rearPitch. */
    bsTune() {
        this._bsReadSliders();
        const c = this.config;
        if (this._bsTuneTimer) clearTimeout(this._bsTuneTimer);
        this._bsTuneTimer = setTimeout(function () {
            fetch('/api/stream/bs/' + c.bsRearFov + '/' + c.bsSideFov + '/' + c.bsYaw +
                  '/' + c.bsRoll + '/' + c.bsFeather + '/' + c.bsProjExp + '/1.0/' + c.bsPitch +
                  '/' + c.bsRearRoll + '/' + c.bsRearPitch,
                  { method: 'POST' });
        }, 120);
    },

    /** On-screen size of the blind-spot card (% of panel width). The daemon
     *  computes the px rect from size%+corner (it knows the panel size) and
     *  rescales the SurfaceControl layer live — works in debug + playback. */
    bsSetSize(pct) {
        this.config.bsSizePct = parseInt(pct, 10);
        var el = document.getElementById('bsSizeVal');
        if (el) el.textContent = this.config.bsSizePct + '%';
        this._bsPushGeometry();
    },

    /** Pin the card to a screen corner (tl/tr/bl/br). */
    bsSetCorner(corner) {
        this.config.bsCorner = corner;
        this._bsHighlightCorner(corner);
        this._bsPushGeometry();
    },

    /** Highlight the currently-selected corner button so the saved position is
     *  visible at a glance (M3 tonal-selection). */
    _bsHighlightCorner(corner) {
        var map = { tl: 'bsCornerTl', tr: 'bsCornerTr', bl: 'bsCornerBl', br: 'bsCornerBr' };
        for (var k in map) {
            var el = document.getElementById(map[k]);
            if (el) { if (k === corner) el.classList.add('active'); else el.classList.remove('active'); }
        }
    },

    /** POST size%+corner to the daemon (debounced); daemon does the panel math
     *  and rescales the layer. Persists daemon-side to UCM blindspot.geometry. */
    _bsPushGeometry() {
        var c = this.config;
        var pct = c.bsSizePct || 40;
        var corner = c.bsCorner || 'tr';
        if (this._bsGeomTimer) clearTimeout(this._bsGeomTimer);
        this._bsGeomTimer = setTimeout(function () {
            fetch('/api/bs/geometry/preset/' + pct + '/' + corner, { method: 'POST' })
                .catch(function () {});
        }, 120);
    },

    /** Start the live debug preview on the car screen: set debugPreview flag +
     *  debugView side, select the stream view so the overlay paints. */
    async bsPreview(mode) {
        // H2: the flag MUST persist before we hijack the global stream to 7/8.
        // If the UCM write fails (EACCES-from-app-UID is a documented risk), the
        // service never shows the overlay (tick takes the hide branch) and won't
        // restore the stream (streamWarmedView stays -1) — leaving the stream
        // stuck on a blind-spot view with no preview and no error. Bail first.
        const ok = await this._bsSave({ debugPreview: true, debugView: mode });
        if (!ok) { this._toastFailed(); return; }
        this._bsPreviewActive = true;
        // Drive the DEDICATED blind-spot pipeline (port 8889), not the shared
        // live-view stream. The overlay renders the BS lane, so the side must be
        // set on /api/bs/view; the old /api/stream/view/{7,8} switched a different
        // (shared) scaler the overlay never shows — so "switch to right" stayed
        // on whatever side the BS lane was last on (left).
        try { await fetch('/api/bs/view/' + mode, { method: 'POST' }); } catch (e) {}
        // Push current slider values immediately so the preview matches the UI.
        this.bsTune();
        this._bsSyncNative();
    },

    async bsPreviewStop() {
        this._bsPreviewActive = false;
        const ok = await this._bsSave({ debugPreview: false });
        if (!ok) this._toastFailed();   // L6: surface a stuck-preview write failure
        this._bsSyncNative();
    },

    async bsSave() {
        this._bsReadSliders();
        const c = this.config;
        const ok = await this._bsSave({
            rearFov: c.bsRearFov, sideFov: c.bsSideFov, yaw: c.bsYaw,
            roll: c.bsRoll, pitch: c.bsPitch, feather: c.bsFeather,
            projExp: c.bsProjExp, rearRoll: c.bsRearRoll, rearPitch: c.bsRearPitch
        });
        if (ok) this._toastSaved(); else this._toastFailed();
    },

    async bsResetDefaults() {
        const c = this.config;
        c.bsRearFov = 1.66; c.bsSideFov = 1.98; c.bsYaw = 1.23;
        c.bsRoll = 0.25; c.bsPitch = -0.275; c.bsFeather = 0.38;
        c.bsProjExp = 1.0; c.bsRearRoll = 0.0; c.bsRearPitch = 0.0;
        this._bsSetSlider('bsRearFov', 'bsRearFovVal', c.bsRearFov);
        this._bsSetSlider('bsSideFov', 'bsSideFovVal', c.bsSideFov);
        this._bsSetSlider('bsYaw', 'bsYawVal', c.bsYaw);
        this._bsSetSlider('bsRoll', 'bsRollVal', c.bsRoll);
        this._bsSetSlider('bsPitch', 'bsPitchVal', c.bsPitch);
        this._bsSetSlider('bsFeather', 'bsFeatherVal', c.bsFeather);
        this._bsSetSlider('bsProjExp', 'bsProjExpVal', c.bsProjExp);
        this._bsSetSlider('bsRearRoll', 'bsRearRollVal', c.bsRearRoll);
        this._bsSetSlider('bsRearPitch', 'bsRearPitchVal', c.bsRearPitch);
        this.bsTune();
        await this.bsSave();
    }
};

// Alias mirroring RecSettings / SurvSettings naming.
window.RoadSenseSettings = BYD.roadSense;
