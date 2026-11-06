plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Mia's Compose-free logic layer — the conversation model, the mock assistant backend,
// and the view models (docs/proposals/home-and-mia-apps.md §4/§6). Mirrors
// :home-shared-logic; the Compose UI lives in :mia-shared-ui. Packages are shared across
// the two modules (`app.tenshu.mia.*`).
//
// M3 had NO service dependencies — the assistant was a keyword-routed mock behind the
// ConversationRepository seam. MQ-1 (mia-query-layer.md) gives Mia its FIRST Tenshu client
// dependency: `TenshuConversationRepository` asks the proxy-side query layer, and the mock
// stays only as the not-connected default binding.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.mia.logic"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // Compose-free base ViewModel + viewModelScope (the VMs extend it).
                api(libs.lifecycle.viewmodel)
                // MQ-D2: Mia's first client stack. `api` because TenshuClient appears in
                // TenshuConversationRepository's public constructor, exactly as Home's
                // ClientHubStatusSource did when Home stopped being client-less.
                api(project(":mia-service-client"))
                // MQ-2b: the grant switches ride ordinary realm preferences.
                api(project(":preference-service"))
                api(project(":preference-service-client"))
                // `ControlReachability` for the shell's Control-unavailable surface (ADR 0025).
                // `api`, since the reason type appears in `MiaReachability`'s signature.
                api(project(":app-reachability"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
            }
        }
        // Shared JVM parent for androidMain + desktopMain, mirroring the sibling apps.
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
