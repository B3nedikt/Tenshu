plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Timeline's Compose UI — screens, shared composables, App(), navigation, DI assembly
// (docs/proposals/timeline-app.md). Depends on :timeline-shared-logic as `api` (VM /
// repository types flow transitively to the app shells) and :ui-core as implementation
// (TenshuTheme + tokens + KmpNavHost). android + desktop only (no iOS UI in v1).
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.timeline.shared"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        getByName("desktopTest") {
            dependencies {
                implementation(project(":viewmodel-test-utils"))
                implementation(project(":core-test-utils"))
                // Compose UI tests for the shared screens — the same harness `drive-shared-ui`
                // uses (FilesScreenIntegrationTest). HR #7: tests over screenshots.
                implementation(compose.desktop.uiTestJUnit4)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlin.test)
            }
        }
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":ui-core"))
                // Realm storage, for `RealmStorageBanner` — `StorageSyncGate` gates the personal realm's
                // PUSH at the scheduler, so an unfunded or full realm stops Timeline's moments leaving
                // the device just as surely as Drive's photos.
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // The offline basemap surface for trip routes (maps-app.md §2 step 3 / R6).
                implementation(project(":ui-map"))
                // `api` so the app shells (:timeline-desktop, :timeline-android) get VM /
                // repository types transitively; screens here resolve them via the shared
                // package across the module boundary (no import).
                api(project(":timeline-shared-logic"))
                // The real data layer (in-process proxy + projection repository over the
                // three services) — P3b part 2. Bound in `timelineRealRepositoryModule()`.
                implementation(project(":timeline-shared-data"))
                // FP-6: the owner's units ride the preference service (fit-app.md §18.13).
                implementation(project(":preference-service"))
                implementation(project(":preference-service-common"))
                implementation(project(":preference-service-client"))
                implementation(project(":diagnostics"))
                // Timeline's best-effort region client (maps-region-delivery.md D6): a second
                // TenshuClient reaching Control's MapRegionService over IPC, feeding the shared
                // render terminus. The client machinery + the map-region wire modules.
                implementation(project(":core-common"))
                // SK-3 (`core-schema-skew-surface.md` §8.3.2, Option A): read this device's own
                // realm pull fence and the proxy's RuntimeIdentity, so the shell banner can name
                // the app that actually needs updating.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-common"))
                implementation(project(":sync-service-client"))
                // Timeline's data plane: the three services it speaks, registered into the client
                // that reaches Control's proxy (timelineClientModule). Control hosts them.
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-client"))
                implementation(project(":place-service-common"))
                implementation(project(":place-service-client"))
                // T-P1 (`timeline-photos.md`): the wire + client halves of `:file-service`, so the
                // moment hero can read the photos you took out of Drive's tree. Registered in
                // `timelineClientModule` — the client half is what makes `File.Preview` resolvable.
                implementation(project(":file-service-common"))
                implementation(project(":file-service-client"))
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
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
    }
}

// ADR 0016 mirror: pin the generated `Res` accessor package so call sites can
// `import app.tenshu.timeline.shared.resources.Res` predictably. Bundled fonts live
// under commonMain/composeResources/font/.
compose.resources {
    packageOfResClass = "app.tenshu.timeline.shared.resources"
    generateResClass = always
}
