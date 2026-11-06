plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// A tiny, app-neutral module for **durable outboxes**: journal a value to app-private storage
// *before* attempting the real write, remove it only on confirmed success, replay pending entries
// later — so work captured while the proxy is unreachable (or before onboarding finishes) is never
// lost. Extracted from Timeline's visit outbox (`CapturedVisitOutbox` in `:timeline-shared-data`)
// once a second app — Fit's recorded-workout saves — needed the identical mechanic; forking it was
// the wrong move (same reasoning as `:app-liveness`). Serialization only, Android-free in
// commonMain, all targets; the shared file-backed store lives in a jvm+android intermediate
// source set (java.io), other platforms bind their own store or none.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.outbox"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    js { browser() }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // java.io-backed store shared by Android and desktop (the two platforms with a plain
        // filesystem); iOS/JS bind their own OutboxStore when they grow a consumer.
        val jvmFileMain = create("jvmFileMain") { dependsOn(commonMain) }
        getByName("androidMain") { dependsOn(jvmFileMain) }
        getByName("desktopMain") { dependsOn(jvmFileMain) }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
