// Without the Android SDK there is often no access to Google's Maven repository either.
val jvmOnly = providers.gradleProperty("redde.jvmOnly").orNull == "true"

pluginManagement {
    repositories {
        if (providers.gradleProperty("redde.jvmOnly").orNull != "true") google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
    }
}

rootProject.name = "redde"

// Pure Kotlin/JVM modules: all the protocol, parsing and crypto logic, testable without Android.
include(":core:model", ":core:transport", ":core:markdown", ":core:crypto", ":core:store")

// Android modules. `-Predde.jvmOnly=true` leaves them out, for machines without the Android SDK
// or Google's Maven repository (the JVM modules then build and test on their own).
if (!jvmOnly) {
    include(":app", ":core:data", ":feature:voice", ":feature:phonetools", ":feature:terminal")
}
