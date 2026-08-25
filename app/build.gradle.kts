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
        versionCode = 5
        versionName = "0.9.1"
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

    // Release signing (#67): sourced from env vars, never from committed files or
    // gradle.properties. If NONE of the four vars are set, `signingConfig` is left null and
    // `assembleRelease` produces an unsigned APK as before — the everyday local-build path for
    // contributors. If SOME are set, that's a broken invocation (e.g. a typo'd secret name in
    // CI) and must fail loudly rather than silently falling back to unsigned, per this repo's
    // fail-fast-and-loud philosophy — an unsigned "release" APK published by CI is exactly the
    // kind of silent wrong-success this project explicitly guards against.
    val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val releaseSigningVars = listOf(keystorePath, keystorePassword, keyAlias, keyPassword)
    val releaseSigningConfigured = releaseSigningVars.all { !it.isNullOrEmpty() }
    check(releaseSigningConfigured || releaseSigningVars.all { it.isNullOrEmpty() }) {
        "Partial release signing config: RELEASE_KEYSTORE_PATH/RELEASE_KEYSTORE_PASSWORD/" +
            "RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD must be either all set or all unset."
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    // `:protocol` declares kotlinx-coroutines-core as `implementation`, not `api` (deliberately —
    // see its own build.gradle.kts), so it is not exposed transitively to `:app`'s compile
    // classpath. `dev.tonexotg.app.probe` (S20 / issue #25) uses coroutines APIs directly
    // (CoroutineScope, Channel, suspendCancellableCoroutine, ...), so this is declared explicitly
    // rather than relied on incidentally via some other transitive dependency.
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // S23 (issue #74): the real app's navigation graph (preset list <-> parameter editor <->
    // about). See docs/architecture/s23-ui-wiring.md for the route graph this backs.
    implementation(libs.androidx.navigation.compose)

    // S14 (issue #19): local preset-alias store, DataStore-backed.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    // Spike: non-visual Compose semantics-tree assertions via Robolectric, which runs as a
    // plain JVM unit test (`testDebugUnitTest`) rather than `connectedAndroidTest` — no
    // emulator/device required. See S15/issue #20's verification notes for why this is a
    // JUnit4 island inside an otherwise JUnit5 codebase: Robolectric's `RobolectricTestRunner`
    // is JUnit4-only, and `androidx.compose.ui:ui-test-junit4` requires it.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    // S14 (issue #19): PresetAliasStoreTest uses Turbine for Flow assertions, matching
    // :protocol's convention for testing Flow-returning APIs.
    testImplementation(libs.turbine)
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
