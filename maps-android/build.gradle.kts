plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

// Maps Android app shell (docs/proposals/maps-app.md §6 Ph0). ComponentActivity +
// Application that start Koin (Ph0: empty modules) and render the shared App() inside
// TenshuTheme — the 412 px frame parity target. Mirrors :timeline-android.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.maps.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.maps.android"
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
    implementation(project(":maps-shared-ui"))
    implementation(project(":ui-core"))
    // The manifest declares org.tenshu.core.common.ClientCallbackService (the reverse Binder
    // channel). :maps-shared-ui only pulls core-common transitively as `implementation`, so lint's
    // MissingClass check can't see it on the app's compile classpath — depend on it directly like
    // :drive-android does.
    implementation(project(":core-common"))
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

repositories {
    mavenCentral()
    google()
}
