pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val useMavenLocal = providers.gradleProperty("useMavenLocal").orNull == "true"

dependencyResolutionManagement {
    repositories {
        // `-PuseMavenLocal=true` resolves an unreleased agent built with
        // `cd android-agent && ./gradlew publishToMavenLocal`. Without it, the app uses exactly what
        // a client app would: Maven Central.
        if (useMavenLocal) {
            mavenLocal {
                content { includeGroup("io.github.flowscopedev") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "flowscope-sample-app"

include(":app")
