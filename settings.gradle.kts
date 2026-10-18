pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "flowscope"

include(":recorder-host")
include(":recorder-ide")
