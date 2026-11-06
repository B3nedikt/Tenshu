package org.tenshu.core.common.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.app.AppIdentityResolver
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.app.proxy.appRegistrationTrigger
import org.tenshu.core.common.app.proxy.initAppService
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The handshake-triggered registration writer (A4,
 * `docs/tracks/track-a-control-apps.md`). Exercises the callback
 * `ClientRegistry` fires on a new session: it resolves the connecting clientId
 * to a canonical appId via the allowlist and writes an `AppRegistration` when a
 * realm is active, no-ops for an unknown client or before a realm exists, and
 * is idempotent across re-binds. Complements `AppServiceLocalTest` (op
 * semantics) and `ClientRegistryTest` (the generic hook fires once per session).
 */
class AppRegistrationTriggerTest {

    private val mappedClientId = "app.tenshu.drive.android"
    private val appId = "app.tenshu.drive"
    private val deviceId = "device-A"

    /** Allowlist that maps exactly one client id, like the bundled resolver. */
    private val resolver = AppIdentityResolver { clientId ->
        if (clientId == mappedClientId) appId else null
    }

    @Test
    fun mappedClient_withRealm_writesActiveRegistration() = runTest {
        val proxy = createProxy(backgroundScope)
        val trigger = appRegistrationTrigger(resolver, deviceIdProvider = { deviceId })

        trigger(mappedClientId, proxy.executionContext)

        assertNotNull(
            findRegistration(proxy),
            "an allowlisted client connecting with an active realm must register",
        )
    }

    /**
     * `verified` is the attestation claim (`desktop-ipc-client-identity.md` P3): on desktop it
     * reflects whether the peer's code signature satisfied the Team-ID-pinned requirement.
     * It was hardcoded `false` for the whole of v1, so both directions are pinned here — a
     * flag that is always false passes any test that only checks the false case.
     */
    @Test
    fun attestedClient_registersAsVerified() = runTest {
        val proxy = createProxy(backgroundScope)
        val trigger = appRegistrationTrigger(
            resolver,
            deviceIdProvider = { deviceId },
            verifiedProvider = { it == mappedClientId },
        )

        trigger(mappedClientId, proxy.executionContext)

        val row = assertNotNull(findRegistration(proxy)) as AppRegistration
        assertTrue(row.verified.get(), "an attested peer must register as verified")
    }

    @Test
    fun unattestedClient_registersAsUnverified() = runTest {
        val proxy = createProxy(backgroundScope)
        // No probe supplied — the default. A platform that makes no attestation claim must
        // never over-state it.
        val trigger = appRegistrationTrigger(resolver, deviceIdProvider = { deviceId })

        trigger(mappedClientId, proxy.executionContext)

        val row = assertNotNull(findRegistration(proxy)) as AppRegistration
        assertFalse(row.verified.get(), "an unattested peer must register as unverified")
    }

    @Test
    fun unknownClient_doesNotRegister() = runTest {
        val proxy = createProxy(backgroundScope)
        val trigger = appRegistrationTrigger(resolver, deviceIdProvider = { deviceId })

        trigger("com.example.stranger", proxy.executionContext)

        assertNull(findRegistration(proxy), "a client not on the allowlist must not be registered")
    }

    @Test
    fun mappedClient_withoutRealm_doesNotRegister() = runTest {
        val proxy = createProxy(backgroundScope)
        // deviceIdProvider returns null ⇒ no realm yet; registration is deferred.
        val trigger = appRegistrationTrigger(resolver, deviceIdProvider = { null })

        trigger(mappedClientId, proxy.executionContext)

        assertNull(findRegistration(proxy), "no realm ⇒ skip; a later re-bind retries")
    }

    @Test
    fun reBind_isIdempotent_singleRow() = runTest {
        val proxy = createProxy(backgroundScope)
        val trigger = appRegistrationTrigger(resolver, deviceIdProvider = { deviceId })

        trigger(mappedClientId, proxy.executionContext)
        val first = findRegistration(proxy)
        assertNotNull(first)

        // Second connection (re-bind) fires the trigger again — idempotent.
        trigger(mappedClientId, proxy.executionContext)
        assertNotNull(findRegistration(proxy), "re-bind must keep the registration (idempotent)")
    }

    // ---- harness -----------------------------------------------------------

    private fun createProxy(scope: CoroutineScope): TestProxy =
        TestProxy(coroutineScope = scope).also { it.executionContext.initAppService() }

    /** The `(appId, deviceId)` row by its derived StringDataId, or null. */
    private fun findRegistration(proxy: TestProxy) =
        proxy.executionContext.database.getData(
            DataId.StringDataId.create(
                context = proxy.tContext,
                type = AppRegistration.ID,
                keyValue = AppRegistration.compositeKey(appId, deviceId),
            )
        )
}
