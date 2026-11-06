package app.tenshu.control.data

import org.tenshu.core.common.delegate.SecureStorageDelegate
import java.io.File
import java.util.Properties

/**
 * Desktop [SecureStorageDelegate].
 *
 * Splits the two buckets the way the `LocalStore` (non-secret) vs `MasterKeyStore`
 * (secret) design in `docs/tech-debt.md` describes:
 *
 * - **Non-secret** device-local strings — `DEVICE_ID`, `LAST_SYNC_TIMESTAMP`
 *   (everything written via [storeString]/[getString]) — go to a plaintext
 *   properties file alongside the object store, so the device identity survives a
 *   restart. Without this, [org.tenshu.core.common.delegate.InMemorySecureStorageDelegate]
 *   minted a fresh `DEVICE_ID` on every launch while the persistent `objects.db`
 *   kept the old `Device` rows, so the synced Devices relation accumulated one
 *   duplicate desktop entry per restart.
 *   ⚠ Since workstream B the device id is DERIVED from the A4 key, which lives in the secret
 *   bucket below — so `DEVICE_ID` here is only a cache, and identity across restarts now rests on
 *   the A4 key surviving. Where the secret bucket falls back to in-memory, every restart is a new
 *   key and therefore a new device id (the duplicate-entry failure above, by the other door).
 *
 * - **Master-key-grade secrets** (`RECOVERY_PHRASE`, `*_WRAPPER`, `OWNER_ID`,
 *   `JOINER_PAYLOAD`, volume/identity keys — everything written via
 *   [storeSecret]/[getSecret]) go to [masterKeyStore], which encrypts them at
 *   rest under an OS-credential-store-held wrapping key (macOS login Keychain;
 *   see [DesktopMasterKeyStore] / [KeychainWrappingKeyStore]). Where no such
 *   store exists (Windows/Linux today) it falls back to in-memory and the device
 *   re-pairs after a restart — see [DesktopMasterKeyStore.isPersistent].
 *
 * `docs/proposals/desktop-masterkeystore.md` §3 (Phase 1); threat-model A21 / T-C-17.
 */
class DesktopSecureStorageDelegate(
    storageDir: File,
    private val masterKeyStore: DesktopMasterKeyStore,
) : SecureStorageDelegate {

    private val file = File(storageDir, "local-store.properties")

    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    override fun storeSecret(key: String, value: String) = masterKeyStore.storeSecret(key, value)

    override fun getSecret(key: String): String? = masterKeyStore.getSecret(key)

    @Synchronized
    override fun storeString(key: String, value: String) {
        props.setProperty(key, value)
        persist()
    }

    override fun getString(key: String): String? = props.getProperty(key)

    @Synchronized
    override fun remove(key: String) {
        masterKeyStore.remove(key)
        if (props.remove(key) != null) persist()
    }

    private fun persist() {
        file.outputStream().use { props.store(it, "Tenshu desktop local store (non-secret)") }
    }
}
