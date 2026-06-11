package com.overdrive.app.roadsense.sidecar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.overdrive.app.R
import com.overdrive.app.roadsense.detect.ImuAccelSample
import com.overdrive.app.roadsense.detect.ImuFrameCodec
import com.overdrive.app.roadsense.detect.ImuGyroSample
import com.overdrive.app.roadsense.detect.ImuSource
import com.overdrive.app.services.DaemonKeepaliveService
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The ONLY app-process component of RoadSense (D-023).
 *
 * It acquires the real `-iner` accelerometer + gyroscope (the one thing that
 * cannot be read from the daemon's existing singletons — no component streams
 * 100 Hz inertial data), batches samples, and ships them to the CameraDaemon's
 * IPC server (port 19877) as `IMU_BATCH` frames. ALL detection/storage/sync logic
 * runs daemon-side; this service is a dumb pump.
 *
 * Mirrors `LocationSidecarService`: a foreground service, one socket per outbound
 * message (the daemon's IPC server is one-line-per-connection), background thread
 * for all sensor + network work. The difference is RATE — 100 Hz × 2 sensors — so
 * we **batch** (~[ImuFrameCodec.TARGET_BATCH_MS] per frame) instead of one write
 * per sample (D-023: per-sample sockets would dominate CPU and starve the camera).
 *
 * Resource scaling (D-021) is driven by the daemon via start/stop of THIS service
 * and the [EXTRA_RATE] hint: DRIVING → FAST (~100 Hz), RELAXED → SLOW, ACC OFF →
 * the daemon stops the service entirely (zero cost). The service itself just
 * honors the rate it's started with; the daemon owns the policy (VehicleStateGate).
 */
class RoadSenseImuSidecarService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accel: Sensor? = null
    private var gyro: Sensor? = null
    private var ioThread: HandlerThread? = null
    private var ioHandler: android.os.Handler? = null
    // Rate we're currently registered at (null = not registered). Used to skip a
    // redundant same-rate re-register (audit). Read/written on ioHandler's thread
    // (registerSensors) but also cleared from onDestroy on the main thread, so
    // @Volatile gives the cross-thread visibility that makes the clear reliable.
    @Volatile private var registeredRate: ImuRate? = null

    // Batch accumulators — only touched on ioHandler's thread (sensor callbacks
    // are delivered there), so no locking needed.
    private val accelBatch = ArrayList<ImuAccelSample>(16)
    private val gyroBatch = ArrayList<ImuGyroSample>(16)
    private var batchStartMs = 0L
    @Volatile private var currentRate = ImuRate.FAST

    enum class ImuRate { FAST, SLOW }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        val t = HandlerThread("roadsense-imu", Process.THREAD_PRIORITY_DEFAULT)
        t.start()
        ioThread = t
        ioHandler = android.os.Handler(t.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rateName = intent?.getStringExtra(EXTRA_RATE)
        currentRate = if (rateName == ImuRate.SLOW.name) ImuRate.SLOW else ImuRate.FAST
        ioHandler?.post { registerSensors(currentRate) }
        // Not sticky: the daemon owns our lifecycle (D-021). If the OS kills us,
        // the daemon's controller re-starts us on the next DRIVING evaluation.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sensorManager?.unregisterListener(this) } catch (_: Throwable) {}
        registeredRate = null
        ioThread?.quitSafely()
        ioThread = null
        ioHandler = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Sensor acquisition ────────────────────────────────────────────────────

    private fun registerSensors(rate: ImuRate) {
        // Idempotence (audit): a redundant start at the SAME rate (e.g. the daemon
        // re-issuing the same regime, or any duplicate `am`) would otherwise force an
        // unregister + re-register cycle that briefly drops the listener for no reason.
        // Skip when we're already registered at this exact rate.
        if (rate == registeredRate && sensorManager != null) {
            Log.d(TAG, "registerSensors: already at rate=$rate, no-op")
            return
        }
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: run {
            Log.e(TAG, "no SensorManager")
            return
        }
        sensorManager = sm
        // Re-register cleanly if rate changed mid-run.
        sm.unregisterListener(this)
        registeredRate = null

        val resolved = ImuSource.resolve(sm)
        if (!resolved.usableForDetection) {
            // R-EXT-6 graceful fallback: no real -iner accel on this trim. There's
            // nothing to stream and no point holding a foreground service + its
            // notification, so self-stop. The daemon's regime poll won't usefully
            // re-start us (still no sensor), so this cleanly disables the feature
            // on unsupported hardware rather than leaving a zombie FGS up.
            Log.w(TAG, "no usable -iner accelerometer on this trim; stopping IMU sidecar")
            stopSelf()
            return
        }
        accel = resolved.accelerometer
        gyro = resolved.gyroscope

        val delay = when (rate) {
            ImuRate.FAST -> SensorManager.SENSOR_DELAY_FASTEST   // ~100 Hz (F-005)
            ImuRate.SLOW -> SensorManager.SENSOR_DELAY_NORMAL    // relaxed, ~5 Hz
        }
        accel?.let { sm.registerListener(this, it, delay, ioHandler) }
        gyro?.let { sm.registerListener(this, it, delay, ioHandler) }
        registeredRate = rate
        Log.i(TAG, "IMU sidecar registered: accel=${accel?.name} gyro=${gyro?.name} rate=$rate")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val tMs = wallClockFromElapsed(event.timestamp)
        if (batchStartMs == 0L) batchStartMs = tMs
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                accelBatch.add(ImuAccelSample(tMs, event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_GYROSCOPE ->
                gyroBatch.add(ImuGyroSample(tMs, event.values[0], event.values[1], event.values[2]))
        }
        // Flush when the batch spans the target window OR a defensive cap is hit
        // (so a stall-then-burst can't build an unbounded list).
        val span = tMs - batchStartMs
        val overCap = accelBatch.size >= ImuFrameCodec.MAX_SAMPLES_PER_FRAME ||
            gyroBatch.size >= ImuFrameCodec.MAX_SAMPLES_PER_FRAME
        if (span >= ImuFrameCodec.TARGET_BATCH_MS || overCap) flush()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* ignore */ }

    /** Encode the current batch and ship it; runs on ioHandler's thread. */
    private fun flush() {
        if (accelBatch.isEmpty() && gyroBatch.isEmpty()) return
        val line = ImuFrameCodec.encode(accelBatch, gyroBatch)
        accelBatch.clear()
        gyroBatch.clear()
        batchStartMs = 0L
        sendLine(line)
    }

    /** One socket per batch, fire-and-forget — mirrors LocationSidecarService. */
    private fun sendLine(line: String) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", DAEMON_IPC_PORT), 1000)
            socket.soTimeout = 1000
            val out: OutputStream = socket.getOutputStream()
            out.write(line.toByteArray())
            out.flush()
            // Daemon replies with a JSON ack; we don't need it. Closing without
            // reading is fine for localhost.
        } catch (_: java.net.ConnectException) {
            // Daemon not up yet / restarting — expected transient, drop the batch.
        } catch (_: Throwable) {
            // Never let IPC trouble crash the sensor pump.
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    private fun wallClockFromElapsed(elapsedNs: Long): Long {
        val nowWall = System.currentTimeMillis()
        val nowEr = SystemClock.elapsedRealtimeNanos()
        return nowWall - (nowEr - elapsedNs) / 1_000_000L
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "RoadSense IMU", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("RoadSense")
            .setContentText("Road sensing active")
            .setSmallIcon(R.drawable.ic_diagnostics)
            .setOngoing(true)
            .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build()
    }

    companion object {
        private const val TAG = "RoadSense/ImuSidecar"
        private const val CHANNEL_ID = "roadsense_imu"
        private const val NOTIFICATION_ID = 9987
        private const val DAEMON_IPC_PORT = 19877

        /** Intent extra carrying the desired [ImuRate] name (D-021 rate scaling). */
        const val EXTRA_RATE = "rate"

        /** Fully-qualified component for the daemon's `am` launch (see [start]). */
        private const val COMPONENT =
            "com.overdrive.app/com.overdrive.app.roadsense.sidecar.RoadSenseImuSidecarService"

        /**
         * Start the sidecar at [rate]. Called from the CameraDaemon (app_process,
         * shell uid) whose synthetic Context CANNOT launch an app-process Service via
         * startForegroundService() — that is a silent cross-process no-op, the bug
         * that left RoadSense inert (no IMU → calibration stuck at 0 → "Calibrating"
         * forever, empty hazard store). So we use the SAME proven mechanism the daemon
         * already uses for LocationSidecarService (see GpsMonitor.START_CMD): a shell
         * `am start-foreground-service -n <component>` exec. The service is
         * `exported="true"` (like LocationSidecarService) so `am` from the shell-uid
         * daemon can reach it; it then runs in the real app process. The [rate] rides
         * as a string extra.
         */
        fun start(rate: ImuRate) {
            exec("am start-foreground-service -n $COMPONENT --es $EXTRA_RATE ${rate.name}")
        }

        fun stop() {
            exec("am stopservice -n $COMPONENT")
        }

        /**
         * Run an `am` command via Runtime.exec — fire-and-forget, exactly like
         * GpsMonitor's daemon→sidecar launch (no waitFor). This runs on the daemon's
         * ~2 Hz regime/warning tick thread, so we must NOT block it: `am` returns in
         * ~100 ms normally, but a hung `am` must never stall the tick (which also
         * drives approach warnings). The OS reaps the short-lived child; a failed
         * launch is harmless and self-corrects on the next regime transition.
         */
        private fun exec(cmd: String) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "exec failed [$cmd]: ${t.message}")
            }
        }
    }
}
