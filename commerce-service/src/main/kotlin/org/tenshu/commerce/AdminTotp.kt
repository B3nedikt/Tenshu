package org.tenshu.commerce

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP — the admin console's **second factor** (WC-5, §5.3 / Q6).
 *
 * §5.3 calls MFA on this console "non-negotiable before it holds real users", and WC-4 raised the
 * stake by adding an export. The shape is forced by what the console already is: a **bearer-token,
 * curl-driven** surface (a browser form cannot send the header, which is why the mutations are curl
 * calls). WebAuthn needs a browser session and mTLS is a Caddy concern, so the factor that composes
 * with `curl` is a time-based code in a second header — the bearer token lives in a password manager,
 * the TOTP seed on a phone, and an attacker needs both.
 *
 * ⚠⚠ **Replay is the reason [AdminTotpVerifier] is stateful.** A TOTP code stays valid for its step
 * plus the skew window — up to ~90 s here — so an intercepted code is a usable credential for that
 * long. Each accepted `(step, code)` is therefore burned: a second presentation of the same code is
 * refused even while it is still arithmetically valid. Without that, "MFA" only raises the cost of a
 * captured request from forever to a minute and a half.
 *
 * Hand-rolled rather than a dependency, mirroring `StripeSignature` (also hand-rolled HMAC) — the
 * algorithm is a dozen lines and adding a library to this module for it is not worth the supply
 * chain.
 */
internal object AdminTotp {

    /** The standard 30-second step. */
    const val STEP_SECONDS = 30L

    /**
     * How many steps either side of "now" are accepted. One step (±30 s) absorbs ordinary clock
     * skew between the server and an operator's phone.
     *
     * ⚠ Widening this widens the replay window proportionally — it is a security parameter, not a
     * convenience knob. Fix the server's clock instead.
     */
    const val SKEW_STEPS = 1

    /** The `[0-9]{6}` code for [stepIndex], given the raw shared secret. */
    fun code(secret: ByteArray, stepIndex: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(stepIndex).array())
        // Dynamic truncation (RFC 4226 §5.4).
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    fun stepIndex(nowMs: Long): Long = nowMs / 1000 / STEP_SECONDS

    /**
     * Decode a base32 secret as authenticator apps present it (RFC 4648, padding and spacing
     * tolerated), or null if it is not valid base32.
     */
    fun decodeBase32(raw: String): ByteArray? {
        val cleaned = raw.trim().replace(" ", "").replace("-", "").trimEnd('=').uppercase()
        if (cleaned.isEmpty()) return null
        var buffer = 0L
        var bitsLeft = 0
        val out = ArrayList<Byte>(cleaned.length * 5 / 8)
        for (character in cleaned) {
            val value = BASE32_ALPHABET.indexOf(character)
            if (value < 0) return null
            buffer = (buffer shl 5) or value.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}

/**
 * Verifies an admin TOTP code, refusing replays.
 *
 * ⚠ **Locked by default**: a null/blank secret verifies nothing, so an unconfigured verifier admits
 * nobody rather than everybody — the same posture as `TokenAdminAuth`. `CommerceConfig.validate()`
 * refuses to start with an admin console and no secret when `DEV_MODE=false`, so the "no MFA"
 * combination cannot reach production at all.
 */
class AdminTotpVerifier(base32Secret: String?) {

    private val secret: ByteArray? = base32Secret
        ?.takeIf { it.isNotBlank() }
        ?.let { AdminTotp.decodeBase32(it) }
        ?.takeIf { it.isNotEmpty() }

    /** Steps already spent, so an intercepted code cannot be presented twice. */
    private val burned = LinkedHashSet<Pair<Long, String>>()

    /** True when a secret is configured — used only to decide whether the factor applies at all. */
    val isConfigured: Boolean get() = secret != null

    @Synchronized
    fun verify(presented: String?, nowMs: Long): Boolean {
        val key = secret ?: return false
        val code = presented?.trim() ?: return false
        if (code.length != 6 || code.any { !it.isDigit() }) return false

        val current = AdminTotp.stepIndex(nowMs)
        for (offset in -AdminTotp.SKEW_STEPS..AdminTotp.SKEW_STEPS) {
            val step = current + offset
            // ⚠ Constant-time compare, as everywhere a secret is checked here — a timing oracle on
            // a six-digit code is a small keyspace to walk.
            if (!constantTimeEquals(AdminTotp.code(key, step), code)) continue
            if (!burned.add(step to code)) return false // already spent: a replay
            pruneBurned(current)
            return true
        }
        return false
    }

    /** Drop entries that can no longer be presented anyway, so the set cannot grow without bound. */
    private fun pruneBurned(currentStep: Long) {
        burned.removeAll { (step, _) -> step < currentStep - AdminTotp.SKEW_STEPS - 1 }
    }
}
