package com.studyspot.plugin;

import android.Manifest;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.location.LocationManagerCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge between main.js and Android.
 * Actions: permStatus, permRequest, openSettings, locationEnabled, openLocationSettings,
 *          setConfig, startService, stopService, serviceRunning,
 *          getPins, clearPins, sharePhoto, onEvent.
 */
public class StudySpotPlugin extends CordovaPlugin {

    static final String PREFS = "studyspot_prefs";

    /** Open callback used to push live events from the service to JavaScript. */
    private static CallbackContext eventCallback;

    /** requestCode -> {CallbackContext, permission-group name} */
    private final Map<Integer, Object[]> pending = new HashMap<>();

    // ------------------------------------------------------------------
    // Cordova entry point
    // ------------------------------------------------------------------
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) throws JSONException {
        switch (action) {
            case "permStatus":
                cb.success(permState(args.getString(0)));
                return true;
            case "permRequest":
                requestPerm(args.getString(0), cb);
                return true;
            case "openSettings":
                openSettings();
                cb.success();
                return true;
            case "locationEnabled":
                cb.success(isLocationOn() ? 1 : 0);
                return true;
            case "openLocationSettings":
                startSettingsIntent(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                cb.success();
                return true;
            case "setConfig":
                saveConfig(args.getJSONObject(0));
                cb.success();
                return true;
            case "startService":
                startService(cb);
                return true;
            case "stopService":
                ctx().stopService(new Intent(ctx(), StudySpotService.class));
                cb.success();
                return true;
            case "serviceRunning":
                cb.success(StudySpotService.running ? 1 : 0);
                return true;
            case "getPins":
                cb.success(prefs().getString("pins", "[]"));
                return true;
            case "clearPins":
                prefs().edit().remove("pins").apply();
                cb.success();
                return true;
            case "readFile":
                readFile(args.getString(0), cb);
                return true;
            case "sharePhoto":
                sharePhoto(args.getString(0), cb);
                return true;
            case "onEvent":
                eventCallback = cb;
                PluginResult keep = new PluginResult(PluginResult.Status.NO_RESULT);
                keep.setKeepCallback(true);
                cb.sendPluginResult(keep);
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onReset() {
        eventCallback = null;           // WebView reloaded: old callback is no longer valid
    }

    /** Called by the service to push an event to JavaScript. */
    static void emit(JSONObject event) {
        CallbackContext cb = eventCallback;
        if (cb == null) return;
        PluginResult r = new PluginResult(PluginResult.Status.OK, event);
        r.setKeepCallback(true);
        cb.sendPluginResult(r);
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------
    private Context ctx() {
        return cordova.getActivity().getApplicationContext();
    }

    private SharedPreferences prefs() {
        return ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String[] permsFor(String name) {
        switch (name) {
            case "camera":
                return new String[]{Manifest.permission.CAMERA};
            case "location":
                // Android 12+ requires FINE and COARSE to be requested together.
                return new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION};
            case "notifications":
                return Build.VERSION.SDK_INT >= 33
                        ? new String[]{Manifest.permission.POST_NOTIFICATIONS}
                        : new String[0];
            default:
                return new String[0];
        }
    }

    private int codeFor(String name) {
        switch (name) {
            case "camera": return 1;
            case "location": return 2;
            default: return 3;
        }
    }

    private boolean anyGranted(String[] perms) {
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(ctx(), p) == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    private boolean shouldShowRationale(String[] perms) {
        for (String p : perms) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(cordova.getActivity(), p)) return true;
        }
        return false;
    }

    /**
     * granted | prompt (never asked) | denied (can ask again) | permanently_denied (Android will not ask).
     * "blocked_" is stored only when a request ended in denial with no rationale left.
     */
    private String permState(String name) {
        SharedPreferences p = prefs();
        if (name.equals("notifications") && Build.VERSION.SDK_INT < 33) {
            // Before Android 13 there is no runtime permission, but the user can switch notifications off.
            return NotificationManagerCompat.from(ctx()).areNotificationsEnabled() ? "granted" : "permanently_denied";
        }
        String[] perms = permsFor(name);
        if (perms.length == 0) return "granted";
        if (anyGranted(perms)) {
            p.edit().remove("blocked_" + name).apply();
            return "granted";
        }
        if (p.getBoolean("blocked_" + name, false)) return "permanently_denied";
        if (p.getBoolean("asked_" + name, false)) return "denied";
        return "prompt";
    }

    private void requestPerm(String name, CallbackContext cb) {
        String[] perms = permsFor(name);
        if (perms.length == 0 || anyGranted(perms)) {
            cb.success(permState(name));
            return;
        }
        prefs().edit().putBoolean("asked_" + name, true).apply();
        int code = codeFor(name);
        pending.put(code, new Object[]{cb, name});
        cordova.requestPermissions(this, code, perms);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        Object[] entry = pending.remove(requestCode);
        if (entry == null) return;
        CallbackContext cb = (CallbackContext) entry[0];
        String name = (String) entry[1];
        if (grantResults.length > 0) {          // empty array = request was interrupted
            boolean granted = false;
            for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) granted = true;
            SharedPreferences.Editor e = prefs().edit();
            if (granted || shouldShowRationale(permsFor(name))) e.remove("blocked_" + name);
            else e.putBoolean("blocked_" + name, true);     // denied and Android will not ask again
            e.apply();
        }
        cb.success(permState(name));
    }

    // ------------------------------------------------------------------
    // Settings screens / location services
    // ------------------------------------------------------------------
    private void startSettingsIntent(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        cordova.getActivity().startActivity(i);
    }

    private void openSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx().getPackageName(), null));
        startSettingsIntent(i);
    }

    private boolean isLocationOn() {
        LocationManager lm = (LocationManager) ctx().getSystemService(Context.LOCATION_SERVICE);
        return lm != null && LocationManagerCompat.isLocationEnabled(lm);
    }

    // ------------------------------------------------------------------
    // Service control + configuration
    // ------------------------------------------------------------------
    private void saveConfig(JSONObject c) {
        prefs().edit()
                .putInt("cfg_sens", c.optInt("sensitivity", 5))
                .putInt("cfg_cooldown", c.optInt("cooldown", 3))
                .apply();
    }

    private void startService(CallbackContext cb) {
        if (!anyGranted(permsFor("location"))) {
            cb.error("NO_LOCATION_PERMISSION");
            return;
        }
        try {
            // Must be called while the app is visible (Android 12+ background-start limits).
            ContextCompat.startForegroundService(ctx(), new Intent(ctx(), StudySpotService.class));
            cb.success();
        } catch (Exception e) {
            cb.error(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // Read the camera photo (file:// or content://) and return it as base64
    // ------------------------------------------------------------------
    /** Only our own app's files / the camera plugin's provider may be read (least privilege). */
    private boolean isAllowedUri(Uri uri) {
        String pkg = ctx().getPackageName();
        String scheme = uri.getScheme();
        try {
            if ("content".equals(scheme)) {
                String authority = uri.getAuthority();
                return authority != null && authority.startsWith(pkg);
            }
            if ("file".equals(scheme) && uri.getPath() != null) {
                String path = new File(uri.getPath()).getCanonicalPath();
                return path.contains("/" + pkg + "/");
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void readFile(final String uriString, final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                Uri uri = Uri.parse(uriString);
                if (!isAllowedUri(uri)) {
                    cb.error("NOT_ALLOWED");
                    return;
                }
                try (InputStream in = ctx().getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in == null) {
                        cb.error("OPEN_FAILED");
                        return;
                    }
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    cb.success(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                }
            } catch (Exception e) {
                cb.error(String.valueOf(e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------
    // Share sheet (implicit ACTION_SEND intent)
    // ------------------------------------------------------------------
    private void sharePhoto(final String base64, final CallbackContext cb) {
        cordova.getThreadPool().execute(() -> {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(ctx().getCacheDir(), "shared");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();                       // remove earlier shared photos
                if (old != null) for (File f : old) f.delete();
                File file = new File(dir, "studyspot_photo_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
                Uri uri = FileProvider.getUriForFile(ctx(), ctx().getPackageName() + ".studyspot.fileprovider", file);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/jpeg");
                send.putExtra(Intent.EXTRA_STREAM, uri);            // only the photo is sent
                send.setClipData(ClipData.newRawUri("photo", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                final Intent chooser = Intent.createChooser(send, "Share photo");
                cordova.getActivity().runOnUiThread(() -> cordova.getActivity().startActivity(chooser));
                cb.success();
            } catch (Exception e) {
                cb.error(String.valueOf(e.getMessage()));
            }
        });
    }
}
