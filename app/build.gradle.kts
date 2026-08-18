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

// Bundles the repo-root CREDITS.md and the two upstream licence texts as Android assets so the
// About/credits screen (S19 / issue #24) reads the actual attribution source of truth at
// runtime, instead of a hand-typed second copy that could silently drift from CREDITS.md. This
// task's output lives under `build/` only — nothing here is checked into git, and every build
// re-copies the current repo-root files fresh.
val syncLicensingAssets = tasks.register<Copy>("syncLicensingAssets") {
    from(rootProject.file("CREDITS.md"))
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE-APACHE-2.0.txt" }
    }
    from(rootProject.file("LICENSES/MIT-vit3k.txt"))
    into(layout.buildDirectory.dir("generated/licensingAssets"))
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

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/licensingAssets"))
        }
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

// The generated assets dir is a plain directory reference to AGP's source-set API, which does
// not by itself infer a task dependency on the Copy task that populates it. Wire it explicitly
// so `assets.srcDir(...)` above is never stale: every asset-merge task (debug/release, and the
// unit-test variant produced by `isIncludeAndroidResources = true`) depends on the sync running
// first.
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(syncLicensingAssets)
}
tasks.named("preBuild") {
    dependsOn(syncLicensingAssets)
}
