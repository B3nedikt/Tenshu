// The offline-basemap render hop shared by the iOS apps that draw a map — Fit, Timeline and Maps
// (`docs/proposals/ios-per-app-frameworks.md` §5.1, §5.3): `IosRegionSupport`'s stager and terminus
// over `:map-region-app`. Each app's `RegionBundleResolver` adapter is that app's own code, so a
// framework that links this links no other app's data layer.
//
// An ordinary KMP library, not exported: Swift never names `RegionTerminus`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("iosMain") {
            dependencies {
                api(project(":core-common"))
                api(project(":map-region-app"))
            }
        }
    }
}
