package dev.flowscope.host;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes the deliberately restricted host-side actions shared by tests and suites. */
final class HostRuntime {
    private static final Pattern OFFSET = Pattern.compile("([+-]?)(\\d+)([dhms])");
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final RuntimeValues values;
    private final FlowHttpClient http;
    private final Path sourceDirectory;
    private final Clock clock;
    private final SecureRandom random;

    HostRuntime(RuntimeValues values, FlowHttpClient http, Path sourceDirectory) {
        this(values, http, sourceDirectory, Clock.systemUTC(), new SecureRandom());
    }

    HostRuntime(RuntimeValues values, FlowHttpClient http, Path sourceDirectory,
        Clock clock, SecureRandom random) {
        this.values = values; this.http = http;
        this.sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
        this.clock = clock; this.random = random;
    }

    Map<String, String> execute(String action, Map<String, String> raw, long timeout) throws Exception {
        boolean secretInput = raw.values().stream().anyMatch(values::isSecretExpression);
        Map<String, String> args = expanded(raw);
        return switch (action) {
            case "secret" -> bindSecret(args);
            case "set-value" -> setValue(args, secretInput);
            case "set-date" -> setDate(args, secretInput);
            case "transform" -> transform(args, secretInput);
            case "api-request" -> apiRequest(args, timeout);
            default -> throw new IllegalArgumentException("Not a host runtime action: " + action);
        };
    }

    Map<String, String> expanded(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, values.interpolate(value)));
        return result;
    }

    private Map<String, String> bindSecret(Map<String, String> args) {
        values.bindSecret(args.get("name"), args.get("source"));
        return Map.of("variable", args.get("name"), "source", args.get("source"), "value", "hidden");
    }

    private Map<String, String> setValue(Map<String, String> args, boolean secretInput) {
        String generated = args.get("generate"), value;
        if (generated == null) value = args.get("value");
        else value = switch (generated) {
            case "uuid" -> randomUuid();
            case "integer" -> randomInteger(args);
            case "string" -> randomString(integer(args, "length", 16, 1, 1024));
            case "email" -> randomEmail(args);
            default -> throw new IllegalArgumentException("Unsupported generator: " + generated);
        };
        values.put(args.get("name"), value, secretInput);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("variable", args.get("name"));
        details.put("operation", generated == null ? "literal" : generated);
        details.put("value", "hidden");
        return Map.copyOf(details);
    }

    private String randomInteger(Map<String, String> args) {
        int min = integer(args, "min", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        int max = integer(args, "max", Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (max < min) throw new IllegalArgumentException("set-value max must be at least min");
        long range = (long) max - min + 1;
        return Long.toString(min + random.nextLong(range));
    }

    private String randomString(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(ALPHANUM.charAt(random.nextInt(ALPHANUM.length())));
        return result.toString();
    }

    private String randomEmail(Map<String, String> args) {
        String prefix = args.getOrDefault("prefix", "flowscope");
        String domain = args.getOrDefault("domain", "example.invalid");
        if (!prefix.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("email prefix contains unsupported characters");
        }
        if (!domain.matches("[A-Za-z0-9.-]+") || !domain.contains(".")) {
            throw new IllegalArgumentException("email domain must be a hostname");
        }
        return prefix + "+" + randomString(12) + "@" + domain;
    }

    private String randomUuid() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static int integer(Map<String, String> args, String key, int fallback, int min, int max) {
        if (!args.containsKey(key)) return fallback;
        int value;
        try { value = Integer.parseInt(args.get(key)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be an integer"); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " is out of range");
        return value;
    }

    private Map<String, String> setDate(Map<String, String> args, boolean secretInput) {
        ZoneId zone;
        try { zone = ZoneId.of(args.getOrDefault("zone", "UTC")); }
        catch (Exception invalid) { throw new IllegalArgumentException("Unknown timezone: " + args.get("zone")); }
        ZonedDateTime value = parseDate(args.get("value"), zone);
        if (args.containsKey("offset")) value = offset(value, args.get("offset"));
        if (args.containsKey("start-of")) value = boundary(value, args.get("start-of"), false);
        if (args.containsKey("end-of")) value = boundary(value, args.get("end-of"), true);
        String formatted = formatDate(value, args.get("format"));
        values.put(args.get("name"), formatted, secretInput);
        return Map.of("variable", args.get("name"), "operation", "date",
            "format", args.get("format"), "zone", zone.getId(), "value", "hidden");
    }

    private ZonedDateTime parseDate(String raw, ZoneId zone) {
        if (raw.equals("now")) return ZonedDateTime.now(clock).withZoneSameInstant(zone);
        try { return Instant.parse(raw).atZone(zone); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(raw).atZoneSameInstant(zone); } catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(raw).atStartOfDay(zone); } catch (DateTimeParseException ignored) {}
        throw new IllegalArgumentException("set-date value must be now or an ISO date/time");
    }

    private static ZonedDateTime offset(ZonedDateTime value, String raw) {
        Matcher shortOffset = OFFSET.matcher(raw);
        if (shortOffset.matches()) {
            long amount = Long.parseLong(shortOffset.group(2));
            if (shortOffset.group(1).equals("-")) amount = -amount;
            return switch (shortOffset.group(3)) {
                case "d" -> value.plusDays(amount);
                case "h" -> value.plusHours(amount);
                case "m" -> value.plusMinutes(amount);
                default -> value.plusSeconds(amount);
            };
        }
        try { return value.plus(Period.parse(raw)); } catch (DateTimeParseException ignored) {}
        try { return value.plus(Duration.parse(raw)); } catch (DateTimeParseException ignored) {}
        throw new IllegalArgumentException("Invalid date offset");
    }

    private static ZonedDateTime boundary(ZonedDateTime value, String unit, boolean end) {
        ZonedDateTime start = switch (unit) {
            case "day" -> value.toLocalDate().atStartOfDay(value.getZone());
            case "week" -> value.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(value.getZone());
            case "month" -> value.withDayOfMonth(1).toLocalDate().atStartOfDay(value.getZone());
            default -> throw new IllegalArgumentException("Unknown date boundary");
        };
        if (!end) return start;
        return switch (unit) {
            case "day" -> start.plusDays(1).minusNanos(1);
            case "week" -> start.plusWeeks(1).minusNanos(1);
            default -> start.plusMonths(1).minusNanos(1);
        };
    }

    private static String formatDate(ZonedDateTime value, String format) {
        return switch (format) {
            case "iso-instant" -> DateTimeFormatter.ISO_INSTANT.format(value.toInstant());
            case "iso-offset" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
            case "epoch-seconds" -> Long.toString(value.toEpochSecond());
            case "epoch-millis" -> Long.toString(value.toInstant().toEpochMilli());
            default -> {
                try { yield DateTimeFormatter.ofPattern(format, Locale.ROOT).format(value); }
                catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("Invalid date format pattern");
                }
            }
        };
    }

    private Map<String, String> transform(Map<String, String> args, boolean secretInput) {
        String input = args.get("from");
        String output = switch (args.get("operation")) {
            case "trim" -> input.strip();
            case "lowercase" -> input.toLowerCase(Locale.ROOT);
            case "uppercase" -> input.toUpperCase(Locale.ROOT);
            case "url-encode" -> URLEncoder.encode(input, StandardCharsets.UTF_8);
            case "base64-encode" -> Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
            case "substring" -> substring(input, args);
            case "prefix" -> args.getOrDefault("value", "") + input;
            case "suffix" -> input + args.getOrDefault("value", "");
            default -> throw new IllegalArgumentException("Unsupported transform operation");
        };
        values.put(args.get("name"), output, secretInput);
        return Map.of("variable", args.get("name"), "operation", args.get("operation"), "value", "hidden");
    }

    private static String substring(String input, Map<String, String> args) {
        int start = integer(args, "start", 0, 0, input.length());
        int end = integer(args, "end", input.length(), start, input.length());
        return input.substring(start, end);
    }

    private Map<String, String> apiRequest(Map<String, String> args, long timeout) throws Exception {
        String method = args.getOrDefault("method", "GET").toUpperCase(Locale.ROOT);
        URI uri = withQuery(URI.create(args.get("url")), args);
        if (!Set.of("http", "https").contains(uri.getScheme())) {
            throw new IllegalArgumentException("api-request URL must use http or https");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        args.forEach((key, value) -> { if (key.startsWith("header.")) headers.put(key.substring(7), value); });
        String body = args.get("body");
        if (args.containsKey("body-file")) body = values.interpolate(Files.readString(
            resolveRelativeFile(args.get("body-file")), StandardCharsets.UTF_8));
        long interval = args.containsKey("retry-interval") ? Long.parseLong(args.get("retry-interval")) : 0;
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        Exception last = null;
        do {
            try {
                long remaining = Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
                FlowHttpClient.Response response = http.send(method, uri, headers, body, remaining);
                assertResponse(response, args);
                List<String> captured = capture(response, args);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("method", method); details.put("url", values.redact(safeUrl(uri)));
                details.put("statusCode", Integer.toString(response.status()));
                details.put("durationMillis", Long.toString(response.durationMillis()));
                if (!headers.isEmpty()) details.put("headerNames", String.join(",", headers.keySet()));
                List<String> queryNames = args.keySet().stream().filter(key -> key.startsWith("query."))
                    .map(key -> key.substring(6)).toList();
                if (!queryNames.isEmpty()) details.put("queryNames", String.join(",", queryNames));
                if (!captured.isEmpty()) details.put("captured", String.join(",", captured));
                return Map.copyOf(details);
            } catch (Exception failure) {
                last = failure;
                if (interval <= 0 || System.nanoTime() >= deadline) break;
                Thread.sleep(Math.min(interval,
                    Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
            }
        } while (System.nanoTime() < deadline);
        throw last == null ? new IllegalStateException("API request failed") : last;
    }

    private static URI withQuery(URI uri, Map<String, String> args) {
        List<Map.Entry<String, String>> additions = args.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("query.")).toList();
        if (additions.isEmpty()) return uri;
        Set<String> existing = new HashSet<>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            existing.add(java.net.URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8));
        }
        StringBuilder query = new StringBuilder(uri.getRawQuery() == null ? "" : uri.getRawQuery());
        for (var entry : additions) {
            String name = entry.getKey().substring(6);
            if (!existing.add(name)) throw new IllegalArgumentException("Duplicate API query name: " + name);
            if (!query.isEmpty()) query.append('&');
            query.append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        try {
            StringBuilder result = new StringBuilder(uri.getScheme()).append("://")
                .append(uri.getRawAuthority()).append(uri.getRawPath() == null ? "" : uri.getRawPath())
                .append('?').append(query);
            if (uri.getRawFragment() != null) result.append('#').append(uri.getRawFragment());
            return URI.create(result.toString());
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid API URL"); }
    }

    private void assertResponse(FlowHttpClient.Response response, Map<String, String> args) {
        if (args.containsKey("expect-status")) {
            Set<Integer> expected = new HashSet<>();
            for (String value : args.get("expect-status").split(",")) expected.add(Integer.parseInt(value.strip()));
            if (!expected.contains(response.status())) throw new IllegalStateException(
                "Expected HTTP " + args.get("expect-status") + " but received " + response.status());
        }
        if (args.containsKey("expect-duration-max")
            && response.durationMillis() > Long.parseLong(args.get("expect-duration-max"))) {
            throw new IllegalStateException("API response exceeded expected duration");
        }
        for (var assertion : args.entrySet()) {
            if (assertion.getKey().startsWith("expect-header.")) {
                String name = assertion.getKey().substring(14);
                String actual = header(response, name);
                if (!Objects.equals(actual, assertion.getValue())) {
                    throw new IllegalStateException("HTTP header " + name + " did not match expected value");
                }
            }
        }
        boolean needsJson = args.keySet().stream().anyMatch(key -> key.startsWith("expect-json"));
        Object json = needsJson ? ApiJson.parse(response.body()) : null;
        for (var assertion : args.entrySet()) assertJson(json, assertion.getKey(), assertion.getValue());
    }

    private static void assertJson(Object json, String key, String expected) {
        String prefix = List.of("expect-json-exists.", "expect-json-length.", "expect-json-gt.",
            "expect-json-gte.", "expect-json-lt.", "expect-json-lte.", "expect-json-prefix.",
            "expect-json-suffix.", "expect-json.").stream().filter(key::startsWith).findFirst().orElse(null);
        if (prefix == null) return;
        String path = key.substring(prefix.length());
        boolean exists = jsonPathOrMissing(json, path) != Missing.VALUE;
        if (prefix.equals("expect-json-exists.")) {
            if (exists != Boolean.parseBoolean(expected)) throw mismatch(path); return;
        }
        if (!exists) throw new IllegalStateException("JSON path not found: " + path);
        Object actual = jsonPathOrMissing(json, path);
        boolean matches = switch (prefix) {
            case "expect-json." -> scalar(actual).equals(expected);
            case "expect-json-length." -> length(actual) == Integer.parseInt(expected);
            case "expect-json-prefix." -> scalar(actual).startsWith(expected);
            case "expect-json-suffix." -> scalar(actual).endsWith(expected);
            case "expect-json-gt." -> number(actual).compareTo(new java.math.BigDecimal(expected)) > 0;
            case "expect-json-gte." -> number(actual).compareTo(new java.math.BigDecimal(expected)) >= 0;
            case "expect-json-lt." -> number(actual).compareTo(new java.math.BigDecimal(expected)) < 0;
            case "expect-json-lte." -> number(actual).compareTo(new java.math.BigDecimal(expected)) <= 0;
            default -> false;
        };
        if (!matches) throw mismatch(path);
    }

    private static IllegalStateException mismatch(String path) {
        return new IllegalStateException("JSON field " + path + " did not match expected value");
    }

    private List<String> capture(FlowHttpClient.Response response, Map<String, String> args) {
        List<String> names = new ArrayList<>();
        Object json = null;
        for (var entry : args.entrySet()) {
            boolean normal = entry.getKey().startsWith("capture.");
            boolean secret = entry.getKey().startsWith("capture-secret.");
            if (!normal && !secret) continue;
            if (json == null) json = ApiJson.parse(response.body());
            String name = entry.getKey().substring(secret ? 15 : 8);
            values.put(name, scalar(jsonPath(json, entry.getValue())), secret);
            names.add(name);
        }
        for (var entry : args.entrySet()) {
            boolean normal = entry.getKey().startsWith("capture-header.");
            boolean secret = entry.getKey().startsWith("capture-secret-header.");
            if (!normal && !secret) continue;
            String name = entry.getKey().substring(secret ? 22 : 15);
            String value = header(response, entry.getValue());
            if (value == null) throw new IllegalStateException("HTTP header " + entry.getValue() + " was not present");
            values.put(name, value, secret);
            names.add(name);
        }
        return names;
    }

    private static String header(FlowHttpClient.Response response, String name) {
        return response.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
    }

    private enum Missing { VALUE }
    private static Object jsonPathOrMissing(Object root, String path) {
        try { return jsonPath(root, path); } catch (IllegalStateException missing) { return Missing.VALUE; }
    }

    static Object jsonPath(Object root, String path) {
        Object current = root;
        for (String segment : path.replaceFirst("^\\$\\.", "").split("\\.")) {
            Matcher part = Pattern.compile("([^\\[]+)(?:\\[(\\d+)])?").matcher(segment);
            if (!part.matches() || !(current instanceof Map<?, ?> map) || !map.containsKey(part.group(1))) {
                throw new IllegalStateException("JSON path not found: " + path);
            }
            current = map.get(part.group(1));
            if (part.group(2) != null) {
                if (!(current instanceof List<?> list)) throw new IllegalStateException("JSON path is not an array: " + path);
                int index = Integer.parseInt(part.group(2));
                if (index >= list.size()) throw new IllegalStateException("JSON array index out of range: " + path);
                current = list.get(index);
            }
        }
        return current;
    }

    private static String scalar(Object value) {
        if (value == null) return "null";
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalStateException("JSON path does not resolve to a scalar value");
    }

    private static int length(Object value) {
        if (value instanceof String string) return string.length();
        if (value instanceof List<?> list) return list.size();
        throw new IllegalStateException("JSON length assertion requires a string or array");
    }

    private static java.math.BigDecimal number(Object value) {
        if (!(value instanceof Number number)) throw new IllegalStateException("JSON numeric assertion requires a number");
        return new java.math.BigDecimal(number.toString());
    }

    Path resolveRelativeFile(String raw) {
        Path relative = Path.of(raw);
        if (relative.isAbsolute() || raw.contains("\\")) throw new IllegalArgumentException("body-file must be a relative path");
        for (Path segment : relative) if (segment.toString().equals("..")) {
            throw new IllegalArgumentException("body-file must not contain '..'");
        }
        Path resolved = sourceDirectory.resolve(relative).normalize();
        if (!resolved.startsWith(sourceDirectory)) throw new IllegalArgumentException("body-file escapes the test directory");
        return resolved;
    }

    static String safeUrl(URI uri) {
        StringBuilder result = new StringBuilder(uri.getScheme()).append("://").append(uri.getAuthority());
        if (uri.getPath() != null) result.append(uri.getPath());
        if (uri.getRawQuery() != null) {
            List<String> names = java.util.Arrays.stream(uri.getRawQuery().split("&"))
                .map(part -> part.split("=", 2)[0]).toList();
            result.append("?").append(String.join("&", names.stream().map(name -> name + "={redacted}").toList()));
        }
        return result.toString();
    }
}
