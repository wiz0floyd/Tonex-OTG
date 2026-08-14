rootProject.name = "tonex-otg"

// Only `:protocol` is registered here for now.
//
// `:protocol` is pure Kotlin/JVM and resolves solely from Maven Central, which keeps it
// buildable and testable in environments without the Android SDK. The `:app` module is
// added in S10 (issue #15) together with the Android Gradle Plugin; deliberately not
// declared yet, because merely applying AGP forces resolution against Google's Maven and
// would make this build unresolvable wherever that host is unreachable.
include(":protocol")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
