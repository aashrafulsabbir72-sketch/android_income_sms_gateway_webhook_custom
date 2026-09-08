package tech.bogomolov.incomingsmsgateway;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * Instrumented tests for {@link DeviceInfo}, the %version%/%battery%/%power%/
 * %network% data points (issue #39). These read real device state (battery
 * sticky broadcast, ConnectivityManager), so they run on a device/emulator
 * rather than the local JVM. They assert the values are well-formed rather than
 * exact, since the emulator's battery/network state is environment-dependent.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceInfoTest {

    private final Context context =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void testVersionIsNotEmpty() {
        String version = DeviceInfo.getVersion();
        assertNotNull(version);
        assertTrue(!version.isEmpty());
    }

    @Test
    public void testBatteryPercentageInRange() {
        int battery = DeviceInfo.getBatteryPercentage(context);
        // -1 means "unknown"; otherwise it must be a valid 0-100 percentage.
        assertTrue("battery out of range: " + battery, battery == -1 || (battery >= 0 && battery <= 100));
    }

    @Test
    public void testPowerSourceIsKnownValue() {
        List<String> allowed = Arrays.asList("ac", "usb", "wireless", "unplugged", "unknown");
        String power = DeviceInfo.getPowerSource(context);
        assertTrue("unexpected power source: " + power, allowed.contains(power));
    }

    @Test
    public void testNetworkTypeIsKnownValue() {
        List<String> allowed = Arrays.asList("wifi", "mobile", "ethernet", "other", "none");
        String network = DeviceInfo.getNetworkType(context);
        assertTrue("unexpected network type: " + network, allowed.contains(network));
    }
}
