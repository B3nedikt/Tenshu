plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

// Calendar Android app shell (docs/proposals/calendar-app.md §6 Ph0). ComponentActivity +
// Application that start Koin and render the shared App() inside TenshuTheme — the 412 px
// frame parity target. Mirrors :maps-android: it carries the same Binder client to Control
// (the `:contact-service` read surface, Ph2), with INTERNET stripped in the manifest.
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.calendar.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.calendar.android"
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

    // ⚠ One scoped ignore, explained in the file: Calendar carries `:app-notify`'s Android
    // presenter on its classpath but never builds one — Control posts the reminders.
    lint {
        lintConfig = file("lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":calendar-shared-ui"))
    implementation(project(":ui-core"))
    // The manifest declares org.tenshu.core.common.ClientCallbackService (the reverse Binder
    // channel). :calendar-shared-ui only pulls core-common transitively as `implementation`,
    // so lint's MissingClass check can't see it on the app's compile classpath — depend on
    // it directly like :drive-android does. Also carries the schema-lockstep beacon.
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
    implementation(libs.kotlinx.serialization.core)
    // CA-7c's home-screen widget (the `:mia-android` precedent).
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.koinCore)
    implementation(libs.kotlinx.datetime)

    implementation(libs.koin)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

repositories {
    mavenCentral()
    google()
}
