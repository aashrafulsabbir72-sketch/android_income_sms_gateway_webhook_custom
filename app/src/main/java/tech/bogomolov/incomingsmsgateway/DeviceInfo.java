package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;

/**
 * Reads device-health data points (issue #39) exposed as template placeholders:
 * %version%, %battery%, %power%, %network%. These describe the phone's state at
 * the moment a message is forwarded, for fleet/uptime monitoring.
 * <p>
 * Every getter is null-context safe so {@link ForwardingConfig#prepareMessage}
 * stays callable (and JVM-unit-testable) without a real Context: a null context
 * yields the {@code -1}/{@code "unknown"}/{@code "none"} fallbacks instead of
 * touching Android APIs. None of these reads needs a runtime permission
 * (%network% uses the install-time ACCESS_NETWORK_STATE already in the manifest).
 */
public class DeviceInfo {

    public static String getVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /** Battery charge as a 0-100 percentage, or -1 if unknown. */
    public static int getBatteryPercentage(Context context) {
        Intent batteryStatus = getBatteryStatus(context);
        if (batteryStatus == null) {
            return -1;
        }
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return -1;
        }
        return Math.round(level * 100f / scale);
    }

    /** Power source: "ac", "usb", "wireless", "unplugged", or "unknown". */
    public static String getPowerSource(Context context) {
        Intent batteryStatus = getBatteryStatus(context);
        if (batteryStatus == null) {
            return "unknown";
        }
        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        switch (plugged) {
            case 0:
                return "unplugged";
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: // API 17+, constant value 4
                return "wireless";
            default:
                return "unknown";
        }
    }

    /** Active network transport: "wifi", "mobile", "ethernet", "other", or "none". */
    public static String getNetworkType(Context context) {
        if (context == null) {
            return "none";
        }
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return "none";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return "none";
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) {
                return "none";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "wifi";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "mobile";
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return "ethernet";
            }
            return "other";
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return "none";
            }
            switch (info.getType()) {
                case ConnectivityManager.TYPE_WIFI:
                    return "wifi";
                case ConnectivityManager.TYPE_MOBILE:
                    return "mobile";
                case ConnectivityManager.TYPE_ETHERNET:
                    return "ethernet";
                default:
                    return "other";
            }
        }
    }

    // Returns the current sticky ACTION_BATTERY_CHANGED intent (passing a null
    // receiver just reads the last sticky broadcast, it does not register one),
    // or null when there is no context (e.g. unit tests).
    private static Intent getBatteryStatus(Context context) {
        if (context == null) {
            return null;
        }
        return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
