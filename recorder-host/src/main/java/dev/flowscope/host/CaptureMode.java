package dev.flowscope.host;

public enum CaptureMode {
    ZERO_CODE("zero-code"),
    INSTRUMENTED("instrumented");

    private final String cliName;

    CaptureMode(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    public static CaptureMode parse(String value) {
        for (CaptureMode mode : values()) {
            if (mode.cliName.equalsIgnoreCase(value)) return mode;
        }
        throw new IllegalArgumentException(
            "Unknown mode: " + value + ". Expected zero-code or instrumented");
    }
}
