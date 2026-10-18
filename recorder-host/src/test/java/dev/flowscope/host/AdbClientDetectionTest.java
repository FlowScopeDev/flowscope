package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdbClientDetectionTest {

    @Test
    void readsTopResumedActivity() {
        String output = """
              mResumedActivity=null
              topResumedActivity=ActivityRecord{252146507 u0 com.example.app/com.example.app.MainActivity t261}
            """;
        assertEquals("com.example.app", AdbClient.parseForegroundPackage(output));
    }

    @Test
    void fallsBackToCurrentFocus() {
        String output = """
              mCurrentFocus=null
              mCurrentFocus=Window{91e0e90 u0 com.sec.android.app.launcher/com.sec.android.app.launcher.activities.LauncherActivity}
            """;
        assertEquals("com.sec.android.app.launcher", AdbClient.parseForegroundPackage(output));
    }

    @Test
    void ignoresNullFocusLines() {
        assertNull(AdbClient.parseForegroundPackage("  mCurrentFocus=null\n  mResumedActivity=null\n"));
        assertNull(AdbClient.parseForegroundPackage(""));
        assertNull(AdbClient.parseForegroundPackage(null));
    }

    @Test
    void readsDebuggableFlag() {
        assertTrue(AdbClient.parseDebuggable(
            "    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA LARGE_HEAP ]"));
        // A bare hex flags line must not be mistaken for the symbolic one.
        assertFalse(AdbClient.parseDebuggable("    flags=0x0"));
        assertFalse(AdbClient.parseDebuggable("    flags=[ HAS_CODE ]"));
    }

    @Test
    void creditsAgentEventsOnlyToTheOwningPid() {
        String logcat = """
            10-17 22:41:46.424 20514 20514 I FlowScopeAgent: {"kind":"activity.lifecycle"}
            10-17 22:41:47.000 30999 30999 I FlowScopeAgent: {"kind":"ui.tap"}
            """;
        assertTrue(AdbClient.agentSeenForPid(logcat, "20514"));
        assertTrue(AdbClient.agentSeenForPid(logcat, "30999"));
        // A different app's agent must not mark this package as instrumented.
        assertFalse(AdbClient.agentSeenForPid(logcat, "11111"));
    }

    @Test
    void handlesEmptyLogcat() {
        assertFalse(AdbClient.agentSeenForPid("", "20514"));
        assertFalse(AdbClient.agentSeenForPid(null, "20514"));
        assertFalse(AdbClient.agentSeenForPid("10-17 ... I FlowScopeAgent: {}", null));
    }

    @Test
    void picksTheHighestSupportedRefreshRate() {
        // Shape taken from a real device (SM-A075F, Android 16).
        String output = "  mSupportedRefreshRates=[90.0, 60.0]\n";
        assertEquals(90, AdbClient.parseRefreshHz(output));
    }

    @Test
    void fallsBackToRenderFrameRate() {
        assertEquals(90, AdbClient.parseRefreshHz("DisplayDeviceInfo{..., renderFrameRate 90.0, ...}"));
    }

    @Test
    void clampsToASaneCaptureRange() {
        assertEquals(30, AdbClient.parseRefreshHz("mSupportedRefreshRates=[24.0]"));
        assertEquals(90, AdbClient.parseRefreshHz("mSupportedRefreshRates=[144.0, 120.0]"));
    }

    @Test
    void defaultsWhenTheDeviceCannotBeQueried() {
        assertEquals(AdbClient.DEFAULT_REFRESH_HZ, AdbClient.parseRefreshHz(""));
        assertEquals(AdbClient.DEFAULT_REFRESH_HZ, AdbClient.parseRefreshHz(null));
        assertEquals(AdbClient.DEFAULT_REFRESH_HZ, AdbClient.parseRefreshHz("nothing useful here"));
    }

    @Test
    void ignoresMalformedEntries() {
        assertEquals(60, AdbClient.parseRefreshHz("mSupportedRefreshRates=[abc, 60.0]"));
    }

    @Test
    void readsPhysicalDisplaySize() {
        assertArrayEquals(new int[] {720, 1600}, AdbClient.parseDisplaySize("Physical size: 720x1600"));
    }

    @Test
    void anOverrideSizeWins() {
        String output = "Physical size: 1440x3200\nOverride size: 720x1600\n";
        assertArrayEquals(new int[] {720, 1600}, AdbClient.parseDisplaySize(output));
    }

    @Test
    void returnsNullWhenUnavailable() {
        assertNull(AdbClient.parseDisplaySize(""));
        assertNull(AdbClient.parseDisplaySize(null));
        assertNull(AdbClient.parseDisplaySize("adb: device not found"));
    }
}
