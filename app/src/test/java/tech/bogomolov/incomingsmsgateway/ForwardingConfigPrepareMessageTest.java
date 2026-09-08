package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-JVM unit tests for {@link ForwardingConfig#prepareMessage}.
 * <p>
 * prepareMessage does template placeholder substitution and JSON-escaping and
 * touches no Android APIs for the SMS-derived placeholders, so it can run on the
 * local JVM without a device. The device-health placeholders (%battery%,
 * %power%, %network%) read live state via {@link DeviceInfo}, which is
 * null-context safe and returns fallbacks here; their real values are covered by
 * the instrumented {@code DeviceInfoTest}. %version% comes from BuildConfig and
 * needs no context.
 */
public class ForwardingConfigPrepareMessageTest {

    private ForwardingConfig configWithTemplate(String template) {
        ForwardingConfig config = new ForwardingConfig(null);
        config.setTemplate(template);
        return config;
    }

    @Test
    public void testSubstitutesAllPlaceholders() {
        ForwardingConfig config = configWithTemplate(
                "from=%from% text=%text% sent=%sentStamp% recv=%receivedStamp% sim=%sim%");

        long before = System.currentTimeMillis();
        String result = config.prepareMessage("alice", "hi", "sim2", 42L);
        long after = System.currentTimeMillis();

        assertTrue(result, result.contains("from=alice "));
        assertTrue(result, result.contains(" text=hi "));
        assertTrue(result, result.contains(" sent=42 "));
        assertTrue(result, result.endsWith(" sim=sim2"));

        // %receivedStamp% is filled with the current time at call time.
        Matcher matcher = Pattern.compile("recv=(\\d+)").matcher(result);
        assertTrue("receivedStamp not found in: " + result, matcher.find());
        long received = Long.parseLong(matcher.group(1));
        assertTrue(received >= before && received <= after);
    }

    @Test
    public void testEscapesJsonInText() {
        ForwardingConfig config = configWithTemplate("\"text\":\"%text%\"");

        String result = config.prepareMessage("123", "he said \"hi\"", "sim1", 0L);

        // The embedded quotes must be JSON-escaped so the template stays valid JSON.
        assertEquals("\"text\":\"he said \\\"hi\\\"\"", result);
    }

    @Test
    public void testDollarSignInTextIsLiteral() {
        // %text% is wrapped in Matcher.quoteReplacement, so a '$' in the SMS body
        // must not be interpreted as a regex group reference.
        ForwardingConfig config = configWithTemplate("[%text%]");

        String result = config.prepareMessage("123", "$100", "sim1", 0L);

        assertEquals("[$100]", result);
    }

    @Test
    public void testBackslashInTextIsEscapedAndLiteral() {
        ForwardingConfig config = configWithTemplate("[%text%]");

        String result = config.prepareMessage("123", "a\\b", "sim1", 0L);

        // escapeJson turns the backslash into "\\"; quoteReplacement keeps it literal.
        assertEquals("[a\\\\b]", result);
    }

    @Test
    public void testPlaceholderInSmsBodyIsNotResubstituted() {
        // %text% is substituted last, so a placeholder-looking SMS body is left as-is.
        ForwardingConfig config = configWithTemplate("from=%from% text=%text%");

        String result = config.prepareMessage("alice", "call %from% now", "sim1", 0L);

        assertEquals("from=alice text=call %from% now", result);
    }

    @Test
    public void testDollarSignInSenderIsLiteral() {
        // Alphanumeric SMS sender IDs can contain '$'. %from% is not regex-escaped
        // by the template author, so prepareMessage must quote the replacement to
        // avoid a regex group reference (which would throw IndexOutOfBoundsException).
        ForwardingConfig config = configWithTemplate("from=%from%");

        String result = config.prepareMessage("ab$1cd", "hi", "sim1", 0L);

        assertEquals("from=ab$1cd", result);
    }

    @Test
    public void testDollarSignInSimIsLiteral() {
        ForwardingConfig config = configWithTemplate("sim=%sim%");

        String result = config.prepareMessage("123", "hi", "$im$1", 0L);

        assertEquals("sim=$im$1", result);
    }

    @Test
    public void testNewlineInTextIsEscaped() {
        ForwardingConfig config = configWithTemplate("[%text%]");

        String result = config.prepareMessage("123", "line1\nline2", "sim1", 0L);

        assertEquals("[line1\\nline2]", result);
    }

    @Test
    public void testRegexExtractsCapturingGroup() {
        // With a capturing group, group 1 is forwarded, not the whole match.
        ForwardingConfig config = configWithTemplate("[%Regex=code is (\\d+)%]");

        String result = config.prepareMessage("123", "Your code is 456789", "sim1", 0L);

        assertEquals("[456789]", result);
    }

    @Test
    public void testRegexWithoutGroupReturnsWholeMatch() {
        // No capturing group -> the whole match is used (must not throw on group(1)).
        ForwardingConfig config = configWithTemplate("[%Regex=\\d+%]");

        String result = config.prepareMessage("123", "amount 4200 usd", "sim1", 0L);

        assertEquals("[4200]", result);
    }

    @Test
    public void testRegexNoMatchReturnsEmpty() {
        ForwardingConfig config = configWithTemplate("[%Regex=(\\d+)%]");

        String result = config.prepareMessage("123", "no digits here", "sim1", 0L);

        assertEquals("[]", result);
    }

    @Test
    public void testInvalidRegexDoesNotCrashAndReturnsEmpty() {
        // An unbalanced character class is an invalid pattern; it must be ignored
        // rather than throwing PatternSyntaxException out of prepareMessage.
        ForwardingConfig config = configWithTemplate("[%Regex=[1-0]%]");

        String result = config.prepareMessage("123", "anything 5", "sim1", 0L);

        assertEquals("[]", result);
    }

    @Test
    public void testRegexExtractedValueIsJsonEscaped() {
        // A captured value containing a quote must be JSON-escaped so the body
        // stays valid JSON.
        ForwardingConfig config = configWithTemplate("\"code\":\"%Regex=is (.+)%\"");

        String result = config.prepareMessage("123", "is \"x\"", "sim1", 0L);

        assertEquals("\"code\":\"\\\"x\\\"\"", result);
    }

    @Test
    public void testRegexExtractedDollarSignIsLiteral() {
        // A '$' in the captured value must not be treated as a regex group ref.
        ForwardingConfig config = configWithTemplate("[%Regex=amount (.+)%]");

        String result = config.prepareMessage("123", "amount $100", "sim1", 0L);

        assertEquals("[$100]", result);
    }

    @Test
    public void testMultipleRegexPlaceholdersAreIndependent() {
        // Each %Regex=...% uses its own pattern, not the first one for all.
        ForwardingConfig config = configWithTemplate(
                "a=%Regex=a=(\\d+)% b=%Regex=b=(\\d+)%");

        String result = config.prepareMessage("123", "a=11 b=22", "sim1", 0L);

        assertEquals("a=11 b=22", result);
    }

    @Test
    public void testRegexIsCaseSensitiveByDefault() {
        ForwardingConfig config = configWithTemplate("[%Regex=CODE (\\d+)%]");

        String result = config.prepareMessage("123", "code 999", "sim1", 0L);

        // Default is case-sensitive, so "CODE" does not match "code" -> empty.
        assertEquals("[]", result);
    }

    @Test
    public void testRegexInlineCaseInsensitiveFlag() {
        ForwardingConfig config = configWithTemplate("[%Regex=(?i)CODE (\\d+)%]");

        String result = config.prepareMessage("123", "code 999", "sim1", 0L);

        assertEquals("[999]", result);
    }

    @Test
    public void testRegexCanMatchLiteralPercentWhenEscaped() {
        // \% is an escaped percent: it does not end the pattern and matches a
        // literal % in the message.
        ForwardingConfig config = configWithTemplate("[%Regex=(\\d+)\\%%]");

        String result = config.prepareMessage("123", "Sale: 50% off", "sim1", 0L);

        assertEquals("[50]", result);
    }

    @Test
    public void testUnescapedPercentStillTerminatesPattern() {
        // The first unescaped % ends the pattern, so "(\d+)" is the regex here and
        // the following % is the closing delimiter.
        ForwardingConfig config = configWithTemplate("[%Regex=(\\d+)%]");

        String result = config.prepareMessage("123", "value 77", "sim1", 0L);

        assertEquals("[77]", result);
    }

    @Test
    public void testRegexCanMatchLiteralBackslashWhenEscaped() {
        // \\ is an escaped backslash and matches a literal backslash in the body.
        ForwardingConfig config = configWithTemplate("[%Regex=a(\\\\)b%]");

        String result = config.prepareMessage("123", "a\\b", "sim1", 0L);

        assertEquals("[\\\\]", result);
    }

    @Test
    public void testRegexCoexistsWithTextPlaceholder() {
        ForwardingConfig config = configWithTemplate(
                "{\"text\":\"%text%\",\"code\":\"%Regex=(\\d+)%\"}");

        String result = config.prepareMessage("123", "code 4242", "sim1", 0L);

        assertEquals("{\"text\":\"code 4242\",\"code\":\"4242\"}", result);
    }

    @Test
    public void testBareStampsAreEpochMillis() {
        // Without "=<format>" the stamps stay bare epoch millis (unchanged).
        ForwardingConfig config = configWithTemplate("sent=%sentStamp% recv=%receivedStamp%");

        long before = System.currentTimeMillis();
        String result = config.prepareMessage("123", "hi", "sim1", 1717761600000L);
        long after = System.currentTimeMillis();

        assertTrue(result, result.startsWith("sent=1717761600000 recv="));
        Matcher matcher = Pattern.compile("recv=(\\d+)").matcher(result);
        assertTrue(result, matcher.find());
        long received = Long.parseLong(matcher.group(1));
        assertTrue(received >= before && received <= after);
    }

    @Test
    public void testSentStampWithFormat() throws java.text.ParseException {
        // "=<format>" applies a SimpleDateFormat pattern to the sent time, rendered
        // in the device's local timezone. Parsing the output back with the same
        // pattern must yield the original instant regardless of the test machine's
        // timezone (the offset token XXX round-trips it).
        String pattern = "yyyy-MM-dd'T'HH:mm:ssXXX";
        ForwardingConfig config = configWithTemplate("%sentStamp=" + pattern + "%");

        // 2024-06-07 12:00:00 UTC.
        String result = config.prepareMessage("123", "hi", "sim1", 1717761600000L);

        java.text.SimpleDateFormat parser =
                new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
        assertEquals(1717761600000L, parser.parse(result).getTime());
    }

    @Test
    public void testReceivedStampWithFormatIsNotEpoch() {
        ForwardingConfig config = configWithTemplate("%receivedStamp=yyyy%");

        String result = config.prepareMessage("123", "hi", "sim1", 0L);

        // A 4-digit year, not a 13-digit epoch-millis number.
        assertTrue(result, result.matches("\\d{4}"));
    }

    @Test
    public void testInvalidStampFormatDoesNotCrashAndReturnsEmpty() {
        // An unterminated quote is an invalid SimpleDateFormat pattern; it must be
        // ignored rather than throwing IllegalArgumentException out of the method.
        ForwardingConfig config = configWithTemplate("[%sentStamp=yyyy'unterminated%]");

        String result = config.prepareMessage("123", "hi", "sim1", 1717761600000L);

        assertEquals("[]", result);
    }

    @Test
    public void testBareAndFormattedStampsCoexist() {
        ForwardingConfig config = configWithTemplate(
                "epoch=%sentStamp% year=%sentStamp=yyyy%");

        String result = config.prepareMessage("123", "hi", "sim1", 1717761600000L);

        assertTrue(result, result.startsWith("epoch=1717761600000 year="));
        assertTrue(result, result.matches("epoch=1717761600000 year=\\d{4}"));
    }

    @Test
    public void testVersionPlaceholderUsesBuildConfig() {
        // %version% needs no context; it always resolves to the app version name.
        ForwardingConfig config = configWithTemplate("v=%version%");

        String result = config.prepareMessage("123", "hi", "sim1", 0L);

        assertEquals("v=" + BuildConfig.VERSION_NAME, result);
    }

    @Test
    public void testDeviceHealthPlaceholdersFallBackWithoutContext() {
        // With a null context (as in these JVM tests) DeviceInfo returns its
        // fallbacks rather than touching Android APIs, so the placeholders still
        // substitute to well-formed values.
        ForwardingConfig config = configWithTemplate(
                "battery=%battery% power=%power% network=%network%");

        String result = config.prepareMessage("123", "hi", "sim1", 0L);

        assertEquals("battery=-1 power=unknown network=none", result);
    }
}
