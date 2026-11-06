plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // @Serializable on the ImportedHealth DTOs — the recorder's durable workout outbox journals
    // them as local JSON (never wire; House Rule #4 unaffected).
    alias(libs.plugins.kotlinx.serialization)
}

// Fit's **activity-file layer**: turning a picked file's bytes into `ImportedHealth`. The GPX/TCX
// parser, the `ImportedHealth` DTOs it produces, and the `XmlReader` seam with its per-platform
// DOM bindings (`javax.xml` / `DOMParser` / `NSXMLParser`).
//
// ## Why this is its own module: it is the one piece of Fit that must reach the web
//
// `fit-app.md` §9.10 put the GPX/TCX parser on the web, and did it by adding `js(IR) { browser() }`
// to `:fit-shared-data` — which has an `api` dep on `:fit-shared-logic`, so **both** had to be
// JS-enabled together. Fit's entire logic layer, every ViewModel and `FitRepository` included,
// inherited a JS target so that ~470 lines of parser could compile.
//
// That is a local exception to House Rule #5, which Drive follows exactly: the *protocol* layer is
// shared via KMP → JS, the *app logic* layer is not (`drive-shared-logic` targets android, desktop
// and iOS only, and Drive-on-web is the TypeScript rebuild over the thin SDK). The exception cost
// something concrete: `androidx.paging` publishes no `paging-common-js`, so `:fit-shared-logic`
// could not take `:core-paging` while it targeted JS, and History could not be paged
// (`fit-read-scaling.md` Phase 2).
//
// Extracting the parser here restores the boundary **and keeps §9.10's capability** — the web
// parser still compiles and is still tested (`jsTest`), it just no longer drags the app's
// ViewModels to JS with it.
//
// ## Contents, and one wart
//
// ⚠ The `XmlReader` seam is **not** Fit-specific — it is a generic DOM interface with three
// platform bindings. It lives here rather than in an `:app-xml` because moving it alone would not
// have unblocked anything (the *parser* is what must reach JS), and because XML appears nowhere
// else in the repo: the `app-*` family earns a module on the **second** consumer, not the first
// (`:app-liveness` when Fit needed Drive's mechanic, `:app-list-state` when Fit needed Drive's
// types). It is already behind an interface, so promoting it the day something else parses XML is
// a file move with no call-site change.
//
// ⚠ Binary FIT stays JVM-only (the Garmin SDK) behind the optional `FitBinaryDecoder` seam, whose
// only implementation is `:fit-shared-data`'s desktop `FitDecoder`. Absent — on JS and iOS — the
// FIT branch yields empty rather than failing.
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()

    android {
        namespace = "app.tenshu.fit.file"
        compileSdk = 37
        minSdk = 24
    }

    jvm("desktop")

    js {
        browser()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // HrSample / RouteSample — the stream types the parser fills. Already JS-capable
                // (the web SDK track), which is what makes this module's JS target free.
                api(project(":health-service-common"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // Android and desktop are both JVM and the XML path is pure JDK (`javax.xml`), so they
        // share one reader rather than copying it — the arrangement :fit-shared-data used, where
        // having it in desktopMain alone once meant Android could not read an activity file at all.
        val jvmMain = create("jvmMain") {
            dependsOn(commonMain)
        }
        getByName("androidMain") { dependsOn(jvmMain) }
        getByName("desktopMain") { dependsOn(jvmMain) }
        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
