package org.tenshu.commerce

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hashing + random-secret helpers for the account layer (`commerce-accounts-and-sessions.md` §4.1):
 * OTP codes and session tokens are stored **hashed** so a store dump never yields a usable secret.
 * SHA-256 is sufficient here — these are high-entropy (session token) or short-lived single-use
 * (OTP) secrets, not passwords, so no work-factor/salt is needed.
 */
private val secureRandom = SecureRandom()

/** Hex SHA-256 of [value]. Used to store OTP-code and session-token hashes. */
fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Lowercase-hex HMAC-SHA256 of [message] under [secret] — the primitive Stripe uses to sign
 * webhooks (`v1=` in the `Stripe-Signature` header, §6). Verifying it is what makes a forged
 * `invoice.paid` non-mintable (T-CB-07 / §5.3).
 */
fun hmacSha256Hex(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Constant-time comparison so an OTP/token check doesn't leak via timing. */
fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

/** Email-OTP: a fresh 6-digit numeric code, and the hashing the store sees. */
object OtpCodes {
    /** A zero-padded 6-digit code (`000000`–`999999`), uniform over the range. */
    fun generate(): String = "%06d".format(secureRandom.nextInt(1_000_000))

    fun hash(code: String): String = sha256Hex(code.trim())

    /** True iff [candidate] hashes to [storedHash] (constant-time). */
    fun matches(candidate: String, storedHash: String): Boolean =
        constantTimeEquals(hash(candidate), storedHash)
}

/** Commerce session: an opaque high-entropy bearer token (the raw value goes to the client once). */
object SessionTokens {
    private const val TOKEN_BYTES = 32

    /** A fresh url-safe random token. */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { secureRandom.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String = sha256Hex(token.trim())
}
