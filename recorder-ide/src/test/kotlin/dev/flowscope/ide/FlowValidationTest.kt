package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowValidationTest {

    private val valid = """
        flowscope 1
        name "Launch"
        package com.example.debug
        timeout 10000

        step launch
        step tap id="btn_continue"
    """.trimIndent()

    @Test
    fun `accepts a well formed test`() {
        val result = validateFlow(valid)
        assertTrue(result.isValid)
        assertNull(result.errorDetail)
        assertEquals(2, result.stepCount)
    }

    @Test
    fun `validates and groups lifecycle instructions`() {
        val source = """
            flowscope 1
            package com.example.debug
            before clear-app-data
            before api-request method=GET url="${'$'}{env.API_BASE_URL}/health" expect-status=200
            step launch
            after screenshot name=final
            after force-stop
        """.trimIndent()

        val result = validateFlow(source)
        val lines = flowStepLines(source)
        assertTrue(result.isValid)
        assertEquals(5, result.stepCount)
        assertEquals(listOf("before", "before", "step", "after", "after"), lines.map { it.phase })
        assertEquals("Call host API", describeStep(lines[1].body))
        assertEquals("Compute a runtime date", describeStep("before set-date name=today value=now format=yyyy-MM-dd"))
        assertEquals("Create a runtime value", describeStep("before set-value name=id generate=uuid"))
        assertEquals("Transform a runtime value", describeStep("before transform name=x from=y operation=trim"))
        assertEquals("Bind a secret value", describeStep("before secret name=jwt source=API_JWT"))
    }

    @Test
    fun `reports the failing line for a bad step`() {
        val source = """
            flowscope 1
            package com.example.debug
            step tap
        """.trimIndent()
        val result = validateFlow(source)
        assertTrue(!result.isValid)
        assertEquals(3, result.errorLine)
        assertEquals(
            "tap requires id=, text=, text-contains=, desc=, or class=",
            result.errorDetail,
        )
        assertEquals(LineMark.Error, result.marks[2])
    }

    @Test
    fun `missing package is a whole-file error, not a line error`() {
        val result = validateFlow("flowscope 1\nstep launch\n")
        assertTrue(!result.isValid)
        assertNull(result.errorLine)
        assertNotNull(result.errorDetail)
    }

    @Test
    fun `text selectors warn but still parse`() {
        val source = """
            flowscope 1
            package com.example.debug
            step tap text="Continue"
        """.trimIndent()
        val result = validateFlow(source)
        assertTrue(result.isValid, "a text selector is legal")
        assertEquals(LineMark.Warning, result.marks[2], "but it is fragile and should be flagged")
    }

    @Test
    fun `optional tap validates with a selector`() {
        val source = """
            flowscope 1
            package com.example.debug
            step tap-if-visible text="Dismiss" timeout=3000
        """.trimIndent()

        val result = validateFlow(source)

        assertTrue(result.isValid)
        assertEquals(1, result.stepCount)
        assertEquals(LineMark.Warning, result.marks[2])
    }

    @Test
    fun `selectorless actions are not warned about`() {
        val source = """
            flowscope 1
            package com.example.debug
            step launch
            step back
            step screenshot name=after
        """.trimIndent()
        val result = validateFlow(source)
        assertTrue(result.isValid)
        listOf(2, 3, 4).forEach { assertEquals(LineMark.Valid, result.marks[it], "line ${it + 1}") }
    }

    @Test
    fun `long timeouts are flagged`() {
        val source = """
            flowscope 1
            package com.example.debug
            timeout 120000
            step launch
        """.trimIndent()
        val result = validateFlow(source)
        assertTrue(result.isValid)
        assertEquals(LineMark.Warning, result.marks[2])
    }

    @Test
    fun `comments and blank lines carry no mark`() {
        val source = """
            flowscope 1
            # a comment

            package com.example.debug
            step launch
        """.trimIndent()
        val result = validateFlow(source)
        assertEquals(LineMark.None, result.marks[1])
        assertEquals(LineMark.None, result.marks[2])
    }

    @Test
    fun `marks array always matches the line count`() {
        val source = "flowscope 1\npackage com.example.debug\nstep launch\n\n\n"
        assertEquals(source.split("\n").size, validateFlow(source).marks.size)
    }

    @Test
    fun `highlighting preserves the source text exactly`() {
        // The editor shows a VisualTransformation; if it altered length, the caret would desync.
        listOf(valid, "", "# just a comment", "step tap id=\"a b\"").forEach { source ->
            assertEquals(source, highlight(source).text, "round trip for: $source")
        }
    }
}

class StepEditingTest {

    private val source = """
        flowscope 1
        package com.example.debug
        step launch
        step tap id="a"
        step back
    """.trimIndent()

    @Test
    fun `finds every step including disabled ones`() {
        val withDisabled = source.replace("step back", "# step back")
        val steps = flowStepLines(withDisabled)
        assertEquals(3, steps.size)
        assertTrue(steps[0].enabled)
        assertTrue(!steps[2].enabled)
        assertEquals("step back", steps[2].body)
    }

    @Test
    fun `moving swaps with the neighbouring step, not the neighbouring line`() {
        val moved = moveStep(source, lineIndex = 3, delta = -1)
        val steps = flowStepLines(moved).map { it.body }
        assertEquals(listOf("""step tap id="a"""", "step launch", "step back"), steps)
    }

    @Test
    fun `moving past either end is a no-op`() {
        assertEquals(source, moveStep(source, lineIndex = 2, delta = -1))
        assertEquals(source, moveStep(source, lineIndex = 4, delta = 1))
    }

    @Test
    fun `duplicate inserts directly below`() {
        val steps = flowStepLines(duplicateStep(source, 2)).map { it.body }
        assertEquals(listOf("step launch", "step launch", """step tap id="a"""", "step back"), steps)
    }

    @Test
    fun `delete removes only that step`() {
        val steps = flowStepLines(deleteStep(source, 3)).map { it.body }
        assertEquals(listOf("step launch", "step back"), steps)
    }

    @Test
    fun `toggle round trips through a comment`() {
        val off = toggleStep(source, 2)
        assertTrue(!flowStepLines(off).first().enabled)
        assertEquals(source, toggleStep(off, 2))
    }

    @Test
    fun `disabled steps are excluded from the parsed test`() {
        val off = toggleStep(source, 3)
        assertEquals(2, validateFlow(off).stepCount)
    }

    @Test
    fun `describes steps in plain language`() {
        assertEquals("Launch the app under test", describeStep("step launch"))
        assertEquals("Tap btn_continue", describeStep("""step tap id="btn_continue""""))
        assertEquals("Scroll down", describeStep("step scroll direction=down"))
        assertEquals("Swipe left from carousel", describeStep("step swipe direction=left id=carousel"))
        assertEquals("Swipe up", describeStep("step swipe direction=up"))
        assertEquals("Scroll until Order total is visible", describeStep("""step scroll-to text="Order total""""))
        assertEquals("Long-press Row", describeStep("step long-press text=Row"))
        assertEquals("Clear the text in email", describeStep("step clear-text id=email"))
        assertEquals("Press the enter key", describeStep("step press-key key=enter"))
        assertEquals("Hide the keyboard", describeStep("step hide-keyboard"))
        assertEquals("""Type "hello" into field_name""", describeStep("""step type id="field_name" value="hello""""))
        assertEquals("Assert Welcome is visible", describeStep("""step assert-visible text="Welcome""""))
        assertEquals("Assert WebView cookie metadata", describeStep("step web-assert-cookie name=session_id"))
        assertEquals("Assert a native JavaScript bridge", describeStep("step web-assert-interface name=Android"))
        assertEquals("Capture sanitized WebView state", describeStep("step web-snapshot name=checkout"))
    }
}

class SuiteValidationTest {
    @Test
    fun `accepts a valid suite and counts its tests`() {
        val validation = validateSuite(
            """
            flowscope-suite 1
            name "Nightly regression"
            continue-on-failure true

            test tests/login.flow
            test tests/terms.flow
            """.trimIndent())

        assertTrue(validation.isValid)
        assertNull(validation.errorDetail)
        assertEquals(2, validation.stepCount)
    }

    @Test
    fun `marks a bad header on line one`() {
        val validation = validateSuite("flowscope 1\ntest tests/login.flow\n")

        assertFalse(validation.isValid)
        assertEquals(1, validation.errorLine)
        assertEquals("first statement must be: flowscope-suite 1", validation.errorDetail)
        assertEquals(LineMark.Error, validation.marks[0])
    }

    @Test
    fun `marks an unknown statement on its own line and leaves the rest clean`() {
        val validation = validateSuite(
            """
            flowscope-suite 1
            test tests/login.flow
            retry 3
            """.trimIndent())

        assertEquals(3, validation.errorLine)
        assertEquals("unknown statement: retry", validation.errorDetail)
        assertEquals(LineMark.Valid, validation.marks[0])
        assertEquals(LineMark.Valid, validation.marks[1])
        assertEquals(LineMark.Error, validation.marks[2])
    }

    @Test
    fun `reports a suite with no tests against the whole file`() {
        val validation = validateSuite("flowscope-suite 1\nname \"Empty\"\n")

        assertFalse(validation.isValid)
        assertNull(validation.errorLine)
        assertTrue(validation.errorDetail!!.startsWith("Suite has no tests"))
    }

    @Test
    fun `rejects a reference that would only resolve on the author's machine`() {
        assertEquals("test path must be relative to the flows directory: /abs/login.flow",
            validateSuite("flowscope-suite 1\ntest /abs/login.flow\n").errorDetail)
        assertEquals("test path must end in .flow: tests/login",
            validateSuite("flowscope-suite 1\ntest tests/login\n").errorDetail)
    }

    @Test
    fun `leaves blanks and comments unmarked`() {
        val validation = validateSuite(
            """
            flowscope-suite 1

            # the smoke tests
            test tests/login.flow
            """.trimIndent())

        assertEquals(LineMark.None, validation.marks[1])
        assertEquals(LineMark.None, validation.marks[2])
        assertEquals(LineMark.Valid, validation.marks[3])
    }

    @Test
    fun `routes each buffer to the parser for its own format`() {
        val flow = "flowscope 1\nname \"T\"\npackage com.example.debug\nstep launch\n"
        val suite = "flowscope-suite 1\ntest tests/login.flow\n"

        assertTrue(validateSource(flow, EditorKind.Flow).isValid)
        assertTrue(validateSource(suite, EditorKind.Suite).isValid)
        // Each format is invalid when read as the other one.
        assertFalse(validateSource(suite, EditorKind.Flow).isValid)
        assertFalse(validateSource(flow, EditorKind.Suite).isValid)
    }

    @Test
    fun `highlights suite keywords from the suite word list`() {
        assertEquals(SuiteWords, syntaxWordsFor(EditorKind.Suite))
        assertEquals(FlowWords, syntaxWordsFor(EditorKind.Flow))
        // The highlighter must round-trip the text unchanged whichever word list it uses.
        val suite = "flowscope-suite 1\ntest tests/login.flow\n"
        assertEquals(suite, highlight(suite, DarkFlowColors, SuiteWords).text)
    }

    @Test
    fun `editor keywords match every action the host parser accepts`() {
        val lifecycle = setOf("before", "step", "after")
        assertEquals(
            dev.flowscope.host.FlowTestParser.actions().toSortedSet(),
            (FlowWords.keywords - lifecycle).toSortedSet(),
        )
    }

    @Test
    fun `validates the new gesture actions through the host parser`() {
        val valid = "flowscope 1\npackage com.example.debug\nstep launch"
        val source = valid + "\nstep scroll-to text=\"Total\"\nstep long-press id=row duration=900"
        assertTrue(validateFlow(source).isValid)
        val bad = valid + "\nstep long-press duration=900"
        assertFalse(validateFlow(bad).isValid)
    }
}
