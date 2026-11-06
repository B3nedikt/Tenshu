plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.calendar.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    // ⚠ No `js` target, deliberately — do not add one without a consumer.
    //
    // Nothing would consume it: `:calendar-shared-ui` is android+desktop, `tenshu-sdk-ios-calendar`
    // is iOS, no `web/` project references Calendar and `:tenshu-sdk-js` does not export it. It
    // would cost a `CalendarDateFormat` actual written only to satisfy the compiler, and — like
    // Fit's, see `fit-app.md` §9.10a — it blocks `:core-paging`, whose `paging-common` publishes no
    // JS artifact.
    //
    // Nor is it needed as a **portability canary** (`calendar-app.md` records one catching
    // `Math.floorDiv`, a JVM-only API the `jvm()` target compiles happily): this module also
    // targets iOS, and Kotlin/Native rejects every `java.*` API just as JS does, so the same
    // mistake fails on `iosArm64`.
    //
    // House Rule #5 is the general form: the *protocol* layer is shared via KMP → JS, an app's
    // *logic* layer is not (`drive-shared-logic` never had a JS target). `:calendar-service*`
    // keeps its own, correctly — that is protocol.

    // ⚠ Compose-free by construction (House Rule #5 / the `:basemap-core` D7
    // precedent): this module is the half an iOS shell can consume, so no
    // Compose dependency may ever be added here. The Compose UI lives in
    // `:calendar-shared-ui`.
    // ⚠ No `iosX64()` — deliberately, and every sibling `*-shared-logic` agrees. The app frameworks
    // cannot link an Intel-simulator slice at all (`:core-paging` / `:drive-shared-logic` pull
    // `androidx.paging:paging-common`, which ships no iosX64 klib — see `tenshu-sdk-ios-calendar`), and
    // `calendar-ios/project.yml` sets `EXCLUDED_ARCHS[iphonesimulator*] = x86_64`.
    // Declaring it here only produces `KMP Dependencies Resolution Failure` errors on every
    // build, because `:app-reachability` — like every other module at this layer — has no
    // iosX64 slice to resolve against.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":calendar-service"))
                api(project(":calendar-service-common"))
                api(project(":calendar-service-client"))
                api(project(":core-common"))
                // The OS-notification seam. `api` so a shell binding a
                // presenter sees the types without depending on it twice.
                api(project(":app-notify"))
                // The one Control-reachability engine an app shell reads to
                // explain an empty screen. `api` for the same reason as above — the
                // iOS surface hands `ControlReachability.state` straight to Swift.
                api(project(":app-reachability"))
                api(project(":preference-service"))
                api(project(":preference-service-client"))
                implementation(libs.kotlinx.datetime)
                // ⚠ `lifecycle-viewmodel` only — NOT the `-compose` sibling.
                // This module must stay Compose-free so an iOS shell can use it.
                api(libs.lifecycle.viewmodel)
            }
        }
        // ⚠ Shared JVM parent for androidMain + jvmMain: the default hierarchy
        // template doesn't materialise one under the Android KMP plugin, and
        // `CalendarDateFormat`'s `java.text` actual is identical on both (it
        // avoids `java.time` precisely so no API-26 branch is needed).
        val javaMain = create("javaMain") { dependsOn(commonMain) }
        getByName("androidMain") { dependsOn(javaMain) }
        getByName("jvmMain") { dependsOn(javaMain) }

        getByName("commonTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":calendar-service-test-utils"))
                implementation(project(":calendar-service-proxy"))
            }
        }
    }
}
