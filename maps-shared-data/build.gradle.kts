plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Maps' real data layer — the client-backed `RegionRepository` (offline basemap region
// delivery, docs/proposals/maps-region-delivery.md D5/D6) behind the same seam the Maps UI
// consumes, plus `InProcessMapRegionProxy`: a local in-process proxy hosting
// `:map-region-service` (mirrors Timeline's InProcessTimelineProxy, P3b) so the picker lights
// up over the real service surface without inter-app IPC yet. Compose-free; mirrors
// :maps-shared-logic's targets.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.maps.data"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // The RegionRepository seam + RegionItem presentation model.
                api(project(":maps-shared-logic"))
                // The Control-reachability engine (fit-app.md §9.14, shared). `api`: the engine
                // type appears in the repository constructors + the mapsControlReachability
                // factory the DI module calls.
                api(project(":app-reachability"))
                // The shared app-side render terminus (RegionBundleStager + RegionTerminus),
                // reused across :ui-map consumers. `api` so :maps-shared-ui sees the stager type
                // it binds per platform.
                api(project(":map-region-app"))

                // The map-region service, read over a TenshuClient. The `-proxy` module +
                // :core-common are needed by InProcessMapRegionProxy (the production twin of
                // TestProxy) which hosts the service in-process for the app.
                implementation(project(":core"))
                implementation(project(":core-common"))
                implementation(project(":core-client"))
                implementation(project(":map-region-service"))
                implementation(project(":map-region-service-common"))
                implementation(project(":map-region-service-client"))
                implementation(project(":map-region-service-proxy"))

                // The place-service, read over a TenshuClient (Ph9): the real POI store behind the
                // PlaceRepository seam. -proxy + :core-common host it in-process (InProcessMapsPlaceProxy).
                implementation(project(":place-service"))
                implementation(project(":place-service-common"))
                implementation(project(":place-service-client"))
                // NOT :place-service-proxy — Control hosts :place-service (maps-app.md §7). The app
                // is a client; the proxy module is a *test* dependency (the in-process harness).

                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                // The pipeline test stands :place-service up in-process; the app never does.
                implementation(project(":place-service-proxy"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
            }
        }
    }
}
