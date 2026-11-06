plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Mia's Compose UI — the chat surface (bubbles + rich-result cells), History, Settings,
// the three-tab App() shell, and DI assembly (docs/proposals/home-and-mia-apps.md §4/§6).
// Depends on :mia-shared-logic as `api`; :home-shared-ui also depends on THIS module so
// Home can embed the identical chat surface (D1 — the conversation UI is authored once).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.mia.shared"
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
                implementation(project(":ui-core"))
                // Realm storage, for `RealmStorageBanner` (drive-synced-folders.md §8b.5c).
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // ADR 0025 §Decision 8: `getSyncStatus` for the Control-not-set-up wall.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-common"))
                implementation(project(":sync-service-client"))
                // `api` so the app shells and :home-shared-ui get VM / repository types
                // transitively.
                api(project(":mia-shared-logic"))

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
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
                // MQ-1: Mia's first client stack (`miaClientModule`). The service
                // modules only — Mia's cross-service reads happen on the PROXY (MQ-D2),
                // so this must never grow into file-service/timeline-service deps.
                implementation(project(":core-common"))
                implementation(project(":mia-service-common"))
                implementation(project(":mia-service-client"))
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
                implementation(libs.ui.tooling)
                // The Binder half of Mia's client (`miaPlatformClientModule`). `:core`
                // for the FD / SharedMemory caches `BinderTaskTransport` requires (it
                // arrives as `implementation` everywhere else, so it is not transitive),
                // koin for the module DSL in a non-Compose file.
                implementation(project(":core"))
                implementation(libs.koin)
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

// ADR 0016 mirror: pin the generated `Res` accessor package.
compose.resources {
    packageOfResClass = "app.tenshu.mia.shared.resources"
    generateResClass = always
}
