package dev.flowscope.host;

/**
 * JSON reader for API responses. API payloads may contain fractional and exponent numbers, unlike
 * FlowScope's deliberately integer-only project configuration format.
 */
final class ApiJson {
    private ApiJson() {}

    static Object parse(String text) {
        try {
            return CdpJson.parse(text);
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            if (message != null) message = message.replace("CDP JSON", "API JSON");
            throw new IllegalArgumentException(message == null ? "invalid API JSON" : message, invalid);
        }
    }
}
