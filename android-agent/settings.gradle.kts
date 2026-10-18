pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flowscope-android-agent"

include(":agent")
include(":agent-noop")
include(":webview-fixture")
