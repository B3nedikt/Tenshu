package org.tenshu.core.common.delegate

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [SecureStorageDelegate].
 *
 * Secrets are sealed with an AndroidKeyStore-resident AES-GCM key (non-extractable — the raw key
 * never leaves the secure element on devices that have one) and the resulting `IV‖ciphertext`
 * blobs are stored in a plain SharedPreferences file. Plain (non-secret) strings stay in a separate
 * unencrypted file.
 *
 * **Restore safety.** The AndroidKeyStore key is device-bound and is NOT part of Android Auto
 * Backup, while SharedPreferences files are. A backup restored onto a new device therefore can't
 * decrypt these blobs. Two defences:
 *   1. The host app (Control) excludes this store from Auto Backup, so stale blobs are never
 *      restored in the first place (see `control/src/main/res/xml/data_extraction_rules.xml`).
 *   2. [getSecret] returns `null` on any decrypt failure instead of crashing, so a partial or
 *      foreign restore degrades to "no secret stored" (the caller re-onboards / recovers).
 */
class AndroidSecureStorageDelegate(
    context: Context,
    fileName: String = "tenshu_secret_store",
) : SecureStorageDelegate {

    private val secretPrefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    private val plainSharedPreferences = context.getSharedPreferences(
        "tenshu_plain_storage",
        Context.MODE_PRIVATE,
    )

    private val secretKey: SecretKey by lazy { getOrCreateSecretKey() }

    override fun storeSecret(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        // AndroidKeyStore generates a fresh random IV per encryption and forbids caller-supplied IVs
        // (its defence against GCM IV reuse), so read it back rather than providing one.
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        secretPrefs.edit { putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    override fun getSecret(key: String): String? {
        val blob = secretPrefs.getString(key, null) ?: return null
        return try {
            val sealed = Base64.decode(blob, Base64.NO_WRAP)
            val iv = sealed.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = sealed.copyOfRange(GCM_IV_LENGTH, sealed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Key mismatch (e.g. a blob restored onto a device whose Keystore lacks the original
            // key) or tampered ciphertext. Treat as "no secret"; the caller re-onboards / recovers.
            null
        }
    }

    override fun storeString(key: String, value: String) {
        plainSharedPreferences.edit { putString(key, value) }
    }

    override fun getString(key: String): String? = plainSharedPreferences.getString(key, null)

    override fun remove(key: String) {
        secretPrefs.edit { remove(key) }
        plainSharedPreferences.edit { remove(key) }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        // StrongBox-preferred (control-keep-device-unlocked.md Q2): request a StrongBox-backed key
        // where the device has a secure element, else fall back to TEE. StrongBox is absent on many
        // devices — requiring it would make the default seed key unprovisionable there — so we retry
        // without the flag on StrongBoxUnavailableException. This is the key the default headless
        // autoload's RECOVERY_PHRASE/JOINER_PAYLOAD are sealed under, so hardening it hardens the
        // whole default at-rest posture.
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tenshu_secure_storage_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
