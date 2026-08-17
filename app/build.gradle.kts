plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// `:app` is the only module that resolves against Google's Maven: AGP itself, AndroidX, and
// Compose are all served from there. `:protocol` deliberately declares no `repositories {}`
// block of its own and therefore only ever sees the centralized `mavenCentral()` declared in
// `settings.gradle.kts` — see that file and `protocol/build.gradle.kts` for the full story
// (S10 / issue #15).
repositories {
    google()
    mavenCentral()
}

android {
    namespace = "dev.tonexotg.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.tonexotg.app"
        // minSdk 26 is the floor where UsbRequest.queue(ByteBuffer) and
        // UsbDeviceConnection.requestWait(long) land — below it, the no-arg requestWait()
        // blocks forever and cannot be interrupted (AOSP issue 39522). Do not lower this.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Placeholder scaffold only (S10). Real screens are S15-S19; the design gate has not
    // been signed off yet, so no UI beyond a single empty Activity belongs here.
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Spike: non-visual Compose semantics-tree assertions via Robolectric, which runs as a
    // plain JVM unit test (`testDebugUnitTest`) rather than `connectedAndroidTest` — no
    // emulator/device required. See S15/issue #20's verification notes for why this is a
    // JUnit4 island inside an otherwise JUnit5 codebase: Robolectric's `RobolectricTestRunner`
    // is JUnit4-only, and `androidx.compose.ui:ui-test-junit4` requires it.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
}
