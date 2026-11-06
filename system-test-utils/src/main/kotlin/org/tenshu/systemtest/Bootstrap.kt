package org.tenshu.systemtest

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.common.util.Bip39
import org.tenshu.sync.client.syncService
import org.tenshu.sync.createRealm

/**
 * Bootstrap this client as the first device in a brand-new realm. Wraps
 * `SyncService.createRealm`, which:
 *
 *   1. Initialises [syncManager] (if not already), deriving the master keypair
 *      from [phrase].
 *   2. Signs a canonical bootstrap message with the master key.
 *   3. POSTs `/sync/realm` to register the realm + this device's public key in
 *      the relay's `KnownDevicesStore`.
 *
 * After this returns successfully the host can mint JWTs that the relay's
 * `JwtAuthenticator` accepts. The joiner doesn't call this — it gets its
 * device credentials via the pairing flow.
 *
 * @param deviceName human-readable label for this device (shows up in the
 *                   pairing UI of joiners).
 * @param phrase optional 24-word BIP39 mnemonic. Defaults to a freshly
 *               generated one — same as the production `PairingWizardViewModel`.
 *               Pass an explicit phrase if a test wants two devices to share
 *               a realm without going through pairing (single-test simulation
 *               of an already-paired pair).
 */
suspend fun TestClient.bootstrapAsHost(
    deviceName: String,
    phrase: String = Bip39.generateMnemonic()
): TResult<Data?> {
    require(role == TestClientRole.RealmHost) {
        "bootstrapAsHost is for RealmHost; this client is $role"
    }
    return tenshuClient.syncService.createRealm(phrase, deviceName)
}
