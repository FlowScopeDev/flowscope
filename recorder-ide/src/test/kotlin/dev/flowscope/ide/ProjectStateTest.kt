package dev.flowscope.ide

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectStateTest {

    /**
     * `java.nio.file.Path` is an `Iterable<Path>` over its name elements, so `missing + path` binds
     * to the `Iterable` overload of `Set.plus` and marks every *segment* of the path. The rail
     * showed six rows — `Users`, `abhishek.kumar`, `Documents`, `StudioProjects`, `Personal`,
     * `UI Test Automation` — instead of the one deleted test.
     */
    @Test
    fun `marking a missing file adds one row, not one per path segment`() {
        val state = ProjectState()
        val path = Path.of("/Users/me/Projects/app/flows/tests/login.flow")

        state.markMissing(path)

        assertEquals(setOf(path), state.missing)
        assertEquals(1, state.missing.size)
    }

    @Test
    fun `marking the same file twice keeps one row`() {
        val state = ProjectState()
        val path = Path.of("/Users/me/Projects/app/flows/tests/login.flow")

        state.markMissing(path)
        state.markMissing(path)

        assertEquals(1, state.missing.size)
    }

    @Test
    fun `two deleted files are two rows`() {
        val state = ProjectState()
        val login = Path.of("/Users/me/Projects/app/flows/tests/login.flow")
        val terms = Path.of("/Users/me/Projects/app/flows/tests/terms.flow")

        state.markMissing(login)
        state.markMissing(terms)

        assertEquals(setOf(login, terms), state.missing)
    }
}
