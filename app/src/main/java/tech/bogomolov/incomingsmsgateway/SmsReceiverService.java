package tech.bogomolov.incomingsmsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

// Keeps the foreground "F" indicator alive and hosts the heartbeat ping. SMS
// delivery itself is handled by the manifest-declared SmsBroadcastReceiver (see
// AndroidManifest.xml / issue #78), so this service no longer registers an SMS
// receiver at runtime — doing so would double-deliver every message.
public class SmsReceiverService extends Service {

    private static final String CHANNEL_ID = "SmsDefault";

    // Sent by SettingsActivity after the user edits the heartbeat settings, so a
    // running service re-reads them without a full restart (see onStartCommand).
    public static final String ACTION_RESCHEDULE_HEARTBEAT =
            "tech.bogomolov.incomingsmsgateway.RESCHEDULE_HEARTBEAT";

    // The heartbeat runs on its own thread: hosting it in this foreground service
    // (rather than WorkManager, whose periodic minimum is 15 min) is what lets the
    // ping survive Doze at sub-15-min intervals, since a foreground service keeps
    // the process out of App Standby.
    private HandlerThread heartbeatThread;
    private Handler heartbeatHandler;
    private Runnable heartbeatRunnable;

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);

            // IMPORTANCE_LOW keeps the "F" indicator silent (no sound, collapsed in
            // the shade) like the original IMPORTANCE_NONE, but is not created in a
            // blocked state — IMPORTANCE_NONE leaves the channel off on Android 13+,
            // which greys out the user's "Allow notifications" toggle (issue #77).
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getText(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW);

            notificationManager.createNotificationChannel(channel);

            Notification notification =
                    new Notification.Builder(this, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_f)
                            .setColor(getColor(R.color.colorPrimary))
                            .setOngoing(true)
                            .build();

            startForeground(1, notification);
        }

        startHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESCHEDULE_HEARTBEAT.equals(intent.getAction())) {
            startHeartbeat();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopHeartbeat();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }

    // Re-reads the heartbeat settings and (re)schedules the periodic ping. Safe to
    // call repeatedly: it first tears down any existing schedule.
    private void startHeartbeat() {
        stopHeartbeat();

        HeartbeatSettings settings = HeartbeatSettings.load(this);
        if (!settings.isEnabled() || settings.getUrl().isEmpty()) {
            return;
        }

        final String url = settings.getUrl();
        final long interval = settings.getIntervalMillis();

        heartbeatThread = new HandlerThread("HeartbeatThread");
        heartbeatThread.start();
        heartbeatHandler = new Handler(heartbeatThread.getLooper());

        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                sendHeartbeat(url);
                heartbeatHandler.postDelayed(this, interval);
            }
        };

        // First ping after one interval — sending immediately would re-fire on every
        // settings edit / service restart, which adds nothing for monitoring.
        heartbeatHandler.postDelayed(heartbeatRunnable, interval);
    }

    private void stopHeartbeat() {
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        if (heartbeatThread != null) {
            heartbeatThread.quit();
            heartbeatThread = null;
        }
        heartbeatHandler = null;
        heartbeatRunnable = null;
    }

    // Runs on the heartbeat thread. Reuses Request (HttpURLConnection) with an empty
    // body in fixed-length mode, so the ping is a plain Content-Length: 0 POST.
    private void sendHeartbeat(String url) {
        try {
            Request request = new Request(url, "");
            request.setUseChunkedMode(false);
            String result = request.execute();
            Log.i("SmsGateway", "heartbeat: " + result);
        } catch (Exception e) {
            Log.e("SmsGateway", "heartbeat error: " + e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
