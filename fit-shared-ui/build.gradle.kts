plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Fit's Compose UI — screens, shared composables (Ring / TrendBars / HrChart /
// RoutePath / SourceBadge / …), App(), navigation, DI assembly
// (docs/proposals/fit-app.md). Depends on :fit-shared-logic as `api` (VM / repository
// types flow transitively to the app shells) and :ui-core as implementation
// (TenshuTheme + tokens + AdaptiveNavScaffold + KmpNavHost). android + desktop only
// (no iOS UI in v1).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.fit.shared"
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
                // `api` so the app shells (:fit-desktop, :fit-android) get VM /
                // repository types transitively; screens here resolve them via the
                // shared package across the module boundary (no import).
                api(project(":fit-shared-logic"))
                // The real data layer — the projector repository the DI binds over Control's client.
                implementation(project(":fit-shared-data"))
                // Fit's data plane: `:health-service` schema + client modules, registered into the
                // client that reaches Control's proxy (fitClientModule). Control hosts the service.
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
                // FA-1: the timeline schema + client modules, registered into the same client so
                // Fit can read `MovementTrace`. Control already hosts the service.
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-client"))
                // TS-4: task-composite client surface for the import task.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-common"))
                implementation(project(":sync-service-client"))

                // DS-7: the device roster, so a Data sources row can show the name the user gave
                // that device. ⚠ Registered in `fitClientModule` too — the module edge alone
                // compiles, but the read fails at dispatch without the runtime registration.
                implementation(project(":identity-service-common"))
                implementation(project(":identity-service-client"))
                // The realm-wide storage answer behind `RealmStorageBanner` — `StorageSyncGate`
                // gates the personal realm's PUSH at the scheduler, so an unfunded or full realm
                // stops Fit's workouts leaving the device just as surely as Drive's photos.
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // The offline basemap surface for workout routes (maps-region-delivery.md R6) +
                // Fit's best-effort region client reaching Control's MapRegionService (D6).
                implementation(project(":ui-map"))
                implementation(project(":core-common"))
                implementation(project(":map-region-app"))
                implementation(project(":map-region-service-common"))
                implementation(project(":map-region-service-client"))

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
                // collectAsLazyPagingItems + LazyPagingItems for History's paged feed; the
                // `Flow<PagingData<Workout>>` itself comes from :fit-shared-logic, which
                // `api`-exposes :core-paging / paging-common.
                implementation(libs.paging.compose)
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
                implementation(libs.koin)
                implementation(libs.ui.tooling)
                // JIT recorder permissions (§9.8): the Compose permission launcher + ContextCompat.
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
        // Compose-render coverage for the Fit screens (mirrors Drive/Control's
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
// `import app.tenshu.fit.shared.resources.Res` predictably.
compose.resources {
    packageOfResClass = "app.tenshu.fit.shared.resources"
    generateResClass = always
}
