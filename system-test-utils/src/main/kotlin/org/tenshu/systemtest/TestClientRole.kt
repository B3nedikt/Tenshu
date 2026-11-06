package org.tenshu.systemtest

/**
 * What a [TestClient] is in the system before any flow runs. Drives the
 * identity bootstrap inside [TestSystem.client]: a [RealmHost] initialises
 * its [SyncManager] and registers its public key with the shared relay's
 * [KnownDevicesStore]; a [JoiningDevice] does neither and waits for pairing
 * to finish before it has a realm.
 */
enum class TestClientRole {
    RealmHost,
    JoiningDevice
}
