package app.tenshu.control.di

import org.koin.dsl.koinApplication
import org.tenshu.core.common.di.coreCommonModule
import org.tenshu.core.common.schema.proxyHostIdentity
import org.tenshu.core.core.TContext
import org.tenshu.sync.RuntimeIdentity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The announce reply's host identity (`SessionAdmission.proxyAppId` / `proxyAppVersion`) as the
 * production wiring produces it.
 *
 * ⚠⚠ The first test resolves through the **real** path: the Koin-backed `TContext` from
 * `coreCommonModule()` falls back to `koin.getOrNull<TComponent>(named(key))` for ids it does not
 * name, and the binding lives in Control's `PlatformProxyModule`. `TestProxy(additionalComponents)`
 * bypasses both, so an id typo or a binding under the wrong type would pass every other test and
 * leave every reply anonymous.
 */
class ProxyHostIdentityWiringTest {

    @Test
    fun `the desktop proxy module's binding resolves through the production TContext`() {
        val koin = koinApplication { modules(coreCommonModule(), buildPlatformProxyModule()) }.koin

        val identity = assertNotNull(
            koin.get<TContext>().proxyHostIdentity(),
            "the TContext fallback did not find the ProxyHostIdentity binding",
        )
        assertEquals(RuntimeIdentity.CONTROL_APP_ID, identity.appId)
        assertEquals(
            "",
            identity.appVersion,
            "desktop has no version; the reply must carry empty (= unknown), never the word 'unknown'",
        )
    }

    @Test
    fun `the unknown sentinel becomes empty and a real version passes through`() {
        assertEquals("", controlProxyHostIdentity(null, RuntimeIdentity.UNKNOWN_VERSION).appVersion)
        assertEquals("1.4.0", controlProxyHostIdentity("app.tenshu.control", "1.4.0").appVersion)
        assertEquals(RuntimeIdentity.CONTROL_APP_ID, controlProxyHostIdentity(" ", "1.4.0").appId)
        assertEquals("app.tenshu.drive", controlProxyHostIdentity("app.tenshu.drive", "2").appId)
    }

    /** Android and iOS cannot be resolved from a desktop test; hold them to the same helper. */
    @Test
    fun `every platform binds through controlProxyHostIdentity`() {
        val violations = listOf("androidMain", "desktopMain", "iosMain").mapNotNull { set ->
            val module = File("src/$set/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
            assertTrue(module.isFile, "the $set PlatformProxyModule moved; this test is no longer guarding.")
            val text = module.readText()
            val binding = text.substringAfter("named(ProxyHostIdentity.COMPONENT_ID)", "")
                .substringBefore("\n    }\n")
            if ("controlProxyHostIdentity(" in binding) null
            else "$set: the ProxyHostIdentity binding does not go through controlProxyHostIdentity"
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}
