plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// A tiny, app-neutral module for the **app side of a typed error**: the catalogue key an app
// renders a `TError` through (`docs/proposals/core-error-copy-catalogue.md`).
//
// ⚠⚠ Why it is its own module and not `:core`. The key is presentation vocabulary — a derivation
// from an error's schema id to a catalogue entry name — and `:core` is the type foundation; a
// string transform for rendering does not belong there. Nor can it live in `:ui-core`: the
// ViewModel and repository layers name this type in their `UiState`s, and those modules are
// deliberately Compose-free (that is what makes an app iOS-capable), while `:ui-core` has no `js`
// target at all and could not serve the web SDK.
//
// So it sits where `:app-reachability`'s `UnreachableReason` sits, for the same reason: a small
// vocabulary that the Compose UI, the Compose-free VM layer, and the iOS and JS SDKs all need to
// name. All five targets, no Compose, no coroutines — declarations and one pure function.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.error"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")
    js { browser() }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // `TError` for the `errorCopyKey()` bridge — the one declaration here that needs
                // the error taxonomy. `api`, since the receiver type is part of the signature.
                api(project(":core"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
