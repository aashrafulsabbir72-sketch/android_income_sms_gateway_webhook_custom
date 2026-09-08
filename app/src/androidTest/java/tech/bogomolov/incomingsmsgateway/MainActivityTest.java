package tech.bogomolov.incomingsmsgateway;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.ViewInteraction;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;

@RunWith(AndroidJUnit4.class)
public class MainActivityTest {

    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    // On Android 13+ the activity also requests POST_NOTIFICATIONS (issue #77);
    // grant it up front so no system dialog appears mid-test and blocks the UI.
    @Rule
    public GrantPermissionRule permissionRule = GrantPermissionRule.grant(permissionsToGrant());

    private static String[] permissionsToGrant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{Manifest.permission.RECEIVE_SMS};
    }

    @Before
    public void clearSharedPrefs() {
        SharedPreferences sharedPreferences = context.getSharedPreferences(
                context.getString(R.string.key_phones_preference),
                Context.MODE_PRIVATE
        );
        sharedPreferences.edit().clear().commit();

        activityRule.getScenario().recreate();
    }

    @Test
    public void testAddDialogOpen() {
        onView(withId(R.id.btn_add)).perform(click());
        onView(withId(R.id.dialog_config_edit_form)).check(matches(isDisplayed()));
    }

    @Test
    public void testEmptySenderError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_url))
                .perform(scrollTo(), typeText("https://example.com"), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_phone))
                .check(matches(hasErrorText(getResourceString(R.string.error_empty_sender))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testEmptyUrlError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_phone))
                .perform(scrollTo(), typeText("test"), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_url))
                .check(matches(hasErrorText(getResourceString(R.string.error_empty_url))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testWrongUrlError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_phone))
                .perform(scrollTo(), typeText("test"));

        onView(withId(R.id.input_url))
                .perform(scrollTo(), typeText("not url"), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_url))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_url))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testEmptyJsonTemplateError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_phone))
                .perform(scrollTo(), typeText("test"));

        onView(withId(R.id.input_url))
                .perform(scrollTo(), typeText("https://example.com"));

        // The advanced section is collapsed by default; expand it first.
        onView(withId(R.id.advanced_header)).perform(scrollTo(), click());

        onView(withId(R.id.input_json_template))
                .perform(scrollTo(), replaceText(""), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_json_template))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_json))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testWrongJsonTemplateError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_phone))
                .perform(scrollTo(), typeText("test"));

        onView(withId(R.id.input_url))
                .perform(scrollTo(), typeText("https://example.com"));

        // The advanced section is collapsed by default; expand it first.
        onView(withId(R.id.advanced_header)).perform(scrollTo(), click());

        onView(withId(R.id.input_json_template))
                .perform(scrollTo(), replaceText("{"), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_json_template))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_json))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testEmptyJsonHeadersError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_phone))
                .perform(scrollTo(), typeText("test"));

        onView(withId(R.id.input_url))
                .perform(scrollTo(), typeText("https://example.com"));

        // The advanced section is collapsed by default; expand it first.
        onView(withId(R.id.advanced_header)).perform(scrollTo(), click());

        onView(withId(R.id.input_json_headers))
                .perform(scrollTo(), replaceText(""), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_json_headers))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_json))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testWrongJsonHeadersError() {
        onView(withId(R.id.btn_add)).perform(click());
        ViewInteraction dialog = onView(withId(R.id.dialog_config_edit_form));

        onView(withId(R.id.input_phone))
                .perform(scrollTo(), typeText("test"));

        onView(withId(R.id.input_url))
                .perform(scrollTo(), typeText("https://example.com"));

        // The advanced section is collapsed by default; expand it first.
        onView(withId(R.id.advanced_header)).perform(scrollTo(), click());

        onView(withId(R.id.input_json_headers))
                .perform(scrollTo(), replaceText("{"), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        onView(withId(R.id.input_json_headers))
                .check(matches(hasErrorText(getResourceString(R.string.error_wrong_json))));

        dialog.check(matches(isDisplayed()));
    }

    @Test
    public void testAddDeleteRecord() {
        String sender = "1234";
        String url = "https://example.com";

        onView(withId(R.id.btn_add)).perform(click());
        onView(withId(R.id.input_phone)).perform(scrollTo(), typeText(sender));
        onView(withId(R.id.input_url)).perform(scrollTo(), typeText(url), closeSoftKeyboard());

        onView(withText(R.string.btn_add)).perform(click());

        ViewInteraction record = onView(allOf(
                withId(R.id.list_item),
                hasDescendant(withText(containsString(sender))),
                hasDescendant(withText(containsString(url))),
                isDescendantOfA(withId(R.id.listView)))
        );
        record.check(matches(isDisplayed()));

        onView(withId(R.id.dialog_config_edit_form)).check(doesNotExist());

        ViewInteraction deleteButton = onView(allOf(
                withId(R.id.delete_button),
                isDescendantOfA(withId(R.id.listView)),
                withText(R.string.btn_delete),
                isDisplayed())
        );
        deleteButton.perform(click());

        onView(withText(R.string.delete_record)).check(matches(isDisplayed()));

        onView(allOf(withId(android.R.id.button1), withText(R.string.btn_delete))).perform(click());

        onView(withText(R.string.delete_record)).check(doesNotExist());
        record.check(doesNotExist());
        onView(withId(R.id.dialog_config_edit_form)).check(doesNotExist());
    }

    private String getResourceString(int id) {
        Context targetContext = ApplicationProvider.getApplicationContext();
        return targetContext.getResources().getString(id);
    }
}
