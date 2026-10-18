plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "dev.flowscope.agent"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "AGENT_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

}

mavenPublishing {
    coordinates("io.github.flowscopedev", "flowscope-agent", project.version.toString())

    // Signing is only configured when a key is supplied, so publishToMavenLocal works without one.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    publishToMavenCentral()

    pom {
        name.set("FlowScope Agent")
        description.set("FlowScope debug-only Android agent: emits structured UI, lifecycle and extension events for instrumented capture. Installs itself; no app code required.")
        inceptionYear.set("2026")
        url.set("https://github.com/FlowScopeDev/flowscope")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("imabhishekkumar")
                name.set("Abhishek Kumar")
                url.set("https://github.com/imabhishekkumar")
            }
        }
        scm {
            url.set("https://github.com/FlowScopeDev/flowscope")
            connection.set("scm:git:git://github.com/FlowScopeDev/flowscope.git")
            developerConnection.set("scm:git:ssh://git@github.com/FlowScopeDev/flowscope.git")
        }
    }
}
