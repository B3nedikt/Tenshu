plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// A tiny, app-neutral module for **Control-reachability tracking**: the probe-owned detection +
// active-recovery engine behind each app's "Can't reach Tenshu Control" banner (fit-app.md
// §9.14), shared by Fit, Timeline and Maps, which read through the Control proxy and so share the
// same silent-blank gap. One module rather than three copies, as `:app-liveness`/`:app-outbox`
// do. Coroutines only, Android-free,
// all targets, so any app's shared-data layer can depend on it.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.reachability"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    js { browser() }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines)
                // `core-schema-skew-surface.md`: only for the announce → UnreachableReason
                // adapter. ⚠ `implementation`, so consumers do NOT inherit it on their compile
                // classpath — `:app-notify` is the one consumer without core-common of its own and
                // stays that way. The engine itself still touches nothing from here.
                implementation(project(":core-common"))
                // `suite-sync-status.md` SS-1: [SuiteSyncProblem]'s inputs. The connectivity values
                // are read from `SyncHealthStatus`'s own constants rather than spelled again here, so
                // a value renamed there cannot silently fall through to "no problem".
                // `implementation`: only the constants are used, never in a signature.
                implementation(project(":sync-service"))
                // `RealmStorageState` is a parameter of [evaluateSuiteSyncProblem], so `api`.
                // Declarations only, as in `:ui-core` — no storage client.
                api(project(":storage-service-common"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
