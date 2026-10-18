package dev.flowscope.ide

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectFilesTest {
    @Test
    fun `reads the name directive from a buffer`() {
        assertEquals("Login smoke", testNameFromSource(
            """
            flowscope 1
            name "Login smoke"
            package com.example.debug

            step launch
            """.trimIndent()))
    }

    @Test
    fun `reads an unquoted name and ignores a commented one`() {
        assertEquals("Login smoke", testNameFromSource("flowscope 1\nname Login smoke\n"))
        assertNull(testNameFromSource("flowscope 1\n# name \"Commented\"\nstep launch\n"))
        assertNull(testNameFromSource("flowscope 1\npackage com.example.debug\n"))
        assertNull(testNameFromSource(""))
    }

    @Test
    fun `takes the first name when a buffer has several`() {
        assertEquals("First", testNameFromSource("flowscope 1\nname \"First\"\nname \"Second\"\n"))
    }

    @Test
    fun `turns a test name into a safe file name`() {
        assertEquals("login-smoke.flow", suggestedFileName("Login smoke"))
        assertEquals("terms-policies.flow", suggestedFileName("Terms & Policies"))
        assertEquals("example-debug-launch.flow", suggestedFileName("  Example debug launch  "))
        assertEquals("checkout-pay.flow", suggestedFileName("Checkout → Pay"))
        assertEquals("a1b2.flow", suggestedFileName("a1b2"))
    }

    @Test
    fun `falls back to untitled for a name with nothing usable in it`() {
        assertEquals("untitled.flow", suggestedFileName(null))
        assertEquals("untitled.flow", suggestedFileName(""))
        assertEquals("untitled.flow", suggestedFileName("   "))
        assertEquals("untitled.flow", suggestedFileName("—— ——"))
    }

    @Test
    fun `truncates a very long name`() {
        val name = suggestedFileName("a".repeat(120))
        assertEquals(48 + ".flow".length, name.length)
        assertTrue(name.endsWith(".flow"))
    }

    @Test
    fun `never leaves a trailing hyphen after truncation`() {
        // 48 characters of stem would land exactly on the separator.
        val name = suggestedFileName("x".repeat(48) + " tail")
        assertFalse(name.removeSuffix(".flow").endsWith("-"))
    }

    @Test
    fun `prefixes windows device names so the file is openable there`() {
        assertEquals("flow-con.flow", suggestedFileName("con"))
        assertEquals("flow-nul.flow", suggestedFileName("NUL"))
        assertEquals("flow-com1.flow", suggestedFileName("com1"))
        assertEquals("flow-lpt9.flow", suggestedFileName("LPT9"))
        // Not a device name, so untouched.
        assertEquals("com10.flow", suggestedFileName("com10"))
        assertEquals("console.flow", suggestedFileName("console"))
    }

    @Test
    fun `honours the extension so suites get one too`() {
        assertEquals("nightly-regression.suite",
            suggestedFileName("Nightly regression", "suite"))
    }

    @Test
    fun `leaves a free file name alone and numbers a taken one`() {
        assertEquals("login.flow", uniqueFileName(emptySet(), "login.flow"))
        assertEquals("login-2.flow", uniqueFileName(setOf("login.flow"), "login.flow"))
        assertEquals("login-4.flow",
            uniqueFileName(setOf("login.flow", "login-2.flow", "login-3.flow"), "login.flow"))
        assertEquals("nightly-2.suite",
            uniqueFileName(setOf("nightly.suite"), "nightly.suite"))
    }

    @Test
    fun `numbers a name that has no extension`() {
        assertEquals("login-2", uniqueFileName(setOf("login"), "login"))
    }

    @Test
    fun `expands a leading tilde`() {
        val home = Path.of(System.getProperty("user.home"))
        assertEquals(home, expandUserPath("~"))
        assertEquals(home.resolve("work/app"), expandUserPath("~/work/app"))
        assertEquals(home.resolve("work/app"), expandUserPath("  \"~/work/app\"  "))
        assertEquals(Path.of("/tmp/app"), expandUserPath("/tmp/app"))
    }

    @Test
    fun `canonicalises a buffer the way a save writes it`() {
        assertEquals("flowscope 1\n", flowFileContents("flowscope 1"))
        assertEquals("flowscope 1\n", flowFileContents("flowscope 1\n"))
        assertEquals("flowscope 1\n", flowFileContents("flowscope 1\n\n\n"))
        assertEquals("flowscope 1\n", flowFileContents("flowscope 1   \n  \n"))
    }

    @Test
    fun `a buffer is not dirty immediately after it is saved`() {
        // The regression this guards: `saveTest` writes `trimEnd() + "\n"`, so a buffer whose text
        // lacks a trailing newline would read as dirty the instant it was written.
        val buffer = "flowscope 1\nname \"Login\"\nstep launch"
        val written = flowFileContents(buffer)
        assertFalse(isDirty(buffer, written))
        assertFalse(isDirty(buffer, buffer))
        assertFalse(isDirty("$buffer\n\n", written))
    }

    @Test
    fun `a buffer is dirty once its content really differs`() {
        assertTrue(isDirty("flowscope 1\nstep launch\n", "flowscope 1\n"))
        assertTrue(isDirty("flowscope 1\n", ""))
    }

    @Test
    fun `classifies the open buffer by extension`() {
        assertEquals(EditorKind.Suite, editorKindOf(Path.of("flows/suites/nightly.suite")))
        assertEquals(EditorKind.Flow, editorKindOf(Path.of("flows/tests/login.flow")))
        assertEquals(EditorKind.Flow, editorKindOf(null))
        assertEquals(EditorKind.Flow, editorKindOf(Path.of("notes.txt")))
    }

    @Test
    fun `shows a path inside the project relative to it`() {
        val root = Path.of("/Users/me/app")
        assertEquals("flows/tests/login.flow",
            displayPath(Path.of("/Users/me/app/flows/tests/login.flow"), root))
        assertEquals("reports/20261018-101500-Login_smoke",
            displayPath(Path.of("/Users/me/app/reports/20261018-101500-Login_smoke"), root))
        assertEquals("flowscope.json", displayPath(Path.of("/Users/me/app/flowscope.json"), root))
    }

    @Test
    fun `shows the project root itself as a dot`() {
        val root = Path.of("/Users/me/app")
        assertEquals(".", displayPath(root, root))
    }

    @Test
    fun `falls back to a home-relative path outside the project`() {
        val home = Path.of(System.getProperty("user.home"))
        assertEquals("~/.flowscope/test-runs/run",
            displayPath(home.resolve(".flowscope/test-runs/run"), Path.of("/Users/me/app")))
        assertEquals("~/.flowscope/tests/login.flow",
            displayPath(home.resolve(".flowscope/tests/login.flow"), null))
    }

    @Test
    fun `leaves a path outside both the project and home absolute`() {
        assertEquals("/tmp/scratch/login.flow",
            displayPath(Path.of("/tmp/scratch/login.flow"), Path.of("/Users/me/app")))
    }

    @Test
    fun `a sibling directory sharing a name prefix is not treated as inside the project`() {
        // "/Users/me/app-2" must not be reported relative to "/Users/me/app".
        val shown = displayPath(Path.of("/Users/me/app-2/flows/tests/a.flow"), Path.of("/Users/me/app"))
        assertFalse(shown.startsWith("-2"), shown)
        assertTrue(shown.endsWith("app-2/flows/tests/a.flow"), shown)
    }

    @Test
    fun `normalises before comparing so a dotted path still resolves into the project`() {
        assertEquals("flows/tests/login.flow",
            displayPath(Path.of("/Users/me/app/flows/suites/../tests/login.flow"),
                Path.of("/Users/me/app")))
    }

    @Test
    fun `the home directory itself stays absolute rather than becoming a bare tilde`() {
        val home = Path.of(System.getProperty("user.home"))
        assertEquals(home.toString(), displayPath(home, null))
    }
}

class SuiteReferenceRewriteTest {
    private val suite = """
        flowscope-suite 1
        name "Nightly regression"
        continue-on-failure true

        # the smoke path first
        test tests/login.flow
        test tests/checkout/pay.flow
        test tests/login.flow
    """.trimIndent()

    @Test
    fun `rewrites every occurrence and leaves the rest byte-identical`() {
        val result = rewriteSuiteReference(suite, "tests/login.flow", "tests/sign-in.flow")

        assertEquals(
            """
            flowscope-suite 1
            name "Nightly regression"
            continue-on-failure true

            # the smoke path first
            test tests/sign-in.flow
            test tests/checkout/pay.flow
            test tests/sign-in.flow
            """.trimIndent(),
            result,
        )
    }

    @Test
    fun `leaves a suite that does not reference the file untouched`() {
        assertEquals(suite, rewriteSuiteReference(suite, "tests/absent.flow", "tests/other.flow"))
        assertEquals(suite, rewriteSuiteReference(suite, "tests/login.flow", "tests/login.flow"))
    }

    @Test
    fun `does not touch a commented-out reference`() {
        val commented = "flowscope-suite 1\n# test tests/login.flow\ntest tests/a.flow\n"
        assertEquals(commented,
            rewriteSuiteReference(commented, "tests/login.flow", "tests/renamed.flow"))
    }

    @Test
    fun `does not match a different file that shares a prefix`() {
        val source = "flowscope-suite 1\ntest tests/login-2.flow\ntest tests/login.flow\n"
        val result = rewriteSuiteReference(source, "tests/login.flow", "tests/x.flow")
        assertEquals("flowscope-suite 1\ntest tests/login-2.flow\ntest tests/x.flow\n", result)
    }

    @Test
    fun `preserves indentation`() {
        val indented = "flowscope-suite 1\n    test tests/login.flow\n"
        assertEquals("flowscope-suite 1\n    test tests/renamed.flow\n",
            rewriteSuiteReference(indented, "tests/login.flow", "tests/renamed.flow"))
    }

    @Test
    fun `handles a quoted reference on either side of the rename`() {
        val quoted = "flowscope-suite 1\ntest \"tests/my login.flow\"\n"
        assertEquals("flowscope-suite 1\ntest tests/login.flow\n",
            rewriteSuiteReference(quoted, "tests/my login.flow", "tests/login.flow"))

        val plain = "flowscope-suite 1\ntest tests/login.flow\n"
        assertEquals("flowscope-suite 1\ntest \"tests/my login.flow\"\n",
            rewriteSuiteReference(plain, "tests/login.flow", "tests/my login.flow"))
    }

    @Test
    fun `the rewritten suite still parses and names the new test`() {
        val result = rewriteSuiteReference(suite, "tests/login.flow", "tests/sign-in.flow")
        val parsed = dev.flowscope.host.FlowSuiteParser.parseSource(result, "nightly.suite")

        assertEquals(3, parsed.entries().size)
        assertEquals(
            listOf("tests/sign-in.flow", "tests/checkout/pay.flow", "tests/sign-in.flow"),
            parsed.entries().map { it.reference() },
        )
    }

    @Test
    fun `detects whether a suite references a file`() {
        assertTrue(suiteReferences(suite, "tests/login.flow"))
        assertTrue(suiteReferences(suite, "tests/checkout/pay.flow"))
        assertFalse(suiteReferences(suite, "tests/absent.flow"))
        assertFalse(suiteReferences("flowscope-suite 1\n# test tests/x.flow\n", "tests/x.flow"))
    }
}

/**
 * The on-disk half of a rename: the same read → rewrite → write sequence `repointSuites` performs,
 * asserted against real files and the real suite parser.
 */
class SuiteRepointOnDiskTest {
    @Test
    fun `a renamed test leaves every suite parseable and pointing at the new file`() {
        val root = kotlin.io.path.createTempDirectory("fs-repoint")
        try {
            val flows = root.resolve("flows")
            val tests = java.nio.file.Files.createDirectories(flows.resolve("tests"))
            val suites = java.nio.file.Files.createDirectories(flows.resolve("suites"))

            java.nio.file.Files.writeString(tests.resolve("login.flow"),
                "flowscope 1\nname \"Login\"\npackage com.example.debug\nstep launch\n")
            val nightly = suites.resolve("nightly.suite")
            java.nio.file.Files.writeString(nightly,
                "flowscope-suite 1\nname \"Nightly\"\n\n# smoke first\ntest tests/login.flow\n" +
                    "    test tests/login.flow\n")
            val untouched = suites.resolve("other.suite")
            val untouchedBefore = "flowscope-suite 1\ntest tests/elsewhere.flow\n"
            java.nio.file.Files.writeString(untouched, untouchedBefore)

            // Rename on disk, then repoint exactly as the IDE does.
            java.nio.file.Files.move(tests.resolve("login.flow"), tests.resolve("sign-in.flow"))
            for (suite in listOf(nightly, untouched)) {
                val source = java.nio.file.Files.readString(suite)
                if (!suiteReferences(source, "tests/login.flow")) continue
                java.nio.file.Files.writeString(suite,
                    rewriteSuiteReference(source, "tests/login.flow", "tests/sign-in.flow"))
            }

            val rewritten = java.nio.file.Files.readString(nightly)
            val parsed = dev.flowscope.host.FlowSuiteParser.parseSource(rewritten, "nightly.suite")
            assertEquals(listOf("tests/sign-in.flow", "tests/sign-in.flow"),
                parsed.entries().map { it.reference() })
            assertEquals("Nightly", parsed.name())
            assertTrue(rewritten.contains("# smoke first"))
            assertTrue(rewritten.contains("    test tests/sign-in.flow"))

            // Every reference now resolves to a file that exists.
            for (entry in parsed.entries()) {
                assertTrue(java.nio.file.Files.isRegularFile(flows.resolve(entry.reference())),
                    "dangling reference: ${entry.reference()}")
            }
            // A suite that never named the file is byte-identical.
            assertEquals(untouchedBefore, java.nio.file.Files.readString(untouched))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

class ExistingPathDetectionTest {
    @Test
    fun `recognises an existing directory and file`() {
        val root = kotlin.io.path.createTempDirectory("fs-detect")
        try {
            val file = java.nio.file.Files.writeString(root.resolve("report.json"), "{}")
            assertEquals(root, existingPathIn(root.toString()))
            assertEquals(file, existingPathIn(file.toString()))
            // Surrounding whitespace is incidental, not meaningful.
            assertEquals(root, existingPathIn("  $root  "))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects a path that does not exist`() {
        assertNull(existingPathIn("/definitely/not/here/at/all-12345"))
    }

    @Test
    fun `rejects prose that merely mentions a directory`() {
        val root = kotlin.io.path.createTempDirectory("fs-detect")
        try {
            // The whole string must be the path, or a sentence would become a clickable
            // affordance that reveals the wrong thing.
            assertNull(existingPathIn("Recording stopped $root"))
            assertNull(existingPathIn("$root is where it went"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects relative paths, blanks and multi-line bodies`() {
        assertNull(existingPathIn(""))
        assertNull(existingPathIn("   "))
        assertNull(existingPathIn("flows/tests/login.flow"))
        assertNull(existingPathIn("./build"))
        assertNull(existingPathIn("/tmp\n/tmp"))
    }

    @Test
    fun `rejects a pretty-printed json payload`() {
        // Event payloads render as JSON in the same drawer body; none of it is a path.
        assertNull(existingPathIn("{\n  \"kind\": \"ui.tap\"\n}"))
    }

    // ------------------------------------------------- the file remembered for a project

    @Test
    fun `restores the file remembered for a project`() {
        val root = Path.of("/projects/alpha")
        val recents = listOf(RecentProject(root, "flows/tests/login.flow"))
        assertEquals(
            Path.of("/projects/alpha/flows/tests/login.flow"),
            recentOpenFileFor(root, recents),
        )
    }

    @Test
    fun `has nothing to restore for an unlisted project or a bare entry`() {
        val root = Path.of("/projects/alpha")
        assertNull(recentOpenFileFor(root, emptyList()))
        assertNull(recentOpenFileFor(root, listOf(RecentProject(Path.of("/projects/beta"), "a.flow"))))
        assertNull(recentOpenFileFor(root, listOf(RecentProject(root, null))))
        assertNull(recentOpenFileFor(root, listOf(RecentProject(root, "   "))))
    }

    /**
     * The bug this guards: the IDE used to record the outgoing project's open file against the
     * incoming project's root, so reopening would pull up another project's test.
     */
    @Test
    fun `refuses a remembered file that escapes the project`() {
        val root = Path.of("/projects/alpha")
        assertNull(recentOpenFileFor(root, listOf(RecentProject(root, "../beta/flows/tests/login.flow"))))
        assertNull(recentOpenFileFor(
            root,
            listOf(RecentProject(root, "../../../../Other/flows/tests/terms.flow")),
        ))
        assertNull(recentOpenFileFor(root, listOf(RecentProject(root, "/elsewhere/login.flow"))))
    }

    /** A `..` that stays inside the project is still inside it. */
    @Test
    fun `allows a path that climbs but lands back inside`() {
        val root = Path.of("/projects/alpha")
        assertEquals(
            Path.of("/projects/alpha/flows/tests/login.flow"),
            recentOpenFileFor(root, listOf(RecentProject(root, "flows/suites/../tests/login.flow"))),
        )
    }
}
