package dev.flowscope.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON reader for Chrome DevTools payloads, including fractional and exponent numbers. */
final class CdpJson {
    private CdpJson() {}

    static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.value();
        parser.space();
        if (parser.has()) throw parser.error("unexpected content after the top-level value");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) { this.text = text; }
        boolean has() { return index < text.length(); }
        char peek() { return text.charAt(index); }
        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid CDP JSON at character " + (index + 1) + ": " + message);
        }
        void space() { while (has() && Character.isWhitespace(peek())) index++; }

        Object value() {
            space();
            if (!has()) throw error("expected a value");
            return switch (peek()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> keyword("true", Boolean.TRUE);
                case 'f' -> keyword("false", Boolean.FALSE);
                case 'n' -> keyword("null", null);
                default -> {
                    if (peek() == '-' || Character.isDigit(peek())) yield number();
                    throw error("unexpected character");
                }
            };
        }

        Map<String, Object> object() {
            index++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (has() && peek() == '}') { index++; return result; }
            while (true) {
                space();
                if (!has() || peek() != '"') throw error("expected a quoted key");
                String key = string();
                space();
                if (!has() || text.charAt(index++) != ':') throw error("expected ':'");
                if (result.put(key, value()) != null) throw error("duplicate key: " + key);
                space();
                if (!has()) throw error("unterminated object");
                char next = text.charAt(index++);
                if (next == '}') return result;
                if (next != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            index++;
            List<Object> result = new ArrayList<>();
            space();
            if (has() && peek() == ']') { index++; return result; }
            while (true) {
                result.add(value());
                space();
                if (!has()) throw error("unterminated array");
                char next = text.charAt(index++);
                if (next == ']') return result;
                if (next != ',') throw error("expected ',' or ']'");
            }
        }

        String string() {
            if (text.charAt(index++) != '"') throw error("expected string");
            StringBuilder out = new StringBuilder();
            while (has()) {
                char c = text.charAt(index++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (!has()) throw error("unterminated escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) throw error("short unicode escape");
                        try { out.append((char) Integer.parseInt(text.substring(index, index + 4), 16)); }
                        catch (NumberFormatException invalid) { throw error("invalid unicode escape"); }
                        index += 4;
                    }
                    default -> throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        Object number() {
            int start = index;
            if (peek() == '-') index++;
            while (has() && Character.isDigit(peek())) index++;
            boolean decimal = false;
            if (has() && peek() == '.') {
                decimal = true; index++;
                while (has() && Character.isDigit(peek())) index++;
            }
            if (has() && (peek() == 'e' || peek() == 'E')) {
                decimal = true; index++;
                if (has() && (peek() == '+' || peek() == '-')) index++;
                while (has() && Character.isDigit(peek())) index++;
            }
            String raw = text.substring(start, index);
            try { return decimal ? Double.parseDouble(raw) : Long.parseLong(raw); }
            catch (NumberFormatException invalid) { throw error("invalid number"); }
        }

        Object keyword(String expected, Object value) {
            if (!text.startsWith(expected, index)) throw error("invalid keyword");
            index += expected.length();
            return value;
        }
    }
}
