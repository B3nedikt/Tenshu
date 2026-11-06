package org.tenshu.core.common.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.AppService
import org.tenshu.core.app.registerApp
import org.tenshu.core.app.removeApp
import org.tenshu.core.app.setAppGrant
import org.tenshu.core.collection.list.search
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.app.proxy.initAppService
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.core.time.UnixInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import org.tenshu.core.TBoolean
import kotlin.test.assertTrue

/**
 * Local (single-proxy) round-trip via the SDK chain for the app registration /
 * permission surface (`docs/proposals/control-app-registration.md`). Proves
 * Phase A0: the [RegisterApp][org.tenshu.core.app.RegisterApp] /
 * [RemoveApp][org.tenshu.core.app.RemoveApp] /
 * [SetAppGrant][org.tenshu.core.app.SetAppGrant] operations write the right
 * composites, the proxy-side relation maintainer (`initAppService`) surfaces
 * them on the service relations, and the lifecycle invariants (idempotency,
 * `Removed` suppresses re-register, grant purge on removal) hold.
 */
class AppServiceLocalTest {

    private val appId = "app.drive"
    private val deviceId = "device-A"

    @Test
    fun registerApp_createsActiveRow_visibleOnServiceRelation() = runTest {
        val service = appService(backgroundScope)

        val result = service.registerApp(appId, deviceId, verified = false)
        assertTrue(result is TResult.Success<*>, "registerApp failed: $result")

        val reg = findRegistration(service, appId)
        assertNotNull(reg, "AppRegistration should be reachable via the SDK chain after register")
        assertEquals(AppRegistration.State.ACTIVE, reg.getField<TString>(AppRegistration.Field.STATE).get())
        assertEquals(appId, reg.getField<TString>(AppRegistration.Field.APP_ID).get())
        assertEquals(deviceId, reg.getField<TString>(AppRegistration.Field.DEVICE_ID).get())
        assertEquals(false, reg.getField<org.tenshu.core.TBoolean>(AppRegistration.Field.VERIFIED).get())
        // createdAt stamped to a real wall-clock instant (the install date).
        assertTrue(reg.getField<UnixInstant>(AppRegistration.Field.CREATED_AT).get() > 0L)
    }

    @Test
    fun registerApp_isIdempotent_secondCallDoesNotRestamp() = runTest {
        val service = appService(backgroundScope)

        assertTrue(service.registerApp(appId, deviceId, verified = true) is TResult.Success<*>)
        val firstCreatedAt = findRegistration(service, appId)!!
            .getField<UnixInstant>(AppRegistration.Field.CREATED_AT).get()

        // Second register on the same (appId, deviceId) is a no-op.
        assertTrue(service.registerApp(appId, deviceId, verified = true) is TResult.Success<*>)
        val secondCreatedAt = findRegistration(service, appId)!!
            .getField<UnixInstant>(AppRegistration.Field.CREATED_AT).get()

        assertEquals(firstCreatedAt, secondCreatedAt, "idempotent re-register must not re-stamp createdAt")
    }

    /**
     * `verified` is a *current* property of the connecting peer, not a fact about the install, so
     * re-registration refreshes it. Frozen-at-first-contact had a visible cost: rows written
     * before desktop attestation and Android's claim existed kept `verified = false` forever,
     * and because the rows are synced and durable they never self-healed — the Apps screen's
     * badge stayed on for every app and therefore meant nothing.
     */
    @Test
    fun reRegistration_refreshesVerified_withoutRestampingCreatedAt() = runTest {
        val service = appService(backgroundScope)

        assertTrue(service.registerApp(appId, deviceId, verified = false) is TResult.Success<*>)
        val firstCreatedAt = findRegistration(service, appId)!!
            .getField<UnixInstant>(AppRegistration.Field.CREATED_AT).get()

        // The peer now attests — the badge must follow.
        assertTrue(service.registerApp(appId, deviceId, verified = true) is TResult.Success<*>)

        val reg = assertNotNull(findRegistration(service, appId))
        assertTrue(
            reg.getField<TBoolean>(AppRegistration.Field.VERIFIED).get(),
            "an attested re-registration must update the badge",
        )
        assertEquals(
            firstCreatedAt,
            reg.getField<UnixInstant>(AppRegistration.Field.CREATED_AT).get(),
            "the install date must not creep forward on a re-bind",
        )
    }

    /** …and the other direction: an attestation that stops holding must clear the badge. */
    @Test
    fun reRegistration_canClearVerified() = runTest {
        val service = appService(backgroundScope)

        assertTrue(service.registerApp(appId, deviceId, verified = true) is TResult.Success<*>)
        assertTrue(service.registerApp(appId, deviceId, verified = false) is TResult.Success<*>)

        assertFalse(
            findRegistration(service, appId)!!.getField<TBoolean>(AppRegistration.Field.VERIFIED).get(),
            "the badge tracks the peer, so it must be able to go back down",
        )
    }

    /**
     * ⚠ The tombstone stays completely untouched — refreshing a badge on a `Removed` app would be
     * the first step back towards the silent re-registration §5.1 exists to prevent.
     */
    @Test
    fun removedRow_isNotEvenBadgeRefreshed() = runTest {
        val service = appService(backgroundScope)

        assertTrue(service.registerApp(appId, deviceId, verified = false) is TResult.Success<*>)
        assertTrue(service.removeApp(appId, deviceId) is TResult.Success<*>)

        assertTrue(service.registerApp(appId, deviceId, verified = true) is TResult.Success<*>)

        val reg = assertNotNull(findRegistration(service, appId))
        assertEquals(
            AppRegistration.State.REMOVED,
            reg.getField<TString>(AppRegistration.Field.STATE).get(),
        )
        assertFalse(
            reg.getField<TBoolean>(AppRegistration.Field.VERIFIED).get(),
            "a Removed row must not be updated at all, badge included",
        )
    }

    @Test
    fun removedRow_blocksReRegistration() = runTest {
        val service = appService(backgroundScope)

        assertTrue(service.registerApp(appId, deviceId) is TResult.Success<*>)
        assertTrue(service.removeApp(appId, deviceId) is TResult.Success<*>)

        // The tombstone must survive a subsequent register attempt — a
        // still-installed binary cannot silently re-register.
        assertTrue(service.registerApp(appId, deviceId) is TResult.Success<*>)

        val reg = findRegistration(service, appId)
        assertNotNull(reg, "the Removed row should still be present")
        assertEquals(
            AppRegistration.State.REMOVED,
            reg.getField<TString>(AppRegistration.Field.STATE).get(),
            "re-register must not flip a Removed row back to Active",
        )
    }

    @Test
    fun removeApp_purgesGrants() = runTest {
        val service = appService(backgroundScope)

        assertTrue(service.registerApp(appId, deviceId) is TResult.Success<*>)
        assertTrue(
            service.setAppGrant(appId, "svc.files", "op.read", AppGrant.Grant.GRANTED) is TResult.Success<*>
        )
        assertNotNull(findGrant(service, appId), "grant should exist before removal")

        assertTrue(service.removeApp(appId) is TResult.Success<*>)

        assertNull(findGrant(service, appId), "removeApp must purge the app's grants")
    }

    @Test
    fun setAppGrant_roundTripsTriState() = runTest {
        val service = appService(backgroundScope)

        assertTrue(
            service.setAppGrant(appId, "svc.files", "op.read", AppGrant.Grant.DENIED) is TResult.Success<*>
        )

        val grant = findGrant(service, appId)
        assertNotNull(grant)
        assertEquals(AppGrant.Grant.DENIED, grant.getField<TString>(AppGrant.Field.GRANT).get())
        assertEquals("svc.files", grant.getField<TString>(AppGrant.Field.SERVICE_ID).get())
        assertEquals("op.read", grant.getField<TString>(AppGrant.Field.OPERATION_ID).get())

        // Re-setting overwrites the same row (keyed on compositeKey).
        assertTrue(
            service.setAppGrant(appId, "svc.files", "op.read", AppGrant.Grant.GRANTED) is TResult.Success<*>
        )
        assertEquals(
            AppGrant.Grant.GRANTED,
            findGrant(service, appId)!!.getField<TString>(AppGrant.Field.GRANT).get(),
        )
    }

    @Test
    fun appRegistration_dataIdDerivesFromCompositeKey() = runTest {
        val proxy = createProxy(backgroundScope)
        val service = environmentFor(proxy).getService<AppService>(AppService.ID)
        service.registerApp(appId, deviceId)

        // The single-field stringDataId provider keys the row on the derived
        // compositeKey; getData by that StringDataId must find the same row.
        val dataId = DataId.StringDataId.create(
            context = proxy.tContext,
            type = AppRegistration.ID,
            keyValue = AppRegistration.compositeKey(appId, deviceId),
        )
        assertNotNull(
            proxy.executionContext.database.getData(dataId),
            "AppRegistration DataId must derive from its compositeKey",
        )
    }

    // ---- harness -----------------------------------------------------------

    private fun createProxy(scope: CoroutineScope): TestProxy =
        TestProxy(coroutineScope = scope).also {
            it.executionContext.initAppService()
        }

    private fun appService(scope: CoroutineScope): AppService {
        val proxy = createProxy(scope)
        return environmentFor(proxy).getService(AppService.ID)
    }

    private suspend fun findRegistration(service: AppService, appId: String): Composite? {
        val result = service.appRegistrations
            .search<AppRegistration> { this.appId equalTo appId }
            .resolve()
        if (result is TResult.Failure<*>) error("search failed: ${result.error}")
        return (result as TResult.Success<*>).value as? Composite
    }

    private suspend fun findGrant(service: AppService, appId: String): Composite? {
        val result = service.appGrants
            .search<AppGrant> { this.appId equalTo appId }
            .resolve()
        if (result is TResult.Failure<*>) error("search failed: ${result.error}")
        return (result as TResult.Success<*>).value as? Composite
    }

    private fun environmentFor(proxy: TestProxy): TenshuEnvironment {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = proxy.tContext,
        )
    }
}
