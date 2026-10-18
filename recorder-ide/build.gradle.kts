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
            packageVersion = "1.0.0"
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
// ID are handled by the signing block above; this cleanup only prepares the unsigned local app.
tasks.matching { it.name == "createDistributable" }.configureEach {
    doLast {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return@doLast
        if (providers.gradleProperty("flowscope.mac.signingIdentity").isPresent) return@doLast

        val app = layout.buildDirectory.dir("compose/binaries/main/app/Flow Scope.app").get().asFile
        fun runTool(vararg command: String) {
            val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
            check(exit == 0) { "Command failed ($exit): ${command.joinToString(" ")}" }
        }
        runTool("xattr", "-cr", app.absolutePath)
        runTool("codesign", "--force", "--deep", "--sign", "-", app.absolutePath)
    }
}
