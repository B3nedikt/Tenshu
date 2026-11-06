package org.tenshu.commerce

import org.tenshu.identity.EntitlementSignature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommerceConfigTest {

    private fun env(map: Map<String, String?>): (String) -> String? = { map[it] }

    @Test
    fun devDefaults_validateAndUseDevSeed() {
        val config = CommerceConfig.fromEnv { null }
        assertTrue(config.devMode)
        assertEquals(CommerceConfig.DEFAULT_ISSUER_ID, config.issuerId)
        assertTrue(config.issuerSeed.contentEquals(CommerceConfig.DEV_ISSUER_SEED))
        config.validate() // dev mode with the dev seed is legal
    }

    @Test
    fun missingSeed_whenNotDevMode_isRejected() {
        val config = CommerceConfig.fromEnv(env(mapOf("DEV_MODE" to "false")))
        // No ISSUER_SEED supplied → falls back to the committed dev seed, which is illegal in prod.
        assertFailsWith<InvalidCommerceConfigException> { config.validate() }
    }

    @Test
    fun suppliedSeed_whenNotDevMode_validates() {
        val seed = ByteArray(32) { (it + 7).toByte() }
        val config = CommerceConfig.fromEnv(
            env(
                mapOf(
                    "DEV_MODE" to "false",
                    "ISSUER_SEED" to Base64.getEncoder().encodeToString(seed),
                    // A non-dev deployment must also carry a real mail transport (CE-2 / D3) —
                    // the dev sender prints the OTP, the only factor in this account model. The
                    // refusal itself is covered in CommerceConfigEmailTest.
                    "SMTP_HOST" to "smtp.example.test",
                    "SMTP_FROM" to "no-reply@tenshu.app",
                )
            )
        )
        config.validate()
        assertTrue(config.issuerSeed.contentEquals(seed))
    }

    @Test
    fun devPublisherId_isRejected() {
        val config = CommerceConfig.fromEnv(env(mapOf("ISSUER_ID" to EntitlementSignature.DEV_PUBLISHER_ID)))
        assertFailsWith<InvalidCommerceConfigException> { config.validate() }
    }

    @Test
    fun accountLayerEnv_isParsed() {
        val config = CommerceConfig.fromEnv(
            env(
                mapOf(
                    "DATABASE_URL" to "jdbc:postgresql://localhost/commerce",
                    "OTP_TTL_MINUTES" to "10",
                    "OTP_MAX_ATTEMPTS" to "4",
                    "SESSION_TTL_HOURS" to "48",
                )
            )
        )
        assertEquals("jdbc:postgresql://localhost/commerce", config.databaseUrl)
        assertEquals(10L, config.otpTtlMinutes)
        assertEquals(4, config.otpMaxAttempts)
        assertEquals(48L, config.sessionTtlHours)
    }

    @Test
    fun databaseUrl_defaultsToNull_inMemoryStore() {
        assertEquals(null, CommerceConfig.fromEnv { null }.databaseUrl)
    }

    @Test
    fun redemptionSeeds_parseCodeProductMaxUsesExpiry() {
        val config = CommerceConfig.fromEnv(
            env(mapOf("REDEMPTION_CODES" to "FOUNDER:tenshu.drive.premium:3, ONCE:tenshu.drive.basic:1:1700000000000, bad-entry"))
        )
        assertEquals(2, config.redemptionSeeds.size, "the malformed third entry is skipped")
        val founder = config.redemptionSeeds[0]
        assertEquals("FOUNDER", founder.code)
        assertEquals("tenshu.drive.premium", founder.productId)
        assertEquals(3, founder.maxUses)
        assertEquals(null, founder.expiresAtMs)
        assertEquals(1700000000000L, config.redemptionSeeds[1].expiresAtMs)
    }
}
