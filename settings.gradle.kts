rootProject.name = "tonex-otg"

// Plugin resolution (the AGP/Kotlin-Android/Compose plugin *jars themselves*) is separate
// from dependency resolution below. `google()` has to be here for `:app`'s plugins to
// resolve at all, but this has no bearing on which repositories a project's own
// dependencies come from — that's controlled per-project below.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

// `:protocol` is pure Kotlin/JVM and resolves solely from Maven Central, which keeps it
// buildable and testable in environments without the Android SDK (see `protocol/build.gradle.kts`
// and STEP 3 of S10 / issue #15, which re-verifies this with the SDK path unavailable).
//
// `:app` is added in S10 (issue #15) together with the Android Gradle Plugin. It declares
// its own `google()` repository (see `app/build.gradle.kts`) so AndroidX/Compose artifacts
// resolve there without exposing that repository to `:protocol`.
include(":protocol")
include(":app")

dependencyResolutionManagement {
    // PREFER_PROJECT (rather than FAIL_ON_PROJECT_REPOS) is required so that `:app` can add
    // `google()` via its own `repositories {}` block without affecting `:protocol`, which has
    // no such block and therefore only ever sees the centralized `mavenCentral()` below.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}
