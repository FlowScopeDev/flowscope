plugins {
    id("com.android.library") version "8.11.1" apply false
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

subprojects {
    group = "io.github.flowscopedev"
    version = providers.gradleProperty("VERSION_NAME").get()
}
