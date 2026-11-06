plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

// Tests run on a JDK 21, though this module still compiles to 17. `zoomable` 2.14.0+ ships Java 21
// bytecode (class major 65): kotlinc targeting 17 reads it fine, but a 17 test JVM dies with
// `UnsupportedClassVersionError` the first time a test loads it. The shipped desktop app is not
// affected, since `:drive-desktop` runs on its JDK-25 runtime.
// ⚠ The bytecode target stays 17 because Android caps the shared modules' `jvmTarget`
// (`:sync-relay-server`'s build file has the reasoning).
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.drive.shared"
        compileSdk = 37
        minSdk = 24
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                implementation(project(":app-error-handling"))
                implementation(project(":ui-core"))
                // `QrScanner` — contact-invite redemption.
                implementation(project(":ui-qr-scanner"))
                implementation(project(":core"))
                implementation(project(":core-common"))
                // Drive's Compose-free logic layer (VMs / repos / mappers / domain).
                // `api` so dependents (:drive, :drive-desktop) that reference VM /
                // repository types get them transitively. Screens here resolve those
                // types via the shared package across the module boundary (no import).
                api(project(":drive-shared-logic"))
                implementation(project(":notification-service"))
                implementation(project(":file-service"))
                implementation(project(":file-service-common"))
                implementation(project(":notification-service-common"))
                implementation(project(":file-service-client"))
                implementation(project(":notification-service-client"))
                implementation(project(":timeline-service-common"))
                implementation(project(":timeline-service-client"))
                implementation(project(":health-service-common"))
                implementation(project(":health-service-client"))
                implementation(project(":place-service-common"))
                implementation(project(":place-service-client"))
                implementation(project(":ingest-service"))
                implementation(project(":ingest-service-common"))
                implementation(project(":ingest-service-client"))
                implementation(project(":sync-service"))
                implementation(project(":sync-service-common"))
                implementation(project(":sync-service-client"))
                implementation(project(":preference-service"))
                implementation(project(":preference-service-common"))
                implementation(project(":preference-service-client"))
                implementation(project(":contact-service"))
                implementation(project(":contact-service-common"))
                implementation(project(":contact-service-client"))
                // storage-capacity-gating.md Phase 2: read-only StorageStatus
                // signal for the Tier-1 share/upload gate.
                implementation(project(":storage-service"))
                implementation(project(":storage-service-common"))
                implementation(project(":storage-service-client"))
                // Drive opts in to identity-service — the FileModification.Device consumer.
                // (file-service keeps identity as `implementation`, so it isn't transitive.)
                implementation(project(":identity-service"))
                implementation(project(":identity-service-common"))
                implementation(project(":identity-service-client"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.uiUtil)
                implementation(compose.components.resources)
                implementation(compose.preview)
                implementation(compose.materialIconsExtended)

                implementation(libs.koinMultiplatform)
                implementation(libs.koinComposeViewmodel)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.datetime)
                implementation(libs.zoomable)
                // collectAsLazyPagingItems + LazyPagingItems for the screens; the
                // `Flow<PagingData<…>>` the VMs expose comes from :drive-shared-logic
                // (which `api`-exposes :core-paging / paging-common). `toPagingData`
                // moved to the logic module as a plain commonMain function.
                implementation(libs.paging.compose)

                implementation(libs.kotlinx.serialization.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
        // Shared JVM source set spanning androidMain + desktopMain. The default
        // hierarchy template doesn't materialise this parent when the Android KMP
        // plugin is in play, so we create it and re-parent both JVM children.
        // Houses the JVM `platformShareHostOverride` actual (ShareHost.jvm.kt).
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(project(":core-client"))
                implementation(project(":file-service-client"))
                implementation(project(":file-service-common"))
                implementation(project(":notification-service-client"))
                implementation(project(":notification-service-common"))
                implementation(project(":ingest-service-client"))
                implementation(project(":ingest-service-common"))
                implementation(libs.navigation3.ui)
                implementation(libs.lifecycle.viewmodel.navigation3)
                implementation(libs.koin)
                implementation(libs.ui.tooling)
                implementation(libs.documentfile)
                // androidx.core.content.FileProvider for "open in another app" (D3).
                implementation(libs.core.ktx)
                // Phase 2c — the in-Drive background backup worker (WorkManager).
                implementation(libs.androidx.work.runtime)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.savedstate)
                implementation(libs.savedstate.compose)
                // Desktop-only PDF page→bitmap rendering (PdfRender.desktop.kt);
                // Android renders via the platform android.graphics.pdf instead.
                implementation(libs.pdfbox)
                // ImageIO bridge for the formats Skia can't decode — HEIC above all
                // (drive-content-preview.md §3.1). `:ui-core` has it as an
                // `implementation` dep, so it isn't transitive.
                implementation(project(":macos-helpers"))
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(project(":core-test-utils"))
                implementation(project(":viewmodel-test-utils"))
                implementation(project(":control-shared"))
                implementation(compose.desktop.uiTestJUnit4)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.koinTest)
                // asSnapshot() — drives a Flow<PagingData<…>> to a List in a
                // non-Compose test so we can assert getFilesPaged's contents.
                implementation(libs.paging.testing)
            }
        }
    }
}

// ADR 0016: per-module CMP resource catalogues. Pinning the generated
// `Res` accessor package lets call sites use a predictable
// `import app.tenshu.drive.shared.resources.Res` regardless of how the
// plugin defaults change. Strings live in
// `commonMain/composeResources/values/strings.xml`.
compose.resources {
    packageOfResClass = "app.tenshu.drive.shared.resources"
    generateResClass = always
}
