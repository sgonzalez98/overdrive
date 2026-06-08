package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.logging.LogManager

/**
 * Launches Zrok tunnel processes via ADB shell for remote access.
 * 
 * MODES:
 * 1. RESERVED MODE (Recommended): Uses a pre-reserved token for permanent URL
 *    - URL never changes: https://<unique-name>.share.zrok.io
 *    - Requires one-time setup: `zrok reserve public http://localhost:8080 --unique-name <name>`
 *    - Then use: `zrok share reserved <token>`
 * 
 * 2. PUBLIC MODE (Fallback): Creates random URL each time
 *    - URL changes on every restart
 *    - Uses: `zrok share public http://localhost:8080`
 * 
 * IMPORTANT: Zrok has a 5-device limit on the free tier!
 * - `zrok enable <token>` = Register device (LIMITED to 5 times total!)
 * - `zrok share` = Start tunnel (UNLIMITED restarts)
 * - `zrok reserve` = Reserve a permanent URL (counts as 1 share slot)
 * 
 * Uses AdbShellExecutor for shell operations.
 */
class ZrokLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "ZrokLauncher"
        
        // Zrok paths
        private const val ZROK_TMP_PATH = "/data/local/tmp/zrok"
        private const val ZROK_LOG = "/data/local/tmp/zrok.log"
        private const val ZROK_HOME = "/data/local/tmp"

        // Watchdog state. Mirrors the CameraDaemon pattern: a shell wrapper
        // that re-execs `zrok share` if it ever exits, with a sentinel file
        // the UI/update flows can plant to tell the watchdog to stop.
        // Without this, app-process death (OOM during long parks) leaves
        // zrok unsupervised — if the share process then drops its session
        // the public URL stays registered at the edge but routes to
        // nothing, and the user sees 502 until the next manual restart.
        const val ZROK_WATCHDOG_SCRIPT = "/data/local/tmp/start_zrok.sh"
        const val ZROK_DISABLED_SENTINEL = "/data/local/tmp/zrok.disabled"
        
        // Identity file - THIS IS THE KEY FILE that proves device is enabled
        private const val ZROK_IDENTITY_FILE = "/data/local/tmp/.zrok/environment.json"
        
        // Reserved token file - stores the reserved share token
        private const val ZROK_RESERVED_TOKEN_FILE = "/data/local/tmp/.zrok/reserved_token"
        
        // Enable token file - stores the enable token for cross-UID access
        private const val ZROK_ENABLE_TOKEN_FILE = "/data/local/tmp/.zrok/enable_token"
        
        // Unique name file - stores the generated unique name
        private const val ZROK_UNIQUE_NAME_FILE = "/data/local/tmp/.zrok/unique_name"
        
        // Process name for identification
        private const val ZROK_PROCESS = "zrok"
        
        // Default enable token - can be overridden via setEnableToken()
        // This is loaded from unified storage at runtime
        var zrokToken: String = ""
        
        // Flag to track if token has been loaded from storage
        private var tokenLoaded = false
        
        // Reserved share token (obtained from `zrok reserve` command)
        // Set this after running reserve command once
        var reservedShareToken: String? = null
        
        // Unique name for reserved URL (e.g., "overdrive1a2b3c" -> https://overdrive1a2b3c.share.zrok.io)
        // Generated automatically - must be lowercase alphanumeric only, 4-32 chars
        var uniqueName: String = "overdrive"
        
        // Prefix for unique name generation (no hyphens allowed!)
        private const val UNIQUE_NAME_PREFIX = "overdrive"
        
        // Proxy settings for sing-box (socks5 for zrok)
        private const val PROXY_HOST = "127.0.0.1"
        private const val PROXY_PORT = "8119"
        
        /**
         * Build the start_zrok.sh watchdog script body (one element per line).
         * Static so the Telegram bot daemon — which lives in a separate
         * process and can't easily own a ZrokLauncher instance — can emit
         * the SAME watchdog the UI uses, instead of running zrok unsupervised.
         *
         * The watchdog is sentinel-gated, has no retry cap (sentinel is the
         * only legitimate stop), uses /proc/uptime for monotonic uptime,
         * and applies exponential backoff capped at 60s. See
         * [[feedback_watchdog_no_retry_cap]] for the no-cap rationale.
         *
         * Edge-stale probe (in-shell): a background curl loop runs alongside
         * `zrok share` and kills the share PID after 2 consecutive 502/503/504
         * responses from the public URL. This catches the failure mode where
         * the OpenZiti SDK swallows apiSession auth errors and never exits —
         * process stays alive, public URL returns 502 forever. The in-process
         * checkTunnelHealth() detector handles the same case while the app is
         * alive; the in-shell probe makes recovery survive MainActivity OOM
         * during long parks (the original 8–9hr 502 outage on 2026-05-29 was
         * exactly this: app dead, share alive, edge stale, watchdog blind).
         *
         * CURL_FAIL (transport error) is intentionally NOT counted as a strike
         * — it indicates network loss, where killing+respawning zrok would
         * thrash without helping.
         */
        fun buildZrokWatchdogScriptStatic(reserved: Boolean, shareToken: String, useProxy: Boolean): List<String> {
            val proxyExports = if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                "ALL_PROXY=$proxyUrl HTTP_PROXY=$proxyUrl HTTPS_PROXY=$proxyUrl NO_PROXY=localhost,127.0.0.1 "
            } else ""

            val zrokInvocation = if (reserved) {
                "HOME=$ZROK_HOME ${proxyExports}$ZROK_TMP_PATH share reserved $shareToken --headless"
            } else {
                "HOME=$ZROK_HOME ${proxyExports}$ZROK_TMP_PATH share public http://localhost:8080 --headless"
            }

            return listOf(
                "#!/system/bin/sh",
                "# Zrok Tunnel Watchdog Script",
                "LOG_FILE=\"$ZROK_LOG\"",
                "SENTINEL=\"$ZROK_DISABLED_SENTINEL\"",
                "UNIQUE_NAME_FILE=\"/data/local/tmp/.zrok/unique_name\"",
                "RETRY_COUNT=0",
                "HEALTHY_UPTIME_SEC=300",
                "PROBE_INTERVAL_SEC=60",
                "PROBE_INITIAL_DELAY_SEC=60",
                "PROBE_STRIKES=2",
                "",
                "while true; do",
                "  if [ -f \"\$SENTINEL\" ]; then",
                "    echo \"[\$(date)] Tunnel disabled by user (sentinel file exists). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                // Catch a zrok.log left oversized by a previous run before we
                // restart the share. Real-time bounding during the share's life
                // is handled by the log poller co-process added after ZROK_PID
                // below (alongside the edge-stale probe). Shared helper +
                // constant with the daemon watchdogs — single rotation policy.
                *DaemonLauncher.logRotateGuardLines().toTypedArray(),
                "  echo \"[\$(date)] Starting zrok share...\" >> \"\$LOG_FILE\"",
                "  START_EPOCH=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
                "",
                // Run zrok in background so we can supervise it with a probe.
                // Backgrounded directly (no subshell wrapper) so \$! is zrok's
                // own PID — `kill \$ZROK_PID` then signals the share process,
                // not a wrapper shell whose death would orphan zrok to init.
                "  $zrokInvocation >> \"\$LOG_FILE\" 2>&1 &",
                "  ZROK_PID=\$!",
                "",
                // Log-size poller: bounds zrok.log in real time for the whole
                // life of the share (the edge can flap once a minute, one log
                // line per failed probe → unbounded across multi-day parks).
                // Truncates in place on the shared cadence; killed after wait.
                *DaemonLauncher.logRotateCoprocessLines("ZROK_PID", "ROTATE_PID").toTypedArray(),
                "",
                // Edge-stale probe loop. Lives only as long as ZROK_PID.
                // Reads unique_name fresh each tick (it can change after a
                // factory-reset / re-reserve while the watchdog is running).
                // Kills the share PID on 2 consecutive HTTP 502/503/504 from
                // the public URL — outer while loop respawns it with a fresh
                // apiSession. Routes through sing-box socks5 if present so
                // the probe traverses the same network path zrok itself uses.
                "  (",
                "    PROBE_FAILS=0",
                "    sleep \$PROBE_INITIAL_DELAY_SEC",
                "    while kill -0 \$ZROK_PID 2>/dev/null; do",
                "      if [ -f \"\$SENTINEL\" ]; then break; fi",
                "      NAME=\$(cat \"\$UNIQUE_NAME_FILE\" 2>/dev/null)",
                "      if [ -z \"\$NAME\" ]; then sleep \$PROBE_INTERVAL_SEC; continue; fi",
                "      if pgrep -f 'sing-box' >/dev/null 2>&1; then",
                "        STATUS=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 --socks5-hostname 127.0.0.1:$PROXY_PORT \"https://\${NAME}.share.zrok.io\" 2>/dev/null || echo CURL_FAIL)",
                "      else",
                "        STATUS=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \"https://\${NAME}.share.zrok.io\" 2>/dev/null || echo CURL_FAIL)",
                "      fi",
                "      case \"\$STATUS\" in",
                "        502|503|504)",
                "          PROBE_FAILS=\$((PROBE_FAILS + 1))",
                "          echo \"[\$(date)] Edge probe got HTTP \$STATUS for \$NAME (consecutive=\$PROBE_FAILS)\" >> \"\$LOG_FILE\"",
                "          if [ \$PROBE_FAILS -ge \$PROBE_STRIKES ]; then",
                // PID-recycle guard: confirm /proc/<pid>/cmdline still
                // contains "zrok" before signalling. On Android PID
                // recycle within a 60-second window is statistically
                // rare but possible — without this guard a worst-case
                // race could SIGKILL an unrelated process. Same defensive
                // pattern used by acquireSingletonLock for daemon locks.
                "            if grep -aq zrok \"/proc/\$ZROK_PID/cmdline\" 2>/dev/null; then",
                "              echo \"[\$(date)] Edge stale confirmed — killing zrok pid \$ZROK_PID for respawn\" >> \"\$LOG_FILE\"",
                "              kill \$ZROK_PID 2>/dev/null",
                "              sleep 2",
                "              if grep -aq zrok \"/proc/\$ZROK_PID/cmdline\" 2>/dev/null; then",
                "                kill -9 \$ZROK_PID 2>/dev/null",
                "              fi",
                "            else",
                "              echo \"[\$(date)] PID \$ZROK_PID no longer zrok (recycled or already exited) — skipping kill, exiting probe\" >> \"\$LOG_FILE\"",
                "            fi",
                "            break",
                "          fi",
                "          ;;",
                "        *)",
                // CURL_FAIL falls into this bucket on purpose: don't count
                // network loss as a strike, but also don't reset the strike
                // counter on it — preserve any prior 5xx evidence across a
                // brief offline blip. Reset only on a real successful probe.
                "          if [ \"\$STATUS\" != \"CURL_FAIL\" ] && [ -n \"\$STATUS\" ] && [ \"\$STATUS\" != \"000\" ]; then",
                "            if [ \$PROBE_FAILS -gt 0 ]; then",
                "              echo \"[\$(date)] Edge probe recovered (HTTP \$STATUS), resetting strike counter\" >> \"\$LOG_FILE\"",
                "            fi",
                "            PROBE_FAILS=0",
                "          fi",
                "          ;;",
                "      esac",
                "      sleep \$PROBE_INTERVAL_SEC",
                "    done",
                "  ) &",
                "  PROBE_PID=\$!",
                "",
                // Wait for zrok to exit — either naturally (apiSession
                // exhausted, OOM, etc.) or because the probe killed it on
                // confirmed edge-stale.
                "  wait \$ZROK_PID",
                "  EXIT_CODE=\$?",
                // Probe + log poller may already have exited via kill -0
                // returning 1; kill is harmless on dead pid. wait reaps zombies.
                "  kill \$PROBE_PID 2>/dev/null",
                "  wait \$PROBE_PID 2>/dev/null",
                "  kill \$ROTATE_PID 2>/dev/null",
                "  wait \$ROTATE_PID 2>/dev/null",
                "",
                "  END_EPOCH=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
                "  UPTIME_SEC=\$((END_EPOCH - START_EPOCH))",
                "  if [ \$UPTIME_SEC -lt 0 ]; then UPTIME_SEC=0; fi",
                "  if [ -f \"\$SENTINEL\" ]; then",
                "    echo \"[\$(date)] Tunnel disabled by user (sentinel written during shutdown). Exiting watchdog.\" >> \"\$LOG_FILE\"",
                "    exit 0",
                "  fi",
                "  if [ \$UPTIME_SEC -ge \$HEALTHY_UPTIME_SEC ] && [ \$RETRY_COUNT -gt 0 ]; then",
                "    echo \"[\$(date)] Tunnel ran healthy for \${UPTIME_SEC}s before exit \$EXIT_CODE — resetting retry counter\" >> \"\$LOG_FILE\"",
                "    RETRY_COUNT=0",
                "  fi",
                "  RETRY_COUNT=\$((RETRY_COUNT + 1))",
                "  DELAY=\$((RETRY_COUNT * 3))",
                "  if [ \$DELAY -gt 60 ]; then DELAY=60; fi",
                "  echo \"[\$(date)] Tunnel exited with code \$EXIT_CODE after \${UPTIME_SEC}s (attempt \$RETRY_COUNT), retrying in \${DELAY}s...\" >> \"\$LOG_FILE\"",
                "  sleep \$DELAY",
                "done"
            )
        }

        /**
         * Generate a unique name for this device.
         * Format: overdrive<6-char-random>
         * Must be lowercase alphanumeric only, 4-32 chars (zrok requirement).
         * Returns a NEW random value each time called.
         */
        fun generateUniqueName(context: Context): String {
            // Generate random 6-char alphanumeric string (lowercase only, no hyphens)
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val random = java.util.Random()
            val suffix = (1..6)
                .map { chars[random.nextInt(chars.length)] }
                .joinToString("")
            
            return "$UNIQUE_NAME_PREFIX$suffix"
        }
    }
    
    interface ZrokCallback {
        fun onLog(message: String)
        fun onTunnelUrl(url: String)
        fun onError(error: String)
    }
    
    /**
     * Launch Zrok tunnel via ADB shell.
     * 
     * Priority:
     * 1. If reservedShareToken is set, use reserved mode (permanent URL)
     * 2. Otherwise, use public mode (random URL)
     * 
     * NOTE: Zrok and Cloudflared are mutually exclusive - this will kill cloudflared first.
     */
    fun launchZrok(callback: ZrokCallback) {
        logManager.info(TAG, "Launching Zrok tunnel...")
        callback.onLog("Loading token...")
        
        // First ensure token is loaded from unified storage
        ensureTokenLoaded { hasToken ->
            if (!hasToken) {
                logManager.error(TAG, "No enable token configured!")
                callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
                return@ensureTokenLoaded
            }
            
            callback.onLog("Checking for existing tunnel...")
            
            // Kill cloudflared first (mutually exclusive)
            killCloudflaredIfRunning {
                // Then check if zrok tunnel is already running
                isTunnelRunning { isRunning ->
                    if (isRunning) {
                        logManager.info(TAG, "Zrok already running, checking for URL...")
                        callback.onLog("Tunnel already running, getting URL...")
                        // Clear the disable sentinel synchronously: report
                        // "running" to the caller only after the rm has
                        // landed. If we proceeded asynchronously, a daemon
                        // exit racing the rm could let the watchdog gate-2
                        // see the still-present sentinel and exit 0
                        // (silent permanent stop). The continuation runs
                        // inside the rm callback so ordering is guaranteed.
                        val proceedAfterSentinelClear = {
                            getTunnelUrl { existingUrl ->
                                if (existingUrl != null) {
                                    logManager.info(TAG, "Reusing existing tunnel: $existingUrl")
                                    callback.onLog("Reusing existing tunnel")
                                    callback.onTunnelUrl(existingUrl)
                                } else {
                                    logManager.info(TAG, "Tunnel running but no URL yet, waiting...")
                                    callback.onLog("Waiting for tunnel URL...")
                                    waitForTunnelUrl(callback, 1)
                                }
                            }
                        }
                        adbShellExecutor.execute(
                            command = "rm -f $ZROK_DISABLED_SENTINEL 2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) { proceedAfterSentinelClear() }
                                override fun onError(e: String) {
                                    logManager.warn(TAG, "Sentinel rm failed on already-running fast path: $e")
                                    proceedAfterSentinelClear()
                                }
                            }
                        )
                    } else {
                        // Not running, check if binary is installed
                        callback.onLog("Setting up zrok...")
                        checkAndInstallZrok(callback)
                    }
                }
            }
        }
    }
    
    /**
     * Launch Zrok in RESERVED mode with a specific token.
     * This gives you a permanent URL that never changes.
     * 
     * @param shareToken The reserved share token (from `zrok reserve` command)
     * @param permanentUrl The known permanent URL (e.g., https://byd-sentry-01.share.zrok.io)
     */
    fun launchZrokReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        reservedShareToken = shareToken
        logManager.info(TAG, "Launching Zrok in RESERVED mode...")
        callback.onLog("Starting reserved tunnel...")
        
        // Kill cloudflared first (mutually exclusive)
        killCloudflaredIfRunning {
            isTunnelRunning { isRunning ->
                if (isRunning) {
                    logManager.info(TAG, "Zrok already running")
                    callback.onLog("Tunnel already running")
                    // Clear the disable sentinel synchronously, then
                    // reconcile. The reconcile + onTunnelUrl callback runs
                    // inside the rm's onSuccess so ordering is guaranteed.
                    val proceedAfterSentinelClear = {
                        // Reconcile against the running tunnel's actual URL —
                        // the zrok process from a previous boot may have
                        // bound a name that disagrees with the permanentUrl
                        // the app cached.
                        reconcileTunnelUrl(permanentUrl, attempt = 1) { actualUrl ->
                            callback.onTunnelUrl(actualUrl)
                        }
                    }
                    adbShellExecutor.execute(
                        command = "rm -f $ZROK_DISABLED_SENTINEL 2>/dev/null; echo done",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(o: String) { proceedAfterSentinelClear() }
                            override fun onError(e: String) {
                                logManager.warn(TAG, "Sentinel rm failed on reserved fast path: $e")
                                proceedAfterSentinelClear()
                            }
                        }
                    )
                } else {
                    callback.onLog("Setting up zrok...")
                    checkAndInstallZrokForReserved(shareToken, permanentUrl, callback)
                }
            }
        }
    }
    
    /**
     * Reserve a permanent URL (ONE-TIME setup).
     * Run this once to get a reserved share token.
     * 
     * @param customName Optional custom name. If null, uses auto-generated unique name.
     * @return The reserved share token via callback
     */
    fun reservePermanentUrl(customName: String? = null, callback: ZrokCallback) {
        // Use custom name or load/generate unique name
        if (customName != null) {
            uniqueName = customName
            saveUniqueName(customName)
            doReservePermanentUrl(callback)
        } else {
            loadSavedUniqueName { savedName ->
                if (savedName != null) {
                    uniqueName = savedName
                } else {
                    uniqueName = generateUniqueName(context)
                    saveUniqueName(uniqueName)
                }
                doReservePermanentUrl(callback)
            }
        }
    }
    
    private fun doReservePermanentUrl(callback: ZrokCallback) {
        logManager.info(TAG, "Reserving permanent URL with name: $uniqueName")
        callback.onLog("Reserving: https://$uniqueName.share.zrok.io")
        
        // First ensure device is enabled
        checkAndInstallZrok(object : ZrokCallback {
            override fun onLog(message: String) {
                callback.onLog(message)
            }
            
            override fun onTunnelUrl(url: String) {
                // After enable, run reserve command
                runReserveCommand(uniqueName, callback)
            }
            
            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    
    private fun runReserveCommand(uniqueName: String, callback: ZrokCallback) {
        // Check if sing-box proxy is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    executeReserveCommand(uniqueName, useProxy, callback)
                }
                
                override fun onError(error: String) {
                    executeReserveCommand(uniqueName, false, callback)
                }
            }
        )
    }
    
    private fun executeReserveCommand(uniqueName: String, useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                val proxyUrl = "socks5h://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            
            // Note: `zrok reserve` doesn't support --headless
            append("$ZROK_TMP_PATH reserve public http://localhost:8080 --unique-name $uniqueName 2>&1")
        }
        
        logManager.debug(TAG, "Executing reserve: $cmd")
        callback.onLog("Running reserve command...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserve output: $output")
                    
                    // Parse the reserved token from output
                    // Expected: [INFO] your reserved share token is 'abc-xyz-123'
                    val tokenPattern = Regex("token is '([^']+)'")
                    val match = tokenPattern.find(output)
                    
                    if (match != null) {
                        val token = match.groupValues[1]
                        logManager.info(TAG, "✅ Reserved token: $token")
                        callback.onLog("✅ Reserved! Token: $token")
                        callback.onLog("Permanent URL: https://$uniqueName.share.zrok.io")
                        
                        // Save token to file for persistence
                        saveReservedToken(token)
                        reservedShareToken = token
                        
                        // Return the permanent URL
                        callback.onTunnelUrl("https://$uniqueName.share.zrok.io")
                    } else if (output.contains("already reserved") || output.contains("exists")) {
                        callback.onLog("⚠️ Name already reserved. Use existing token.")
                        callback.onError("Name '$uniqueName' already reserved. Check saved token or use different name.")
                    } else {
                        callback.onError("Failed to reserve: $output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Reserve failed: $error")
                    callback.onError("Reserve failed: $error")
                }
            }
        )
    }
    
    private fun saveReservedToken(token: String) {
        adbShellExecutor.execute(
            command = "echo '$token' > $ZROK_RESERVED_TOKEN_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserved token saved to file")
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to save token: $error")
                }
            }
        )
    }
    
    /**
     * Load saved reserved token from file.
     */
    fun loadReservedToken(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_RESERVED_TOKEN_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val token = output.trim()
                    if (token.isNotEmpty() && !token.contains("No such file")) {
                        reservedShareToken = token
                        callback(token)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    private fun checkAndInstallZrokForReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        adbShellExecutor.execute(
            command = "test -x $ZROK_TMP_PATH && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        checkEnableAndLaunchReserved(shareToken, permanentUrl, callback)
                    } else {
                        installZrokThenReserved(shareToken, permanentUrl, callback)
                    }
                }
                
                override fun onError(error: String) {
                    installZrokThenReserved(shareToken, permanentUrl, callback)
                }
            }
        )
    }
    
    private fun installZrokThenReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libzrok.so"
        
        callback.onLog("Installing zrok...")
        
        adbShellExecutor.execute(
            command = "test -f $srcPath && cp $srcPath $ZROK_TMP_PATH && chmod +x $ZROK_TMP_PATH && echo ok || echo fail",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "ok") {
                        callback.onLog("zrok installed")
                        checkEnableAndLaunchReserved(shareToken, permanentUrl, callback)
                    } else {
                        callback.onError("Failed to install zrok")
                    }
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to install zrok: $error")
                }
            }
        )
    }
    
    private fun checkEnableAndLaunchReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        callback.onLog("Checking zrok identity...")
        
        adbShellExecutor.execute(
            command = "test -f $ZROK_IDENTITY_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        logManager.info(TAG, "✅ Device already enabled")
                        callback.onLog("✅ Device enabled")
                        launchZrokShareReserved(shareToken, permanentUrl, callback)
                    } else {
                        logManager.warn(TAG, "⚠️ Device not enabled. Enabling now...")
                        callback.onLog("⚠️ Registering device...")
                        enableZrokThenReserved(shareToken, permanentUrl, callback)
                    }
                }
                
                override fun onError(error: String) {
                    enableZrokThenReserved(shareToken, permanentUrl, callback)
                }
            }
        )
    }
    
    private fun enableZrokThenReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    enableZrokWithConfigThenReserved(shareToken, permanentUrl, useProxy, callback)
                }
                
                override fun onError(error: String) {
                    enableZrokWithConfigThenReserved(shareToken, permanentUrl, false, callback)
                }
            }
        )
    }
    
    private fun enableZrokWithConfigThenReserved(shareToken: String, permanentUrl: String, useProxy: Boolean, callback: ZrokCallback) {
        // Check for token first
        if (zrokToken.isEmpty()) {
            logManager.error(TAG, "No enable token configured!")
            callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
            return
        }
        
        val cmd = buildString {
            append("HOME=$ZROK_HOME ")
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl HTTP_PROXY=$proxyUrl HTTPS_PROXY=$proxyUrl NO_PROXY=localhost,127.0.0.1 ")
            }
            append("$ZROK_TMP_PATH enable $zrokToken --headless 2>&1")
        }
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Enable output: $output")
                    // Settle on reconcileScheduler instead of parking the
                    // ADB executor for 500ms.
                    reconcileScheduler.schedule({
                        launchZrokShareReserved(shareToken, permanentUrl, callback)
                    }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                
                override fun onError(error: String) {
                    callback.onError("Failed to enable zrok: $error")
                }
            }
        )
    }
    
    /**
     * Launch zrok share in RESERVED mode.
     * Uses: `zrok share reserved <token> --headless`
     * This is the recommended mode for permanent URLs.
     */
    private fun launchZrokShareReserved(shareToken: String, permanentUrl: String, callback: ZrokCallback) {
        callback.onLog("Starting reserved share...")
        
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    startZrokShareReservedProcess(shareToken, permanentUrl, useProxy, callback)
                }
                
                override fun onError(error: String) {
                    startZrokShareReservedProcess(shareToken, permanentUrl, false, callback)
                }
            }
        )
    }
    
    private fun startZrokShareReservedProcess(shareToken: String, permanentUrl: String, useProxy: Boolean, callback: ZrokCallback) {
        // Pre-launch sweep via script-tmpfile form (executeScript) so the
        // toybox pkill -f self-match doesn't drop trailing commands. The
        // running shell's argv is `sh <tmpPath>`, no "zrok" pattern visible.
        val cleanupScript =
            "rm -f $ZROK_DISABLED_SENTINEL $ZROK_WATCHDOG_SCRIPT $ZROK_LOG 2>/dev/null\n" +
            com.overdrive.app.launcher.DaemonLauncher.psAwkKillLine("zrok") +
            "echo done\n"
        adbShellExecutor.executeScript(
            scriptBody = cleanupScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    writeAndLaunchWatchdog(reserved = true, shareToken = shareToken,
                            permanentUrl = permanentUrl, useProxy = useProxy, callback = callback)
                }
                override fun onError(error: String) {
                    writeAndLaunchWatchdog(reserved = true, shareToken = shareToken,
                            permanentUrl = permanentUrl, useProxy = useProxy, callback = callback)
                }
            }
        )
    }

    /**
     * Write `start_zrok.sh` and launch it. The watchdog re-execs the share
     * binary on exit (with retry-counter reset after HEALTHY_UPTIME_SEC of
     * uptime, exactly like start_cam_daemon.sh) and bails out if the
     * disable sentinel exists. Used by both reserved and public mode.
     */
    private fun writeAndLaunchWatchdog(
            reserved: Boolean,
            shareToken: String,
            permanentUrl: String,
            useProxy: Boolean,
            callback: ZrokCallback
    ) {
        val scriptLines = buildZrokWatchdogScript(reserved, shareToken, useProxy)
        val writeCmd = buildString {
            append("rm -f $ZROK_WATCHDOG_SCRIPT 2>/dev/null; ")
            scriptLines.forEachIndexed { index, line ->
                val escaped = line
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\$", "\\$")
                        .replace("`", "\\`")
                if (index == 0) {
                    append("echo \"$escaped\" > $ZROK_WATCHDOG_SCRIPT; ")
                } else {
                    append("echo \"$escaped\" >> $ZROK_WATCHDOG_SCRIPT; ")
                }
            }
            append("chmod 755 $ZROK_WATCHDOG_SCRIPT")
        }

        adbShellExecutor.execute(
            command = writeCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    launchWatchdog(reserved, permanentUrl, callback)
                }
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to write zrok watchdog script: $error")
                    // Fall back to bare nohup launch — better than nothing.
                    // Route by mode: public-mode would otherwise be invoked
                    // with shareToken="" and produce a malformed
                    // `zrok share reserved  --headless` invocation.
                    if (reserved) {
                        launchReservedProcessBare(shareToken, permanentUrl, useProxy, callback)
                    } else {
                        launchPublicProcessBare(useProxy, callback)
                    }
                }
            }
        )
    }

    private fun buildZrokWatchdogScript(reserved: Boolean, shareToken: String, useProxy: Boolean): List<String> =
            buildZrokWatchdogScriptStatic(reserved, shareToken, useProxy)

    private fun launchWatchdog(reserved: Boolean, permanentUrl: String, callback: ZrokCallback) {
        val launchCmd = "nohup sh $ZROK_WATCHDOG_SCRIPT > /dev/null 2>&1 &"
        logManager.debug(TAG, "Launching zrok watchdog: $launchCmd")
        adbShellExecutor.execute(
            command = launchCmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "✅ Zrok watchdog launched (reserved=$reserved)")
                    callback.onLog("✅ Tunnel started!")
                    if (reserved) {
                        // Reconcile against the actual URL zrok bound — token
                        // may resolve server-side to a name that disagrees
                        // with the local unique_name file.
                        reconcileTunnelUrl(permanentUrl, attempt = 1) { actualUrl ->
                            callback.onLog("Permanent URL: $actualUrl")
                            callback.onTunnelUrl(actualUrl)
                        }
                    } else {
                        callback.onLog("Waiting for tunnel URL...")
                        waitForTunnelUrl(callback, 1)
                    }
                }
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch zrok watchdog: $error")
                    callback.onError("Watchdog launch failed: $error")
                }
            }
        )
    }

    /**
     * Last-resort fallback when the watchdog script can't be written —
     * launch zrok bare, no supervisor. Same shape as the pre-watchdog
     * code path.
     */
    private fun launchReservedProcessBare(shareToken: String, permanentUrl: String, useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            append("nohup sh -c '")
            append("HOME=$ZROK_HOME ")

            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }

            append("$ZROK_TMP_PATH share reserved $shareToken --headless")
            append("' > $ZROK_LOG 2>&1 &")
        }

        logManager.debug(TAG, "Executing reserved share (bare fallback): $cmd")

        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.warn(TAG, "Reserved tunnel started without watchdog (fallback)")
                    callback.onLog("✅ Tunnel started (no watchdog)!")
                    // The reserved token's URL is decided by zrok server-side, NOT
                    // by this client's local `uniqueName` field. If the local file
                    // ever drifted from the token's actual reserved name (manual
                    // shell `zrok reserve`, factory-reset that wiped the unique_name
                    // file but kept the token, cross-device token copy, etc.), the
                    // local `permanentUrl` would be a fiction. Read the log and
                    // reconcile the file with what zrok actually bound. Without
                    // this, HttpServer.isPwaOrigin() rejects /sw.js on the live
                    // tunnel because its hostname doesn't match unique_name file.
                    reconcileTunnelUrl(permanentUrl, attempt = 1) { actualUrl ->
                        callback.onLog("Permanent URL: $actualUrl")
                        callback.onTunnelUrl(actualUrl)
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch reserved share: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }

    /**
     * Public-mode bare fallback. Mirrors launchReservedProcessBare but emits
     * `zrok share public http://localhost:8080` instead of the reserved-token
     * form. Without a public-specific bare path, the script-write-failure
     * fallback for public mode would route into the reserved variant with
     * shareToken="" and produce `zrok share reserved  --headless` (empty
     * token), which zrok rejects.
     */
    private fun launchPublicProcessBare(useProxy: Boolean, callback: ZrokCallback) {
        val cmd = buildString {
            append("nohup sh -c '")
            append("HOME=$ZROK_HOME ")

            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }

            append("$ZROK_TMP_PATH share public http://localhost:8080 --headless")
            append("' > $ZROK_LOG 2>&1 &")
        }

        logManager.debug(TAG, "Executing public share (bare fallback): $cmd")

        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.warn(TAG, "Public tunnel started without watchdog (fallback)")
                    callback.onLog("✅ Tunnel started (no watchdog)!")
                    waitForTunnelUrl(callback, 1)
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to launch public share: $error")
                    callback.onError("Launch failed: $error")
                }
            }
        )
    }

    /**
     * Dedicated scheduler for reconcile retries. Using a separate thread
     * means the inter-attempt wait does NOT hold the shared AdbShellExecutor
     * hostage — DaemonLauncher (which uses the same executor to start
     * CameraDaemon) can interleave its shell commands between reconcile
     * attempts. Single-thread is fine: only one reconcile flow runs at a time.
     */
    private val reconcileScheduler: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "zrok-reconcile").apply { isDaemon = true }
            }

    /**
     * Shut down the reconcile scheduler. Call from cleanup paths to prevent
     * the stranded daemon thread on instance teardown. The scheduler thread
     * is daemon-flagged so the JVM doesn't block on it at exit, but on
     * Android the process keeps running across Activity teardowns and we
     * accumulate stranded threads otherwise.
     */
    fun shutdown() {
        try {
            reconcileScheduler.shutdownNow()
        } catch (e: Exception) {
            logManager.warn(TAG, "reconcileScheduler shutdown failed: ${e.message}")
        }
    }

    /**
     * Read zrok's log to discover the actual tunnel URL it bound, then
     * reconcile the local {@code unique_name} file + in-memory state with
     * what zrok server-side resolved the reserved token to. Falls back to
     * the locally-computed {@code expectedUrl} after a bounded number of
     * polls if the log never emits a URL (e.g. a transient share failure
     * already surfaced as an error elsewhere).
     *
     * Calls {@code onUrl} with the URL the rest of the launcher should
     * propagate to its callback. Idempotent — writing the same name back
     * to the file is harmless.
     */
    private fun reconcileTunnelUrl(expectedUrl: String, attempt: Int, onUrl: (String) -> Unit) {
        // 10 attempts × ~1s each = ~10s to find the URL in the log. zrok
        // typically emits within 1-2s; the higher cap covers slow links.
        if (attempt > 10) {
            logManager.warn(TAG, "reconcileTunnelUrl: log never emitted URL after 10 attempts, " +
                    "falling back to local expected=$expectedUrl")
            onUrl(expectedUrl)
            return
        }
        // Wait 1s on the scheduler thread, THEN enqueue a fast grep on the
        // shared ADB executor. The shared executor is only held for the
        // grep itself (~50ms), not the inter-attempt wait. CameraDaemon's
        // launch shell commands queued on the same executor can interleave
        // between attempts without delay.
        reconcileScheduler.schedule({
            adbShellExecutor.execute(
                    "grep -oE 'https://[a-z0-9]+\\.share\\.zrok\\.io' $ZROK_LOG 2>/dev/null | head -1",
                    object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            val actualUrl = output.trim()
                            if (actualUrl.isEmpty() || !actualUrl.startsWith("https://")) {
                                reconcileTunnelUrl(expectedUrl, attempt + 1, onUrl)
                                return
                            }
                            // Extract the unique-name segment from "https://<name>.share.zrok.io".
                            val nameRegex = Regex("^https://([a-z0-9]+)\\.share\\.zrok\\.io$")
                            val nameMatch = nameRegex.find(actualUrl)
                            if (nameMatch == null) {
                                logManager.warn(TAG, "reconcileTunnelUrl: couldn't parse unique-name from $actualUrl")
                                onUrl(actualUrl)
                                return
                            }
                            val actualName = nameMatch.groupValues[1]
                            if (actualName != uniqueName) {
                                logManager.warn(TAG,
                                        "Reserved tunnel name drifted: expected=$uniqueName actual=$actualName " +
                                        "→ rewriting unique_name file. (Likely cause: token was reserved " +
                                        "with a different name than the local file remembers — manual zrok " +
                                        "reserve, cross-device token copy, or factory-reset of /data/local/tmp/.zrok.)")
                                uniqueName = actualName
                                saveUniqueName(actualName)
                            } else {
                                logManager.info(TAG, "Tunnel URL reconciled: $actualUrl (matches local unique_name)")
                            }
                            onUrl(actualUrl)
                        }
                        override fun onError(error: String) {
                            reconcileTunnelUrl(expectedUrl, attempt + 1, onUrl)
                        }
                    }
            )
        }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }
    
    /**
     * Kill cloudflared if running (mutual exclusion).
     */
    private fun killCloudflaredIfRunning(onComplete: () -> Unit) {
        adbShellExecutor.execute(
            command = "pgrep -f cloudflared",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().isNotEmpty()) {
                        logManager.info(TAG, "Killing cloudflared (mutually exclusive with zrok)")
                        adbShellExecutor.execute(
                            command = "killall -9 cloudflared 2>/dev/null; echo done",
                            callback = object : AdbShellExecutor.ShellCallback {
                                override fun onSuccess(o: String) {
                                    // Settle on the reconcile scheduler, NOT on the ADB
                                    // executor — Thread.sleep here parks the shared
                                    // single-thread executor, blocking every other
                                    // queued shell command for 300ms.
                                    reconcileScheduler.schedule({ onComplete() }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }
                                override fun onError(e: String) { onComplete() }
                            }
                        )
                    } else {
                        onComplete()
                    }
                }
                override fun onError(error: String) { onComplete() }
            }
        )
    }
    
    private fun checkAndInstallZrok(callback: ZrokCallback) {
        adbShellExecutor.execute(
            command = "test -x $ZROK_TMP_PATH && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        checkEnableAndLaunch(callback)
                    } else {
                        installZrok(callback)
                    }
                }
                
                override fun onError(error: String) {
                    installZrok(callback)
                }
            }
        )
    }
    
    private fun installZrok(callback: ZrokCallback) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val srcPath = "$nativeLibDir/libzrok.so"
        
        callback.onLog("Installing zrok...")
        
        // Check if source exists
        adbShellExecutor.execute(
            command = "test -f $srcPath && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() != "yes") {
                        logManager.error(TAG, "libzrok.so not found")
                        callback.onError("libzrok.so not found. Add it to jniLibs/arm64-v8a/")
                        return
                    }
                    
                    // Copy and make executable
                    adbShellExecutor.execute(
                        command = "cp $srcPath $ZROK_TMP_PATH && chmod +x $ZROK_TMP_PATH",
                        callback = object : AdbShellExecutor.ShellCallback {
                            override fun onSuccess(copyOutput: String) {
                                callback.onLog("zrok installed")
                                checkEnableAndLaunch(callback)
                            }
                            
                            override fun onError(error: String) {
                                logManager.error(TAG, "Failed to install zrok: $error")
                                callback.onError("Failed to install zrok: $error")
                            }
                        }
                    )
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to check zrok source: $error")
                    callback.onError("Failed to check zrok source: $error")
                }
            }
        )
    }

    /**
     * CRITICAL: Check if device is already enabled before running `zrok enable`.
     * Also checks for reserved token to use reserved mode if available.
     * 
     * The free tier only allows 5 device registrations TOTAL.
     * We check for environment.json to avoid wasting registrations.
     */
    private fun checkEnableAndLaunch(callback: ZrokCallback) {
        callback.onLog("Checking zrok identity...")
        
        // First load saved unique name (if any)
        loadSavedUniqueName { savedName ->
            if (savedName != null) {
                uniqueName = savedName
                logManager.info(TAG, "Loaded saved unique name: $uniqueName")
            } else {
                // Generate new unique name for this device
                uniqueName = generateUniqueName(context)
                logManager.info(TAG, "Generated unique name: $uniqueName")
                saveUniqueName(uniqueName)
            }
            
            // Then check if we have a reserved token
            loadReservedToken { savedToken ->
                if (savedToken != null) {
                    logManager.info(TAG, "Found saved reserved token, using reserved mode")
                    callback.onLog("✅ Using reserved mode (permanent URL)")
                    val permanentUrl = "https://$uniqueName.share.zrok.io"
                    checkEnableAndLaunchReserved(savedToken, permanentUrl, callback)
                    return@loadReservedToken
                }
                
                // No reserved token, check if reservedShareToken is set programmatically
                if (reservedShareToken != null) {
                    logManager.info(TAG, "Using programmatic reserved token")
                    callback.onLog("✅ Using reserved mode (permanent URL)")
                    val permanentUrl = "https://$uniqueName.share.zrok.io"
                    checkEnableAndLaunchReserved(reservedShareToken!!, permanentUrl, callback)
                    return@loadReservedToken
                }
                
                // No reserved token, use public mode (random URL)
                logManager.info(TAG, "No reserved token, using public mode")
                checkEnableAndLaunchPublic(callback)
            }
        }
    }
    
    private fun loadSavedUniqueName(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_UNIQUE_NAME_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val name = output.trim()
                    // Must be lowercase alphanumeric, 4-32 chars, starting with "overdrive"
                    if (name.isNotEmpty() && !name.contains("No such file") && 
                        name.startsWith("overdrive") && name.matches(Regex("^[a-z0-9]{4,32}$"))) {
                        callback(name)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    private fun saveUniqueName(name: String) {
        adbShellExecutor.execute(
            command = "mkdir -p /data/local/tmp/.zrok && echo '$name' > $ZROK_UNIQUE_NAME_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Unique name saved: $name")
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to save unique name: $error")
                }
            }
        )
    }
    
    private fun checkEnableAndLaunchPublic(callback: ZrokCallback) {
        // Check for the SPECIFIC identity file, not just the directory
        adbShellExecutor.execute(
            command = "test -f $ZROK_IDENTITY_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim() == "yes") {
                        // Device already enabled - now check if we need to reserve
                        logManager.info(TAG, "✅ Device already enabled (environment.json exists)")
                        callback.onLog("✅ Device already enabled")
                        checkReserveAndLaunch(callback)
                    } else {
                        // Need to enable - THIS COUNTS AGAINST THE 5-DEVICE LIMIT!
                        logManager.warn(TAG, "⚠️ Device not enabled. Will register now (uses 1 of 5 slots)")
                        callback.onLog("⚠️ Registering device (1 of 5 allowed)...")
                        enableZrokEnvironment(callback)
                    }
                }
                
                override fun onError(error: String) {
                    // Can't check - try to enable anyway
                    logManager.warn(TAG, "Cannot check identity file, attempting enable")
                    enableZrokEnvironment(callback)
                }
            }
        )
    }
    
    /**
     * Check if we have a reserved token. If not, reserve one automatically.
     * This is similar to the enable check - reserve once, use forever.
     */
    private fun checkReserveAndLaunch(callback: ZrokCallback) {
        callback.onLog("Checking for reserved URL...")
        
        // Check if reserved token file exists
        adbShellExecutor.execute(
            command = "cat $ZROK_RESERVED_TOKEN_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val token = output.trim()
                    if (token.isNotEmpty() && !token.contains("No such file")) {
                        // Have reserved token - use reserved mode
                        logManager.info(TAG, "✅ Found reserved token, using permanent URL")
                        callback.onLog("✅ Using permanent URL")
                        reservedShareToken = token
                        val permanentUrl = "https://$uniqueName.share.zrok.io"
                        launchZrokShareReserved(token, permanentUrl, callback)
                    } else {
                        // No reserved token - need to reserve first (ONE TIME)
                        logManager.info(TAG, "⚠️ No reserved token. Reserving permanent URL...")
                        callback.onLog("⚠️ Reserving permanent URL (one-time setup)...")
                        autoReserveAndLaunch(callback)
                    }
                }
                
                override fun onError(error: String) {
                    // No reserved token - need to reserve first
                    logManager.info(TAG, "⚠️ No reserved token. Reserving permanent URL...")
                    callback.onLog("⚠️ Reserving permanent URL (one-time setup)...")
                    autoReserveAndLaunch(callback)
                }
            }
        )
    }
    
    /**
     * Automatically reserve a permanent URL and then launch.
     * This runs `zrok reserve` once, saves the token, then uses `zrok share reserved`.
     */
    private fun autoReserveAndLaunch(callback: ZrokCallback) {
        callback.onLog("Reserving: https://$uniqueName.share.zrok.io")
        
        // Check if sing-box proxy is running
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    executeAutoReserve(useProxy, callback, 0)
                }
                
                override fun onError(error: String) {
                    executeAutoReserve(false, callback, 0)
                }
            }
        )
    }
    
    private fun executeAutoReserve(useProxy: Boolean, callback: ZrokCallback, retryCount: Int = 0) {
        if (retryCount > 3) {
            logManager.error(TAG, "Reserve failed after 3 retries")
            callback.onError("Failed to reserve URL after multiple attempts")
            return
        }
        
        val cmd = buildString {
            // Use timeout to prevent hanging (30 seconds max)
            append("timeout 30 sh -c '")
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
            }
            
            // Note: `zrok reserve` doesn't support --headless, only `zrok share` does
            append("$ZROK_TMP_PATH reserve public http://localhost:8080 --unique-name $uniqueName")
            append("' 2>&1")
        }
        
        logManager.debug(TAG, "Executing auto-reserve (attempt ${retryCount + 1}): $cmd")
        callback.onLog("Reserving URL (attempt ${retryCount + 1})...")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Reserve output: $output")
                    
                    // Check for timeout
                    if (output.isEmpty() || output.contains("timeout")) {
                        logManager.warn(TAG, "Reserve command timed out, retrying...")
                        callback.onLog("⚠️ Timeout, retrying...")
                        executeAutoReserve(useProxy, callback, retryCount + 1)
                        return
                    }
                    
                    // Parse the reserved token from output
                    // Expected: [INFO] your reserved share token is 'abc-xyz-123'
                    val tokenPattern = Regex("token is '([^']+)'")
                    val match = tokenPattern.find(output)
                    
                    if (match != null) {
                        val token = match.groupValues[1]
                        logManager.info(TAG, "✅ Reserved! Token: $token")
                        callback.onLog("✅ Reserved! Permanent URL ready")
                        
                        // Save token for future use
                        saveReservedToken(token)
                        reservedShareToken = token
                        
                        // Now launch with reserved token
                        val permanentUrl = "https://$uniqueName.share.zrok.io"
                        launchZrokShareReserved(token, permanentUrl, callback)
                    } else if (output.contains("already reserved") || output.contains("exists") || output.contains("duplicate")) {
                        // Name already taken - generate new name and retry
                        logManager.warn(TAG, "Name '$uniqueName' already taken, generating new name...")
                        callback.onLog("⚠️ Name taken, trying new name...")
                        uniqueName = generateUniqueName(context)
                        saveUniqueName(uniqueName)
                        executeAutoReserve(useProxy, callback, 0) // Reset retry count for new name
                    } else if (output.contains("error") || output.contains("failed") || output.contains("ERROR")) {
                        logManager.error(TAG, "Reserve failed: $output")
                        callback.onError("Failed to reserve URL: $output")
                    } else {
                        // Unexpected output - try to extract token anyway or fail
                        logManager.warn(TAG, "Unexpected reserve output: $output")
                        callback.onError("Reserve failed: $output")
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Reserve command failed: $error")
                    // Check if it's a timeout or network issue - retry
                    if (error.contains("timeout") || error.contains("connection")) {
                        callback.onLog("⚠️ Connection issue, retrying...")
                        executeAutoReserve(useProxy, callback, retryCount + 1)
                    } else {
                        callback.onError("Reserve failed: $error")
                    }
                }
            }
        )
    }
    
    /**
     * Enable zrok environment with token.
     * Uses same proxy detection as cloudflared.
     */
    private fun enableZrokEnvironment(callback: ZrokCallback) {
        callback.onLog("Enabling zrok environment...")
        
        // Check if sing-box proxy is running (same as cloudflared)
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    if (useProxy) {
                        logManager.info(TAG, "Sing-box detected, using socks5 proxy")
                    }
                    enableZrokWithConfig(callback, useProxy)
                }
                
                override fun onError(error: String) {
                    logManager.info(TAG, "Sing-box not running, enabling zrok without proxy")
                    enableZrokWithConfig(callback, false)
                }
            }
        )
    }
    
    private fun enableZrokWithConfig(callback: ZrokCallback, useProxy: Boolean) {
        // First ensure we have a token
        if (zrokToken.isEmpty()) {
            logManager.error(TAG, "No enable token configured!")
            callback.onError("❌ No Zrok token configured. Please set your token in Daemons settings.")
            return
        }
        
        val cmd = buildString {
            append("HOME=$ZROK_HOME ")
            
            if (useProxy) {
                // Zrok uses socks5 proxy (different from cloudflared's http proxy)
                val proxyUrl = "socks5://$PROXY_HOST:$PROXY_PORT"
                append("ALL_PROXY=$proxyUrl ")
                append("HTTP_PROXY=$proxyUrl ")
                append("HTTPS_PROXY=$proxyUrl ")
                append("NO_PROXY=localhost,127.0.0.1 ")
                callback.onLog("Using sing-box socks5 proxy...")
            } else {
                callback.onLog("Direct connection (no proxy)...")
            }
            
            append("$ZROK_TMP_PATH enable $zrokToken --headless 2>&1")
        }
        
        logManager.debug(TAG, "Executing enable: $cmd")
        
        adbShellExecutor.execute(
            command = cmd,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok enable output: $output")
                    
                    // Check for errors
                    val lowerOutput = output.lowercase()
                    when {
                        lowerOutput.contains("already enabled") || 
                        lowerOutput.contains("environment already") -> {
                            callback.onLog("✅ Zrok already enabled")
                            // After enable, check for reserve
                            checkReserveAndLaunch(callback)
                        }
                        lowerOutput.contains("error") || lowerOutput.contains("failed") -> {
                            if (lowerOutput.contains("limit") || lowerOutput.contains("maximum")) {
                                callback.onError("❌ Device limit reached! You've used all 5 registrations.")
                            } else {
                                callback.onError("Failed to enable zrok: $output")
                            }
                        }
                        else -> {
                            callback.onLog("✅ Zrok environment enabled")
                            // Small delay before checking reserve. Schedule
                            // the continuation on reconcileScheduler — calling
                            // Thread.sleep(500) here parks the ADB executor.
                            reconcileScheduler.schedule({
                                checkReserveAndLaunch(callback)
                            }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                    }
                }
                
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to enable zrok: $error")
                    callback.onError("Failed to enable zrok: $error")
                }
            }
        )
    }
    
    /**
     * Launch zrok share public.
     * This is SAFE to run unlimited times - it doesn't count against device limit.
     */
    private fun launchZrokShare(callback: ZrokCallback) {
        callback.onLog("Starting zrok share (unlimited restarts OK)...")
        
        // Check if sing-box proxy is running (same as cloudflared)
        adbShellExecutor.execute(
            command = "pgrep -f sing-box",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val useProxy = output.trim().isNotEmpty()
                    launchZrokShareWithConfig(callback, useProxy)
                }
                
                override fun onError(error: String) {
                    logManager.info(TAG, "Sing-box not running, launching zrok without proxy")
                    launchZrokShareWithConfig(callback, false)
                }
            }
        )
    }
    
    private fun launchZrokShareWithConfig(callback: ZrokCallback, useProxy: Boolean) {
        // Same pre-launch sweep as the reserved-mode path, via script-tmpfile
        // form to avoid pkill self-match.
        val cleanupScript =
            "rm -f $ZROK_DISABLED_SENTINEL $ZROK_WATCHDOG_SCRIPT $ZROK_LOG 2>/dev/null\n" +
            com.overdrive.app.launcher.DaemonLauncher.psAwkKillLine("zrok") +
            "echo done\n"
        adbShellExecutor.executeScript(
            scriptBody = cleanupScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    writeAndLaunchWatchdog(reserved = false, shareToken = "",
                            permanentUrl = "", useProxy = useProxy, callback = callback)
                }
                override fun onError(error: String) {
                    writeAndLaunchWatchdog(reserved = false, shareToken = "",
                            permanentUrl = "", useProxy = useProxy, callback = callback)
                }
            }
        )
    }


    private fun waitForTunnelUrl(callback: ZrokCallback, attempt: Int) {
        if (attempt > 30) {
            // Timeout - get final log
            adbShellExecutor.execute(
                command = "cat $ZROK_LOG 2>/dev/null",
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        logManager.error(TAG, "Zrok timed out. Log: ${output.takeLast(500)}")
                        callback.onError("Failed to get URL. Log tail:\n${output.takeLast(500)}")
                    }

                    override fun onError(error: String) {
                        callback.onError("Timed out waiting for tunnel URL")
                    }
                }
            )
            return
        }

        // Schedule the next poll on reconcileScheduler instead of doing
        // `Thread.sleep(1000)` here. This function is invoked from inside
        // an adbShellExecutor callback, so a sleep parks the SHARED ADB
        // worker thread for 1s — every queued shell command (UI Stop/Start
        // taps, the 30s health check) sits behind. Across 30 attempts
        // that's up to 30s of executor blackout while a single zrok
        // handshake completes. reconcileScheduler is a separate single
        // thread dedicated to delayed work, so the ADB executor stays
        // free to service other calls during the wait.
        reconcileScheduler.schedule({
            doWaitForTunnelUrlPoll(callback, attempt)
        }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun doWaitForTunnelUrlPoll(callback: ZrokCallback, attempt: Int) {
        adbShellExecutor.execute(
            command = "cat $ZROK_LOG 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(logContent: String) {
                    // Zrok URL pattern: https://xxx.share.zrok.io
                    val zrokUrlPattern = Regex("https://([a-z0-9]+)\\.share\\.zrok\\.io")
                    val match = zrokUrlPattern.find(logContent)
                    
                    if (match != null) {
                        val tunnelUrl = match.value
                        logManager.info(TAG, "Tunnel established: $tunnelUrl")
                        callback.onLog("Tunnel established: $tunnelUrl")
                        callback.onTunnelUrl(tunnelUrl)
                        return
                    }
                    
                    // Check for proxy errors
                    if (logContent.contains("proxyconnect") ||
                        (logContent.contains("proxy") && logContent.contains("refused"))) {
                        logManager.error(TAG, "Proxy error - is sing-box running?")
                        callback.onError("Proxy Error: Is sing-box running on port $PROXY_PORT?\n${logContent.takeLast(200)}")
                        return
                    }
                    
                    // Check for connection errors
                    if (logContent.contains("connection refused") || logContent.contains("dial tcp")) {
                        logManager.error(TAG, "Connection error: $logContent")
                        callback.onError("Zrok connection error: ${logContent.takeLast(300)}")
                        return
                    }
                    
                    // Check for token/identity errors
                    val lowerContent = logContent.lowercase()
                    if (lowerContent.contains("invalid") && lowerContent.contains("token")) {
                        logManager.error(TAG, "Invalid token: $logContent")
                        callback.onError("Invalid zrok token. Please check your token.")
                        return
                    }
                    
                    // Check for identity not found (need to re-enable)
                    if (lowerContent.contains("identity") && lowerContent.contains("not found")) {
                        logManager.error(TAG, "Identity not found - need to re-enable")
                        callback.onError("Identity not found. Device may need re-registration.")
                        return
                    }
                    
                    callback.onLog("Waiting... ($attempt/30)")
                    waitForTunnelUrl(callback, attempt + 1)
                }
                
                override fun onError(error: String) {
                    callback.onLog("Waiting... ($attempt/30)")
                    waitForTunnelUrl(callback, attempt + 1)
                }
            }
        )
    }
    
    /**
     * Stop the zrok tunnel.
     * Safe to call - doesn't affect device registration.
     */
    fun stopTunnel(callback: ZrokCallback) {
        logManager.info(TAG, "Stopping zrok tunnel...")
        callback.onLog("Stopping tunnel...")

        // Use the script-via-tmp-file form so toybox `pkill -f 'zrok'` can't
        // self-match the calling shell's argv (which contains "zrok"). The
        // running shell's argv with executeScript is `sh <tmpPath>`, so
        // pkill cannot kill the shell mid-stream and EVERY command runs
        // including the trailing killall.
        val killScript =
            "echo \"disabled by ui at \$(date)\" > $ZROK_DISABLED_SENTINEL\n" +
            "chmod 666 $ZROK_DISABLED_SENTINEL 2>/dev/null\n" +
            "rm -f $ZROK_WATCHDOG_SCRIPT $ZROK_LOG 2>/dev/null\n" +
            com.overdrive.app.launcher.DaemonLauncher.psAwkKillLine("zrok") +
            "killall -9 zrok 2>/dev/null\n" +
            "echo stopped\n"
        adbShellExecutor.executeScript(
            scriptBody = killScript,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok tunnel stopped")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }

                override fun onError(error: String) {
                    // Even on error, consider it stopped
                    logManager.info(TAG, "Zrok tunnel stopped (with warning: $error)")
                    callback.onLog("Tunnel stopped")
                    callback.onTunnelUrl("")
                }
            }
        )
    }
    
    /**
     * Check if zrok tunnel is running.
     */
    fun isTunnelRunning(callback: (Boolean) -> Unit) {
        // Check for actual zrok share process specifically
        // Look for process with 'zrok share' in command line to avoid false positives
        adbShellExecutor.execute(
            command = "ps -A -o ARGS 2>/dev/null | grep 'zrok share' | grep -v grep | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val isRunning = output.trim().isNotEmpty() && output.contains("zrok share")
                    logManager.debug(TAG, "isTunnelRunning check: $isRunning (output: '${output.trim().take(50)}')")
                    callback(isRunning)
                }

                override fun onError(error: String) {
                    // grep returns exit code 1 when no match - that's expected when not running
                    logManager.debug(TAG, "isTunnelRunning: not running (grep found nothing)")
                    callback(false)
                }
            }
        )
    }

    /**
     * Edge-session-stale detection. The original 8–9 hour 502 bug: the
     * `zrok share` process is alive (so isTunnelRunning returns true) but
     * the underlay session at the zrok edge has gone stale, so external
     * requests to the public URL return HTTP 502. The watchdog never
     * triggers because zrok never exits; the in-process health-check
     * passes because pgrep finds the alive process. Result: tunnel is
     * dead at the user-visible level forever.
     *
     * This method does an HTTP probe against the public URL (or any
     * `https://<name>.share.zrok.io` we can find in the log) and returns:
     *   - HEALTHY    : process alive AND probe got a non-502/503/504 response
     *   - PROCESS_DEAD: zrok share process not running
     *   - EDGE_STALE : process alive but probe got 502/503/504, or curl
     *                  errored out repeatedly (consecutive-failure counter)
     *
     * Stickiness: a single 502 is not enough — the zrok edge can blip
     * during a transient network event. We keep a per-instance counter
     * of consecutive failed probes; treat as EDGE_STALE only after 2
     * consecutive failures, matching the recommendation in the audit.
     */
    enum class TunnelHealth { HEALTHY, PROCESS_DEAD, EDGE_STALE }

    private var consecutiveProbeFailures: Int = 0

    fun checkTunnelHealth(callback: (TunnelHealth) -> Unit) {
        isTunnelRunning { processAlive ->
            if (!processAlive) {
                consecutiveProbeFailures = 0
                callback(TunnelHealth.PROCESS_DEAD)
                return@isTunnelRunning
            }
            // Re-read the unique_name file before each probe. The
            // `uniqueName` companion-object var defaults to "overdrive"
            // (a name we don't own); a freshly-revived MainActivity that
            // hits this tick before any launchZrok flow has populated
            // `uniqueName` would otherwise probe the wrong host. The
            // unique_name file is the cross-UID source of truth.
            //
            // If the file is absent AND the in-memory name is still the
            // default UNIQUE_NAME_PREFIX placeholder, we have no real URL
            // to probe — report HEALTHY (no-op) rather than send a probe
            // to a hostname we don't own. The in-shell watchdog also
            // skips probing when unique_name is empty, so the layered
            // behavior is consistent.
            loadSavedUniqueName { savedName ->
                if (savedName != null && savedName != uniqueName) {
                    logManager.debug(TAG, "checkTunnelHealth: refreshing in-memory uniqueName ${uniqueName} -> $savedName")
                    uniqueName = savedName
                }
                if (savedName == null && uniqueName == UNIQUE_NAME_PREFIX) {
                    logManager.debug(TAG, "checkTunnelHealth: no reserved unique_name yet, skipping probe (HEALTHY no-op)")
                    callback(TunnelHealth.HEALTHY)
                    return@loadSavedUniqueName
                }
                doHealthProbe(callback)
            }
        }
    }

    private fun doHealthProbe(callback: (TunnelHealth) -> Unit) {
            // Process alive — probe the public URL. Two attempts: first
            // try the cached `uniqueName`-based URL (cheap, no log read).
            // If that's empty, fall back to the URL grep used by
            // getTunnelUrl. Probe with curl from the DEVICE so we test
            // the same network path the user does.
            val probeUrl = "https://${uniqueName}.share.zrok.io"
            // `curl -s -o /dev/null -w '%{http_code}' --max-time 5 <url>`
            // emits just the HTTP status code. Codes that mean "tunnel
            // reachable, backend may or may not respond":
            //   200/2xx (HttpServer responded), 401 (auth required),
            //   404 (path not found), other 4xx — all healthy from the
            //   tunnel-perspective.
            // Codes that mean "tunnel dead at zrok edge":
            //   502 (Bad Gateway — edge has no underlay session)
            //   503 (Service Unavailable)
            //   504 (Gateway Timeout)
            // curl exit non-zero (DNS / connect / SSL fail) is also
            // counted as a probe failure — could be transient network,
            // so the consecutive-failure counter handles it.
            //
            // Sing-box-aware routing: if sing-box is running on
            // 127.0.0.1:8119 (the same socks5 proxy the zrok-share
            // process is using on this device), route the probe through
            // it. Otherwise we'd test direct egress to the public
            // internet, which on sing-box-only-egress deployments
            // (some BYD setups) returns CURL_FAIL → 2-strike →
            // false-positive EDGE_STALE → relaunch loop every minute.
            // The probe should mirror the same network path the
            // tunnel itself uses.
            //
            // Inline `pgrep -f sing-box` adds the --socks5h flag only
            // when present; one shell call instead of two ADB round
            // trips. Direct egress is the fallback when no sing-box.
            val probeCmd =
                "if pgrep -f 'sing-box' >/dev/null 2>&1; then " +
                "  curl -s -o /dev/null -w '%{http_code}' --max-time 5 " +
                "    --socks5-hostname 127.0.0.1:$PROXY_PORT '$probeUrl' 2>/dev/null || echo CURL_FAIL; " +
                "else " +
                "  curl -s -o /dev/null -w '%{http_code}' --max-time 5 '$probeUrl' 2>/dev/null || echo CURL_FAIL; " +
                "fi"
            adbShellExecutor.execute(
                command = probeCmd,
                callback = object : AdbShellExecutor.ShellCallback {
                    override fun onSuccess(output: String) {
                        val status = output.trim()
                        // Three buckets, mirroring the in-shell probe:
                        //   - STALE: 502/503/504 from the zrok edge → strike
                        //   - INCONCLUSIVE: CURL_FAIL / empty / 000
                        //     (transport error — could be transient network
                        //     loss; killing+respawning zrok wouldn't fix it).
                        //     Don't strike, don't reset.
                        //   - HEALTHY: any other code (2xx/3xx/4xx) — tunnel
                        //     reachable through the edge → reset counter.
                        val isStale = status == "502" || status == "503" || status == "504"
                        val isInconclusive = status == "CURL_FAIL" || status.isEmpty() || status == "000"
                        if (isStale) {
                            consecutiveProbeFailures += 1
                            logManager.warn(
                                TAG,
                                "Zrok edge probe failed (status='$status', consecutive=$consecutiveProbeFailures): $probeUrl"
                            )
                            if (consecutiveProbeFailures >= 2) {
                                logManager.warn(
                                    TAG,
                                    "Zrok edge stale confirmed after $consecutiveProbeFailures consecutive probe failures — relaunch needed"
                                )
                                consecutiveProbeFailures = 0
                                callback(TunnelHealth.EDGE_STALE)
                            } else {
                                callback(TunnelHealth.HEALTHY)
                            }
                        } else if (isInconclusive) {
                            // Transport error — preserve any prior strike
                            // evidence across an offline blip but don't
                            // escalate. Logged at debug to avoid spam.
                            logManager.debug(
                                TAG,
                                "Zrok edge probe inconclusive (status='$status', preserved consecutive=$consecutiveProbeFailures): $probeUrl"
                            )
                            callback(TunnelHealth.HEALTHY)
                        } else {
                            // 2xx / 3xx / 4xx — tunnel is healthy from the edge's perspective.
                            if (consecutiveProbeFailures > 0) {
                                logManager.info(TAG, "Zrok edge probe recovered (status=$status), resetting failure counter")
                            }
                            consecutiveProbeFailures = 0
                            callback(TunnelHealth.HEALTHY)
                        }
                    }
                    override fun onError(error: String) {
                        // ADB-side failure (NOT a 5xx from the zrok edge)
                        // — usually executor timeout or shell write error.
                        // Treat as inconclusive: preserve prior strike
                        // counter but don't escalate. Killing+respawning
                        // zrok over an ADB executor hiccup would just
                        // thrash.
                        logManager.warn(TAG, "Zrok edge probe ADB error (inconclusive, preserved consecutive=$consecutiveProbeFailures): $error")
                        callback(TunnelHealth.HEALTHY)
                    }
                }
            )
    }

    /**
     * Get current tunnel URL from log file.
     * SOTA FIX: Only read last 50 lines to avoid loading entire log into memory.
     * The URL appears early in the log, but we use tail to limit memory usage.
     */
    fun getTunnelUrl(callback: (String?) -> Unit) {
        // Use grep to find URL directly instead of loading entire log
        // This eliminates the 85MB+ allocations from reading large log files
        adbShellExecutor.execute(
            command = "grep -o 'https://[a-z0-9]*\\.share\\.zrok\\.io' $ZROK_LOG 2>/dev/null | head -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val url = output.trim()
                    if (url.isNotEmpty() && url.startsWith("https://")) {
                        logManager.info(TAG, "Found tunnel URL: $url")
                        callback(url)
                    } else {
                        logManager.debug(TAG, "No tunnel URL found in log")
                        callback(null)
                    }
                }
                
                override fun onError(error: String) {
                    logManager.warn(TAG, "Zrok log not found - tunnel may need restart")
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Check if device is already enabled (has identity file).
     */
    fun isDeviceEnabled(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -f $ZROK_IDENTITY_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }
                
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Disable zrok environment (cleanup).
     * WARNING: This will require re-enabling which uses one of your 5 device slots!
     */
    fun disableEnvironment(callback: ZrokCallback? = null) {
        logManager.warn(TAG, "⚠️ Disabling zrok environment - will need to re-register!")
        callback?.onLog("⚠️ Disabling environment (will need re-registration)...")
        
        adbShellExecutor.execute(
            command = "HOME=$ZROK_HOME $ZROK_TMP_PATH disable 2>&1; rm -rf $ZROK_HOME/.zrok 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok environment disabled")
                    callback?.onLog("Environment disabled")
                    callback?.onTunnelUrl("")
                }
                
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to disable zrok: $error")
                    callback?.onError("Failed to disable: $error")
                }
            }
        )
    }
    
    // ==================== Enable Token Management ====================
    
    /**
     * Save enable token to unified storage (cross-UID accessible).
     * Stores in /data/local/tmp/.zrok/enable_token for daemon access.
     */
    fun saveEnableToken(token: String, callback: ((Boolean) -> Unit)? = null) {
        val trimmedToken = token.trim()
        if (trimmedToken.isEmpty()) {
            callback?.invoke(false)
            return
        }
        
        // Update in-memory token
        zrokToken = trimmedToken
        tokenLoaded = true
        
        adbShellExecutor.execute(
            command = "mkdir -p /data/local/tmp/.zrok && echo '$trimmedToken' > $ZROK_ENABLE_TOKEN_FILE && chmod 666 $ZROK_ENABLE_TOKEN_FILE",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Enable token saved to unified storage")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to save enable token: $error")
                    callback?.invoke(false)
                }
            }
        )
    }
    
    /**
     * Load enable token from unified storage.
     * Returns the token via callback, or null if not found.
     */
    fun loadEnableToken(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat $ZROK_ENABLE_TOKEN_FILE 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    val token = output.trim()
                    if (token.isNotEmpty() && !token.contains("No such file")) {
                        zrokToken = token
                        tokenLoaded = true
                        logManager.info(TAG, "Enable token loaded from unified storage")
                        callback(token)
                    } else {
                        callback(null)
                    }
                }
                override fun onError(error: String) {
                    callback(null)
                }
            }
        )
    }
    
    /**
     * Delete enable token from unified storage AND wipe everything else
     * derived from the previous zrok account.
     *
     * The reserved_token and unique_name belong to the account whose enable
     * token we just deleted — leaving them on disk causes `share reserved`
     * to fail or, worse, causes the next start to auto-reserve a new name
     * under the new account and silently rotate the public URL. That breaks
     * every PWA install on every phone (push subscriptions are origin-bound).
     *
     * environment.json is the account identity; carrying it forward into a
     * different account is incoherent. Kill the entire ~/.zrok directory.
     */
    fun deleteEnableToken(callback: ((Boolean) -> Unit)? = null) {
        zrokToken = ""
        tokenLoaded = false
        reservedShareToken = null
        uniqueName = UNIQUE_NAME_PREFIX

        adbShellExecutor.execute(
            command = "rm -rf $ZROK_HOME/.zrok 2>/dev/null; echo done",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "Zrok state wiped (token, identity, reserved share, unique name)")
                    callback?.invoke(true)
                }
                override fun onError(error: String) {
                    logManager.warn(TAG, "Failed to wipe zrok state: $error")
                    callback?.invoke(false)
                }
            }
        )
    }
    
    /**
     * Check if enable token exists in unified storage.
     */
    fun hasEnableToken(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -f $ZROK_ENABLE_TOKEN_FILE && test -s $ZROK_ENABLE_TOKEN_FILE && echo yes || echo no",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(output.trim() == "yes")
                }
                override fun onError(error: String) {
                    callback(false)
                }
            }
        )
    }
    
    /**
     * Ensure enable token is loaded before operations.
     * Call this before launchZrok() to ensure token is available.
     */
    fun ensureTokenLoaded(callback: (Boolean) -> Unit) {
        if (tokenLoaded && zrokToken.isNotEmpty()) {
            callback(true)
            return
        }
        
        loadEnableToken { token ->
            callback(token != null)
        }
    }
}
