plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.flowscopedev.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.flowscopedev.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

val agentVersion = providers.gradleProperty("FLOWSCOPE_AGENT_VERSION").get()

dependencies {
    // The only FlowScope wiring a client app needs.
    debugImplementation("io.github.flowscopedev:flowscope-agent:$agentVersion")
    releaseImplementation("io.github.flowscopedev:flowscope-agent-no-op:$agentVersion")

    // Both UI toolkits are present on purpose: FlowScope must record and replay either.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
}
