plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Contacts' Compose-free logic layer — domain model, the `ContactsRepository` seam, the
// mock repository, and the view models (docs/proposals/contacts-app.md §6).
// Mirrors :maps-shared-logic, so it compiles for iOS (route 1) too; the Compose
// UI lives in :contacts-shared-ui, which depends on this. Packages are shared across the
// two modules (`app.tenshu.contacts.*`) so a screen and its view model resolve each other
// over the module boundary with no import.
//
// NO service dependencies here — this half is UI-over-mock by D8. The real read surface
// (`:contact-service` over a TenshuClient) lives in :contacts-shared-data and replaces the
// mock behind the same interface with one Koin binding.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.contacts.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // Compose-free base ViewModel + viewModelScope (the view models extend it).
                api(libs.lifecycle.viewmodel)
                // `api`: UnreachableReason rides ContactsRepository.controlUnreachableReason(),
                // so :contacts-shared-data and :contacts-shared-ui both read it (SK-1b,
                // `core-schema-skew-surface.md`).
                api(project(":app-reachability"))
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                api(project(":app-error-handling"))
                // `ErrorCopyKey` on the UiStates — `core-error-copy-catalogue.md` §5.0: the VM
                // layer carries a catalogue key and the Compose layer renders it, so no proxy
                // sentence reaches a screen. `api`, since the type appears in the signatures.
                api(project(":core"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        // Shared JVM parent for androidMain + desktopMain — the default hierarchy template
        // doesn't materialise this under the Android KMP plugin. Present for symmetry with
        // :maps-shared-logic even though there are no JVM-only actuals yet.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") { dependsOn(jvmMain) }
        getByName("desktopMain") { dependsOn(jvmMain) }

        getByName("commonTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
