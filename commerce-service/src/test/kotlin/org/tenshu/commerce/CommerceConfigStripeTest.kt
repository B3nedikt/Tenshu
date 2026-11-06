package org.tenshu.commerce

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Config coverage for the Phase-4 Stripe fields — parsing, the enabled gate, and the half-config guard. */
class CommerceConfigStripeTest {

    @Test
    fun parsePriceIds_parsesAndSkipsMalformed() {
        val map = CommerceConfig.parsePriceIds("tenshu.drive.basic=price_a, tenshu.drive.premium=price_b, junk, =price_c, x=")
        assertEquals(mapOf("tenshu.drive.basic" to "price_a", "tenshu.drive.premium" to "price_b"), map)
    }

    @Test
    fun parsePriceIds_blankIsEmpty() {
        assertTrue(CommerceConfig.parsePriceIds(null).isEmpty())
        assertTrue(CommerceConfig.parsePriceIds("  ").isEmpty())
    }

    @Test
    fun stripeEnabled_requiresBothSecrets() {
        val env = mapOf(
            "STRIPE_SECRET_KEY" to "sk_test",
            "STRIPE_WEBHOOK_SECRET" to "whsec_test",
            "STRIPE_PRICE_IDS" to "tenshu.drive.basic=price_a",
        )
        val config = CommerceConfig.fromEnv { env[it] }
        assertTrue(config.stripeEnabled)
        assertEquals("price_a", config.stripePriceIds["tenshu.drive.basic"])
    }

    @Test
    fun noStripe_isDisabled() {
        assertFalse(CommerceConfig.fromEnv { null }.stripeEnabled)
    }

    @Test
    fun secretWithoutWebhookSecret_failsValidation() {
        val config = CommerceConfig.fromEnv { name -> if (name == "STRIPE_SECRET_KEY") "sk_test" else null }
        assertFailsWith<InvalidCommerceConfigException> { config.validate() }
    }
}
