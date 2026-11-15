plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

/**
 * Where inference would actually run (IN-1) — the `InferenceBackend` seam, the fake that lets the
 * whole consumer stack be built and tested with **no model and no hardware**, and the proxy-side
 * consent read.
 */
/**
 * ⚠⚠ Forward the harness's `-D` flags into the **test** JVM (`inference-service.md` §13.2).
 *
 * `./gradlew … -Dtenshu.inference.model=…` sets the property on *Gradle's* JVM, not on the forked
 * test JVM — so the documented invocation ran, passed, and **silently skipped**, which is the one
 * failure mode a measurement harness must not have. Found the first time it was pointed at a real
 * model.
 *
 * ⚠ The value is also declared as a task **input**: without that, a second run against a different
 * model is UP-TO-DATE and reports the *previous* model's numbers.
 */
private val harnessProperties = listOf(
    "tenshu.inference.model",
    "tenshu.inference.photos",
    "tenshu.inference.labels",
    // ⚠ Added with the drift comparison, and forgotten here for one run: a flag the harness reads
    // but this list does not forward is a flag that silently does nothing. The harness now echoes
    // what it actually received, so the next omission is visible in its own output.
    "tenshu.inference.baseline",
    // SigLIP's own calibration — see `docs/models.md` §3.6. Without both, the harness prints
    // cosines and says so; it does not invent a default.
    "tenshu.inference.logitScale",
    "tenshu.inference.logitBias",
    "tenshu.inference.preprocess",
    // ⚠ The input size is per CHECKPOINT, not per family — `-224` and `-256-multilingual`
    // differ, and the wrong one produces a vector rather than an error.
    "tenshu.inference.side",
    // R3's packed vocabulary. ⚠ Added here in the same change that first read it — this list is
    // the one the file's own warning is about, and a flag it does not forward is a flag whose
    // test silently skips while reporting success.
    "tenshu.inference.tokenizer",
    // ⚠⚠ Forgotten for exactly one run, immediately after adding the line above it: the text tests
    // reported success having skipped, and the skip line named a DIFFERENT missing flag. This list
    // is the third occurrence of the same defect in this file's history.
    "tenshu.inference.textModel",
)

tasks.withType<Test>().configureEach {
    var measuring = false
    harnessProperties.forEach { key ->
        val value = providers.systemProperty(key).orNull ?: return@forEach
        systemProperty(key, value)
        inputs.property(key, value)
        measuring = true
    }
    // ⚠⚠ A measurement is NEVER up to date. The photo directory is not a task input — declaring it
    // would hash gigabytes of unrelated content — so adding photographs to the library changes
    // nothing Gradle can see, and a second run against a fuller library would report the FIRST
    // run's numbers as though they were fresh. Caught while re-measuring a store that had just
    // grown from 80 files to 140.
    if (measuring) outputs.upToDateWhen { false }
}

/**
 * The Core ML fixtures `iosTest` compiles and loads (`ios-on-device-inference.md` §5, PR-1).
 *
 * A Kotlin/Native test binary carries no resources, so the directory travels as a path. The
 * simulator shares the host's filesystem, and `simctl spawn` hands a child only the variables
 * whose names start with SIMCTL_CHILD_. ⚠ A run without it does not fail: each fixture case prints why it
 * skipped, so a filtered or hand-launched binary stays usable.
 */
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    val fixtures = layout.projectDirectory.dir("src/iosTest/resources/coreml")
    inputs.dir(fixtures)
    environment("SIMCTL_CHILD_TENSHU_COREML_FIXTURES", fixtures.asFile.absolutePath)

    // The real artifacts, for the cases that skip without them: the packed vocabulary (the same
    // flag the JVM run takes) and a directory `scripts/inference/export-coreml-towers.py` wrote.
    // ⚠ Never up to date, for the reason the JVM harness flags above are not.
    listOf(
        "tenshu.inference.tokenizer" to "TENSHU_INFERENCE_TOKENIZER",
        "tenshu.inference.coremlExport" to "TENSHU_COREML_EXPORT",
        // A directory `scripts/inference/export-coreml-face-embedder.py` wrote (ios-face-grouping.md §12).
        "tenshu.inference.coremlFaceExport" to "TENSHU_COREML_FACE_EXPORT",
    ).forEach { (property, variable) ->
        val value = providers.systemProperty(property).orNull ?: return@forEach
        environment("SIMCTL_CHILD_$variable", value)
        inputs.property(property, value)
        outputs.upToDateWhen { false }
    }
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.inference.proxy"
        compileSdk = 37
        minSdk = 24

        // IN-3m: the measurement runs as an instrumented test on real hardware (§15.2), copying
        // `:core-client` / `:core-proxy` rather than inventing wiring.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        // ⚠ IN-6 F3: the golden of `MediaPipeFaceBackend`'s resample runs against a real `Bitmap`,
        // which only Robolectric can host on the JVM (`ios-face-grouping.md` §13).
        withHostTest { }
    }

    jvm()

    js {
        browser()
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":inference-service-common"))
                api(project(":core-proxy"))
                // IN-D6 (1e): the index pass registers itself as a `HeadlessSafe` `ScheduleItem`,
                // which needs `Schedule` + the spec composites — the same pair `:file-service-proxy`
                // and `:calendar-service-proxy` take for their own maintenance ticks.
                implementation(project(":sync-service"))
                api(project(":sync-service-common"))
                // IN-2: the catalog is JSON on a CDN, and the install index is JSON on disk.
                implementation(libs.kotlinx.serialization.json)
                // F2v: `DefaultModelStore`'s store-wide lock — the lock primitives only, no plugin.
                implementation(libs.kotlinx.atomicfu)
                // IN-2b: the catalog is fetched over HTTPS. `api` for the same reason
                // `:map-region-service-proxy` does it — the engine is injected at the DI site, so
                // the client type is part of this module's surface.
                api(libs.ktorClientCore)
            }
        }
        // ⚠⚠ **IN-3b puts ONNX Runtime in the SHIPPED graph**, which the comment below spent four
        // slices deliberately not doing. The order was the point: measure first (§15.12 is the
        // number), then bind. It costs bytes in every build — the Android AAR carries a `.so` per
        // ABI — so it is bound in the two source sets that can run it and nowhere else.
        //
        // ⚠⚠ **`onnxruntime` and `onnxruntime-android` are different artifacts, not variants.** The
        // JVM jar has no native libraries for Android ABIs; reusing it compiles and then fails at
        // runtime with `UnsatisfiedLinkError`, which reads as a device problem and is a dependency
        // problem. ⚠ Their *APIs* differ too: `addNnapi` exists only on the Android side, which is
        // why `src/onnxMain` — compiled into both — uses the default CPU provider (§15.12.3 says it
        // is the fastest one on the phone that has been measured, so this costs nothing today).
        getByName("androidMain") {
            kotlin.srcDir("src/onnxMain/kotlin")
            dependencies {
                implementation(libs.onnxRuntimeAndroid)
                // IN-6 F2: `MediaPipeFaceBackend`'s detector. ⚠ ~12 MB of AAR in every Android build,
                // bound once the phone run said the pipeline is worth shipping (§12.6–§12.8b).
                implementation(libs.mediapipeTasksVision)
            }
        }
        getByName("jvmMain") {
            kotlin.srcDir("src/onnxMain/kotlin")
            dependencies {
                implementation(libs.onnxRuntime)
                // `DesktopIndexingConditions`: IOKit power state and the HID idle timer over FFM, so a
                // multi-hour index pass never starts under someone's hands (§32).
                implementation(project(":macos-helpers"))
            }
        }
        // ⚠⚠ TEST-SCOPED, and deliberately only here — the *harness's* copy, which predates the
        // shipped one above and answered "is a candidate good enough and fast enough" before any
        // native runtime was in the graph at all.
        getByName("jvmTest") {
            // ⚠⚠ **One file, two compilations — not a copy.** `HarnessMath` is the arithmetic both
            // the desktop harness and IN-3m's device run depend on, and it is `src/harnessShared`
            // rather than a shared *source set* because it must stay TEST-scoped: §15.2 rules out
            // `commonMain` on the grounds that it is shipped code, and `androidMain` would ship it
            // too. A `srcDir` gets the sharing without putting scaffolding in the AAR.
            kotlin.srcDir("src/harnessShared/kotlin")
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.onnxRuntime)
                // ⚠⚠ The build tool that PRODUCES the catalog this module parses. Depended on here
                // — test-scope, JVM-only — because the round trip is the only thing that proves the
                // two agree: `parseModelCatalog` is `internal`, so a test living in `:model-signer`
                // could not call it and would have to assert against a hand-copied JSON, which is a
                // second copy of the contract rather than a check on it.
                implementation(project(":model-signer"))
            }
        }
        // IN-3m — the on-device measurement (§15). ⚠⚠ It is NOT a gate: it skips with a printed
        // reason when the model or the photo directory is absent, it is excluded from
        // `scripts/android-smoke.sh`, and it is not reachable from the JVM gate. A measurement that
        // can fail a build is a benchmark pretending to be a test (§15.8).
        getByName("androidDeviceTest") {
            kotlin.srcDir("src/harnessShared/kotlin")
            dependencies {
                implementation(libs.runner)
                implementation(libs.androidxTestCore)
                implementation(libs.ext.junit)
                implementation(libs.onnxRuntimeAndroid)
                // IN-6's detector half — the harness's own copy, which predates the shipped one in
                // androidMain and is kept so the measurement runs on the same pinned version.
                implementation(libs.mediapipeTasksVision)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.robolectric)
                implementation(libs.testCoreKtx)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":preference-service-common"))
                // ⚠ IN-4b. The `photosLike` test drives the op over the WIRE — RO → params →
                // proxy → returned composites — so it needs the CLIENT registration, exactly as
                // `PhotosCapturedBetweenOperationTest` does. An in-process harness cannot catch a
                // client that forgot a module, but it does catch the half that a unit test never
                // reaches: that the params arrive and the return type materialises.
                implementation(project(":inference-service-client"))
                implementation(libs.ktorClientMock)
            }
        }
    }
}
