plugins {
    application
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.flowscope.host.Main"
    applicationName = "flowscope"
}

// FlowScopeVersion reads this at run time, so the CLI and the IDE (which depends on this module)
// report the one version written in gradle.properties. A plain-javac build without this resource
// still runs: FlowScopeVersion falls back to "unknown".
val generateVersionResource by tasks.registering {
    val version = project.version.toString()
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("dev/flowscope/host/version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionResource)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    // Lets FlowScopeVersionTest assert the generated resource agrees with gradle.properties.
    systemProperty("flowscope.expectedVersion", project.version.toString())
}
