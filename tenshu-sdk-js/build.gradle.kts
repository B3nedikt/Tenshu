plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Generates serializers for @Serializable wire DTOs (ShareViewer's
    // ShareDescriptorDto). Without it the annotation compiles but no serializer is
    // emitted, so Ktor's reflective lookup throws "Serializer for class … not found".
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    jvmToolchain(17)

    js {
        browser()
        binaries.executable()
        // ES-module output. Required so Vite (the apex's bundler) can
        // resolve inter-module imports between the per-module .js files
        // emitted at compileSync/.../kotlin/ and tree-shake across them.
        // Matches the convention adopted by :core-common.
        useEsModules()
    }

    sourceSets {
        getByName("jsMain") {
            dependencies {
                // `ErrorCopyKey` — the catalogue key a typed error renders through
                // (`core-error-copy-catalogue.md` §5.0).
                implementation(project(":app-error-handling"))
                api(project(":core"))
                api(project(":diagnostics"))
                api(project(":core-common"))
                api(project(":core-client"))
                api(project(":identity-service"))
                api(project(":identity-service-common"))
                api(project(":identity-service-client"))
                api(project(":file-service"))
                api(project(":file-service-common"))
                api(project(":file-service-client"))
                api(project(":file-service-proxy"))
                api(project(":notification-service"))
                api(project(":notification-service-common"))
                api(project(":notification-service-client"))
                api(project(":notification-service-proxy"))
                api(project(":storage-service"))
                api(project(":storage-service-common"))
                api(project(":storage-service-client"))
                api(project(":storage-service-proxy"))
                api(project(":ingest-service"))
                api(project(":ingest-service-client"))
                api(project(":ingest-service-common"))
                // X-5 owed this and could not pay it: the wanted-class declaration writes a
                // `DeviceSetting` row, and web registered no preference service at all — the types
                // were not even on this module's classpath (`:file-service-proxy` depends on them
                // as `implementation`, so nothing was transitive).
                // ⚠ Safe for existing stores by construction: `StoreSchemaMigrator` stamps a newly
                // registered service at current and touches no rows — it is `recorded < floor` or
                // `recorded > declared` that wipes, and a service the store has never seen is
                // neither.
                api(project(":preference-service"))
                api(project(":preference-service-common"))
                api(project(":preference-service-client"))
                api(project(":preference-service-proxy"))
                api(project(":sync-service"))
                api(project(":sync-service-common"))
                api(project(":sync-service-client"))
                api(project(":sync-service-proxy"))
                api(project(":identity-service-proxy"))
                api(project(":contact-service"))
                api(project(":contact-service-common"))
                api(project(":contact-service-client"))
                api(project(":contact-service-proxy"))
                // Timeline read bridge. All four already declare `js { browser() }`,
                // so nothing on the Kotlin side had to move. ⚠ `-proxy` transitively
                // pulls `:place-service` (its `PlaceRoleInferencePass` writes `PlaceRole`
                // rows). Since F7 the place schema is registered (sync-only, below);
                // no place surface is exported. See TimelinePlugin's KDoc.
                api(project(":timeline-service"))
                api(project(":timeline-service-common"))
                api(project(":timeline-service-client"))
                api(project(":timeline-service-proxy"))
                // The Timeline feed projection — the same module native Home calls, so the web day
                // list folds by ONE implementation (timeline-app.md §Web scope 4a). Pure; no proxy.
                api(project(":timeline-projection"))
                // The Fit projection + model (`FitProjection`, `DataSourceProjection`, `FitPreferences`)
                // — the module native Fit calls, so the web's day aggregates and History rows fold
                // by ONE implementation (`fit-app.md` §Web scope). Pure; no proxy.
                api(project(":fit-projection"))
                // The GPX / TCX parser over the browser `DOMParser` (`fit-app.md` §9.10): `Fit.importActivityFile`.
                api(project(":fit-shared-file"))
                // F7 (`web-app-suite.md` §3): every other pinned service, registered for SYNC
                // ONLY — schema + mergers (SyncedServicePlugins.kt). `-proxy` only where the
                // mergers live (calendar, health, place); nothing else of a proxy is wired.
                // ⚠ Each module must also be in BOTH `paths:` lists of `.github/workflows/web.yml`.
                api(project(":calendar-service"))
                api(project(":calendar-service-common"))
                api(project(":calendar-service-client"))
                api(project(":calendar-service-proxy"))
                api(project(":health-service"))
                api(project(":health-service-common"))
                api(project(":health-service-client"))
                api(project(":health-service-proxy"))
                api(project(":place-service"))
                api(project(":place-service-common"))
                api(project(":place-service-client"))
                api(project(":place-service-proxy"))
                api(project(":map-region-service"))
                api(project(":map-region-service-common"))
                api(project(":map-region-service-client"))
                // W-MAP (`web-app-suite.md` §4): the streamed-region read path (MapRegionPlugin.kt).
                api(project(":map-region-service-proxy"))
                api(project(":map-region-app"))
                api(project(":photo-service"))
                api(project(":photo-service-common"))
                api(project(":photo-service-client"))
                api(project(":photo-service-proxy")) // Photos run A: PhotoPlugin.kt
                api(project(":inference-service"))
                api(project(":inference-service-common"))
                api(project(":inference-service-client"))
                api(project(":mia-service"))
                api(project(":mia-service-common"))
                api(project(":mia-service-client"))
                // Mia run A (`web-app-suite.md` Wave 4): the native query layer, run in the tab (MiaPlugin.kt).
                // ⚠ Brings `:home-device-service-proxy` and `:inference-service-proxy` as its own
                // `implementation` deps; neither is wired here (no device op, no inference backend).
                api(project(":mia-service-proxy"))
                api(project(":routine-service"))
                api(project(":routine-service-common"))
                api(project(":routine-service-client"))
                api(project(":home-device-service"))
                api(project(":home-device-service-common"))
                api(project(":home-device-service-client"))
                api(project(":hub-management-service"))
                api(project(":hub-management-service-common"))
                api(project(":hub-management-service-client"))
                api(project(":logging-service"))
                api(project(":logging-service-common"))
                api(project(":logging-service-client"))
                implementation(libs.kotlinx.coroutines)
                implementation(libs.ktorClientCore)
                implementation(libs.ktorClientJs)
                implementation(libs.ktorClientContentNegotiation)
                implementation(libs.ktorSerializationJson)
                // The IANA zone database `Calendar` resolves event zones with (CalendarBook.kt).
                // ⚠⚠ 2.26.0 = tzdb 2026a, the newest release whose peer range (`@js-joda/core
                // >=1.11.0`) admits the core 3.2.0 kotlinx-datetime 0.8.0 resolves; 3.x needs core
                // >=5.7.0, which kotlinx-datetime does not use. Never copy app-notify's 2.3.0
                // (tzdb 2020a: no Europe/Kyiv, Mexico still on DST) — it is a jsTest-only pin.
                // ⚠ Bump the licences page with it (web/apex/support-pages/sdkBundle.ts).
                implementation(npm("@js-joda/timezone", "2.26.0"))
            }
        }
        getByName("jsTest") {
            // ⚠⚠ The committed service-version pin every iOS app's `DeclaredServiceVersionsTest`
            // compares against (`ios-per-app-frameworks.md` §6.2) — compiled here too, so the web's
            // `DeclaredServiceVersionsTest` holds the browser runtime to the SAME list (F7).
            kotlin.srcDir(rootProject.file("tenshu-sdk-ios-core/src/serviceVersionPin/kotlin"))
            dependencies {
                implementation(kotlin("test-js"))
                implementation(libs.kotlinx.coroutines.test)
                // ShareViewerTest serves the relay's share routes from a MockEngine.
                implementation(libs.ktorClientMock)
            }
        }
    }
}
