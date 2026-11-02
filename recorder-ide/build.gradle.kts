import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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

// jpackage/Compose patch and re-sign executables while assembling the app. macOS can retain
// signature/provenance state for those files even when codesign --verify succeeds. Publish the
// completed, verified bytes on fresh inodes, without inherited extended attributes, at the normal
// output path. Both Finder/runDistributable and packageDmg must consume this final bundle.
// https://developer.apple.com/documentation/security/updating-mac-software
tasks.withType<AbstractJPackageTask>().matching {
    it.name == "createDistributable" || it.name == "createReleaseDistributable"
}.configureEach {
    // Apple Development certificates work for local installs, but Compose's distribution signer
    // only accepts Developer ID/App Store identities. Keep this opt-in local path separate.
    val localSigningIdentity = providers.gradleProperty("flowscope.mac.localSigningIdentity")
    inputs.property("flowscope.mac.localSigningIdentity", localSigningIdentity.orElse("-"))
    doLast {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return@doLast

        val app = destinationDir.get().asFile.resolve("${packageName.get()}.app")
        fun runTool(vararg command: String) {
            val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
            check(exit == 0) { "Command failed ($exit): ${command.joinToString(" ")}" }
        }
        if (!providers.gradleProperty("flowscope.mac.signingIdentity").isPresent) {
            runTool("/usr/bin/xattr", "-cr", app.absolutePath)
            val identity = localSigningIdentity.orNull?.trim() ?: "-"
            check(identity.isNotEmpty()) { "flowscope.mac.localSigningIdentity must not be blank" }
            runTool("/usr/bin/codesign", "--force", "--deep", "--sign", identity,
                "--timestamp=none", "--preserve-metadata=entitlements", app.absolutePath)
        }
        // Developer ID signatures from Compose remain intact; never re-sign them as local/ad-hoc.
        runTool("/usr/bin/codesign", "--verify", "--deep", "--strict", app.absolutePath)
        val staging = Files.createTempDirectory(app.parentFile.toPath(), ".flowscope-package-").toFile()
        var keepRecoveryCopy = false
        try {
            val ready = staging.resolve(app.name)
            val previous = staging.resolve("previous.app")
            runTool("/usr/bin/ditto", "--noextattr", "--norsrc", app.absolutePath, ready.absolutePath)
            runTool("/usr/bin/codesign", "--verify", "--deep", "--strict", ready.absolutePath)
            // Rename on the same filesystem: do not copy back over the files macOS has observed.
            Files.move(app.toPath(), previous.toPath(), ATOMIC_MOVE)
            try {
                Files.move(ready.toPath(), app.toPath(), ATOMIC_MOVE)
            } catch (failure: Exception) {
                try {
                    Files.move(previous.toPath(), app.toPath(), ATOMIC_MOVE)
                } catch (restoreFailure: Exception) {
                    keepRecoveryCopy = true
                    failure.addSuppressed(restoreFailure)
                    logger.error("Could not restore app; recovery copy retained at $previous")
                }
                throw failure
            }
        } finally {
            if (!keepRecoveryCopy) {
                check(staging.deleteRecursively()) { "Could not remove packaging staging directory: $staging" }
            }
        }
    }
}
