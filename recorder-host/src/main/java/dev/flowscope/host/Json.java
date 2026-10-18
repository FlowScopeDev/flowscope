package dev.flowscope.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal JSON writer and reader. FlowScope has no JSON dependency on purpose: the host CLI must
 * stay runnable from a plain JDK, so the one structured format it reads — `flowscope.json` — is
 * parsed by a deliberately small, strict subset here rather than by a library.
 *
 * <p>Accepted: objects, arrays, strings, integers, {@code true}, {@code false}, {@code null}, and
 * arbitrary nesting. Rejected with a located message: trailing commas, comments, single quotes,
 * unquoted keys, duplicate keys, fractional or exponent numbers, raw control characters inside a
 * string, and any content after the top-level value. Strictness is affordable because the format is
 * versioned — a typo must surface rather than be silently dropped.
 */
final class Json {
    private Json() {}

    static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    /**
     * An invalid JSON document. Extends {@link IllegalArgumentException} so it reaches the CLI's
     * exit code 2 through the existing handler in {@link Main} with no extra plumbing.
     */
    static final class FormatError extends IllegalArgumentException {
        private final int line;
        private final int column;

        FormatError(int line, int column, String message) {
            super("line " + line + " column " + column + ": " + message);
            this.line = line;
            this.column = column;
        }

        int line() {
            return line;
        }

        int column() {
            return column;
        }
    }

    /** Parses a complete document whose top-level value must be an object. */
    static Map<String, Object> parseObject(String text) {
        Parser check = new Parser(text);
        check.skipWhitespace();
        if (!check.has() || check.peek() != '{') throw check.error("expected a JSON object");
        Object value = parse(text);
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) object;
        return typed;
    }

    /** Parses any complete JSON value; API assertions may target object or array responses. */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        if (!parser.has()) throw parser.error("expected a JSON value");
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.has()) throw parser.error("unexpected content after the top-level object");
        return value;
    }

    // ---- typed accessors; `path` prefixes messages so the caller reads as a schema ----

    static String requireString(Map<String, Object> object, String key, String path) {
        Object raw = object.get(key);
        if (raw == null) throw typeError(path, key, "is required");
        if (!(raw instanceof String text)) throw typeError(path, key, "must be a string");
        return text;
    }

    static String optionalString(Map<String, Object> object, String key, String path) {
        Object raw = object.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String text)) throw typeError(path, key, "must be a string");
        return text;
    }

    static Long optionalInteger(Map<String, Object> object, String key, String path) {
        Object raw = object.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Long number)) throw typeError(path, key, "must be an integer");
        return number;
    }

    static Boolean optionalBoolean(Map<String, Object> object, String key, String path) {
        Object raw = object.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Boolean flag)) throw typeError(path, key, "must be true or false");
        return flag;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> optionalObject(Map<String, Object> object, String key, String path) {
        Object raw = object.get(key);
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> nested)) throw typeError(path, key, "must be an object");
        return (Map<String, Object>) nested;
    }

    static void rejectUnknownKeys(Map<String, Object> object, Set<String> allowed, String path) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                    qualify(path, key) + ": unknown key. Expected one of: "
                        + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
    }

    private static IllegalArgumentException typeError(String path, String key, String requirement) {
        return new IllegalArgumentException(qualify(path, key) + ": " + requirement);
    }

    private static String qualify(String path, String key) {
        return path == null || path.isEmpty() ? key : path + "." + key;
    }

    /** Recursive-descent reader over one document. Tracks line/column for every message. */
    private static final class Parser {
        private final String text;
        private int index;
        private int line = 1;
        private int column = 1;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        boolean has() {
            return index < text.length();
        }

        char peek() {
            return text.charAt(index);
        }

        FormatError error(String message) {
            return new FormatError(line, column, message);
        }

        private char advance() {
            char c = text.charAt(index++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            return c;
        }

        void skipWhitespace() {
            while (has()) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') advance();
                else if (c == '/') throw error("comments are not valid JSON");
                else return;
            }
        }

        private void expect(char expected, String what) {
            skipWhitespace();
            if (!has() || peek() != expected) throw error("expected '" + expected + "' " + what);
            advance();
        }

        Map<String, Object> readObject() {
            expect('{', "to start an object");
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (has() && peek() == '}') {
                advance();
                return result;
            }
            while (true) {
                skipWhitespace();
                if (!has()) throw error("unterminated object");
                if (peek() != '"') throw error("expected a quoted key");
                String key = readString();
                expect(':', "after a key");
                Object value = readValue();
                if (result.put(key, value) != null) throw error("duplicate key: " + key);
                skipWhitespace();
                if (!has()) throw error("unterminated object");
                char next = advance();
                if (next == '}') return result;
                if (next != ',') throw error("expected ',' or '}' in an object");
                skipWhitespace();
                if (has() && peek() == '}') throw error("trailing comma in an object");
            }
        }

        private List<Object> readArray() {
            expect('[', "to start an array");
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (has() && peek() == ']') {
                advance();
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (!has()) throw error("unterminated array");
                char next = advance();
                if (next == ']') return result;
                if (next != ',') throw error("expected ',' or ']' in an array");
                skipWhitespace();
                if (has() && peek() == ']') throw error("trailing comma in an array");
            }
        }

        Object readValue() {
            skipWhitespace();
            if (!has()) throw error("expected a value");
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readKeyword("true", Boolean.TRUE);
                case 'f' -> readKeyword("false", Boolean.FALSE);
                case 'n' -> readKeyword("null", null);
                case '\'' -> throw error("single-quoted strings are not valid JSON");
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield readInteger();
                    throw error("unexpected character: " + describe(c));
                }
            };
        }

        private Object readKeyword(String keyword, Object value) {
            if (!text.startsWith(keyword, index)) throw error("expected " + keyword);
            for (int i = 0; i < keyword.length(); i++) advance();
            return value;
        }

        /**
         * Integers only. The only numeric field in any FlowScope JSON is a millisecond timeout, so
         * accepting a double would mean silently truncating it somewhere later.
         */
        private Long readInteger() {
            int startLine = line;
            int startColumn = column;
            StringBuilder digits = new StringBuilder();
            if (peek() == '-') digits.append(advance());
            if (!has() || peek() < '0' || peek() > '9') {
                throw new FormatError(startLine, startColumn, "expected a number");
            }
            boolean leadingZero = peek() == '0';
            while (has() && peek() >= '0' && peek() <= '9') digits.append(advance());
            if (leadingZero && digits.length() > (digits.charAt(0) == '-' ? 2 : 1)) {
                throw new FormatError(startLine, startColumn, "number must not have a leading zero");
            }
            if (has() && (peek() == '.' || peek() == 'e' || peek() == 'E')) {
                throw new FormatError(startLine, startColumn, "number must be an integer");
            }
            try {
                return Long.valueOf(digits.toString());
            } catch (NumberFormatException tooLarge) {
                throw new FormatError(startLine, startColumn, "number is out of range");
            }
        }

        private String readString() {
            expect('"', "to start a string");
            StringBuilder out = new StringBuilder();
            while (true) {
                if (!has()) throw error("unterminated string");
                char c = advance();
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("raw control character in a string: " + describe(c));
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (!has()) throw error("unterminated escape");
                char escape = advance();
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(readUnicodeEscape());
                    default -> throw error("invalid escape: \\" + escape);
                }
            }
        }

        private char readUnicodeEscape() {
            if (index + 4 > text.length()) throw error("truncated \\u escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char digit = advance();
                int nibble = Character.digit(digit, 16);
                if (nibble < 0) throw error("invalid \\u escape digit: " + digit);
                value = value * 16 + nibble;
            }
            return (char) value;
        }

        private String describe(char c) {
            return c < 0x20 || c > 0x7e ? String.format("U+%04X", (int) c) : "'" + c + "'";
        }
    }
}
