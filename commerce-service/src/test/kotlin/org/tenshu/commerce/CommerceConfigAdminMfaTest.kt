package org.tenshu.commerce

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The admin console's MFA config gate (WC-5, §5.3 / Q6).
 *
 * ⚠⚠ §5.3 called MFA on this console "non-negotiable before it holds real users", and WC-4 raised
 * the stake by adding an export to the one surface joining **A28 ⨯ A30** in the clear. A checklist
 * item would be forgotten; a startup refusal cannot be — which is why the rule lives here rather
 * than in a runbook, exactly as the dev signing seed (Ph3a), the fake Stripe rail (CE-3) and the
 * log email sender (CE-2) are each refused outside dev mode.
 */
class CommerceConfigAdminMfaTest {

    private fun env(map: Map<String, String?>): (String) -> String? = { map[it] }

    /** The other things a non-dev deployment already owes, so only the MFA rule is under test. */
    private fun productionEnv(vararg extra: Pair<String, String>): Map<String, String> = mapOf(
        "DEV_MODE" to "false",
        "ISSUER_SEED" to Base64.getEncoder().encodeToString(ByteArray(32) { (it + 7).toByte() }),
        "SMTP_HOST" to "smtp.example.test",
        "SMTP_FROM" to "no-reply@tenshu.app",
    ) + extra

    @Test
    fun aProductionAdminConsoleWithoutASecondFactorIsRefused() {
        val config = CommerceConfig.fromEnv(env(productionEnv("ADMIN_TOKEN" to "a-real-token")))
        val failure = assertFailsWith<InvalidCommerceConfigException> { config.validate() }
        // The message has to name the variable, or an operator cannot act on it.
        assert("ADMIN_TOTP_SECRET" in failure.message.orEmpty())
    }

    @Test
    fun aProductionAdminConsoleWithBothFactorsStarts() {
        val config = CommerceConfig.fromEnv(
            env(productionEnv("ADMIN_TOKEN" to "a-real-token", "ADMIN_TOTP_SECRET" to "JBSWY3DPEHPK3PXP")),
        )
        config.validate()
        assertEquals("JBSWY3DPEHPK3PXP", config.adminTotpSecret)
    }

    /** ⚠ No console, no console to protect — the rule is about the surface, not the deployment. */
    @Test
    fun aProductionDeploymentWithNoAdminConsoleIsUnaffected() {
        CommerceConfig.fromEnv(env(productionEnv())).validate()
    }

    /** Dev keeps working single-factor; that is the only place it is legal. */
    @Test
    fun devModeMayRunTheConsoleWithoutASecondFactor() {
        val config = CommerceConfig.fromEnv(env(mapOf("ADMIN_TOKEN" to "local-dev-admin")))
        config.validate()
    }

    /**
     * ⚠ An unparseable seed must fail at **startup**, not at the first login: it would otherwise
     * present as "MFA configured" while rejecting every code — a console locked against its own
     * operator, discovered at the worst possible moment.
     */
    @Test
    fun anUnparseableSecretIsRefusedEvenInDevMode() {
        val config = CommerceConfig.fromEnv(
            env(mapOf("ADMIN_TOKEN" to "local-dev-admin", "ADMIN_TOTP_SECRET" to "not!base32")),
        )
        assertFailsWith<InvalidCommerceConfigException> { config.validate() }
    }
}
