package dev.flowscope.host;

/** Author-written public display metadata, never a runtime expression or action argument. */
final class FlowLabel {
    private FlowLabel() {}
    static String parse(String value, int line) {
        if (value == null) return "";
        if (value.isBlank() || value.length() > 200 || value.contains("${")
            || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new FlowTestParser.ParseError(line,
                "label must be 1–200 characters of literal, nonblank text, without expressions or control characters");
        }
        return value;
    }
    static String json(String value) {
        return value == null || value.isEmpty() ? "" : ",\"label\":" + Json.quote(value);
    }
}
