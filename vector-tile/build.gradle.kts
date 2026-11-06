plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Mapbox Vector Tile decoding — a **pure, dependency-free** KMP module
 * (`docs/proposals/timeline-movement-capture.md` §13.2 / M9).
 *
 * Deliberately *not* part of `:ui-map`: the decoder's first consumer is Timeline's rail
 * map-matching, which has no business pulling a Compose UI module (and its MapLibre/Android
 * dependencies) onto its classpath. Keeping it standalone also keeps it honest — there is nothing
 * here but bytes in, geometry out.
 *
 * No protobuf runtime: MVT is a small, fixed schema and the repo already hand-rolls varint parsing
 * in two places, so a dependency would cost more than it saves.
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.vectortile"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
    }

    // Declared so commonMain stays KMP-honest (catches accidental JVM-only deps); no framework.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// ⚠ The overlay builder lives in the JVM **test** source set on purpose (§33 L2): it is a build-time
// tool, not something any app links. Keeping it out of `jvmMain` means it ships nowhere, while still
// being compiled and unit-tested by the ordinary gate so it cannot rot unnoticed — the same placement
// the ONNX measurement harness uses in `:inference-service-proxy`.
tasks.register<JavaExec>("buildTransitOverlay") {
    group = "build"
    description = "Encode a transit-line overlay (.tlne) from the TSV that scripts/transit-overlay-build.sh emits."
    val jvmTest = kotlin.targets.getByName("jvm").compilations.getByName("test")
    classpath = files(jvmTest.output.classesDirs, jvmTest.runtimeDependencyFiles)
    mainClass.set("app.tenshu.vectortile.overlay.TransitOverlayBuildKt")
    dependsOn("jvmTestClasses")
}
