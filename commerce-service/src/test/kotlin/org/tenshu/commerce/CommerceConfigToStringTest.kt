package org.tenshu.commerce

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⚠⚠ `CommerceConfig` is a data class holding every secret this service has; its generated
 * `toString` would print them all. Pins the redacting override (and the nested ones). Every secret
 * value below carries the marker `SECRET`, so one search covers them all.
 */
class CommerceConfigToStringTest {

    @Test
    fun noSecretReachesToString() {
        val seed = ByteArray(32) { 7 }
        val digest = "ab".repeat(32)
        val env = mapOf(
            "ISSUER_SEED" to java.util.Base64.getEncoder().encodeToString(seed),
            "DATABASE_URL" to "jdbc:postgresql://db/commerce?password=SECRETdbpw",
            "REDEMPTION_CODES" to "SECRETredeem:tenshu.drive.premium:5",
            "ADMIN_TOKEN" to "SECRETadmintoken",
            "ADMIN_TOTP_SECRET" to "SECRETJBSWY3DPEHPK3PXP",
            "STRIPE_SECRET_KEY" to "sk_test_SECRETstripe",
            "STRIPE_WEBHOOK_SECRET" to "whsec_SECRETwebhook",
            "SMTP_HOST" to "smtp.example.com",
            "SMTP_FROM" to "no-reply@example.com",
            "SMTP_USERNAME" to "SECRETsmtpuser",
            "SMTP_PASSWORD" to "SECRETsmtppw",
            "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" to """{"private_key":"SECRETplaykey"}""",
            "GOOGLE_PLAY_RTDN_TOKEN" to "SECRETrtdn",
            "REVIEW_SIGNIN_ACCOUNTS" to "store-a-review-SECRETsuffix@example.com=$digest",
            "REVIEW_SIGNIN_HMAC_KEY" to "SECREThmackey-0123456789abcdef0123",
        )
        val config = CommerceConfig.fromEnv { env[it] }
        val printed = config.toString()

        assertFalse("secret" in printed.lowercase().replace("adminTotpSecret".lowercase(), "").replace("stripeSecretKey".lowercase(), "").replace("stripeWebhookSecret".lowercase(), ""), "a secret reached toString: $printed")
        assertFalse(digest in printed, "a review digest reached toString")
        assertFalse(env.getValue("ISSUER_SEED") in printed, "the issuer seed reached toString")
        assertFalse(seed.contentToString() in printed)
        // Still useful: the non-secret settings are there, and each secret says whether it is set.
        assertTrue("smtp.example.com" in printed)
        assertTrue("adminToken=set" in printed)
        assertTrue("reviewSignInAccounts=set(1)" in printed)
        assertTrue("issuerSeed=set" in printed)
    }
}
