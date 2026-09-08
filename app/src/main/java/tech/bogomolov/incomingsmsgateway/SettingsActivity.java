package tech.bogomolov.incomingsmsgateway;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Global app settings. Currently hosts the heartbeat / app-alive monitor
 * (issue #31); the heartbeat itself runs in {@link SmsReceiverService}.
 */
public class SettingsActivity extends AppCompatActivity {

    // Backup export/import (issue #76). Uses the Storage Access Framework, which is
    // API 19+; the buttons hosting these flows are hidden on older devices.
    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final String BACKUP_FILENAME = "sms-gateway-backup.json";
    private static final String BACKUP_MIME_TYPE = "application/json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        HeartbeatSettings settings = HeartbeatSettings.load(this);

        final SwitchCompat enabledCheckbox = findViewById(R.id.input_heartbeat_enabled);
        enabledCheckbox.setChecked(settings.isEnabled());

        final EditText urlInput = findViewById(R.id.input_heartbeat_url);
        urlInput.setText(settings.getUrl());

        final EditText intervalInput = findViewById(R.id.input_heartbeat_interval);
        intervalInput.setText(String.valueOf(settings.getIntervalMinutes()));

        Button saveButton = findViewById(R.id.btn_heartbeat_save);
        saveButton.setOnClickListener(v -> {
            HeartbeatSettings updated = readSettings();
            if (updated == null) {
                return;
            }
            updated.save(this);
            applyHeartbeat(updated);
            Toast.makeText(this, R.string.heartbeat_saved_toast, Toast.LENGTH_SHORT).show();
            finish();
        });

        Button testButton = findViewById(R.id.btn_heartbeat_test);
        testButton.setOnClickListener(v -> testHeartbeat());

        setupBackupSection();
    }

    // The Storage Access Framework intents used for backup require API 19+; hide
    // the whole section on older devices rather than offer a button that can't
    // resolve an activity.
    private void setupBackupSection() {
        View backupSection = findViewById(R.id.backup_section);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            backupSection.setVisibility(View.GONE);
            return;
        }

        findViewById(R.id.btn_export).setOnClickListener(v -> confirmExport());
        findViewById(R.id.btn_import).setOnClickListener(v -> startImport());
    }

    // Warn before exporting: the file holds webhook URLs, custom headers and HMAC
    // secrets in plain text. Only on confirmation do we open the file picker.
    private void confirmExport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_export_warning_title)
                .setMessage(R.string.backup_export_warning_message)
                .setPositiveButton(R.string.btn_export, (dialog, which) -> startExport())
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BACKUP_MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, BACKUP_FILENAME);
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Some file managers tag .json files as octet-stream, so accept any type
        // and let the JSON parser reject a wrong file.
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            exportTo(uri);
        } else if (requestCode == REQUEST_IMPORT) {
            importFrom(uri);
        }
    }

    private void exportTo(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            String content = ForwardingConfig.exportToJson(this);
            output.write(content.getBytes(Charset.forName("UTF-8")));
            int count = ForwardingConfig.getAll(this).size();
            Toast.makeText(this, getString(R.string.backup_export_success, count),
                    Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            Log.e("SettingsActivity", "export failed: " + e);
            Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void importFrom(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            String content = readStream(input);
            int count = ForwardingConfig.importFromJson(this, content);
            Toast.makeText(this, getString(R.string.backup_import_success, count),
                    Toast.LENGTH_LONG).show();
            // New rules need the SMS-listening service running, just as adding one
            // through MainActivity would.
            startServiceIfNeeded();
        } catch (IOException | JSONException e) {
            Log.e("SettingsActivity", "import failed: " + e);
            Toast.makeText(this, R.string.backup_import_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String readStream(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, Charset.forName("UTF-8")));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    // Starts the foreground SMS service if it isn't already running, so imported
    // rules take effect without a restart. Mirrors MainActivity.startService().
    private void startServiceIfNeeded() {
        if (isServiceRunning()) {
            return;
        }
        Intent intent = new Intent(this, SmsReceiverService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(intent);
        } else {
            getApplicationContext().startService(intent);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Validates the form and returns the entered settings, or null (with an inline
    // error set) when invalid. URL/interval are only enforced when the heartbeat is
    // enabled, so a user can clear the toggle without filling a valid URL first.
    private HeartbeatSettings readSettings() {
        final SwitchCompat enabledCheckbox = findViewById(R.id.input_heartbeat_enabled);
        boolean enabled = enabledCheckbox.isChecked();

        final EditText urlInput = findViewById(R.id.input_heartbeat_url);
        String url = urlInput.getText().toString().trim();

        final EditText intervalInput = findViewById(R.id.input_heartbeat_interval);
        String intervalText = intervalInput.getText().toString().trim();
        int interval = intervalText.isEmpty() ? 0 : Integer.parseInt(intervalText);

        if (enabled) {
            if (TextUtils.isEmpty(url)) {
                urlInput.setError(getString(R.string.error_empty_url));
                return null;
            }
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                urlInput.setError(getString(R.string.error_wrong_url));
                return null;
            }
            if (interval < HeartbeatSettings.MIN_INTERVAL_MINUTES) {
                intervalInput.setError(getString(R.string.error_wrong_interval));
                return null;
            }
        } else if (interval < HeartbeatSettings.MIN_INTERVAL_MINUTES) {
            interval = HeartbeatSettings.DEFAULT_INTERVAL_MINUTES;
        }

        return new HeartbeatSettings(enabled, url, interval);
    }

    // Pushes the new settings to the service. Starting it with the reschedule action
    // both (re)schedules a running service and starts one if needed (e.g. heartbeat
    // turned on while no forwarding rules exist). When disabling with no service
    // running, there is nothing to do — don't spin one up just to stop the ping.
    private void applyHeartbeat(HeartbeatSettings settings) {
        if (!settings.isEnabled() && !isServiceRunning()) {
            return;
        }

        Intent intent = new Intent(this, SmsReceiverService.class);
        intent.setAction(SmsReceiverService.ACTION_RESCHEDULE_HEARTBEAT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(intent);
        } else {
            getApplicationContext().startService(intent);
        }
    }

    private void testHeartbeat() {
        final EditText urlInput = findViewById(R.id.input_heartbeat_url);
        String url = urlInput.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            urlInput.setError(getString(R.string.error_empty_url));
            return;
        }
        try {
            new URL(url);
        } catch (MalformedURLException e) {
            urlInput.setError(getString(R.string.error_wrong_url));
            return;
        }

        new Thread(() -> {
            Request request = new Request(url, "");
            request.setUseChunkedMode(false);
            String result = request.execute();
            runOnUiThread(() ->
                    Toast.makeText(getApplicationContext(), result, Toast.LENGTH_LONG).show());
        }).start();
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (SmsReceiverService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
