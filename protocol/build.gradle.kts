plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM. Adding an Android or AndroidX dependency here is a defect, not a
// convenience: the transport seam is `TonexTransport`, and the Android implementation of
// it lives in `:app`. Keeping this module Android-free is what makes the protocol logic
// testable without hardware or an SDK (NFR5).

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
