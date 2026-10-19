package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlowSuiteParserTest {
    @Test
    void parsesHostOnlyLifecycleAndRequirements() {
        FlowSuite suite = FlowSuiteParser.parseSource("""
            flowscope-suite 1
            name "API suite"
            require secret.API_JWT
            before set-value name=id generate=uuid
            before api-request method=GET url="https://example.com/health" \\
              expect-status=200
            test tests/a.flow
            after api-request method=DELETE url="https://example.com/${var.id}" if-var=id
            """, "suite.suite");

        assertEquals(1, suite.requirements().size());
        assertEquals(3, suite.hooks().size());
        assertEquals(1, suite.entries().size());
    }

    @Test
    void rejectsDeviceActionsInSuiteHooks() {
        assertThrows(FlowTestParser.ParseError.class, () -> FlowSuiteParser.parseSource("""
            flowscope-suite 1
            before clear-app-data
            test tests/a.flow
            """, "suite.suite"));
    }
    @Test
    void parsesAFullSuite() {
        FlowSuite suite = parse("""
            flowscope-suite 1
            name "Nightly regression"
            continue-on-failure true

            # the happy path first
            test tests/login.flow
            test tests/checkout/pay.flow
            """);

        assertEquals("Nightly regression", suite.name());
        assertTrue(suite.continueOnFailure());
        assertEquals(List.of("tests/login.flow", "tests/checkout/pay.flow"),
            suite.entries().stream().map(FlowSuite.Entry::reference).toList());
    }

    @Test
    void recordsTheLineThatRequestedEachTest() {
        FlowSuite suite = parse("""
            flowscope-suite 1

            test tests/a.flow
            test tests/b.flow
            """);
        assertEquals(List.of(3, 4),
            suite.entries().stream().map(FlowSuite.Entry::line).toList());
    }

    @Test
    void defaultsToTheFileNameAndStopOnFailure() {
        FlowSuite suite = FlowSuiteParser.parseSource(
            "flowscope-suite 1\ntest tests/a.flow\n", "regression.suite");
        assertEquals("regression.suite", suite.name());
        assertFalse(suite.continueOnFailure());
    }

    @Test
    void allowsTheSameTestTwice() {
        FlowSuite suite = parse("""
            flowscope-suite 1
            test tests/login.flow
            test tests/login.flow
            """);
        assertEquals(2, suite.entries().size());
    }

    @Test
    void laterDirectivesOverrideEarlierOnesAsInFlow() {
        FlowSuite suite = parse("""
            flowscope-suite 1
            name "First"
            test tests/a.flow
            name "Second"
            continue-on-failure true
            """);
        assertEquals("Second", suite.name());
        assertTrue(suite.continueOnFailure());
    }

    @Test
    void requiresTheVersionHeaderFirst() {
        assertEquals("Line 1: first statement must be: flowscope-suite 1",
            error("test tests/a.flow\n"));
        assertEquals("Line 1: first statement must be: flowscope-suite 1",
            error("flowscope 1\ntest tests/a.flow\n"));
        assertEquals("Line 1: first statement must be: flowscope-suite 1",
            error("flowscope-suite 2\ntest tests/a.flow\n"));
    }

    @Test
    void reportsAnEmptySuiteAndOneWithNoTests() {
        assertEquals("Empty suite: empty.suite",
            assertThrows(FlowTestParser.ParseError.class,
                () -> FlowSuiteParser.parseSource("\n# nothing\n", "empty.suite")).getMessage());
        assertEquals("Suite has no tests: bare.suite",
            assertThrows(FlowTestParser.ParseError.class,
                () -> FlowSuiteParser.parseSource(
                    "flowscope-suite 1\nname \"Bare\"\n", "bare.suite")).getMessage());
    }

    @Test
    void rejectsUnknownStatementsWithTheirLine() {
        FlowTestParser.ParseError failure = assertThrows(FlowTestParser.ParseError.class,
            () -> parse("""
                flowscope-suite 1
                test tests/a.flow
                retry 3
                """));
        assertEquals(3, failure.line());
        assertEquals("unknown statement: retry", failure.detail());
    }

    @Test
    void rejectsReferencesThatOnlyWorkOnTheAuthorsMachine() {
        assertTrue(error("flowscope-suite 1\ntest /abs/login.flow\n").contains("must be relative"));
        assertTrue(error("flowscope-suite 1\ntest C:/app/login.flow\n").contains("must be relative"));
        assertTrue(error("flowscope-suite 1\ntest tests\\login.flow\n")
            .contains("must use forward slashes"));
        assertTrue(error("flowscope-suite 1\ntest ../escape.flow\n").contains("must not contain '..'"));
        assertTrue(error("flowscope-suite 1\ntest tests/login\n").contains("must end in .flow"));
    }

    @Test
    void rejectsAMalformedTestStatement() {
        assertTrue(error("flowscope-suite 1\ntest\n").contains("test requires exactly one path"));
        assertTrue(error("flowscope-suite 1\ntest a.flow b.flow\n")
            .contains("test requires exactly one path"));
    }

    @Test
    void rejectsANonBooleanContinueOnFailure() {
        assertEquals("Line 2: continue-on-failure must be true or false",
            error("flowscope-suite 1\ncontinue-on-failure maybe\n"));
        assertEquals("Line 2: continue-on-failure must be true or false",
            error("flowscope-suite 1\ncontinue-on-failure yes\n"));
        assertEquals("Line 2: continue-on-failure must be true or false",
            error("flowscope-suite 1\ncontinue-on-failure 1\n"));
    }

    @Test
    void sharesTheFlowTokenizerSoQuotingBehavesIdentically() {
        assertEquals("Terms & \"Policies\"", parse("""
            flowscope-suite 1
            name "Terms & \\"Policies\\""
            test tests/a.flow
            """).name());
        assertTrue(error("flowscope-suite 1\nname \"unclosed\ntest tests/a.flow\n")
            .contains("unterminated quoted value"));
    }

    private static FlowSuite parse(String source) {
        return FlowSuiteParser.parseSource(source, "nightly.suite");
    }

    private static String error(String source) {
        return assertThrows(FlowTestParser.ParseError.class, () -> parse(source)).getMessage();
    }
}
