plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

// Fit Android app shell (docs/proposals/fit-app.md). ComponentActivity + Application
// that start Koin (mock modules) and render the shared App() inside TenshuTheme — the
// 412 px frame parity target. Mirrors :timeline-android.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.fit.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.fit.android"
        minSdk = 26   // Health Connect (connect-client 1.1.0) requires API 26+ (P5.2)
        targetSdk = 36
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    configurations.all {
        resolutionStrategy {
            force("androidx.compose.material3:material3:${libs.versions.material3.get()}")
            force("androidx.compose.material3:material3-android:${libs.versions.material3.get()}")
        }
    }

    testOptions {
        // HealthConnectImporterUnavailableTest builds the real importer over a fake Context
        // (`ContextWrapper(null)`, whose android.jar constructor otherwise throws "Stub!"); the
        // SDK status comes from the importer's seam, so no Health Connect call reaches a stub.
        // Same recipe as :core-common's BinderTaskTransportRebindTest.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":fit-shared-ui"))
    // Direct dep: the Android live-import binding (HealthConnectImporter) lives in :fit-shared-data's
    // androidMain, which :fit-shared-ui only depends on with `implementation` (not transitive).
    implementation(project(":fit-shared-data"))
    implementation(project(":fit-shared-logic"))
    implementation(project(":ui-core"))
    // The manifest declares org.tenshu.core.common.ClientCallbackService (the reverse Binder
    // channel). :fit-shared-ui only pulls core-common transitively as `implementation`, so lint's
    // MissingClass check can't see it on the app's compile classpath — depend on it directly like
    // :drive-android and :maps-android do.
    implementation(project(":core-common"))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    // AppForegroundState + the refusable-FGS-start helpers (FitImportServiceLauncher's KDoc).
    implementation(project(":app-liveness"))
    implementation(libs.kotlinx.datetime)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.koin)

    // Health Connect (P5.2): the permission contract + the live-import HealthConnectImporter binding.
    implementation(libs.health.connect.client)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

// HealthPermissionManifestTest reads AndroidManifest.xml off disk rather than through the Android
// runtime (it is a plain JVM unit test), so Gradle cannot infer the dependency: deleting a
// <uses-permission> line would otherwise leave the test UP-TO-DATE and silently unrun — exactly the
// gap that let the READ_HEIGHT regression ship. Declaring it as an input makes the manifest part of
// the task's fingerprint.
tasks.withType<Test>().configureEach {
    inputs.file("src/main/AndroidManifest.xml")
        .withPropertyName("androidManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

repositories {
    mavenCentral()
    google()
}
