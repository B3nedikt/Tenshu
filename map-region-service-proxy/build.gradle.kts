plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Emits serializers for the @Serializable catalog-JSON DTOs + the local install index.
    alias(libs.plugins.kotlinx.serialization)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "org.tenshu.mapregion.proxy"
        compileSdk = 37
        minSdk = 24
    }

    jvm()

    js {
        browser()
        // Matches the :core-common convention so ES-module imports between
        // KMP modules compose. See docs/web/architecture.md §12.6.1.
        useEsModules()
    }

    // commonTest fixtures pull the runBlocking-bridged TestProxy from nonJsMain.
    tasks.matching { it.name == "compileTestKotlinJs" }.configureEach { enabled = false }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":map-region-service-common"))
                api(project(":core-proxy"))
                // PMTiles directory + MVT decoding + rail filtering for the railLinesIn op (M9-4).
                // Pure and dependency-free, so it adds nothing to the proxy's footprint.
                implementation(project(":vector-tile"))
                implementation(libs.kotlinx.coroutines)
                // HTTP catalog fetch + bundle download (the proxy ↔ tile-host leg, D6). The
                // HttpClient itself is injected at the DI site with the platform engine (slice
                // 2c); this module only needs the multiplatform client API + JSON.
                api(libs.ktorClientCore)
                api(libs.ktorClientContentNegotiation)
                api(libs.ktorSerializationJson)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":core-test-utils"))
                implementation(project(":map-region-service-client"))
                implementation(project(":map-region-service-test-utils"))
                // MockEngine drives HttpRegionCatalogSource / RegionDownloader without a network.
                implementation(libs.ktorClientMock)
            }
        }
    }
}
