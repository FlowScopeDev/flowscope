plugins {
    base
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
allprojects {
    group = "dev.flowscope"
    version = "0.1.0-SNAPSHOT"

    repositories {
        google()
        mavenCentral()
    }
}
