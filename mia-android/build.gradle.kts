plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Mia Android app shell (docs/proposals/home-and-mia-apps.md §6). Mirrors :home-android:
// ComponentActivity + Application starting Koin (mock modules) and rendering the shared
// App() inside TenshuTheme. M4 adds the widget (Glance) + the assistant-role request.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.mia.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.mia.android"
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
    implementation(project(":mia-shared-ui"))
    implementation(project(":mia-shared-logic"))
    implementation(project(":ui-core"))
    // Schema-lockstep beacon only (`TENSHU_STORAGE_FORMAT_VERSION` + `schemaLockstepLine`),
    // mirroring `home-android`. Mia is still mock-only with no Control client; this buys the
    // smoke gate a uniform reading across all installed apps instead of a WARN-shaped hole.
    implementation(project(":core-common"))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.koin)

    // The "Ask Mia" home-screen widget (M4).
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

repositories {
    mavenCentral()
    google()
}
