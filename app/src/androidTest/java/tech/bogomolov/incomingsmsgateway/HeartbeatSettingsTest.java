package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests for {@link HeartbeatSettings}: defaults when nothing is
 * stored, the save/load round-trip, the minutes→millis conversion, and null-url
 * normalization. These need a real SharedPreferences, so they run on a
 * device/emulator rather than the local JVM.
 */
@RunWith(AndroidJUnit4.class)
public class HeartbeatSettingsTest {

    // Must mirror HeartbeatSettings' private preference file name, so the test can
    // reset stored state between runs.
    private static final String PREFERENCE = "heartbeat";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        clearPrefs();
    }

    @After
    public void tearDown() {
        // Don't leak stored settings into other test classes that share this context.
        clearPrefs();
    }

    @Test
    public void testDefaultsWhenNothingStored() {
        HeartbeatSettings settings = HeartbeatSettings.load(context);

        assertFalse(settings.isEnabled());
        assertEquals("", settings.getUrl());
        assertEquals(HeartbeatSettings.DEFAULT_INTERVAL_MINUTES, settings.getIntervalMinutes());
    }

    @Test
    public void testSaveAndLoadRoundTrip() {
        new HeartbeatSettings(true, "https://example.com/ping", 15).save(context);

        HeartbeatSettings loaded = HeartbeatSettings.load(context);
        assertTrue(loaded.isEnabled());
        assertEquals("https://example.com/ping", loaded.getUrl());
        assertEquals(15, loaded.getIntervalMinutes());
    }

    @Test
    public void testIntervalMillisConversion() {
        HeartbeatSettings settings = new HeartbeatSettings(true, "https://example.com", 5);

        // 5 minutes in milliseconds; the cast in getIntervalMillis() guards against
        // int overflow for large intervals.
        assertEquals(5L * 60_000L, settings.getIntervalMillis());
    }

    @Test
    public void testNullUrlBecomesEmpty() {
        // load() never yields null (default is ""), but the constructor is public and
        // must not propagate a null that would NPE in SmsReceiverService.
        HeartbeatSettings settings = new HeartbeatSettings(false, null, 5);

        assertEquals("", settings.getUrl());
    }

    private void clearPrefs() {
        context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }
}
