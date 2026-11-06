plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    // 21 (not the shared modules' 17) because the hub system test hosts the real
    // `:sync-relay-server` in-process via Ktor's testApplication — the same
    // arrangement (and reason) as `:system-test-utils`. The consumed KMP modules
    // emit Java-17 bytecode, which a 21 module reads fine.
    jvmToolchain(21)
}

dependencies {
    // The production proxy assembly — `buildProxyModule()` + `buildPlatformProxyModule()`
    // from `:control-shared-proxy` desktopMain, exactly what `control-desktop` boots
    // (hub proposal §6/§13 H1: "control-desktop minus Compose"). `:control-shared-proxy`
    // is Compose-free (the Compose UI layer is `:control-shared`, one module up).
    implementation(project(":control-shared-proxy"))
    implementation(project(":core"))
    implementation(project(":core-common"))
    // Offerer-role pairing ops (`OfferPairing` / `GetOfferStatus`) + the client surface.
    implementation(project(":sync-service"))
    implementation(project(":sync-service-common"))
    implementation(project(":sync-service-client"))
    // H3 keep-everything replica: SyncPolicy + the OfflineKeeper op id (:file-service),
    // the typed file-sync settings facade (:file-service-client), and the
    // preferenceService accessor it hangs off (:preference-service[-client]).
    implementation(project(":file-service"))
    implementation(project(":file-service-client"))
    implementation(project(":preference-service"))
    implementation(project(":preference-service-client"))
    // H4 management surface: the hub authors HubStatus rows + consumes HubCommand
    // rows (schemas from the service module; client surface for the command flow).
    implementation(project(":hub-management-service"))
    implementation(project(":hub-management-service-common"))
    implementation(project(":hub-management-service-client"))
    // HD-1 device spine: the hub authors HomeDevice/DeviceState rows + consumes
    // DeviceCommand rows over the DeviceBridge seam (home-device-service.md).
    implementation(project(":home-device-service"))
    implementation(project(":home-device-service-common"))
    implementation(project(":home-device-service-client"))
    // RE-2: the hub is the only process that can observe a device event in
    // milliseconds, so it hosts the routines engine's fast path.
    implementation(project(":routine-service"))
    implementation(project(":routine-service-common"))
    implementation(project(":routine-service-proxy"))

    implementation(libs.koinCore)
    implementation(libs.kotlinx.coroutines)

    // HD-2 zigbee2mqtt bridge: an MQTT client (behind the `MqttClient` seam)
    // plus JSON for the sidecar's payloads. The serialization *plugin* is
    // deliberately not applied — the bridge navigates `JsonElement`s rather
    // than declaring @Serializable mirrors of z2m's loose, evolving shapes.
    implementation(libs.paho.mqtt.client)
    implementation(libs.kotlinx.serialization.json)

    // Terminal QR for the H1 manual-pairing CLI (hub proposal §13). Nayuki's
    // qrcodegen is a tiny dependency-free MIT-licensed encoder; `:ui-core`'s QR
    // display is a Compose painter and thus off-limits here.
    implementation("io.nayuki:qrcodegen:1.8.0")
    implementation("org.slf4j:slf4j-simple:2.0.19")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(libs.kotlinx.coroutines.test)
    // TContext + schema registry fixtures, for the unit tests that build an
    // OfferStatus/OfferPairingSession without standing up a relay.
    testImplementation(project(":core-test-utils"))
    // Two-party system test: in-process relay + a responder TestClient.
    testImplementation(project(":system-test-utils"))
    testImplementation(project(":file-service"))
    testImplementation(project(":file-service-common"))
    testImplementation(project(":file-service-client"))
    // Home's storage-tile repository (HomeStorageTileSystemTest): the hub's
    // HubStatus row read back through Home's own mapping layer.
    testImplementation(project(":home-shared-logic"))
}

application {
    applicationName = "tenshu-hub"
    mainClass.set("app.tenshu.hub.MainKt")
}
