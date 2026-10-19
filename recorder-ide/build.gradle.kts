plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":recorder-host"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.flowscope.ide.MainKt"
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Flow Scope",
            "-Xdock:name=Flow Scope"
        )

        // ProGuard is off, so the released bytecode is exactly what the tests ran against.
        //
        // It is also broken upstream on Gradle 9: AbstractProguardTask.kt calls the shared
        // ExternalToolRunner with logToConsole=Always, and ExternalToolRunner.kt:68 then reads
        // `spec.standardOutput` before assigning it. Gradle 9's ExecSpec returns null from that
        // getter, so Kotlin throws "getStandardOutput(...) must not be null". The path is
        // unconditional for ProGuard, so no Gradle property avoids it, and it is still present in
        // compose-gradle-plugin 1.12.2 and 1.13.0-alpha02. Verified: the same task succeeds under
        // Gradle 8.14.3 and fails under 9.7.0.
        //
        // Even working, it is a poor trade here: it shrank the app jars from 38.7 to 31.3 MiB —
        // 7.3 MiB, about 5% of a 141 MiB bundle that carries its own JDK — while minifying an app
        // that reflects into the Compose runtime, AWT/SystemTray and java.net.http, which the unit
        // tests cannot cover because they run against unminified classes.
        //
        // Set -Pflowscope.proguard=true to re-test it, e.g. after an upstream fix. That needs
        // Gradle 8 until the plugin is repaired.
        buildTypes.release.proguard {
            isEnabled.set(
                providers.gradleProperty("flowscope.proguard").map(String::toBoolean).orElse(false),
            )
        }

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            // The packaged runtime is a jlink image, so a module the host uses but the default
            // list omits only fails at run time (the developer JDK hides it in unit tests).
            // `java.net.http` backs api-request steps and the DevTools WebSocket.
            modules("java.net.http")
            packageName = "Flow Scope"
            // gradle.properties is the single source; see FlowScopeVersion and the release
            // workflow, which reads the same property rather than parsing this file.
            packageVersion = project.version.toString()
            description = "Record, inspect, and replay Android UI flows"
            vendor = "FlowScope"

            macOS {
                bundleID = "dev.flowscope.ide"
                iconFile.set(project.file("src/main/resources/icons/FlowScope.icns"))
                providers.gradleProperty("flowscope.mac.signingIdentity").orNull?.let { signingIdentity ->
                    signing {
                        sign.set(true)
                        identity.set(signingIdentity)
                        providers.gradleProperty("flowscope.mac.keychain").orNull?.let(keychain::set)
                    }
                }
            }

            windows {
                iconFile.set(project.file("src/main/resources/icons/FlowScope.ico"))
            }
        }
    }
}

// Files copied from a quarantined/provenance-marked JDK can make an otherwise valid local
// development bundle appear "damaged" to macOS. Distribution builds signed with a real Developer
// ID are handled by the signing block above; this cleanup only prepares the unsigned app, local or
// released — the release DMG we actually ship is built from createReleaseDistributable's output,
// so it needs the same treatment as the local createDistributable one.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return@doLast
        if (providers.gradleProperty("flowscope.mac.signingIdentity").isPresent) return@doLast

        val variant = if (name == "createReleaseDistributable") "main-release" else "main"
        val app = layout.buildDirectory.dir("compose/binaries/$variant/app/Flow Scope.app").get().asFile
        fun runTool(vararg command: String) {
            val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
            check(exit == 0) { "Command failed ($exit): ${command.joinToString(" ")}" }
        }
        runTool("xattr", "-cr", app.absolutePath)
        runTool("codesign", "--force", "--deep", "--sign", "-", app.absolutePath)
    }
}
