plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Home Android app shell (docs/proposals/home-and-mia-apps.md §6). ComponentActivity +
// Application that start Koin and render the shared App() inside TenshuTheme — the 412 px
// frame parity target. Mirrors :timeline-android: since HD-3 it carries the same Binder
// client to Control (hub status + bridged devices, INTERNET stripped in the manifest);
// still no capture and no permissions of its own.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.home.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.home.android"
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
    implementation(project(":home-shared-ui"))
    implementation(project(":home-shared-logic"))
    implementation(project(":ui-core"))
    // HD-3: the schema-lockstep beacon + the client assembly Home now starts.
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

repositories {
    mavenCentral()
    google()
}
