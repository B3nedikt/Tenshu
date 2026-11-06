plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Maps' Compose UI — screens, shared composables, App(), navigation, DI assembly
// (docs/proposals/maps-app.md §6). Depends on :maps-shared-logic as `api` (VM /
// repository types flow transitively to the app shells) and :ui-core as implementation
// (TenshuTheme + tokens + KmpNavHost). android + desktop only (no iOS UI in v1).
//
// Ph0 = scaffold: a placeholder App() + empty DI + the two shells. The real screens land
// Ph4–Ph6; the MapLibre basemap snippet is isolated to Ph7.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.maps.shared"
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
                // The offline MapLibre basemap surface (MapSnippet wraps :ui-map's OfflineBasemap).
                implementation(project(":ui-map"))
                // `api` so the app shells (:maps-desktop, :maps-android) get VM /
                // repository types transitively; screens here resolve them via the shared
                // package across the module boundary (no import).
                api(project(":maps-shared-logic"))
                // The real region data layer — ClientRegionRepository over the IPC TenshuClient.
                implementation(project(":maps-shared-data"))
                // The shared render terminus — the platform RegionBundleStager bound below in
                // PlatformClientModule (JvmRegionBundleStager) lives here.
                implementation(project(":map-region-app"))
                // Maps' first client graph (IPC to Control): the transport/client machinery +
                // the map-region wire modules registered in coreSchemaConfigs (D6).
                implementation(project(":core-common"))
                // SK-3 (`core-schema-skew-surface.md` §8.3.2, Option A): read this device's own
                // realm pull fence and the proxy's RuntimeIdentity, so the shell banner can name
                // the app that actually needs updating.
                implementation(project(":sync-service"))
                implementation(project(":sync-service-common"))
                implementation(project(":sync-service-client"))
                // Maps' data plane: :place-service, registered into the client that reaches
                // Control's proxy (mapsClientModule). Control hosts it, shared with Timeline.
                implementation(project(":place-service-common"))
                implementation(project(":place-service-client"))
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
                implementation(project(":core-test-utils"))
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
// `import app.tenshu.maps.shared.resources.Res` predictably. String resources land Ph4.
compose.resources {
    packageOfResClass = "app.tenshu.maps.shared.resources"
    generateResClass = always
}
