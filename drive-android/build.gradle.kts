plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "app.tenshu.drive.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tenshu.drive.android"
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
            force("androidx.concurrent:concurrent-futures:1.3.0")
            force("androidx.test:monitor:1.7.2")
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

    implementation(project(":drive-shared-ui"))
    implementation(project(":ui-core"))
    implementation(project(":core"))
    implementation(project(":core-aidl"))
    implementation(project(":core-common"))
    implementation(project(":file-service"))
    implementation(project(":file-service-common"))
    implementation(project(":file-service-client"))
    implementation(project(":notification-service"))
    implementation(project(":notification-service-common"))
    implementation(project(":notification-service-client"))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.monitor)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.datetime)

    // Compose libs

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.material3)
    implementation(libs.material.icons.extended)

    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.koin)

    implementation(libs.zoomable)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ui.test)
    testImplementation(libs.ui.test.junit4)
    testImplementation(project(":core-test-utils"))

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.androidxTestRules)
    androidTestImplementation(libs.androidxTestCore)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.testCoreKtx)
    androidTestImplementation(project(":core-test-utils"))
    debugImplementation(libs.ui.test.manifest)
    androidTestImplementation(project(":core-test-utils"))
}

repositories {
    mavenCentral()
    google()
}
