package tech.bogomolov.incomingsmsgateway;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented tests for {@link Request#execute()} response-code handling (issue #62).
 * <p>
 * These assert the exact HTTP status read from the server, which is the behavior the
 * old code could not observe: on a 404 it called getInputStream() and threw
 * FileNotFoundException before ever reading the status. Now the code is read via
 * getResponseCode() and the error body drained from getErrorStream(), so a 4xx/5xx
 * maps to a retry while the actual status is captured (and logged).
 * <p>
 * Hits httpbin.org, so it needs a network-connected device/emulator.
 */
@RunWith(AndroidJUnit4.class)
public class RequestResponseCodeTest {

    @Test
    public void testSuccessCodeCaptured() {
        Request request = new Request("https://httpbin.org/status/200", "{}");

        assertThat(request.execute(), is(Request.RESULT_SUCCESS));
        assertThat(request.getResponseCode(), is(200));
    }

    @Test
    public void testNotFoundCodeCaptured() {
        Request request = new Request("https://httpbin.org/status/404", "{}");

        assertThat(request.execute(), is(Request.RESULT_RETRY));
        assertThat(request.getResponseCode(), is(404));
    }

    @Test
    public void testServerErrorCodeCaptured() {
        Request request = new Request("https://httpbin.org/status/500", "{}");

        assertThat(request.execute(), is(Request.RESULT_RETRY));
        assertThat(request.getResponseCode(), is(500));
    }
}
