package com.overdrive.app.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

/**
 * Location Sidecar Service - Sends GPS coordinates to daemon via IPC.
 * 
 * This foreground service has proper permissions to access LocationManager.
 * It sends GPS data via TCP to port 19877 (SurveillanceIpcServer).
 * Sends periodically (every 2s) so daemon gets data even after restart.
 * 
 * Start via: am start-foreground-service -n com.overdrive.app/.services.LocationSidecarService
 */
public class LocationSidecarService extends Service implements LocationListener {

    private static final String TAG = "LocationSidecar";
    private static final String CHANNEL_ID = "location_sidecar";
    private static final int NOTIFICATION_ID = 9999;
    
    private LocationManager locationManager;
    private android.os.Handler handler;
    // Dedicated background looper for the GPS callback + periodic sender + file
    // write. Previously the handler was built on the MAIN looper and the 4-arg
    // requestLocationUpdates delivered onLocationChanged on main, so the per-fix
    // Log.d + synchronous saveToLocalCache() (JSON build + FileWriter + renameTo)
    // ran on the UI thread at ~1-2Hz — measurable jank contribution on the
    // foreground head unit. Moving the looper off-main keeps the UI thread free.
    private android.os.HandlerThread workerThread;
    private Runnable periodicSender;
    // Cross-thread now (GPS-IPC sender thread reads these) → volatile.
    private volatile double latitude = 0.0;
    private volatile double longitude = 0.0;
    private volatile float speed = 0.0f;
    private volatile float heading = 0.0f;
    private volatile float accuracy = 0.0f;
    private volatile double altitude = 0.0;
    private boolean permissionGranted = false;

    // SOTA: Throttling fields to prevent IPC/Disk spam.
    // Holds the last location that was actually SENT to the daemon or SAVED to disk.
    private Location lastProcessedLocation = null;
    private long lastProcessedTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");

        // Background looper for GPS callbacks + sender + file writes (off the UI
        // thread). Started before anything posts to `handler`.
        workerThread = new android.os.HandlerThread("location-sidecar");
        workerThread.start();
        handler = new android.os.Handler(workerThread.getLooper());

        // Create notification channel FIRST
        createNotificationChannel();
        
        // FIX #1: Load previous location immediately from disk cache.
        // Even if GPS is currently off/dead, we report where the car was last seen.
        // This prevents the "0,0 silence trap" when service restarts.
        loadFromLocalCache();
        
        // Check location permission BEFORE starting foreground with location type
        // Android 14+ (SDK 34+) requires runtime permission to be granted before
        // starting a foreground service with FOREGROUND_SERVICE_TYPE_LOCATION
        boolean hasPermission = hasLocationPermission();
        
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ - must have permission before using location type
            if (hasPermission) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                // Start with dataSync type (declared in manifest), will upgrade when permission granted
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-13
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        if (!hasPermission) {
            Log.e(TAG, "Location permission not granted - service will wait for permission");
            permissionGranted = false;
            
            // Start a retry loop to check for permissions (on the worker looper).
            if (handler == null) handler = new android.os.Handler(workerThread.getLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (hasLocationPermission()) {
                        Log.i(TAG, "Location permission now granted, starting updates");
                        permissionGranted = true;
                        
                        // Upgrade to location foreground service type now that we have permission
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            try {
                                stopForeground(STOP_FOREGROUND_DETACH);
                                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                            } catch (Exception e) {
                                Log.w(TAG, "Could not upgrade to location FGS type: " + e.getMessage());
                            }
                        }
                        
                        startLocationUpdates();
                        startPeriodicSender();
                    } else {
                        Log.d(TAG, "Still waiting for location permission...");
                        handler.postDelayed(this, 5000); // Check every 5 seconds
                    }
                }
            }, 5000);
            return;
        }
        
        permissionGranted = true;
        
        // Start location updates
        startLocationUpdates();
        
        // Start periodic sender
        startPeriodicSender();
    }
    
    private void startPeriodicSender() {
        if (handler == null) {
            handler = new android.os.Handler(workerThread.getLooper());
        }
        
        // FIX #3: Always send GPS data, even if 0,0.
        // Thanks to loadFromLocalCache(), we should have valid cached coordinates.
        // If it's truly 0,0 (brand new install), the daemon handles "invalid location" logic.
        // The sender should never be the gatekeeper - that's the daemon's job.
        periodicSender = new Runnable() {
            @Override
            public void run() {
                sendGpsViaTcp();
                
                // If no fresh GPS fix in 30 seconds, request one explicitly
                // This handles cases where the provider stopped sending updates
                if (permissionGranted && locationManager != null) {
                    try {
                        Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        if (lastGps != null) {
                            long fixAge = System.currentTimeMillis() - lastGps.getTime();
                            if (fixAge < 10000) {
                                // Fresh fix available that we might have missed
                                onLocationChanged(lastGps);
                            }
                        }
                    } catch (SecurityException e) {
                        // Permission lost
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                
                // Periodic keep-alive / daemon-restart recovery. Kept at 4s
                // (down from the original 2s for CPU/IPC savings, but well
                // under RoadSense's 5s fix-staleness cutoff) so that when the
                // car is truly stationary and the provider emits no callbacks,
                // GpsMonitor.lastUpdate never ages past the consumer threshold.
                handler.postDelayed(this, 4000);
            }
        };
        handler.postDelayed(periodicSender, 5000);
    }
    
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("GPS tracking for surveillance");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Tag with the shared Overdrive group key so DaemonKeepaliveService's
        // group-summary collapses this entry under a single shade row.
        // Android still requires this FGS notification to exist and remain
        // user-visible; grouping just changes how the shade renders it.
        return builder
            .setContentTitle("Location Active")
            .setContentText("GPS tracking running")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setGroup(com.overdrive.app.services.DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
            .build();
    }

    private void startLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            
            if (locationManager == null) {
                Log.e(TAG, "LocationManager not available");
                return;
            }
            
            // Keep GPS at 1s / 0m so RoadSense back-projection still sees the
            // ~2 Hz distinct-fix stream its GpsRingBuffer is designed around
            // (GPS_POLL_MS≈500, FIX_LATENCY≈700ms). The throttling that cuts
            // IPC/disk spam happens downstream in onLocationChanged (the
            // distance/time gate), NOT at the provider — coarsening the
            // provider here would starve hazard approach detection.
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,  // 1 second
                    0.0f,  // every fix (no provider-side distance filter)
                    this,
                    workerThread.getLooper()  // deliver off the UI thread
                );
                Log.i(TAG, "GPS provider started (1s/0m)");
            }

            // Also use network provider as fallback. 5s cadence is fine; keep
            // min-distance 0 so it doesn't pre-filter fixes the gate wants.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000,  // 5 seconds
                    0.0f,  // every fix
                    this,
                    workerThread.getLooper()  // deliver off the UI thread
                );
                Log.i(TAG, "Network provider started (5s/0m)");
            }
            
            // Get last known location immediately
            Location lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            
            Log.i(TAG, "Last GPS: " + lastGps + ", Last Network: " + lastNetwork);
            
            if (lastGps != null) {
                onLocationChanged(lastGps);
            } else if (lastNetwork != null) {
                onLocationChanged(lastNetwork);
            } else {
                // Send initial update (will fail if daemon not running yet, that's OK)
                sendGpsViaTcp();
                Log.i(TAG, "No last known location, sent initial update");
            }
            
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start location updates: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        
        long now = System.currentTimeMillis();
        float distanceMoved = (lastProcessedLocation != null) ? location.distanceTo(lastProcessedLocation) : Float.MAX_VALUE;
        long timeSinceLastProcess = now - lastProcessedTime;

        // Update volatiles immediately so the periodic sender (and any other 
        // internal consumer) has access to the absolutely latest fix.
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        speed = location.hasSpeed() ? location.getSpeed() : 0.0f;
        heading = location.hasBearing() ? location.getBearing() : 0.0f;
        accuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;
        altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
        
        // Throttle Log, IPC and Disk I/O — but keep the distinct-fix rate as
        // high as the provider delivers (GPS is requested at 1s/0m) so
        // RoadSense back-projection (GpsRingBuffer ~2 Hz design, 5s max fix
        // age) and the nav puck stay fresh. We "Process" if:
        // 1. Car moved > 1.5 meters (drops sub-metre stationary GPS jitter), OR
        // 2. > 500 ms passed since the last processed fix. NOTE: the time gate
        //    is intentionally HALF the 1000 ms provider period — a >=1000 ms
        //    gate would race the provider cadence and drop every other fix
        //    (~2 s spacing); 500 ms lets every ~1 s provider fix through while
        //    still keeping GpsMonitor.lastUpdate far under the 5s staleness
        //    cutoff when stopped at a light, OR
        // 3. it's the first fix ever.
        if (lastProcessedLocation == null || distanceMoved > 1.5f || timeSinceLastProcess > 500) {
            boolean isFirstFix = (lastProcessedLocation == null);
            lastProcessedLocation = new Location(location);
            lastProcessedTime = now;

            if (isFirstFix) {
                Log.i(TAG, "First location fix: " + latitude + ", " + longitude);
            } else if (com.overdrive.app.BuildConfig.DEBUG) {
                // Individual fixes go to DEBUG; only shown if explicitly requested in logcat.
                Log.d(TAG, "Location update (moved=" + String.format("%.1f", distanceMoved) + "m): " + latitude + ", " + longitude);
            }
            
            // Send to daemon via IPC
            sendGpsViaTcp();
            
            // Also save to app's local cache (persists across reboots, readable by daemon)
            saveToLocalCache();
        }
    }
    
    /**
     * Save GPS to app's local cache file.
     * This file persists across reboots and can be read by the daemon.
     * The daemon (UID 2000) can read from /data/data/com.overdrive.app/files/ but cannot write to it.
     */
    private void saveToLocalCache() {
        if (latitude == 0.0 && longitude == 0.0) return;
        
        try {
            JSONObject json = new JSONObject();
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("speed", speed);
            json.put("heading", heading);
            json.put("accuracy", accuracy);
            json.put("altitude", altitude);
            json.put("time", System.currentTimeMillis());
            
            // Write to app's files directory
            java.io.File file = new java.io.File(getFilesDir(), "gps_cache.json");
            java.io.File tmp = new java.io.File(getFilesDir(), "gps_cache.json.tmp");
            
            try (java.io.FileWriter writer = new java.io.FileWriter(tmp)) {
                writer.write(json.toString());
            }
            
            if (!tmp.renameTo(file)) {
                // Fallback: direct write
                try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                    writer.write(json.toString());
                }
                tmp.delete();
            }
            
            // Make readable by other UIDs (daemon UID 2000)
            file.setReadable(true, false);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to save local GPS cache: " + e.getMessage());
        }
    }
    
    /**
     * Load GPS from app's local cache file on service startup.
     * Always loads cached location (better than nothing), but marks it so
     * we know to prioritize fresh GPS fixes when they arrive.
     */
    private void loadFromLocalCache() {
        try {
            java.io.File file = new java.io.File(getFilesDir(), "gps_cache.json");
            if (!file.exists()) {
                Log.i(TAG, "No GPS cache file found");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            
            JSONObject json = new JSONObject(sb.toString());
            double lat = json.optDouble("lat", 0.0);
            double lng = json.optDouble("lng", 0.0);
            
            if (lat != 0.0 || lng != 0.0) {
                this.latitude = lat;
                this.longitude = lng;
                this.speed = (float) json.optDouble("speed", 0.0);
                this.heading = (float) json.optDouble("heading", 0.0);
                this.accuracy = (float) json.optDouble("accuracy", 0.0);
                this.altitude = json.optDouble("altitude", 0.0);
                
                long cacheTime = json.optLong("time", 0);
                long ageMs = cacheTime > 0 ? System.currentTimeMillis() - cacheTime : -1;
                Log.i(TAG, "Loaded cached location (" + (ageMs > 0 ? (ageMs / 1000) + "s old" : "unknown age") + "): " + lat + ", " + lng);
                
                // Send cached location to daemon immediately — better than nothing
                sendGpsViaTcp();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load GPS cache: " + e.getMessage());
        }
    }

    // Persistent IPC socket to the daemon (SurveillanceIpcServer 19877). Only
    // touched on the workerThread looper (every send is posted there), so no
    // locking. Previously each fix spawned a NEW Thread AND a NEW Socket
    // (connect+handshake+teardown) at ~1-2Hz; now one socket is reused and the
    // send runs on the existing worker looper — zero thread spawns, zero
    // connects in steady state. The daemon server loops per-connection.
    private java.net.Socket ipcSocket = null;
    private java.io.OutputStream ipcOut = null;

    private void sendGpsViaTcp() {
        // Snapshot the volatile fields on the caller, build + write on the
        // worker looper (network off the main/GPS-callback thread).
        final JSONObject json = new JSONObject();
        try {
            json.put("command", "UPDATE_GPS");
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("speed", speed);
            json.put("heading", heading);
            json.put("accuracy", accuracy);
            json.put("altitude", altitude);
            json.put("time", System.currentTimeMillis());
        } catch (Exception e) {
            return;
        }
        final byte[] payload = (json.toString() + "\n").getBytes();
        android.os.Handler h = handler;
        if (h != null) {
            h.post(() -> writeGpsWithReconnect(payload, true));
        }
    }

    /** Write over the persistent socket, reconnecting once on failure. Worker thread only. */
    private void writeGpsWithReconnect(byte[] payload, boolean allowReconnect) {
        try {
            if (ipcSocket == null) {
                if (!allowReconnect || !openIpcSocket()) return;
            }
            ipcOut.write(payload);
            ipcOut.flush();
            // Fire-and-forget: we don't block reading the ack (the old code read
            // one line purely to log a warning; the daemon applies the update
            // regardless). Skipping the read removes a per-fix round-trip wait.
        } catch (java.net.ConnectException e) {
            closeIpcSocket();  // daemon not up — expected on startup, drop fix
        } catch (Exception e) {
            closeIpcSocket();
            if (allowReconnect) writeGpsWithReconnect(payload, false);
        }
    }

    private boolean openIpcSocket() {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 19877), 1000);
            s.setSoTimeout(1000);
            s.setTcpNoDelay(true);
            ipcSocket = s;
            ipcOut = s.getOutputStream();
            return true;
        } catch (Exception e) {
            closeIpcSocket();
            return false;
        }
    }

    private void closeIpcSocket() {
        try { if (ipcSocket != null) ipcSocket.close(); } catch (Exception ignored) {}
        ipcSocket = null;
        ipcOut = null;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "Provider " + provider + " status: " + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "Provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.w(TAG, "Provider disabled: " + provider);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        if (handler != null && periodicSender != null) {
            handler.removeCallbacks(periodicSender);
        }
        // Close the persistent IPC socket on the worker thread that owns it,
        // before quitSafely() lets the looper drain and stop.
        if (handler != null) {
            handler.post(this::closeIpcSocket);
        }

        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }

        // Stop the background looper (callbacks already removed above).
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }

        Log.i(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
