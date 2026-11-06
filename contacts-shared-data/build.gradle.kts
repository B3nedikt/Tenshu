plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Contacts' real data layer — `TenshuContactsRepository` over the `:contact-service` read
// surface, behind the same `ContactsRepository` seam :contacts-shared-logic's mock
// implements (docs/proposals/contacts-app.md §6 Ph2). This is the *cheap* phase: §2 of that
// proposal records that the read half of the service already ships, so Ph2 is a repository
// implementation and a Koin binding, with no service work at all.
//
// Compose-free; mirrors :contacts-shared-logic's targets so the iOS shell (Ph10) inherits it.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.contacts.data"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                // The ContactsRepository seam + the Contact/OwnProfile presentation models.
                api(project(":contacts-shared-logic"))
                // The shared Control-reachability engine (fit-app.md §9.14). `api`: the
                // engine type appears in the repository constructor + the
                // contactsControlReachability factory the DI module calls.
                api(project(":app-reachability"))
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                api(project(":app-error-handling"))

                // The contact graph, read over a TenshuClient that reaches Control's proxy.
                // `:core` for the chain DSL terminals (select/flow/getAll), `:core-common`
                // for TenshuClient itself.
                implementation(project(":core"))
                implementation(project(":core-common"))
                implementation(project(":contact-service"))
                implementation(project(":contact-service-common"))
                implementation(project(":contact-service-client"))
                // NOT :contact-service-proxy — Control hosts the service; the app is a
                // client. The proxy module is a *test* dependency (the in-process harness).

                // The own display name is platform-level (`OwnerPreferences.Name`), the same
                // row Drive and Fit's Profile read — D6 must not introduce a second answer
                // to "what is my name".
                implementation(project(":preference-service"))
                implementation(project(":preference-service-common"))
                implementation(project(":preference-service-client"))

                implementation(libs.kotlinx.coroutines)
            }
        }
        getByName("commonTest") {
            dependencies {
                // The pipeline test stands :contact-service up in-process; the app never
                // does — Control hosts it.
                implementation(project(":contact-service-proxy"))
                implementation(project(":contact-service-test-utils"))
                implementation(project(":core-test-utils"))
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
