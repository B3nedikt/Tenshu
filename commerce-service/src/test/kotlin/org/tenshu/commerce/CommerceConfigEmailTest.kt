package org.tenshu.commerce

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CE-2 config: SMTP parsing, the production refusal of the dev log sender, and the OTP budgets.
 */
class CommerceConfigEmailTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    @Test
    fun noSmtpVarsMeansNoTransport() {
        assertNull(CommerceConfig.parseSmtp(env(), ttlMinutes = 15))
    }

    @Test
    fun hostAndFromTogetherProduceATransport() {
        val smtp = CommerceConfig.parseSmtp(
            env(
                "SMTP_HOST" to "smtp.example.test",
                "SMTP_FROM" to "no-reply@tenshu.app",
                "SMTP_USERNAME" to "user",
                "SMTP_PASSWORD" to "pw",
                "SMTP_FROM_NAME" to "Tenshu",
            ),
            ttlMinutes = 20,
        )
        assertTrue(smtp != null)
        assertEquals("smtp.example.test", smtp.host)
        assertEquals(CommerceConfig.DEFAULT_SMTP_PORT, smtp.port)
        assertTrue(smtp.startTls, "STARTTLS defaults on — the OTP is a live credential on the wire")
        assertTrue(smtp.hasCredentials)
        assertEquals(20, smtp.ttlMinutes, "the email must state the window the code is actually issued for")
    }

    @Test
    fun credentialsAreOptional() {
        // An IP-allowlisted submission host or a relay on a private network legitimately has none;
        // requiring them would rule out a valid deployment.
        val smtp = CommerceConfig.parseSmtp(
            env("SMTP_HOST" to "localhost", "SMTP_FROM" to "no-reply@tenshu.app"),
            ttlMinutes = 15,
        )
        assertTrue(smtp != null)
        assertTrue(!smtp.hasCredentials)
    }

    @Test
    fun aHalfConfiguredPairIsRefusedRatherThanSilentlyIgnored() {
        // Quietly degrading to "no real email" is the state an operator would not notice until a
        // user could not sign in — the same reasoning as the Stripe key/webhook-secret pair.
        assertFailsWith<InvalidCommerceConfigException> {
            CommerceConfig.parseSmtp(env("SMTP_HOST" to "smtp.example.test"), ttlMinutes = 15)
        }
        assertFailsWith<InvalidCommerceConfigException> {
            CommerceConfig.parseSmtp(env("SMTP_FROM" to "no-reply@tenshu.app"), ttlMinutes = 15)
        }
    }

    @Test
    fun productionRefusesToStartWithTheDevLogSender() {
        // The log sender prints the OTP — the only factor in this account model. Same treatment as
        // the committed dev signing seed.
        val config = CommerceConfig.fromEnv(
            env("DEV_MODE" to "false", "ISSUER_SEED" to base64Seed(), "ISSUER_ID" to "tenshu.commerce"),
        )
        val failure = assertFailsWith<InvalidCommerceConfigException> { config.validate() }
        assertTrue(failure.message!!.contains("SMTP_HOST"), "the message must name the fix")
    }

    @Test
    fun productionStartsOnceARealTransportIsConfigured() {
        val config = CommerceConfig.fromEnv(
            env(
                "DEV_MODE" to "false",
                "ISSUER_SEED" to base64Seed(),
                "ISSUER_ID" to "tenshu.commerce",
                "SMTP_HOST" to "smtp.example.test",
                "SMTP_FROM" to "no-reply@tenshu.app",
            ),
        )
        config.validate()
    }

    @Test
    fun devModeStillStartsWithNoMailConfigured() {
        // An unconfigured `./gradlew :commerce-service:run` must keep working.
        CommerceConfig.fromEnv(env()).validate()
    }

    @Test
    fun otpBudgetsDefaultAndOverride() {
        val defaults = CommerceConfig.fromEnv(env())
        assertEquals(CommerceConfig.DEFAULT_OTP_SEND_PER_IP_PER_HOUR, defaults.otpSendPerIpPerHour)
        assertEquals(CommerceConfig.DEFAULT_OTP_SEND_PER_EMAIL_PER_HOUR, defaults.otpSendPerEmailPerHour)
        assertEquals(CommerceConfig.DEFAULT_OTP_VERIFY_PER_EMAIL_PER_HOUR, defaults.otpVerifyPerEmailPerHour)

        val tuned = CommerceConfig.fromEnv(
            env(
                "OTP_SEND_PER_IP_PER_HOUR" to "3",
                "OTP_SEND_PER_EMAIL_PER_HOUR" to "2",
                "OTP_VERIFY_PER_EMAIL_PER_HOUR" to "0",
            ),
        )
        assertEquals(3, tuned.otpSendPerIpPerHour)
        assertEquals(2, tuned.otpSendPerEmailPerHour)
        assertEquals(0, tuned.otpVerifyPerEmailPerHour, "0 is the documented 'disabled' value")
    }

    /** A valid 32-byte non-dev seed, base64. */
    private fun base64Seed(): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() })
}
