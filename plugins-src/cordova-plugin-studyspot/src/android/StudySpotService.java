package com.studyspot.plugin;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Real foreground service (type "location").
 *  - persistent notification while running (generic text, hidden on the lock screen)
 *  - listens to the accelerometer with SensorManager and detects shakes itself
 *  - keeps the latest location (GPS + network) so a shake can pin the current study spot
 *  - saves every pin in SharedPreferences ("pins"); the app imports them when it opens
 * A partial wake lock keeps the CPU awake so the accelerometer keeps delivering data
 * while the screen is off.
 */
public class StudySpotService extends Service implements SensorEventListener, LocationListener {

    static final String ACTION_STOP = "com.studyspot.ACTION_STOP";
    static volatile boolean running = false;

    private static final String CH_SERVICE = "studyspot_service";
    private static final String CH_INFO = "studyspot_info";
    private static final int ID_FG = 1001;
    private static final int ID_INFO = 1003;
    private static final double GRAVITY = 9.81;
    private static final int MAX_PINS = 200;

    private SharedPreferences prefs;
    private SensorManager sensors;
    private LocationManager locations;
    private NotificationManager nm;
    private PowerManager.WakeLock wake;
    private Vibrator vibrator;

    private Location lastLocation;
    private long lastShake = -1000000L;            // elapsedRealtime ms
    private long lastCfgLoad = -1000000L;
    private double threshold = 14;                 // m/s^2 away from gravity
    private long cooldownMs = 3000;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(StudySpotPlugin.PREFS, Context.MODE_PRIVATE);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "studyspot:service");
        wake.setReferenceCounted(false);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {   // "Stop" in the notification
            stopSelf();
            return START_NOT_STICKY;
        }

        // Must be called within 5 s of startForegroundService(), every time.
        Notification n = buildServiceNotification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(ID_FG, n);
            }
        } catch (Exception e) {                    // e.g. location permission missing on Android 14
            emitError("Could not start foreground service");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            loadConfig();
            wake.acquire(6 * 60 * 60 * 1000L);     // safety timeout: 6 hours
            registerSensor();
            if (!registerLocation()) return START_NOT_STICKY;
            emitType("started");
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { sensors.unregisterListener(this); } catch (Exception ignored) { }
        try { locations.removeUpdates(this); } catch (Exception ignored) { }
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);     // removes the persistent notification
        nm.cancel(ID_FG);
        nm.cancel(ID_INFO);
        emitType("stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;                                // started service only
    }

    // ------------------------------------------------------------------
    // Settings written by the plugin (setConfig)
    // ------------------------------------------------------------------
    private void loadConfig() {
        lastCfgLoad = SystemClock.elapsedRealtime();
        int sens = prefs.getInt("cfg_sens", 5);
        threshold = 4 + (10 - sens) * 2;            // same formula as counter.js (thresholdFor)
        cooldownMs = prefs.getInt("cfg_cooldown", 3) * 1000L;
    }

    // ------------------------------------------------------------------
    // Shake detection (accelerometer)
    // ------------------------------------------------------------------
    private void registerSensor() {
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCfgLoad > 1000) loadConfig();
        double x = e.values[0], y = e.values[1], z = e.values[2];
        double magnitude = Math.sqrt(x * x + y * y + z * z);
        // Resting phone ~ 9.81. A shake pushes the total far above/below it.
        if (Math.abs(magnitude - GRAVITY) > threshold && now - lastShake > cooldownMs) {
            lastShake = now;                         // cooldown: one shake = one pin
            onShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void onShake() {
        addPin(lastLocation);                        // time-stamped spot (position if we have a fix)
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        postInfo("Spot pinned", "Open StudySpot to rate it.");
        emitType("pin");
    }

    // ------------------------------------------------------------------
    // Location (kept only so a pin can carry a position)
    // ------------------------------------------------------------------
    private boolean has(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** @return false if the service had to stop because location is not allowed. */
    private boolean registerLocation() {
        boolean fine = has(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = has(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!fine && !coarse) {
            emitError("Location permission missing");
            stopSelf();
            return false;
        }
        try {
            if (fine && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 0, this, Looper.getMainLooper());
            }
            if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, this, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            emitError("Location permission missing");
            stopSelf();
            return false;
        }
        return true;
    }

    @Override
    public void onLocationChanged(Location l) {
        // Prefer the more accurate fix (GPS) unless the previous one is old.
        if (lastLocation != null && l.getAccuracy() > lastLocation.getAccuracy()
                && l.getTime() - lastLocation.getTime() < 20000) return;
        lastLocation = l;
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    // ------------------------------------------------------------------
    // Pins (SharedPreferences, oldest first, capped)
    // ------------------------------------------------------------------
    private void addPin(Location l) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("pins", "[]"));
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            if (l != null) {
                o.put("lat", l.getLatitude());
                o.put("lon", l.getLongitude());
                o.put("acc", (double) l.getAccuracy());
            }
            arr.put(o);
            JSONArray out = arr;
            if (arr.length() > MAX_PINS) {
                out = new JSONArray();
                for (int i = arr.length() - MAX_PINS; i < arr.length(); i++) out.put(arr.get(i));
            }
            prefs.edit().putString("pins", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------
    private void createChannels() {
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Shake service", NotificationManager.IMPORTANCE_LOW);
        s.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        s.setShowBadge(false);
        NotificationChannel i = new NotificationChannel(CH_INFO, "Pinned spots", NotificationManager.IMPORTANCE_DEFAULT);
        i.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(s);
        nm.createNotificationChannel(i);
    }

    private PendingIntent openAppIntent() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent();
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildServiceNotification() {
        Intent stop = new Intent(this, StudySpotService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("StudySpot is running")            // generic text only, no coordinates
                .setContentText("Shake your phone to pin this study spot.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // hidden on the lock screen
                .setContentIntent(openAppIntent())
                .addAction(0, "Stop", stopPi)
                .build();
    }

    private void postInfo(String title, String text) {
        Notification n = new NotificationCompat.Builder(this, CH_INFO)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setTimeoutAfter(10000)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build();
        nm.notify(ID_INFO, n);
    }

    // ------------------------------------------------------------------
    // Events to JavaScript
    // ------------------------------------------------------------------
    private void emitType(String type) {
        try { StudySpotPlugin.emit(new JSONObject().put("type", type)); } catch (Exception ignored) { }
    }

    private void emitError(String message) {
        try { StudySpotPlugin.emit(new JSONObject().put("type", "error").put("message", message)); } catch (Exception ignored) { }
    }
}
