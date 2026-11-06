plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * A **device-local diagnostic log** — a single [app.tenshu.diagnostics.LogItem] stream (level + tag +
 * message, plus an optional structured code) that holds ordinary breadcrumbs *and* crashes on one
 * timeline, the way a normal logging API does
 * (`docs/proposals/core-logging-facade.md`, single-stream variant).
 *
 * ⚠ **Deliberately device-local, NOT synced** — unlike `:logging-service`'s `ErrorEvent`. Diagnostics
 * are high-volume, device-specific ("what did *this* phone's capture do?"), often carry PII
 * (coordinates, paths), and are read either in-app on the same device or uploaded on request. Syncing
 * them would push volume onto the sync plane, replicate one device's noise to every other, and drag
 * PII across the store — and it buys nothing, since the developer who wants them has no paired device
 * (retrieval is *upload*, not sync). So this is a plain bounded local store, no schema/op/wire cost.
 *
 * Pure KMP, no dependencies; the platform supplies a [app.tenshu.diagnostics.DiagnosticSink].
 */
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.diagnostics"
        compileSdk = 37
        minSdk = 24
    }

    jvm()
    js { browser() }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Shared by android + jvm(desktop): the file-backed sink + crash handler are plain
        // java.io / java.lang.Thread, identical on both, and JVM-host-testable.
        val jvmSharedMain = create("jvmSharedMain") { dependsOn(commonMain.get()) }
        androidMain { dependsOn(jvmSharedMain) }
        jvmMain { dependsOn(jvmSharedMain) }

        commonTest {
            dependencies { implementation(libs.kotlin.test) }
        }
        jvmTest {
            dependencies { implementation(libs.kotlin.test) }
        }
    }
}
