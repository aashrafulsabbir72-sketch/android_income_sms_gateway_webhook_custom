package tech.bogomolov.incomingsmsgateway;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented (Espresso) tests for {@link SettingsActivity}'s heartbeat form. They
 * cover the validation branches that fire when the heartbeat is enabled, plus the
 * disabled-save path. The enabled-valid-save path is intentionally not exercised
 * here: it calls startForegroundService(), whose side effects (a real running
 * service + persistent notification) don't belong in a form-validation test — the
 * persistence itself is covered by {@link HeartbeatSettingsTest}.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsActivityTest {

    // Must mirror HeartbeatSettings' private preference file name, so the test can
    // reset stored state between runs.
    private static final String PREFERENCE = "heartbeat";

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule
    public ActivityScenarioRule<SettingsActivity> activityRule =
            new ActivityScenarioRule<>(SettingsActivity.class);

    @Before
    public void clearSharedPrefs() {
        clearPrefs();
        // The rule launches the activity before @Before runs, so recreate it to read
        // the just-cleared defaults (disabled, empty url, default interval).
        activityRule.getScenario().recreate();
    }

    @After
    public void tearDown() {
        clearPrefs();
    }

    @Test
    public void testEnabledEmptyUrlError() {
        onView(withId(R.id.input_heartbeat_enabled)).perform(scrollTo(), click());
        onView(withId(R.id.input_heartbeat_url))
                .perform(scrollTo(), replaceText(""), closeSoftKeyboard());

        onView(withId(R.id.btn_heartbeat_save)).perform(scrollTo(), click());

        onView(withId(R.id.input_heartbeat_url))
                .check(matches(hasErrorText(getResourceString(R.string.error_empty_url))));
    }

    @Test
    public void testEnabledWrongUrlError() {
        onView(withId(R.id.input_heartbeat_enabled)).perform(scrollTo(), click());
        onView(withId(R.id.input_heartbeat_url))
                .perform(scrollTo(), replaceText("not a url"), closeSoftKeyboard());

        onView(withId(R.id.btn_heartbeat_save)).perform(scrollTo(), click());

        onView(withId(R.id.input_heartbeat_url))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_url))));
    }

    @Test
    public void testEnabledZeroIntervalError() {
        onView(withId(R.id.input_heartbeat_enabled)).perform(scrollTo(), click());
        onView(withId(R.id.input_heartbeat_url))
                .perform(scrollTo(), replaceText("https://example.com"), closeSoftKeyboard());
        onView(withId(R.id.input_heartbeat_interval))
                .perform(scrollTo(), replaceText("0"), closeSoftKeyboard());

        onView(withId(R.id.btn_heartbeat_save)).perform(scrollTo(), click());

        onView(withId(R.id.input_heartbeat_interval))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_interval))));
    }

    @Test
    public void testDisabledSavePersists() {
        // Left disabled on purpose: applyHeartbeat() then returns early without
        // starting the foreground service, so the form values just persist.
        onView(withId(R.id.input_heartbeat_url))
                .perform(scrollTo(), replaceText("https://example.com/ping"), closeSoftKeyboard());
        onView(withId(R.id.input_heartbeat_interval))
                .perform(scrollTo(), replaceText("10"), closeSoftKeyboard());

        onView(withId(R.id.btn_heartbeat_save)).perform(scrollTo(), click());

        HeartbeatSettings saved = HeartbeatSettings.load(context);
        assertFalse(saved.isEnabled());
        assertEquals("https://example.com/ping", saved.getUrl());
        assertEquals(10, saved.getIntervalMinutes());
    }

    private void clearPrefs() {
        context.getSharedPreferences(PREFERENCE, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private String getResourceString(int id) {
        Context targetContext = ApplicationProvider.getApplicationContext();
        return targetContext.getResources().getString(id);
    }
}
