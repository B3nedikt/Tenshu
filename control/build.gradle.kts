import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.control.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.control.android"
        minSdk = 24
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(project(":control-shared"))
    // TS-3 (core-task-composite.md §6): ProxyService injects TaskRegistry so live
    // bulk tasks contribute to the foreground-service liveness sum.
    implementation(project(":sync-service-proxy"))
    // §14.1 (drive-upload-progress.md): ProxyService injects IngestRunRegistry so the
    // foreground notification stands down while an app narrates its own ingest run.
    implementation(project(":ingest-service-proxy"))
    // §34: `ProxyService` renders the index pass's position in the foreground notification, and
    // reads it from the same `IndexProgressHolder` singleton the indexer ticks.
    implementation(project(":inference-service-proxy"))
    implementation(project(":ui-core"))
    implementation(project(":core"))
    implementation(project(":core-aidl"))
    implementation(project(":core-common"))
    implementation(project(":file-service"))
    implementation(project(":file-service-common"))
    implementation(project(":file-service-client"))
    implementation(project(":file-service-proxy"))
    implementation(project(":notification-service"))
    implementation(project(":notification-service-common"))
    implementation(project(":notification-service-client"))
    implementation(project(":notification-service-proxy"))
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.process)
    implementation(project(":app-liveness"))
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.datetime)

    implementation(libs.koin)

    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}

// ⚠⚠ Control uploads no Google telemetry (AndroidManifest.xml, the TransportBackendDiscovery
// removal): MediaPipe and Play Billing log through `datatransport`, which can only send once a
// `backend:` entry reaches the merged manifest. A dependency bump that renames the service, or
// adds a second discovery entry, would silently re-enable the upload — so every assemble, bundle
// and install of Control fails if one is there.
abstract class CheckNoTelemetryBackend : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile.readText()
        val found = listOf("TransportBackendDiscovery", "backend:com.google.android.datatransport")
            .filter { it in manifest }
        if (found.isNotEmpty()) {
            throw GradleException(
                "Control's merged manifest re-enables Google telemetry upload ($found): strip it " +
                    "with tools:node=\"remove\" in control/src/main/AndroidManifest.xml.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckNoTelemetryBackend>("checkNoTelemetryBackend$name") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name in setOf("assemble$name", "bundle$name", "install$name") }
            .configureEach { dependsOn(check) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
    }
}
