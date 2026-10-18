package dev.flowscope.ide

import java.nio.file.Path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankPackagesTest {

    private val packages = listOf(
        "com.android.settings",
        "com.example.customer",
        "com.example.customer.debug",
        "com.example.app",
    )

    @Test
    fun `favourites come first even without a query`() {
        val ranked = rankPackages(packages, "", setOf("com.example.app"))
        assertEquals("com.example.app", ranked.first())
    }

    @Test
    fun `favourites still win when another entry prefix-matches`() {
        val ranked = rankPackages(packages, "com.example.customer", setOf("com.example.app"))
        // "com.example.app" does not contain the query, so it is filtered out entirely.
        assertTrue(ranked.none { it == "com.example.app" })
        assertEquals("com.example.customer", ranked.first())
    }

    @Test
    fun `a favourite that matches outranks a prefix match`() {
        val ranked = rankPackages(packages, "customer", setOf("com.example.customer.debug"))
        assertEquals("com.example.customer.debug", ranked.first())
    }

    @Test
    fun `prefix matches rank above substring matches`() {
        val ranked = rankPackages(packages, "com.example.c", emptySet())
        assertEquals(listOf("com.example.customer", "com.example.customer.debug"), ranked)
    }

    @Test
    fun `filtering is case insensitive`() {
        assertTrue(rankPackages(packages, "CUSTOMER", emptySet()).isNotEmpty())
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(emptyList(), rankPackages(emptyList(), "anything", emptySet()))
    }
}

class EventExportTest {

    private fun event(
        title: String = "ui.tap",
        detail: String = "Touch",
        payload: String? = null,
    ) = CapturedEvent(
        id = 1,
        time = "21:30:00",
        epochMillis = 1_700_000_000_000,
        symbol = "●",
        title = title,
        detail = detail,
        channel = Channel.Ui,
        severity = Severity.Normal,
        payload = payload,
    )

    @Test
    fun `emits a single line of valid json`() {
        val line = event().toJsonLine()
        assertTrue(line.startsWith("{") && line.endsWith("}"))
        assertTrue(!line.contains("\n"))
        assertTrue(line.contains("\"channel\":\"Ui\""))
        assertTrue(line.contains("\"epochMillis\":1700000000000"), line)
    }

    @Test
    fun `escapes quotes backslashes and newlines`() {
        val line = event(detail = "said \"hi\"\nwith C:\\path\there").toJsonLine()
        assertTrue(line.contains("""\"hi\""""), line)
        assertTrue(line.contains("""\\path"""), line)
        assertTrue(!line.drop(1).dropLast(1).contains("\n"), "newline must be escaped")
    }

    @Test
    fun `escapes control characters`() {
        assertEquals("\\u0001", escapeJson("\u0001"))
    }

    @Test
    fun `omits absent optional fields`() {
        val line = event().toJsonLine()
        assertTrue(!line.contains("suggestedStep"))
        assertTrue(!line.contains("payload"))
    }

    @Test
    fun `includes the payload when present`() {
        val line = event(payload = """{"kind":"ui.tap"}""").toJsonLine()
        assertTrue(line.contains("""\"kind\":\"ui.tap\""""), line)
    }
}

class RecentProjectsTest {

    @Test
    fun `parses recent projects with and without a remembered file`() {
        val parsed = parseRecentProjects(listOf(
            "/Users/me/app\tflows/tests/login.flow",
            "/Users/me/other",
            "",
            "# a comment",
            "   ",
        ))

        assertEquals(2, parsed.size)
        assertEquals(Path.of("/Users/me/app"), parsed[0].root)
        assertEquals("flows/tests/login.flow", parsed[0].lastOpenFile)
        assertEquals(Path.of("/Users/me/other"), parsed[1].root)
        assertNull(parsed[1].lastOpenFile)
    }

    @Test
    fun `drops malformed and duplicate recent project lines`() {
        assertEquals(emptyList(), parseRecentProjects(listOf("\tflows/tests/a.flow")))
        assertEquals(1, parseRecentProjects(listOf("/Users/me/app", "/Users/me/app\tb.flow")).size)
    }

    @Test
    fun `treats an empty remembered file field as none`() {
        assertNull(parseRecentProjects(listOf("/Users/me/app\t")).single().lastOpenFile)
    }

    /**
     * Older builds wrote the previous project's open file against the new project's root. Keeping
     * the project but dropping the escaping file repairs those lines the next time they are read.
     */
    @Test
    fun `keeps the project but drops a remembered file from outside it`() {
        val parsed = parseRecentProjects(listOf(
            "/Users/me/app\t../../../../Other/flows/tests/terms.flow",
            "/Users/me/other\t/absolute/elsewhere.flow",
        ))

        assertEquals(2, parsed.size)
        assertEquals(Path.of("/Users/me/app"), parsed[0].root)
        assertNull(parsed[0].lastOpenFile)
        assertNull(parsed[1].lastOpenFile)
    }

    @Test
    fun `merging moves the opened project to the front without duplicating it`() {
        val existing = listOf("/b", "/a\told.flow", "/c")
        assertEquals(listOf("/a\tnew.flow", "/b", "/c"),
            mergeRecentProject(existing, "/a\tnew.flow"))
    }

    @Test
    fun `merging caps the list and keeps the newest`() {
        val existing = (1..12).map { "/p$it" }
        val merged = mergeRecentProject(existing, "/new")

        assertEquals(RecentProjectCap, merged.size)
        assertEquals("/new", merged.first())
        assertEquals("/p9", merged.last())
    }

    @Test
    fun `merging skips blank and comment lines already in the file`() {
        assertEquals(listOf("/a", "/b"),
            mergeRecentProject(listOf("", "# note", "/b"), "/a"))
    }
}
