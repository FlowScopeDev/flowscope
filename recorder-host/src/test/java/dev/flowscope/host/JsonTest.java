package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesNestedObjectsAndArrays() {
        Map<String, Object> parsed = Json.parseObject("""
            {
              "name": "Example",
              "schemaVersion": 1,
              "defaults": {"package": "com.example.debug", "serial": null},
              "suites": ["a.suite", "b.suite"],
              "enabled": true,
              "disabled": false
            }
            """);

        assertEquals("Example", parsed.get("name"));
        assertEquals(1L, parsed.get("schemaVersion"));
        assertEquals(Boolean.TRUE, parsed.get("enabled"));
        assertEquals(Boolean.FALSE, parsed.get("disabled"));
        assertEquals(List.of("a.suite", "b.suite"), parsed.get("suites"));

        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) parsed.get("defaults");
        assertEquals("com.example.debug", defaults.get("package"));
        assertTrue(defaults.containsKey("serial"));
        assertNull(defaults.get("serial"));
    }

    @Test
    void preservesKeyOrder() {
        Map<String, Object> parsed = Json.parseObject("{\"b\": 1, \"a\": 2, \"c\": 3}");
        assertEquals(List.of("b", "a", "c"), List.copyOf(parsed.keySet()));
    }

    @Test
    void parsesEmptyObjectAndArray() {
        Map<String, Object> parsed = Json.parseObject("{\"o\": {}, \"a\": []}");
        assertEquals(Map.of(), parsed.get("o"));
        assertEquals(List.of(), parsed.get("a"));
    }

    @Test
    void parsesEveryStringEscape() {
        Map<String, Object> parsed = Json.parseObject(
            "{\"v\": \"q\\\" b\\\\ s\\/ \\b \\f \\n \\r \\t u\\u0041\"}");
        assertEquals("q\" b\\ s/ \b \f \n \r \t uA", parsed.get("v"));
    }

    @Test
    void parsesSurrogatePairEscape() {
        Map<String, Object> parsed = Json.parseObject("{\"v\": \"\\uD83D\\uDE00\"}");
        assertEquals("\uD83D\uDE00", parsed.get("v"));
        assertEquals(2, ((String) parsed.get("v")).length());
    }

    @Test
    void parsesNegativeAndZeroIntegers() {
        Map<String, Object> parsed = Json.parseObject("{\"a\": -42, \"b\": 0, \"c\": 10000}");
        assertEquals(-42L, parsed.get("a"));
        assertEquals(0L, parsed.get("b"));
        assertEquals(10000L, parsed.get("c"));
    }

    @Test
    void quoteRoundTripsThroughParse() {
        String awkward = "line\nbreak \"quoted\" back\\slash tab\there bell\u0007 emoji \uD83D\uDE00";
        Map<String, Object> parsed = Json.parseObject("{\"v\": " + Json.quote(awkward) + "}");
        assertEquals(awkward, parsed.get("v"));
    }

    @Test
    void rejectsTrailingCommaInObject() {
        assertTrue(error("{\"a\": 1,}").contains("trailing comma in an object"));
    }

    @Test
    void rejectsTrailingCommaInArray() {
        assertTrue(error("{\"a\": [1,]}").contains("trailing comma in an array"));
    }

    @Test
    void rejectsDuplicateKey() {
        assertTrue(error("{\"a\": 1, \"a\": 2}").contains("duplicate key: a"));
    }

    @Test
    void rejectsUnquotedKey() {
        assertTrue(error("{a: 1}").contains("expected a quoted key"));
    }

    @Test
    void rejectsSingleQuotedString() {
        assertTrue(error("{\"a\": 'x'}").contains("single-quoted strings"));
    }

    @Test
    void rejectsComments() {
        assertTrue(error("{\"a\": 1} // note").contains("comments are not valid JSON"));
    }

    @Test
    void rejectsContentAfterTopLevelObject() {
        assertTrue(error("{\"a\": 1} {\"b\": 2}")
            .contains("unexpected content after the top-level object"));
    }

    @Test
    void rejectsNonObjectTopLevel() {
        assertTrue(error("[1, 2]").contains("expected a JSON object"));
        assertTrue(error("").contains("expected a JSON object"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertTrue(error("{\"a\": \"open}").contains("unterminated string"));
    }

    @Test
    void rejectsUnterminatedObject() {
        assertTrue(error("{\"a\": 1").contains("unterminated object"));
    }

    @Test
    void rejectsFractionalAndExponentNumbers() {
        assertTrue(error("{\"a\": 1.5}").contains("number must be an integer"));
        assertTrue(error("{\"a\": 1e3}").contains("number must be an integer"));
    }

    @Test
    void rejectsLeadingZeroAndLeadingPlus() {
        assertTrue(error("{\"a\": 007}").contains("leading zero"));
        assertTrue(error("{\"a\": +7}").contains("unexpected character"));
    }

    @Test
    void rejectsRawControlCharacterInString() {
        assertTrue(error("{\"a\": \"x\ny\"}").contains("raw control character"));
    }

    @Test
    void rejectsInvalidEscape() {
        assertTrue(error("{\"a\": \"\\q\"}").contains("invalid escape"));
    }

    @Test
    void reportsLineAndColumn() {
        Json.FormatError failure = assertThrows(Json.FormatError.class,
            () -> Json.parseObject("{\n  \"a\": 1,\n  \"a\": 2\n}"));
        assertEquals(3, failure.line());
        assertTrue(failure.getMessage().startsWith("line 3 column "));
    }

    @Test
    void accessorsReadTypedValues() {
        Map<String, Object> object = Json.parseObject(
            "{\"s\": \"text\", \"n\": 7, \"b\": true, \"o\": {\"k\": \"v\"}}");

        assertEquals("text", Json.requireString(object, "s", "root"));
        assertEquals("text", Json.optionalString(object, "s", "root"));
        assertEquals(7L, Json.optionalInteger(object, "n", "root"));
        assertEquals(Boolean.TRUE, Json.optionalBoolean(object, "b", "root"));
        assertEquals("v", Json.optionalObject(object, "o", "root").get("k"));

        assertNull(Json.optionalString(object, "absent", "root"));
        assertNull(Json.optionalInteger(object, "absent", "root"));
        assertNull(Json.optionalBoolean(object, "absent", "root"));
        assertNull(Json.optionalObject(object, "absent", "root"));
    }

    @Test
    void accessorsReportThePathOnFailure() {
        Map<String, Object> object = Json.parseObject("{\"mode\": 1}");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
            () -> Json.requireString(object, "name", "flowscope.json"));
        assertEquals("flowscope.json.name: is required", missing.getMessage());

        IllegalArgumentException wrongType = assertThrows(IllegalArgumentException.class,
            () -> Json.optionalString(object, "mode", "defaults"));
        assertEquals("defaults.mode: must be a string", wrongType.getMessage());
    }

    @Test
    void rejectUnknownKeysNamesTheOffender() {
        Map<String, Object> object = Json.parseObject("{\"name\": \"x\", \"nmae\": \"y\"}");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Json.rejectUnknownKeys(object, Set.of("name"), "flowscope.json"));
        assertTrue(failure.getMessage().startsWith("flowscope.json.nmae: unknown key"));
    }

    private static String error(String text) {
        return assertThrows(IllegalArgumentException.class, () -> Json.parseObject(text))
            .getMessage();
    }
}
