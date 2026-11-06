plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // ⚠ 17, like the ~100 shared modules. `:sync-relay-server`, whose `Application.module()` this
    // module hosts in-process via `testApplication`, pins `jvmToolchain(17)`; a relay that declared
    // no toolchain would inherit the Gradle daemon's JDK and publish a variant a 17 consumer cannot
    // resolve.
    //
    // ⚠ `:macos-helpers` reaches this graph transitively (`:ui-core` desktop → `:control-shared`)
    // and compiles on JDK 25 for FFM — but it pins `jvmTarget = JVM_17` plus matching
    // `java.{source,target}Compatibility` precisely so what lands in its jar stays consumable by
    // the 17 world. Measured, not assumed: its `.class` files are major 61.
    jvmToolchain(17)
}

dependencies {
    // Production modules — SystemTests assemble Koin graphs from the same modules
    // production uses. Mirroring production wiring is a deliberate design point;
    // see `docs/proposals/testing-system-tests.md` §4.3.
    implementation(project(":core"))
    implementation(project(":core-common"))
    implementation(project(":file-service"))
    implementation(project(":file-service-common"))
    implementation(project(":file-service-client"))
    implementation(project(":file-service-proxy"))
    implementation(project(":identity-service"))
    implementation(project(":identity-service-common"))
    implementation(project(":identity-service-client"))
    implementation(project(":identity-service-proxy"))
    implementation(project(":health-service"))
    implementation(project(":health-service-common"))
    implementation(project(":health-service-client"))
    implementation(project(":health-service-proxy"))
    implementation(project(":notification-service"))
    implementation(project(":notification-service-common"))
    implementation(project(":notification-service-client"))
    implementation(project(":notification-service-proxy"))
    // Calendar is here so the shape baseline pins its composites — both
    // the stored rows and the op parameter objects, which are
    // client-serialized composites and break old *callers* mid-parse if they
    // are reshaped without a bump.
    implementation(project(":calendar-service"))
    implementation(project(":calendar-service-common"))
    // ⚠⚠ Add a new service here in the SAME change that creates it. A new service passes
    // `SchemaShapeBaselineEnforcementTest` for the wrong reason until its `-common` module is in
    // this graph — it is simply outside the baseline, so the pin is absent rather than wrong, and
    // nothing would fail until the first reshape silently broke a peer.
    implementation(project(":photo-service-common"))
    implementation(project(":photo-service-client"))
    implementation(project(":calendar-service-client"))
    implementation(project(":calendar-service-proxy"))
    implementation(project(":preference-service"))
    implementation(project(":preference-service-common"))
    implementation(project(":preference-service-client"))
    implementation(project(":preference-service-proxy"))
    implementation(project(":hub-management-service"))
    implementation(project(":hub-management-service-common"))
    implementation(project(":hub-management-service-client"))
    implementation(project(":hub-management-service-proxy"))
    implementation(project(":home-device-service"))
    implementation(project(":home-device-service-common"))
    implementation(project(":home-device-service-client"))
    implementation(project(":home-device-service-proxy"))
    // Mia's query layer, so its two return composites are pinned by
    // SchemaShapeBaselineEnforcementTest like every other wire type.
    implementation(project(":mia-service"))
    // The inference service's synced rows are pinned like every other service's — a new
    // service outside this graph is a service whose positional layout nothing is watching.
    implementation(project(":inference-service-common"))
    // ⚠ The CLIENT twin, not optional bookkeeping: the element request objects a chain-DSL
    // lambda runs against are registered here, never in `-common`, so a graph carrying only the
    // common module reports every one of that service's element types as unregistered —
    // `ElementRequestObjectRegistrationTest` then flags a type its client module does register.
    implementation(project(":inference-service-client"))
    implementation(project(":mia-service-common"))
    implementation(project(":mia-service-client"))
    implementation(project(":mia-service-proxy"))
    implementation(project(":routine-service"))
    implementation(project(":routine-service-common"))
    implementation(project(":routine-service-client"))
    implementation(project(":routine-service-proxy"))
    implementation(project(":contact-service"))
    implementation(project(":contact-service-common"))
    implementation(project(":contact-service-client"))
    implementation(project(":contact-service-proxy"))
    implementation(project(":contact-service-test-utils"))
    implementation(project(":sync-service"))
    implementation(project(":sync-service-common"))
    implementation(project(":sync-service-client"))
    implementation(project(":sync-service-proxy"))
    // The entitlement record→credential provider lives here, so the harness can mirror
    // production wiring.
    implementation(project(":storage-service-proxy"))

    // ── Six read-surface services ─────────────────────────────────────────────────────────
    //
    // Here so `ElementRequestObjectRegistrationTest` and `SchemaShapeBaselineEnforcementTest`
    // judge them (through `serviceGraphConfigs()`). ⚠ `PropertyKindBaselineEnforcementTest` keeps
    // its own, narrower list, which does not include them.
    //
    // ⚠ `-common` AND `-client` for each, always as a pair. Element request objects are
    // registered only in `-client`, so a common-only entry reports every one of that service's
    // element types as unregistered.
    //
    // ⚠ Deliberately NOT `-proxy`: these are read-surface registrations, and pulling six more
    // proxies in would drag their platform wiring into a module that only needs the schemas.
    // (`:storage-service-proxy` above is here for the entitlement provider, a different reason.)
    implementation(project(":timeline-service-common"))
    implementation(project(":timeline-service-client"))
    implementation(project(":logging-service-common"))
    implementation(project(":logging-service-client"))
    implementation(project(":ingest-service-common"))
    implementation(project(":ingest-service-client"))
    implementation(project(":place-service-common"))
    implementation(project(":place-service-client"))
    implementation(project(":map-region-service-common"))
    implementation(project(":map-region-service-client"))
    implementation(project(":storage-service-common"))
    implementation(project(":storage-service-client"))

    // Real relay code — SystemTests host `Application.module()` in-process via
    // Ktor's testApplication, so the JWT/routing/mailbox layer is the same code
    // that ships to production.
    implementation(project(":sync-relay-server"))

    // Test fixtures the framework reuses (FakeClock, schema-registry helpers).
    implementation(project(":core-test-utils"))

    // Koin: each TestClient gets its own KoinApplication.
    api(libs.koinCore)

    // Coroutines test machinery — runTest, virtual time.
    api(libs.kotlinx.coroutines.test)
    api(libs.kotlinx.coroutines)

    // Ktor test host hosts the relay's Application.module() with no socket and
    // exposes a client HttpClient that dispatches into the server pipeline.
    api(libs.ktor.server.test.host)
    api(libs.ktorClientCore)

    api(libs.kotlin.test)

    testImplementation(libs.kotlin.test)
    // The macOS native bridge (NativeBridgeServer + registerFileMethods /
    // registerFileWriteMethods) lives in :control-shared desktopMain; pulled in
    // test-only to exercise the real bridge handlers against the full proxy stack
    // (NativeBridgeServerSystemTest). control-shared's `jvm("desktop")` target is
    // Java-17 bytecode.
    testImplementation(project(":control-shared"))
    // Two-party VM-level flow tests (PairingVmFlowSystemTest): the real
    // PairingWizardViewModel/HostPairingMonitor on both sides of the harness,
    // asserting the user-visible state sequence.
    testImplementation(project(":control-shared-logic"))
    // SyncedThumbnailSystemTest drives the real TenshuPhotosRepository on a byte-less joiner: which
    // rung a photo's preview lands on is decided by the repository's pulls, and a test that copied
    // those pulls would stay green over a blurry viewer (drive-synced-thumbnails.md §16).
    testImplementation(project(":photos-shared-logic"))
    // `TimelineProjectionRepository`, so the moment hero's named rung read is guarded by the
    // same two-device test as Photos'. ⚠ Test-only, and for the same reason the Photos entry is:
    // the defect it covers (a pull that fires only on a null read) is invisible on one device.
    testImplementation(project(":timeline-shared-data"))
    // `CalendarMirrorStopSystemTest` drives the real `TenshuCalendarRepository` on both
    // devices. The defect (the bound phone's freshness stamp bringing back a mirror another device
    // stopped) is a property of which rows the repository writes, so a test that called the ops
    // directly would be testing a copy of that choice rather than the choice.
    testImplementation(project(":calendar-shared-logic"))
    // `commerce-end-to-end-dry-run.md`: CommerceRelaySystemTest runs the REAL commerce issuer
    // in-process next to the real relay, so the commerce -> relay hop is asserted in CI instead of
    // only on a droplet. Test-only: nothing in the harness proper knows the commerce plane exists,
    // and the two remain separate trust boundaries (commerce-billing-plane.md D1).
    testImplementation(project(":commerce-service"))
}

// ⚠ The shape baseline's documented regeneration command is
// `-Dtenshu.schemaBaseline.regenerate=true`, but Gradle's `-D` sets the property
// on the *daemon* JVM — the test runs in a forked one, where
// `System.getProperty` then returns null and the flag silently does nothing.
// Forward it explicitly so the documented command actually regenerates.
// ⚠ Same treatment for the property-kind baseline's flag — forgetting it does not fail, it
// silently ignores the regenerate request and leaves the pinned file stale, which is the one
// outcome a baseline must never have.
tasks.withType<Test>().configureEach {
    val regenerate = providers.systemProperty("tenshu.schemaBaseline.regenerate")
    val regenerateKinds = providers.systemProperty("tenshu.propertyKindBaseline.regenerate")
    inputs.property("schemaBaselineRegenerate", regenerate.orElse("false"))
    inputs.property("propertyKindBaselineRegenerate", regenerateKinds.orElse("false"))
    doFirst {
        systemProperty("tenshu.schemaBaseline.regenerate", regenerate.getOrElse("false"))
        systemProperty(
            "tenshu.propertyKindBaseline.regenerate",
            regenerateKinds.getOrElse("false"),
        )
    }
}
