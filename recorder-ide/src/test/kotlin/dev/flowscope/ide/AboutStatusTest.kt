package dev.flowscope.ide

import dev.flowscope.host.UpdateCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every update state must say in words what happened. CLAUDE.md forbids colour as the only carrier
 * of meaning, and this is the mapping that holds that line.
 */
class AboutStatusTest {
    @Test
    fun `idle says FlowScope never checks on its own`() {
        val message = describeUpdate(UpdateStatus.Idle)
        assertEquals(UpdateTone.Neutral, message.tone)
        assertTrue(message.text.contains("never checks"), message.text)
        assertNull(message.releaseUrl)
    }

    @Test
    fun `checking is neutral and carries no link`() {
        val message = describeUpdate(UpdateStatus.Checking)
        assertEquals(UpdateTone.Neutral, message.tone)
        assertNull(message.releaseUrl)
    }

    @Test
    fun `an available update names the version and keeps its link`() {
        val message = describeUpdate(
            UpdateStatus.Done(UpdateCheck.Outcome.Available("1.1.0", UpdateCheck.RELEASES_PAGE)),
        )
        assertEquals(UpdateTone.Alert, message.tone)
        assertTrue(message.text.contains("1.1.0"), message.text)
        assertEquals(UpdateCheck.RELEASES_PAGE, message.releaseUrl)
    }

    @Test
    fun `up to date reads as good news and offers nothing to click`() {
        val message = describeUpdate(UpdateStatus.Done(UpdateCheck.Outcome.UpToDate("1.0.0")))
        assertEquals(UpdateTone.Good, message.tone)
        assertTrue(message.text.contains("Up to date"), message.text)
        assertNull(message.releaseUrl)
    }

    /** FlowScope's own state until the first DMG release is promoted out of pre-release. */
    @Test
    fun `no published release is stated plainly rather than as a failure`() {
        val message = describeUpdate(UpdateStatus.Done(UpdateCheck.Outcome.NoRelease()))
        assertEquals(UpdateTone.Neutral, message.tone)
        assertTrue(message.text.contains("No published release"), message.text)
    }

    @Test
    fun `a failed check explains itself`() {
        val message = describeUpdate(
            UpdateStatus.Done(UpdateCheck.Outcome.Unavailable("could not reach github.com")),
        )
        assertEquals(UpdateTone.Alert, message.tone)
        assertTrue(message.text.contains("could not reach github.com"), message.text)
    }

    /** No state may be left wordless — an empty label would leave only the colour. */
    @Test
    fun `every state carries text`() {
        val states = listOf(
            UpdateStatus.Idle,
            UpdateStatus.Checking,
            UpdateStatus.Done(UpdateCheck.Outcome.UpToDate("1.0.0")),
            UpdateStatus.Done(UpdateCheck.Outcome.Available("2.0.0", UpdateCheck.RELEASES_PAGE)),
            UpdateStatus.Done(UpdateCheck.Outcome.NoRelease()),
            UpdateStatus.Done(UpdateCheck.Outcome.Unavailable("offline")),
        )
        states.forEach { assertTrue(describeUpdate(it).text.isNotBlank(), it.toString()) }
    }
}
