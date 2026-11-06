plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Calendar's Compose UI — the month grid and agenda (calendar-app.md CA-2).
// ⚠ android + desktop only, deliberately: no CMP UI ships on iOS (B-1), so the
// iOS month view is SwiftUI over the SAME `MonthGrid` math in
// :calendar-shared-logic. That module stays Compose-free for exactly this.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.calendar.shared"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(project(":ui-core"))
                // Realm storage, for `RealmStorageBanner` (drive-synced-folders.md §8b.5c).
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // `api` so the app shells get the VM and repository types.
                api(project(":calendar-shared-logic"))
                implementation(project(":core-common"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)
                implementation(libs.kotlinx.datetime)
                // ⚠ The runtime only — no compiler plugin, matching `:contacts-shared-ui` and
                // `:photos-shared-ui`. `@Serializable` on the navigation routes is the sibling
                // convention; `KmpNavHost` routes by TYPE and the back stack is in memory, so
                // nothing here is actually serialised today.
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.lifecycle.viewmodel.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.koinCore)
                implementation(libs.koinMultiplatform)
                implementation(libs.koinComposeViewmodel)
                api(project(":calendar-service"))
                api(project(":calendar-service-client"))
                api(project(":calendar-service-common"))
                api(project(":preference-service"))
                api(project(":preference-service-client"))
                api(project(":preference-service-common"))
                // The device id every per-device preference row is keyed on
                // comes from the sync service's status row.
                api(project(":sync-service"))
                api(project(":sync-service-client"))
                api(project(":sync-service-common"))
            }
        }

        // CA-6b's file picker and saver, plus CA-6d's intent reading. ⚠ Only these
        // two source sets exist because `:calendar-shared-ui` is android + desktop
        // only. The iOS Calendar shell (CA-8) does exist now and does its own `.ics`
        // import — over SwiftUI's `.fileImporter`, in `calendar-ios`, needing no
        // actual here at all (CA-6c).
        getByName("androidMain") {
            dependencies {
                // The Compose `ActivityResult` launchers behind `OpenDocument` /
                // `CreateDocument`. ⚠ No storage *permission* comes with them, and
                // none is declared: the Storage Access Framework's grant is scoped
                // to the one file the user chose.
                implementation(libs.activity.compose)
            }
        }
        getByName("desktopMain") {
            dependencies {
                // `Dispatchers.Swing`: an AWT `FileDialog` is modal and must open on
                // the event thread.
                implementation(libs.kotlinx.coroutines.swing)
                // *Copy from this Mac* (calendar-external-sources.md CX-2c): EventKit over the
                // FFM Objective-C bridge. ⚠ Emits JVM-17 bytecode, so this module stays on 17; the
                // runtime that executes it is `:calendar-desktop`'s bundled JDK 25.
                implementation(project(":macos-helpers"))
            }
        }

        // ⚠ The month grid's sizing is a *measurement* fact — it fails by rendering
        // the wrong thing, never by throwing — so the only honest test of it renders
        // it at a real window size and reads the layout back. Same harness as
        // `:ui-core`'s `ContentWidthOrderTest`.
        getByName("desktopTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(project(":core-test-utils"))
                implementation(compose.desktop.uiTestJUnit4)
                implementation(kotlin("test"))
            }
        }
    }
}

// ADR 0016 mirror: pin the generated `Res` accessor package so call sites can
// `import app.tenshu.calendar.shared.resources.Res` predictably.
compose.resources {
    packageOfResClass = "app.tenshu.calendar.shared.resources"
    generateResClass = always
}
