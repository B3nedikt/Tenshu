package org.tenshu.commerce

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `WEB_APP_ORIGIN` is the one origin CORS admits (T-CB-09). Ktor's `allowHost("*")` means *any*
 * host, so a wildcard would open the unauthenticated `/register` to every page on the internet,
 * and a value with no scheme would silently never match. Both must refuse at startup.
 */
class CommerceConfigWebOriginTest {

    private fun production(origin: String?): CommerceConfig = CommerceConfig.fromEnv { key ->
        when (key) {
            "DEV_MODE" -> "false"
            "ISSUER_SEED" -> Base64.getEncoder().encodeToString(ByteArray(32) { (it + 3).toByte() })
            "SMTP_HOST" -> "smtp.example.test"
            "SMTP_FROM" -> "no-reply@tenshu.app"
            "WEB_APP_ORIGIN" -> origin
            else -> null
        }
    }

    private fun dev(origin: String?): CommerceConfig =
        CommerceConfig.fromEnv { key -> if (key == "WEB_APP_ORIGIN") origin else null }

    @Test
    fun productionAcceptsOneHttpsOrigin() {
        production("https://tenshu.app").validate()
        production("https://tenshu.app:8443").validate()
        // commerce trims a trailing slash before it validates
        assertEquals("https://tenshu.app", production("https://tenshu.app/").also { it.validate() }.webAppOrigin)
    }

    @Test
    fun blankMeansNoBrowserAccess() {
        val config = production("")
        config.validate()
        assertNull(config.webAppOrigin)
    }

    @Test
    fun wildcardsAreRefused() {
        for (origin in listOf("*", "https://*", "https://*.tenshu.app", "http://*")) {
            assertFailsWith<InvalidCommerceConfigException>(origin) { production(origin).validate() }
            assertFailsWith<InvalidCommerceConfigException>(origin) { dev(origin).validate() }
        }
    }

    @Test
    fun malformedOriginsAreRefused() {
        for (origin in listOf("tenshu.app", "https://tenshu.app/control", "https://user@tenshu.app", "https://Tenshu.app", "https://a b")) {
            assertFailsWith<InvalidCommerceConfigException>(origin) { production(origin).validate() }
        }
    }

    @Test
    fun plainHttpOnlyInDevMode() {
        dev("http://localhost:5173").validate()
        assertFailsWith<InvalidCommerceConfigException> { production("http://tenshu.app").validate() }
    }
}
