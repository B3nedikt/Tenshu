plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

// Timeline Android app shell (docs/proposals/timeline-app.md). ComponentActivity +
// Application that start Koin (mock modules) and render the shared App() inside
// TenshuTheme — the 412 px frame parity target. Mirrors :drive-android.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.timeline.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.timeline.android"
        minSdk = 24
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":app-liveness"))
    implementation(project(":timeline-shared-ui"))
    implementation(project(":ui-core"))
    // Live-capture (I3b): the LocationCaptureSource / engine + InProcessTimelineProxy the
    // foreground service drives, and the AndroidLocationCaptureSource in its androidMain.
    // :core-common / :core supply TenshuClient / TContext for the proxy's client seam.
    implementation(project(":timeline-shared-data"))
    implementation(project(":diagnostics"))
    implementation(project(":core-common"))
    implementation(project(":core"))
    // P3 venue naming: the proxy-mediated ReverseGeocode op (Control does the Photon egress).
    implementation(project(":map-region-service"))
    implementation(project(":map-region-service-client"))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
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

    // WorkManager — the capture watchdog that re-arms the foreground service after an OEM
    // process-kill (mirrors Drive's BackupWorker). More resilient to force-stop than a bare FGS.
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

repositories {
    mavenCentral()
    google()
}
