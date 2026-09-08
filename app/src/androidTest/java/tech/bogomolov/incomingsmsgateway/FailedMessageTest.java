package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Configuration;
import androidx.work.Data;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Instrumented tests for {@link FailedMessage}: the save/getAll/clear round-trip,
 * the size cap, and that retryAll empties the store. These need a real
 * SharedPreferences (and WorkManager for the retry path), so they run on a
 * device/emulator rather than the local JVM.
 */
@RunWith(AndroidJUnit4.class)
public class FailedMessageTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setup() {
        Configuration config = new Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(new SynchronousExecutor())
                .build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config);

        FailedMessage.clear(context);
    }

    @After
    public void tearDown() {
        // Don't leak stored failures into other test classes (e.g. MainActivityTest,
        // where they would make the "retry failed" menu item appear).
        FailedMessage.clear(context);
    }

    @Test
    public void testSaveAndGetAllRoundTrip() {
        FailedMessage.save(context, sampleData("https://example.com/hook", "payload-1"));

        assertEquals(1, FailedMessage.getCount(context));

        List<Data> all = FailedMessage.getAll(context);
        assertEquals(1, all.size());

        Data loaded = all.get(0);
        assertEquals("https://example.com/hook", loaded.getString(RequestWorker.DATA_URL));
        assertEquals("payload-1", loaded.getString(RequestWorker.DATA_TEXT));
        assertEquals("{\"X-Token\":\"abc\"}", loaded.getString(RequestWorker.DATA_HEADERS));
        assertTrue(loaded.getBoolean(RequestWorker.DATA_IGNORE_SSL, false));
        assertFalse(loaded.getBoolean(RequestWorker.DATA_CHUNKED_MODE, true));
        assertEquals(5, loaded.getInt(RequestWorker.DATA_MAX_RETRIES, 0));
        // The retry flag must round-trip so a re-sent message that fails again is
        // stored once more.
        assertTrue(loaded.getBoolean(RequestWorker.DATA_STORE_FAILED, false));
        // Local mode must round-trip too, or the retry regains the
        // validated-internet constraint and a LAN-only delivery never runs.
        assertTrue(loaded.getBoolean(RequestWorker.DATA_LOCAL_MODE, false));
    }

    @Test
    public void testClear() {
        FailedMessage.save(context, sampleData("https://example.com", "a"));
        FailedMessage.save(context, sampleData("https://example.com", "b"));
        assertEquals(2, FailedMessage.getCount(context));

        FailedMessage.clear(context);
        assertEquals(0, FailedMessage.getCount(context));
    }

    @Test
    public void testTrimCapsAtMax() {
        for (int i = 0; i < FailedMessage.MAX_STORED + 3; i++) {
            FailedMessage.save(context, sampleData("https://example.com", "m" + i));
        }
        assertEquals(FailedMessage.MAX_STORED, FailedMessage.getCount(context));
    }

    @Test
    public void testRetryAllClearsStore() {
        FailedMessage.save(context, sampleData("https://example.com", "to-retry"));
        assertEquals(1, FailedMessage.getCount(context));

        FailedMessage.retryAll(context);

        // retryAll empties the store; anything that fails on re-send is re-added
        // later by the worker, not synchronously here.
        assertEquals(0, FailedMessage.getCount(context));
    }

    private Data sampleData(String url, String text) {
        return new Data.Builder()
                .putString(RequestWorker.DATA_URL, url)
                .putString(RequestWorker.DATA_TEXT, text)
                .putString(RequestWorker.DATA_HEADERS, "{\"X-Token\":\"abc\"}")
                .putBoolean(RequestWorker.DATA_IGNORE_SSL, true)
                .putBoolean(RequestWorker.DATA_CHUNKED_MODE, false)
                .putInt(RequestWorker.DATA_MAX_RETRIES, 5)
                .putBoolean(RequestWorker.DATA_STORE_FAILED, true)
                .putBoolean(RequestWorker.DATA_LOCAL_MODE, true)
                .build();
    }
}
