plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Contacts' Compose UI — screens, shared composables, App(), navigation, DI assembly
// (docs/proposals/contacts-app.md §6). Depends on :contacts-shared-logic as `api` (VM /
// repository types flow transitively to the app shells) and :ui-core as implementation
// (TenshuTheme + tokens + AdaptiveNavScaffold + KmpNavHost). android + desktop only —
// the iOS shell is SwiftUI over a Surface seam (Ph10).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.contacts.shared"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                implementation(project(":app-error-handling"))
                implementation(project(":ui-core"))
                // Realm storage, for `RealmStorageBanner` (drive-synced-folders.md §8b.5c).
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // `api` so the app shells (:contacts-desktop, :contacts-android) get VM /
                // repository types transitively; screens here resolve them via the shared
                // package across the module boundary (no import).
                api(project(":contacts-shared-logic"))
                // The real read surface — TenshuContactsRepository over the IPC TenshuClient
                // (Ph2). Bound in contactsModules(); the mock stays a one-line revert.
                implementation(project(":contacts-shared-data"))
                // Contacts' client graph (IPC to Control): the transport/client machinery +
                // the wire modules registered in coreSchemaConfigs.
                implementation(project(":core-common"))
                // SK-3 (`core-schema-skew-surface.md` §8.3.2, Option A): read this device's own
                // realm pull fence and the proxy's RuntimeIdentity, so the shell banner can name
                // the app that actually needs updating.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-common"))
                implementation(project(":sync-service-client"))
                implementation(project(":contact-service-common"))
                implementation(project(":contact-service-client"))
                implementation(project(":preference-service-common"))
                implementation(project(":preference-service-client"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.uiUtil)
                implementation(compose.components.resources)
                implementation(compose.preview)
                implementation(compose.materialIconsExtended)

                implementation(libs.koinMultiplatform)
                implementation(libs.koinComposeViewmodel)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") {
            dependsOn(jvmMain)
            dependencies {
                // `:core` for the FD / SharedMemory caches BinderTaskTransport requires
                // (they arrive as `implementation` everywhere else, so not transitive),
                // koin for the module DSL in a non-Compose file.
                implementation(project(":core"))
                implementation(libs.koin)
                implementation(libs.ui.tooling)
                // Ph7 platform import: the Compose ActivityResult launchers (contact picker,
                // document picker, permission request) + ContextCompat's permission check.
                implementation(libs.activity.compose)
                implementation(libs.core.ktx)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.savedstate)
                implementation(libs.savedstate.compose)
            }
        }
        // Compose-render coverage for the Contacts screens (mirrors Home/Fit/Timeline's
        // `desktopTest` harness): the JUnit4 desktop test runner + a real Main dispatcher
        // (Swing) so `viewModelScope` collectors run.
        getByName("desktopTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(project(":core-test-utils"))
                implementation(compose.desktop.uiTestJUnit4)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// ADR 0016 mirror: pin the generated `Res` accessor package so call sites can
// `import app.tenshu.contacts.shared.resources.Res` predictably.
compose.resources {
    packageOfResClass = "app.tenshu.contacts.shared.resources"
    generateResClass = always
}
