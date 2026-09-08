package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Pure-JVM unit tests for {@link DeviceInfo}'s null-context contract. The battery
 * and network getters short-circuit to their fallbacks before touching any
 * Android API when given a null context, which is what lets
 * {@link ForwardingConfig#prepareMessage} stay callable without a real Context
 * (e.g. these tests, and the dialog's preview path). The real-device values are
 * covered by the instrumented {@code DeviceInfoTest}.
 */
public class DeviceInfoNullContextTest {

    @Test
    public void testVersionNeedsNoContext() {
        assertNotNull(DeviceInfo.getVersion());
        assertFalse(DeviceInfo.getVersion().isEmpty());
    }

    @Test
    public void testBatteryPercentageFallsBackToMinusOne() {
        assertEquals(-1, DeviceInfo.getBatteryPercentage(null));
    }

    @Test
    public void testPowerSourceFallsBackToUnknown() {
        assertEquals("unknown", DeviceInfo.getPowerSource(null));
    }

    @Test
    public void testNetworkTypeFallsBackToNone() {
        assertEquals("none", DeviceInfo.getNetworkType(null));
    }
}
