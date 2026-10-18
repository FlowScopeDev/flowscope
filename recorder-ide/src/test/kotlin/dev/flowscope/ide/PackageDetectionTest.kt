package dev.flowscope.ide

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackageDetectionTest {
    @Test
    fun `reads an applicationId from the Kotlin DSL`() {
        assertEquals(
            "io.github.flowscopedev.sample",
            applicationIdIn(
                """
                android {
                    namespace = "io.github.flowscopedev.sample"
                    defaultConfig {
                        applicationId = "io.github.flowscopedev.sample"
                    }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `reads an applicationId from Groovy`() {
        assertEquals(
            "com.example.app",
            applicationIdIn("android {\n  defaultConfig {\n    applicationId 'com.example.app'\n  }\n}"),
        )
    }

    /** They are usually equal; when they differ it is `applicationId` that ADB and a `.flow` mean. */
    @Test
    fun `prefers applicationId over namespace`() {
        assertEquals(
            "com.example.app.debug",
            applicationIdIn(
                """
                android {
                    namespace = "com.example.app"
                    defaultConfig { applicationId = "com.example.app.debug" }
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `falls back to namespace when no applicationId is declared`() {
        assertEquals("com.example.library", applicationIdIn("android {\n  namespace = \"com.example.library\"\n}"))
    }

    /** Parking a flavour behind `//` is routine; a commented declaration is not a live one. */
    @Test
    fun `ignores a commented out declaration`() {
        assertEquals(
            "com.example.app",
            applicationIdIn(
                """
                android {
                    // applicationId = "com.example.old"
                    namespace = "com.example.app"
                }
                """.trimIndent(),
            ),
        )
        assertNull(applicationIdIn("// applicationId = \"com.example.app\"\n"))
    }

    /** A `//` inside a string is a URL, not a comment — stripping it would eat the line. */
    @Test
    fun `does not treat a url as a comment`() {
        assertEquals(
            "com.example.app",
            applicationIdIn(
                """
                repositories { maven { url = "https://example.com/repo" } }
                android { defaultConfig { applicationId = "com.example.app" } }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `rejects a single segment id and finds nothing in an unrelated script`() {
        assertNull(applicationIdIn("android { defaultConfig { applicationId = \"app\" } }"))
        assertNull(applicationIdIn("plugins { id(\"com.android.application\") }"))
        assertNull(applicationIdIn(""))
    }

    @Test
    fun `detects the application module ahead of the root script`() {
        val root = Files.createTempDirectory("flowscope-detect")
        root.resolve("build.gradle.kts").writeText("plugins { id(\"com.android.application\") apply false }")
        root.resolve("app").createDirectories()
        root.resolve("app/build.gradle.kts")
            .writeText("android { defaultConfig { applicationId = \"com.example.app\" } }")
        assertEquals("com.example.app", detectApplicationId(root))
    }

    /** Generated output under `build/` repeats declarations and must never win. */
    @Test
    fun `skips build output and dot directories`() {
        val root = Files.createTempDirectory("flowscope-detect-skip")
        root.resolve("build").createDirectories()
        root.resolve("build/build.gradle").writeText("android { defaultConfig { applicationId = \"com.generated.app\" } }")
        root.resolve(".hidden").createDirectories()
        root.resolve(".hidden/build.gradle").writeText("android { defaultConfig { applicationId = \"com.hidden.app\" } }")
        assertNull(detectApplicationId(root))
    }

    @Test
    fun `returns null for a project with no gradle build`() {
        val root = Files.createTempDirectory("flowscope-detect-empty")
        root.resolve("flows/tests").createDirectories()
        assertNull(detectApplicationId(root))
    }

    /**
     * The repository's own sample app, which is the shape this exists for: a modern AGP module
     * whose `AndroidManifest.xml` carries no `package` attribute at all.
     */
    @Test
    fun `detects the id of the repository's sample app`() {
        val sampleApp = Path.of("..", "examples", "sample-app").toAbsolutePath().normalize()
        if (!Files.isDirectory(sampleApp)) return
        assertEquals("io.github.flowscopedev.sample", detectApplicationId(sampleApp))
    }
}
