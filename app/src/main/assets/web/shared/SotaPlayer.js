/**
 * SOTA H.264 Player - In-Band Configuration Strategy
 * Glues SPS+PPS+IDR together as one "Super Chunk" for WebCodecs.
 */
(function(global) {
    class SotaPlayer {
        constructor(canvas, url) {
            if (typeof canvas === 'string') {
                this.canvas = document.getElementById(canvas);
            } else {
                this.canvas = canvas;
            }
            this.ctx = this.canvas.getContext('2d');
            this.url = url || null;
            this.ws = null;
            this.decoder = null;
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            this.running = false;
            this.frameCount = 0;
            this.onConnected = null;
            this.onDisconnected = null;
            // Tracks the user's intent to view (vs. transient running state), so
            // we can suspend the stream when the tab is hidden and auto-resume
            // when it returns — without the user re-tapping the camera.
            this.userWantsStream = false;
            // Auto-reconnect backoff (capped) so a dead tunnel link doesn't
            // reconnect every 2 s forever, hammering cellular.
            this.reconnectAttempts = 0;
            this.handleFrame = this.handleFrame.bind(this);
            this.handleError = this.handleError.bind(this);

            // Suspend the H.264 stream while the tab is backgrounded. Without
            // this, a live-view tab left open over the tunnel keeps pulling
            // video (and reconnect-looping) over cellular 24/7 — the single
            // largest data drain. Mirrors performance.js / road-sense.js which
            // already stop their feeds on hide.
            if (typeof document !== 'undefined' && document.addEventListener) {
                this._onVisibility = () => {
                    if (document.hidden) {
                        if (this.running) this._suspendForHidden();
                    } else if (this.userWantsStream && !this.running) {
                        this.reconnectAttempts = 0;
                        this.start();
                    }
                };
                this._onPageHide = () => { if (this.running) this._suspendForHidden(); };
                document.addEventListener('visibilitychange', this._onVisibility);
                window.addEventListener('pagehide', this._onPageHide);
            }
        }

        // Tear down the live socket/decoder but REMEMBER the user wanted it, so
        // the visibilitychange handler can resume on return. Distinct from
        // stop(), which is an explicit user stop and clears userWantsStream.
        _suspendForHidden() {
            this.running = false;
            if (this.ws) { try { this.ws.close(); } catch (e) {} this.ws = null; }
            if (this.decoder) { try { this.decoder.close(); } catch (e) {} this.decoder = null; }
            if (this.onDisconnected) this.onDisconnected();
        }

        static isSupported() {
            return "VideoDecoder" in window && "EncodedVideoChunk" in window;
        }

        toggle() {
            if (this.running) this.stop();
            else this.start();
            return this.running;
        }

        connect(url) {
            this.url = url;
            this.start();
        }

        start() {
            if (this.running) { this.stop(); return; }
            if (!SotaPlayer.isSupported()) { console.error("[SotaPlayer] WebCodecs not supported!"); return; }

            this.userWantsStream = true;
            this.running = true;
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            this.frameCount = 0;
            this.connectWebSocket();
        }

        connectWebSocket() {
            if (!this.url) return;
            
            // Reset decoder state for fresh start
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            
            this.ws = new WebSocket(this.url);
            this.ws.binaryType = "arraybuffer";

            this.ws.onopen = () => {
                this.reconnectAttempts = 0;  // healthy link — reset backoff
                this.initDecoder();
                if (this.onConnected) this.onConnected();
            };
            this.ws.onmessage = (e) => this.ingest(new Uint8Array(e.data));
            this.ws.onclose = () => {
                if (this.onDisconnected) this.onDisconnected();
                // Auto-reconnect with capped exponential backoff (2s → 30s) so a
                // dead tunnel link doesn't reconnect every 2s forever over
                // cellular. Skip while the tab is hidden (visibility handler owns
                // resume). Reset on a successful onopen above.
                if (this.running && !(typeof document !== 'undefined' && document.hidden)) {
                    this.reconnectAttempts = (this.reconnectAttempts || 0) + 1;
                    var backoff = Math.min(30000, 2000 * Math.pow(2, this.reconnectAttempts - 1));
                    setTimeout(() => {
                        if (this.running && !(typeof document !== 'undefined' && document.hidden)) {
                            this.connectWebSocket();
                        }
                    }, backoff);
                }
            };
            // Do NOT zero `running` here: a WebSocket error is always followed
            // by a close event, and onclose owns the capped-backoff reconnect.
            // Clearing running on error would block that reconnect and leave a
            // stuck dead player (live-view's onDisconnected does not reconnect).
            this.ws.onerror = () => { /* close event follows; onclose owns reconnect */ };
        }

        initDecoder() {
            if (this.decoder) return;
            this.decoder = new VideoDecoder({
                output: this.handleFrame,
                error: this.handleError
            });
            // SOTA: Use Baseline profile codec string to match server encoder
            // Server sends H.264 Baseline/Level 3.1 for iOS compatibility
            // Will be reconfigured from SPS if profile differs
            this.decoder.configure({
                codec: "avc1.42C01F",
                hardwareAcceleration: "prefer-hardware",
                optimizeForLatency: true
            });
        }

        ingest(data) {
            if (!this.running) return;
            const nalUnits = this.splitNALUnits(data);
            for (const nal of nalUnits) {
                this.processNAL(nal);
            }
        }

        splitNALUnits(data) {
            const units = [];
            let i = 0, lastStart = -1;

            while (i < data.length - 4) {
                if (data[i] === 0 && data[i+1] === 0) {
                    let startCodeLen = 0;
                    if (data[i+2] === 0 && data[i+3] === 1) startCodeLen = 4;
                    else if (data[i+2] === 1) startCodeLen = 3;

                    if (startCodeLen > 0) {
                        if (lastStart >= 0) units.push(data.slice(lastStart, i));
                        lastStart = i;
                        i += startCodeLen;
                        continue;
                    }
                }
                i++;
            }

            if (lastStart >= 0) units.push(data.slice(lastStart));
            else if (data.length > 0) units.push(data);
            return units;
        }

        processNAL(data) {
            let nalType = 0;
            if (data[0] === 0 && data[1] === 0 && data[2] === 0 && data[3] === 1) {
                nalType = data[4] & 0x1F;
            } else if (data[0] === 0 && data[1] === 0 && data[2] === 1) {
                nalType = data[3] & 0x1F;
            } else {
                return;
            }

            // Capture SPS/PPS — reconfigure decoder if SPS changes (quality switch)
            if (nalType === 7) {
                if (this.sps && !this.arraysEqual(this.sps, data)) {
                    // SPS changed (quality/resolution change) — reset decoder state
                    this.sps = data;
                    this.hasReceivedKeyframe = false;
                    if (this.decoder) {
                        try {
                            this.decoder.reset();
                            this.decoder.configure({
                                codec: this.extractCodecFromSPS(data),
                                hardwareAcceleration: "prefer-hardware",
                                optimizeForLatency: true
                            });
                        } catch (e) {}
                    }
                    return;
                }
                this.sps = data;
                return;
            }
            if (nalType === 8) { this.pps = data; return; }

            // Handle video frames
            if (nalType === 5 || nalType === 1) {
                if (nalType === 5 && !this.hasReceivedKeyframe) {
                    if (this.sps) {
                        const superFrame = this.buildSuperFrame(data);
                        this.decodeChunk(superFrame, true);
                        this.hasReceivedKeyframe = true;
                    }
                    return;
                }

                if (this.hasReceivedKeyframe) {
                    if (nalType === 5 && this.sps) {
                        this.decodeChunk(this.buildSuperFrame(data), true);
                    } else {
                        this.decodeChunk(data, false);
                    }
                }
            }
        }

        buildSuperFrame(idrData) {
            const spsLen = this.sps ? this.sps.length : 0;
            const ppsLen = this.pps ? this.pps.length : 0;
            const superFrame = new Uint8Array(spsLen + ppsLen + idrData.length);
            let offset = 0;
            if (this.sps) { superFrame.set(this.sps, offset); offset += spsLen; }
            if (this.pps) { superFrame.set(this.pps, offset); offset += ppsLen; }
            superFrame.set(idrData, offset);
            return superFrame;
        }

        decodeChunk(data, isKey) {
            try {
                const chunk = new EncodedVideoChunk({
                    type: isKey ? "key" : "delta",
                    timestamp: performance.now() * 1000,
                    data: data
                });
                this.decoder.decode(chunk);
            } catch (e) {}
        }

        handleFrame(frame) {
            if (!this.running) { frame.close(); return; }

            if (this.canvas.width !== frame.displayWidth || this.canvas.height !== frame.displayHeight) {
                this.canvas.width = frame.displayWidth;
                this.canvas.height = frame.displayHeight;
            }

            this.ctx.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
            frame.close();
            this.frameCount++;
            if (this.onFrame) this.onFrame(this.frameCount);
        }

        handleError(e) {
            if (this.decoder) {
                try {
                    this.decoder.reset();
                    this.decoder.configure({
                        codec: this.sps ? this.extractCodecFromSPS(this.sps) : "avc1.42C01F",
                        hardwareAcceleration: "prefer-hardware",
                        optimizeForLatency: true
                    });
                } catch (err) {}
            }
            this.hasReceivedKeyframe = false;
        }

        stop() {
            // Explicit user stop — clear intent so a later tab-show does NOT
            // auto-resume (only _suspendForHidden keeps userWantsStream set).
            this.userWantsStream = false;
            this.reconnectAttempts = 0;
            this.running = false;
            if (this.ws) { this.ws.close(); this.ws = null; }
            if (this.decoder) { try { this.decoder.close(); } catch(e) {} this.decoder = null; }
            this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
            this.sps = null;
            this.pps = null;
            this.hasReceivedKeyframe = false;
            // Remove the visibility listeners on explicit stop so instances that
            // are stopped+discarded don't accumulate orphan handlers. (Kept alive
            // through _suspendForHidden, which must still resume on show.)
            if (typeof document !== 'undefined' && document.removeEventListener) {
                if (this._onVisibility) document.removeEventListener('visibilitychange', this._onVisibility);
                if (this._onPageHide) window.removeEventListener('pagehide', this._onPageHide);
            }
        }

        isRunning() { return this.running; }

        arraysEqual(a, b) {
            if (a.length !== b.length) return false;
            for (let i = 0; i < a.length; i++) {
                if (a[i] !== b[i]) return false;
            }
            return true;
        }

        /**
         * SOTA: Extract avc1 codec string from SPS NAL unit.
         * Format: avc1.PPCCLL where PP=profile_idc, CC=constraint_flags, LL=level_idc
         * This ensures the WebCodecs decoder is configured to match the actual stream.
         */
        extractCodecFromSPS(spsData) {
            try {
                // Find the SPS byte after start code (00 00 00 01 67 or 00 00 01 67)
                let offset = 0;
                if (spsData[0] === 0 && spsData[1] === 0 && spsData[2] === 0 && spsData[3] === 1) {
                    offset = 5;  // Skip start code + NAL header
                } else if (spsData[0] === 0 && spsData[1] === 0 && spsData[2] === 1) {
                    offset = 4;  // Skip start code + NAL header
                } else {
                    offset = 1;  // Raw NAL, skip header
                }
                
                if (offset + 2 < spsData.length) {
                    const profileIdc = spsData[offset];
                    const constraintFlags = spsData[offset + 1];
                    const levelIdc = spsData[offset + 2];
                    const hex = (v) => v.toString(16).padStart(2, '0').toUpperCase();
                    return `avc1.${hex(profileIdc)}${hex(constraintFlags)}${hex(levelIdc)}`;
                }
            } catch (e) {}
            return "avc1.42C01F";  // Fallback: Baseline Profile Level 3.1
        }
    }

    if (typeof module !== 'undefined' && module.exports) module.exports = SotaPlayer;
    else global.SotaPlayer = SotaPlayer;
})(window);
