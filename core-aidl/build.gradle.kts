plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.tenshu.core.aidl"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
