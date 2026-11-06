plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Home's Compose UI — App(), the four-tab shell, the Devices screen, DI assembly
// (docs/proposals/home-and-mia-apps.md §3/§6). Depends on :home-shared-logic as `api`
// (VM / repository types flow transitively to the app shells) and :ui-core as
// implementation (TenshuTheme + tokens + AdaptiveNavScaffold + KmpNavHost). android +
// desktop only (iOS UI is SwiftUI over a Surface seam, M4).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.home.shared"
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
                // `api` so the app shells (:home-desktop, :home-android) get VM /
                // repository types transitively; screens here resolve them via the
                // shared package across the module boundary (no import).
                api(project(":home-shared-logic"))
                // D1: Home embeds Mia's chat surface — the conversation UI is authored
                // once in :mia-shared-ui and the "Ask Mia" destination renders it here.
                implementation(project(":mia-shared-ui"))
                // Home's minimal Tenshu client assembly (homeClientModule): core
                // client + the hub-management surface for the live storage tile.
                // Wired at runtime by :home-desktop only in this slice; Android
                // stays on the mock until Control-on-Android gains the
                // hub-management wiring (tracked follow-up).
                implementation(project(":core-common"))
                implementation(project(":mia-service-common"))
                implementation(project(":mia-service-client"))
                implementation(project(":hub-management-service-common"))
                implementation(project(":hub-management-service-client"))
                implementation(project(":routine-service"))
                implementation(project(":routine-service-common"))
                implementation(project(":routine-service-client"))

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
                // HD-3: the Binder half of Home's client (homePlatformClientModule).
                // `:core` for the FD / SharedMemory caches BinderTaskTransport requires
                // (arrives as `implementation` everywhere else, so not transitive), koin
                // for the module DSL in a non-Compose file.
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
        // Compose-render coverage for the Home screens (mirrors Fit/Timeline's
        // `desktopTest` harness): the JUnit4 desktop test runner + a real Main
        // dispatcher (Swing) so `viewModelScope` collectors run.
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
// `import app.tenshu.home.shared.resources.Res` predictably.
compose.resources {
    packageOfResClass = "app.tenshu.home.shared.resources"
    generateResClass = always
}
