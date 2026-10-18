package dev.flowscope.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNotificationsTest {
    @Test
    fun `test completion distinguishes a passing test`() {
        val notice = testCompletionNotice("Customer login", passed = true, steps = 7)

        assertEquals("FlowScope · Test passed", notice.title)
        assertEquals("Customer login · 7/7 steps passed", notice.message)
        assertFalse(notice.failed)
    }

    @Test
    fun `failed test does not put failure contents in notification`() {
        val notice = testCompletionNotice("Customer login", passed = false, steps = 7)

        assertEquals("FlowScope · Test failed", notice.title)
        assertEquals("Customer login · Open FlowScope for failure details", notice.message)
        assertTrue(notice.failed)
    }

    @Test
    fun `regression completion reports passed failed and skipped totals`() {
        val notice = regressionCompletionNotice(
            "Release smoke", passed = false, total = 12, passedCount = 8, failedCount = 2,
        )

        assertEquals("FlowScope · Regression failed", notice.title)
        assertEquals("Release smoke · 8 passed · 2 failed · 2 skipped", notice.message)
        assertTrue(notice.failed)
    }

    @Test
    fun `passing regression keeps the concise total`() {
        val notice = regressionCompletionNotice(
            "Release smoke", passed = true, total = 12, passedCount = 12, failedCount = 0,
        )

        assertEquals("Release smoke · 12 passed", notice.message)
        assertFalse(notice.failed)
    }
}
