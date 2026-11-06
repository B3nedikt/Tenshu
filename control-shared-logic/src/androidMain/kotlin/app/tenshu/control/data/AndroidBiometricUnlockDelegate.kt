package app.tenshu.control.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android impl of [BiometricUnlockDelegate] using
 * [`androidx.biometric.BiometricPrompt`](https://developer.android.com/reference/androidx/biometric/BiometricPrompt)
 * over an `AndroidKeyStore` AES/GCM key gated by
 * `setUserAuthenticationRequired(true)`. On API 30+ the key is further
 * constrained to `AUTH_BIOMETRIC_STRONG` via
 * `setUserAuthenticationParameters` — on older APIs the prompt itself
 * carries `setAllowedAuthenticators(BIOMETRIC_STRONG)` for the same
 * effective gate.
 *
 * The wrap-key blob (IV + ciphertext, base64-joined by `:`) is persisted
 * in a private SharedPreferences file. No second layer of encryption is
 * applied because the ciphertext is already AEAD — the threat-model
 * value comes entirely from the Keystore-backed key, which can't be
 * exported.
 *
 * `setInvalidatedByBiometricEnrollment(true)` means a fresh biometric
 * enrollment after setup invalidates the key — the next [unwrapWrapKey]
 * surfaces [UnwrapResult.KeyInvalidated], the UI is expected to wipe
 * the proxy-side biometric wrapper via
 * `SyncService.clearBiometricSetup()` and prompt re-enrollment.
 */
class AndroidBiometricUnlockDelegate(
    private val context: Context,
) : BiometricUnlockDelegate {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        BIOMETRIC_PREFS_FILE,
        Context.MODE_PRIVATE,
    )

    // Both probes are main-safe (BiometricManager + KeyStore lookups), so they
    // run inline; `suspend` is just interface conformance (the desktop impl is
    // the one that must shell out off-thread).
    override suspend fun isAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val result = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    override suspend fun hasEnrolled(): Boolean {
        // Blob existence is the first cheap check; Keystore presence is
        // checked too because an OS-driven key invalidation can leave the
        // blob behind. A live `KeyPermanentlyInvalidatedException` only
        // surfaces when we actually try to use the cipher, but we can at
        // least filter the obvious case here.
        if (prefs.getString(BLOB_KEY, null) == null) return false
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            keyStore.containsAlias(KEY_ALIAS)
        } catch (_: Throwable) {
            false
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun enrollWrapKey(
        wrapKey: ByteArray,
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): EnrollResult {
        if (!isAvailable()) return EnrollResult.NotAvailable

        // Discard any prior key + blob so we always start from a fresh
        // Keystore key. Avoids the "stale-key with new wrap-key" hazard.
        clear()

        val cipher = try {
            val key = createKey()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        } catch (t: Throwable) {
            return EnrollResult.Failure(t.message)
        }

        val result = try {
            authenticate(host, copy, BiometricPrompt.CryptoObject(cipher))
        } catch (e: BiometricAuthException) {
            return e.toEnrollResult()
        } catch (t: Throwable) {
            return EnrollResult.Failure(t.message)
        }

        return try {
            val authenticatedCipher = result.cryptoObject?.cipher ?: cipher
            val iv = authenticatedCipher.iv
            val ciphertext = authenticatedCipher.doFinal(wrapKey)
            prefs.edit {
                putString(
                    BLOB_KEY,
                    Base64.encode(iv) + SEPARATOR + Base64.encode(ciphertext),
                )
            }
            EnrollResult.Success
        } catch (t: Throwable) {
            EnrollResult.Failure(t.message)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun unwrapWrapKey(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
    ): UnwrapResult {
        if (!isAvailable()) return UnwrapResult.NotAvailable
        val blob = prefs.getString(BLOB_KEY, null) ?: return UnwrapResult.NotAvailable
        val parts = blob.split(SEPARATOR)
        if (parts.size != 2) return UnwrapResult.NotAvailable
        val iv = try {
            Base64.decode(parts[0])
        } catch (_: IllegalArgumentException) {
            return UnwrapResult.NotAvailable
        }
        val ciphertext = try {
            Base64.decode(parts[1])
        } catch (_: IllegalArgumentException) {
            return UnwrapResult.NotAvailable
        }

        val cipher = try {
            val key = loadKey() ?: return UnwrapResult.NotAvailable
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return UnwrapResult.KeyInvalidated
        } catch (t: Throwable) {
            return UnwrapResult.Failure(t.message)
        }

        val result = try {
            authenticate(host, copy, BiometricPrompt.CryptoObject(cipher))
        } catch (e: BiometricAuthException) {
            return e.toUnwrapResult()
        } catch (t: Throwable) {
            return UnwrapResult.Failure(t.message)
        }

        return try {
            val authenticatedCipher = result.cryptoObject?.cipher ?: cipher
            val wrapKey = authenticatedCipher.doFinal(ciphertext)
            UnwrapResult.Success(wrapKey)
        } catch (_: KeyPermanentlyInvalidatedException) {
            UnwrapResult.KeyInvalidated
        } catch (t: Throwable) {
            UnwrapResult.Failure(t.message)
        }
    }

    override fun clear() {
        prefs.edit { remove(BLOB_KEY) }
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Throwable) {
            // best-effort — Settings-side "disable biometric" must be
            // idempotent even when the Keystore is in a weird state.
        }
    }

    private fun createKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun loadKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private suspend fun authenticate(
        host: BiometricPromptHost,
        copy: BiometricPromptCopy,
        cryptoObject: BiometricPrompt.CryptoObject,
    ): BiometricPrompt.AuthenticationResult = suspendCancellableCoroutine { cont ->
        val activity = host.activity
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (cont.isActive) cont.resume(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        BiometricAuthException(errorCode, errString.toString())
                    )
                }
            }
            // onAuthenticationFailed (rejection / "try again") is
            // intentionally not handled — the prompt stays open and the
            // user can re-attempt. Permanent failures surface as
            // onAuthenticationError with ERROR_LOCKOUT_PERMANENT etc.
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(copy.title)
            .apply { copy.subtitle?.let { setSubtitle(it) } }
            .apply { copy.description?.let { setDescription(it) } }
            .setNegativeButtonText(copy.negativeButton)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, cryptoObject)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "app.tenshu.control.biometric_unlock_wrap_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val BIOMETRIC_PREFS_FILE = "tenshu_biometric_unlock"
        const val BLOB_KEY = "wrap_key_blob"
        const val SEPARATOR = ":"
    }
}

/**
 * Internal carrier for `BiometricPrompt` `onAuthenticationError` codes
 * so the suspending wrapper can resume with a typed exception the
 * caller maps to [EnrollResult] / [UnwrapResult].
 */
private class BiometricAuthException(
    val errorCode: Int,
    message: String,
) : RuntimeException(message)

private fun BiometricAuthException.toEnrollResult(): EnrollResult = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED -> EnrollResult.CancelledByUser

    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE -> EnrollResult.NotAvailable

    else -> EnrollResult.Failure(message)
}

private fun BiometricAuthException.toUnwrapResult(): UnwrapResult = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED -> UnwrapResult.CancelledByUser

    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE -> UnwrapResult.NotAvailable

    else -> UnwrapResult.Failure(message)
}

// Public constructor so the `:control-shared` Compose resolver
// (`rememberBiometricPromptHost`, a separate module) can build it; the
// `activity` field stays `internal` to `:control-shared-logic` — only this
// delegate reads it.
actual class BiometricPromptHost(
    internal val activity: FragmentActivity,
)
