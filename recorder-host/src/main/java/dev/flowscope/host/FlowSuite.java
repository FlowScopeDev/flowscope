package dev.flowscope.host;

import java.util.List;

/**
 * An ordered list of `.flow` tests to replay in one run. References are relative to the project's
 * flows directory and are resolved by {@link FlowProject#resolveSuiteEntry(String)}.
 */
public record FlowSuite(String name, boolean continueOnFailure, List<Entry> entries) {
    /** {@code line} is the suite line that requested the test, so a CI log can point back at it. */
    public record Entry(int line, String reference) {}
}
