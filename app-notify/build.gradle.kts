plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The OS-notification seam (`calendar-app.md` §4.3 gap 2). Calendar forces it open — a
// reminder that cannot reach a lock screen is not a reminder — but it is deliberately a
// *platform* module rather than a Calendar internal: Routines wants it for a routine that
// fired, Fit for an import that finished, Drive for a share that arrived, and every one of
// those would otherwise hand-roll `NotificationCompat` again.
//
// Sits beside :app-liveness / :app-outbox / :app-reachability, and follows their shape:
// commonMain is Android-free so any shared-logic module can depend on it, with the platform
// effect in androidMain.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.notify"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    js { browser() }
    // ⚠ `iosX64` too, unlike :app-liveness. ⚠⚠ Nothing needs it: `:calendar-shared-logic`
    // declares no iosX64 (the umbrella cannot link an Intel-simulator slice at all). The
    // target stays only because removing it changes every remaining consumer's resolution,
    // which belongs in the sweep that drops it elsewhere. The hazard is real either way: a
    // dependency targeting fewer platforms than its consumer fails resolution for the
    // missing one while still printing BUILD SUCCESSFUL, so the only signal is an `e:` line.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("androidMain") {
            dependencies {
                // NotificationCompat / NotificationManagerCompat — the only Android
                // dependency, and the only Android code in the module.
                implementation(libs.core.ktx)
                // ⚠ `koin-core` only, not the Compose or Android artefacts: a
                // framework-instantiated `BroadcastReceiver` has no constructor to
                // inject through, so `AlertActionReceiver` resolves its handler as
                // a `KoinComponent` — the `TenshuOperationWorker` precedent.
                implementation(libs.koinCore)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // ⚠⚠ **Kotlin/JS ships no time-zone database.** `kotlinx-datetime` delegates to js-joda
        // there, whose core carries UTC and fixed offsets only, so `TimeZone.of("Europe/Berlin")`
        // throws `IllegalTimeZoneException: Invalid zone ID` — every `AppleAlertScheduleTest`
        // case would fail on the browser target while passing on every other one.
        //
        // ⚠ `jsTest`, not `jsMain`: this module's production code only ever *receives* a
        // `TimeZone` (`AppleAlertSchedule.fireComponents(deliverAt, zone)`) and never resolves a
        // name, so shipping ~180 KB of tzdata to a browser bundle would be paying for a lookup
        // nothing performs. Move it to `jsMain` the day something here names a zone.
        //
        // ⚠ Not a fixed offset in the test instead: a named zone
        // with DST is the case the iOS consumer actually passes, and one of these tests exists to
        // prove the components are the wall clock *of the zone it is given*.
        getByName("jsTest") {
            dependencies {
                implementation(npm("@js-joda/timezone", "2.3.0"))
            }
        }
    }
}
