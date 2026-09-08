package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure-JVM unit tests for {@link Request#computeHmacSha256Hex}.
 * <p>
 * The HMAC computation uses only javax.crypto, so it runs on the local JVM
 * without a device. These tests pin down the signature contract that a
 * receiving server must reproduce to validate a request: HmacSHA256 over the
 * UTF-8 body bytes, hex-encoded as lowercase with no prefix.
 */
public class RequestHmacSignatureTest {

    @Test
    public void testMatchesRfc4231TestCase2() throws Exception {
        // RFC 4231, Test Case 2: a published HMAC-SHA-256 vector.
        String secret = "Jefe";
        String body = "what do ya want for nothing?";

        String signature = Request.computeHmacSha256Hex(secret, body);

        assertEquals(
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                signature);
    }

    @Test
    public void testOutputIsLowercaseHexOf64Chars() throws Exception {
        String signature = Request.computeHmacSha256Hex("secret", "{\"text\":\"hi\"}");

        // SHA-256 is 32 bytes -> 64 hex chars, and the server expects lowercase.
        assertEquals(64, signature.length());
        assertEquals(signature.toLowerCase(), signature);
        assertEquals(signature, signature.replaceAll("[^0-9a-f]", ""));
    }

    @Test
    public void testSignatureIsSensitiveToSecret() throws Exception {
        String body = "{\"text\":\"hi\"}";

        assertNotEquals(
                Request.computeHmacSha256Hex("secret-a", body),
                Request.computeHmacSha256Hex("secret-b", body));
    }

    @Test
    public void testSignatureIsSensitiveToBody() throws Exception {
        String secret = "secret";

        assertNotEquals(
                Request.computeHmacSha256Hex(secret, "{\"text\":\"hi\"}"),
                Request.computeHmacSha256Hex(secret, "{\"text\":\"ho\"}"));
    }

    @Test
    public void testSetSignatureHeaderWithEmptySecretDoesNotThrow() {
        // SecretKeySpec rejects an empty key with IllegalArgumentException; the
        // delivery path must swallow that (logging it) rather than crash the
        // worker or the dialog's test thread.
        Request request = new Request("https://example.com", "{}");
        request.setSignatureHeader("", "{}");
    }

    @Test
    public void testNonAsciiBodyIsSignedAsUtf8() throws Exception {
        // The body is written to the wire as UTF-8 by Request.execute(), so the
        // signature must be computed over the same UTF-8 bytes. Compare against
        // an HMAC computed independently over the body's UTF-8 encoding.
        String secret = "secret";
        String body = "{\"text\":\"café 😀\"}";

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected =
                Request.convertByteToHexadecimal(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, Request.computeHmacSha256Hex(secret, body));
    }
}
