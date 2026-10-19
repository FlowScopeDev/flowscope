package dev.flowscope.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class FlowScopeVersionTest {
    @Test
    void readsTheGeneratedVersionEntry() {
        assertEquals("1.2.3", FlowScopeVersion.parse("version=1.2.3\n"));
    }

    @Test
    void toleratesCommentsBlankLinesAndSurroundingSpace() {
        assertEquals("1.0.0", FlowScopeVersion.parse("# generated\n\n  version = 1.0.0  \n"));
    }

    @Test
    void fallsBackWhenTheEntryIsMissingEmptyOrNull() {
        assertEquals(FlowScopeVersion.UNKNOWN, FlowScopeVersion.parse(""));
        assertEquals(FlowScopeVersion.UNKNOWN, FlowScopeVersion.parse("name=flowscope\n"));
        assertEquals(FlowScopeVersion.UNKNOWN, FlowScopeVersion.parse("version=\n"));
        assertEquals(FlowScopeVersion.UNKNOWN, FlowScopeVersion.parse(null));
    }

    /**
     * The build passes {@code flowscope.version} in as a system property, so this fails both when
     * the generated resource drops off the classpath and when it disagrees with
     * {@code gradle.properties} — the whole point of generating it.
     */
    @Test
    void reportsTheVersionTheBuildGenerated() {
        String expected = System.getProperty("flowscope.expectedVersion");
        assertNotNull(expected, "the test task must pass -Dflowscope.expectedVersion");
        assertEquals(expected, FlowScopeVersion.current());
    }
}
