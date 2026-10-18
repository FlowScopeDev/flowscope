package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepSelectorTest {

    @Test
    fun `resource id grades highest`() {
        assertEquals(SelectorStability.High, stabilityOfStep("""step tap id="btn_continue""""))
    }

    @Test
    fun `description grades above visible text`() {
        assertEquals(SelectorStability.Medium, stabilityOfStep("""step tap desc="Continue""""))
        assertEquals(SelectorStability.Low, stabilityOfStep("""step tap text="Continue""""))
        assertEquals(
            SelectorStability.Low,
            stabilityOfStep("""step tap text-contains="Terms & Policies""""),
        )
    }

    @Test
    fun `class only or coordinate is not durable`() {
        assertEquals(
            SelectorStability.Unresolved,
            stabilityOfStep("""step tap class="android.widget.Button""""),
        )
        assertEquals(SelectorStability.Unresolved, stabilityOfStep("step scroll direction=down"))
    }

    @Test
    fun `id wins when a step carries several selectors`() {
        assertEquals(SelectorStability.High, stabilityOfStep("""step tap id="btn" text="Go""""))
    }

    @Test
    fun `extracts the selector portion only`() {
        assertEquals(
            """id="btn_continue" text="Go"""",
            selectorOfStep("""step tap id="btn_continue" text="Go""""),
        )
        assertEquals(
            """text-contains="Terms & Policies"""",
            selectorOfStep("""step tap text-contains="Terms & Policies""""),
        )
        assertNull(selectorOfStep("step back"))
    }
}

class PrettyJsonTest {

    @Test
    fun `reflows a single-line object`() {
        val out = prettyJson("""{"kind":"ui.tap","x":12}""")
        assertEquals(
            """
            {
              "kind": "ui.tap",
              "x": 12
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `keeps punctuation inside strings intact`() {
        val out = prettyJson("""{"route":"/v1/loans,{x}","n":1}""")
        assertTrue(out.contains(""""route": "/v1/loans,{x}""""))
    }

    @Test
    fun `leaves non json untouched`() {
        assertEquals("not json at all", prettyJson("not json at all"))
    }

    @Test
    fun `nests objects`() {
        val out = prettyJson("""{"a":{"b":1}}""")
        assertTrue(out.lines().any { it.startsWith("    \"b\"") }, out)
    }
}
