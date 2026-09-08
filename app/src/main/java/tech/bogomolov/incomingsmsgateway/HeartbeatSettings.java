package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Global (not per-rule) settings for the heartbeat / "is the app still alive"
 * monitor (issue #31). When enabled, {@link SmsReceiverService} periodically
 * POSTs to {@link #getUrl()} so an external dead-man's-switch monitor
 * (healthchecks.io, Uptime Kuma push monitors, cronitor, …) can alert the user
 * if the phone dies, gets killed, or loses connectivity and the pings stop.
 *
 * Stored in its own SharedPreferences file, separate from the forwarding rules
 * in {@link ForwardingConfig} (whose {@code getAll()} would otherwise try to
 * parse these entries as configs).
 */
public class HeartbeatSettings {

    private static final String PREFERENCE = "heartbeat";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_URL = "url";
    private static final String KEY_INTERVAL = "interval_minutes";

    public static final int DEFAULT_INTERVAL_MINUTES = 5;
    public static final int MIN_INTERVAL_MINUTES = 1;

    private final boolean enabled;
    private final String url;
    private final int intervalMinutes;

    public HeartbeatSettings(boolean enabled, String url, int intervalMinutes) {
        this.enabled = enabled;
        this.url = url == null ? "" : url;
        this.intervalMinutes = intervalMinutes;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public String getUrl() {
        return this.url;
    }

    public int getIntervalMinutes() {
        return this.intervalMinutes;
    }

    public long getIntervalMillis() {
        return (long) this.intervalMinutes * 60_000L;
    }

    public static HeartbeatSettings load(Context context) {
        SharedPreferences pref = getPreference(context);
        return new HeartbeatSettings(
                pref.getBoolean(KEY_ENABLED, false),
                pref.getString(KEY_URL, ""),
                pref.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)
        );
    }

    public void save(Context context) {
        SharedPreferences.Editor editor = getPreference(context).edit();
        editor.putBoolean(KEY_ENABLED, this.enabled);
        editor.putString(KEY_URL, this.url);
        editor.putInt(KEY_INTERVAL, this.intervalMinutes);
        editor.commit();
    }

    private static SharedPreferences getPreference(Context context) {
        return context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE);
    }
}
