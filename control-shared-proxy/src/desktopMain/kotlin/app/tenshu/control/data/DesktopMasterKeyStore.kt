package app.tenshu.control.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop encrypted-at-rest store for the master-key **secret** bucket
 * (`RECOVERY_PHRASE`, `*_WRAPPER`, `OWNER_ID`, `JOINER_PAYLOAD`, volume/identity
 * keys — everything `SyncManagerImpl` writes via `storeSecret`/`getSecret`).
 *
 * The secrets live in an `AES-256-GCM`-encrypted blob (`secrets.enc`) keyed by a
 * 32-byte wrapping key held in an OS credential store via [wrappingKeyStore]. On
 * macOS that's the login Keychain (works under ad-hoc/dev signing, no biometric);
 * see [KeychainWrappingKeyStore]. This is the **baseline** at-rest path of
 * `docs/proposals/desktop-masterkeystore.md` §3 — Phase 1. The biometric /
 * Touch-ID gate (§4) is Phase 2 and reuses the same wrapping-key indirection.
 *
 * When no wrapping key is available ([WrappingKeyStore.loadOrCreate] returns
 * `null` — e.g. Windows/Linux today, or `security` unavailable) the store runs
 * **in-memory only**, matching the previous desktop behaviour: secrets are lost
 * on restart and the device must re-pair. [isPersistent] reports which mode is
 * active.
 *
 * Threat model: `docs/sync/threat-model.md` asset **A21** + threat **T-C-17**.
 */
class DesktopMasterKeyStore(
    storageDir: File,
    wrappingKeyStore: WrappingKeyStore,
) {

    private val file = File(storageDir, "secrets.enc")

    /** `null` ⇒ no OS credential store ⇒ in-memory-only fallback. */
    private val wrapKey: ByteArray? = try {
        wrappingKeyStore.loadOrCreate()
    } catch (e: Exception) {
        System.err.println("[DesktopMasterKeyStore] wrapping-key store failed (${e.message}); secrets will not persist")
        null
    }

    private val secrets: MutableMap<String, String> = loadInitial()

    /** True when secrets survive a restart (encrypted at rest); false in fallback. */
    fun isPersistent(): Boolean = wrapKey != null

    @Synchronized
    fun storeSecret(key: String, value: String) {
        secrets[key] = value
        persist()
    }

    @Synchronized
    fun getSecret(key: String): String? = secrets[key]

    @Synchronized
    fun remove(key: String) {
        if (secrets.remove(key) != null) persist()
    }

    private fun loadInitial(): MutableMap<String, String> {
        val key = wrapKey ?: return mutableMapOf()
        if (!file.exists()) return mutableMapOf()
        return try {
            val plain = decrypt(key, file.readBytes())
            val props = Properties().apply { load(ByteArrayInputStream(plain)) }
            props.entries.associate { it.key.toString() to it.value.toString() }.toMutableMap()
        } catch (e: Exception) {
            // Wrong/rotated/orphaned wrapping key, or a corrupt blob: treat as an
            // empty bucket (forces a re-pair) rather than crashing — mirrors
            // Android's KeyInvalidated "re-enroll" semantics.
            System.err.println("[DesktopMasterKeyStore] secrets.enc unreadable (${e.message}); treating as empty — re-pair required")
            mutableMapOf()
        }
    }

    private fun persist() {
        val key = wrapKey ?: return // in-memory only — nothing to write
        val props = Properties().apply { secrets.forEach { (k, v) -> setProperty(k, v) } }
        val plain = ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()
        val blob = encrypt(key, plain)
        // Atomic replace so a crash mid-write can't leave a truncated blob.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        return nonce + cipher.doFinal(plaintext)
    }

    private fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_LEN) { "blob too short" }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val ciphertext = blob.copyOfRange(NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        }
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_LEN = 12
        const val GCM_TAG_BITS = 128
    }
}

/**
 * Source of the 32-byte wrapping key that protects [DesktopMasterKeyStore]'s
 * blob. Implementations hold the key in an OS credential store so it never
 * touches disk in plaintext.
 */
interface WrappingKeyStore {
    /**
     * Returns the persistent wrapping key, creating one on first call, or `null`
     * when no OS credential store is available (caller falls back to in-memory).
     */
    fun loadOrCreate(): ByteArray?

    /** Removes the stored wrapping key (idempotent). */
    fun clear()
}

/**
 * macOS [WrappingKeyStore] backed by the login Keychain via the `security` CLI
 * (`/usr/bin/security`). Works under ad-hoc/dev signing because the creating
 * application on the item's ACL is the Apple-signed `security` binary, so the
 * same binary reads it back without a prompt — no provisioning profile, no
 * Secure-Enclave entitlement (that's the Phase-2 path).
 *
 * Caveat (documented in threat-model T-C-17): the one-time create passes the
 * base64 key as an argv to `add-generic-password`, briefly visible to a
 * co-resident process via `ps`. Read-back uses stdout, not argv. The Phase-2
 * Secure-Enclave path removes this by generating the key in-enclave.
 *
 * Any failure (non-zero exit, `security` missing) yields `null` ⇒ in-memory
 * fallback rather than a hard error.
 */
class KeychainWrappingKeyStore(
    private val service: String,
    private val account: String = "master-wrap-key",
) : WrappingKeyStore {

    override fun loadOrCreate(): ByteArray? {
        read()?.let { return it }
        val fresh = ByteArray(WRAP_KEY_LEN).also { SecureRandom().nextBytes(it) }
        return if (store(fresh)) fresh else null
    }

    override fun clear() {
        security("delete-generic-password", "-s", service, "-a", account)
    }

    private fun read(): ByteArray? {
        val out = security("find-generic-password", "-w", "-s", service, "-a", account) ?: return null
        return try {
            Base64.getDecoder().decode(out.trim())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun store(key: ByteArray): Boolean {
        val b64 = Base64.getEncoder().encodeToString(key)
        return security("add-generic-password", "-U", "-s", service, "-a", account, "-w", b64) != null
    }

    /** Runs `/usr/bin/security <args>`; returns stdout on exit 0, else `null`. */
    private fun security(vararg args: String): String? = try {
        val proc = ProcessBuilder(listOf(SECURITY_BIN) + args).start()
        val stdout = proc.inputStream.readBytes().decodeToString()
        if (!proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            null
        } else if (proc.exitValue() == 0) {
            stdout
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SECURITY_BIN = "/usr/bin/security"
        const val WRAP_KEY_LEN = 32
        const val TIMEOUT_SECONDS = 10L
    }
}

/** [WrappingKeyStore] that never persists — forces [DesktopMasterKeyStore] into its in-memory fallback. */
object NoWrappingKeyStore : WrappingKeyStore {
    override fun loadOrCreate(): ByteArray? = null
    override fun clear() = Unit
}
