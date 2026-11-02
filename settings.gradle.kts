pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flos"
include(":core")
// Set -Pflos.coreOnly=true to build and test the pure-Kotlin core without an Android SDK.
if (providers.gradleProperty("flos.coreOnly").orNull != "true") {
    include(":app")
}
