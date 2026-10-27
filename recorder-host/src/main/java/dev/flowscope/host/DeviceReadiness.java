package dev.flowscope.host;

import java.util.List;
import java.util.regex.Pattern;

/** Read-only diagnostics: never wakes, unlocks or switches the user's device. */
final class DeviceReadiness {
    private DeviceReadiness() {}

    static void beforeSetup(AdbClient adb, Device device, FlowTest test) throws Exception {
        boolean deviceWork = test.steps().stream().anyMatch(step -> FlowTestRunner.uiStep(step)
            || java.util.Set.of("launch", "open-deep-link", "install-apk").contains(step.action()));
        if (!deviceWork) return;
        boolean foreground = false;
        for (FlowTest.Step step : test.steps()) {
            if (java.util.Set.of("launch", "open-deep-link").contains(step.action())) break;
            if (FlowTestRunner.uiStep(step)) { foreground = true; break; }
        }
        check(adb, device.serial(), test.packageName(), foreground);
    }

    static void check(AdbClient adb, String serial, String packageName, boolean foreground) throws Exception {
        String policy = adb.run(List.of("-s", serial, "shell", "dumpsys", "window", "policy")).stdout();
        String power = adb.run(List.of("-s", serial, "shell", "dumpsys", "power")).stdout();
        String failure = stateFailure(policy, power);
        if (failure != null) throw new IllegalStateException(failure);
        if (foreground) {
            // Window focus takes precedence: a VPN/password dialog can cover a resumed activity.
            String windows = adb.run(List.of("-s", serial, "shell", "dumpsys", "window", "windows")).stdout();
            String actual = AdbClient.parseForegroundPackage(windows);
            if (actual == null) {
                String displays = adb.run(List.of("-s", serial, "shell", "dumpsys", "window", "displays")).stdout();
                actual = AdbClient.parseForegroundPackage(displays);
            }
            if (actual == null) actual = adb.foregroundPackage(serial);
            if (actual != null && !actual.equals(packageName)) {
                throw new IllegalStateException("foreground is " + actual + "; expected " + packageName);
            }
        }
    }

    static String stateFailure(String policy, String power) {
        if (Pattern.compile("(?:mShowingLockscreen|mKeyguardShowing|isStatusBarKeyguard|showing|mShowing)=true\\b")
            .matcher(policy).find()) return "device is locked";
        if (Pattern.compile("(?:mWakefulness=(?:Asleep|Dozing)|mInteractive=false|Display Power: state=OFF)\\b")
            .matcher(power).find()) return "device screen is off";
        return null;
    }
}
