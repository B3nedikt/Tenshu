package org.tenshu.core.common.app

import org.tenshu.core.Data
import org.tenshu.core.app.AppService
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.app.proxy.AnnounceSessionImplementation
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.ServiceVersionMismatch
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.toTString
import org.tenshu.core.toTBoolean
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.core.create
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SessionAdmission.toSchemaSkew` (`core-schema-skew-surface.md` SK-1) — the distillation that
 * turns a refusal into "which binary do I tell the user to update".
 *
 * ⚠⚠ The first three tests run against the **real** `AnnounceSessionImplementation`, not a
 * hand-built admission. The distillation reads the proxy's own verdict flags, and that is only
 * worth anything if the producer is in the loop — a fixture that sets its own flags would keep
 * passing after the proxy changed which comparison sets which flag.
 */
class SchemaSkewTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy()
    }

    private fun announce(expected: VersionVector, appId: String = "app.test"): SessionAdmission {
        val params = mapOf<String, Data>(
            AnnounceSession.Params.APP_ID to appId.toTString(testProxy.tContext),
            AnnounceSession.Params.CURRENT to expected,
            AnnounceSession.Params.REQUIRES to expected,
        )
        return AnnounceSessionImplementation().execute(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = params,
        )
    }

    /** ⚠ Derived, never a literal — the sibling test learned this the day AppService was bumped. */
    private fun vectorFor(appServiceVersion: Int) = VersionVector.create(
        testProxy.tContext,
        coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
        services = listOf(
            ServiceVersion.create(testProxy.tContext, AppService.ID, appServiceVersion)
        ),
    )

    @Test
    fun anAdmittedSessionHasNoSkew() {
        val admission = announce(testProxy.schemaRegistry.currentVersionVector(testProxy.tContext))

        assertNull(
            admission.toSchemaSkew("app.test"),
            "an admitted announce must clear the verdict, or the banner outlives its own fix",
        )
    }

    @Test
    fun aClientAheadOfItsProxyMeansUpdateControl() {
        val admission = announce(vectorFor(AppService.Schema.version + 1), appId = "app.tenshu.fit")

        val skew = admission.toSchemaSkew("app.tenshu.fit")

        assertEquals(SchemaSkew.Direction.PROXY_BEHIND, skew?.direction)
        assertEquals("app.tenshu.fit", skew?.appId)
        assertEquals(listOf(AppService.ID), skew?.serviceIds)
    }

    /**
     * The other half of the §6.3 asymmetry. Announcing 0 for a service the proxy knows puts the
     * client below the floor whatever the live floor is — the same derive-don't-hardcode rule.
     */
    @Test
    fun aClientBelowTheProxyFloorMeansUpdateThisApp() {
        val admission = announce(vectorFor(0), appId = "app.tenshu.fit")

        assertEquals(
            SchemaSkew.Direction.CLIENT_BEHIND,
            admission.toSchemaSkew("app.tenshu.fit")?.direction,
        )
    }

    /**
     * ⚠⚠ The case that makes [SchemaSkew.Direction] an enum rather than a `Boolean`. Two services
     * disagreeing in *opposite* directions means divergent builds, and naming either app alone
     * leaves the user stuck after they have done exactly what the banner asked.
     *
     * Real producer: the core slot's requirement above the proxy and the app service's current
     * below its floor, in one announce.
     */
    @Test
    fun oppositeDirectionsAcrossServicesMeanBoth() {
        val params = mapOf<String, Data>(
            AnnounceSession.Params.APP_ID to "app.test".toTString(testProxy.tContext),
            AnnounceSession.Params.CURRENT to vectorFor(0),
            AnnounceSession.Params.REQUIRES to VersionVector.create(
                testProxy.tContext,
                coreVersion = TENSHU_STORAGE_FORMAT_VERSION + 1,
                services = listOf(ServiceVersion.create(testProxy.tContext, AppService.ID, 0)),
            ),
        )
        val admission: SessionAdmission = AnnounceSessionImplementation().execute(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = testProxy.executionContext.implementationFactory,
            caller = null,
            params = params,
        )

        val skew = admission.toSchemaSkew("app.test")

        assertEquals(SchemaSkew.Direction.BOTH, skew?.direction)
        assertEquals(
            listOf(AnnounceSessionImplementation.CORE_SERVICE_ID, AppService.ID).sorted(),
            skew?.serviceIds,
            "service ids are sorted so the diagnostic detail is stable across runs",
        )
    }

    /**
     * ⚠⚠ A refusal that sets neither flag must still read as skew. Returning `null` here would
     * render the app as healthy on exactly the sessions where every read fails — the failure mode
     * this whole surface exists to remove. `SessionAdmission.create` cannot build one (it derives
     * `admitted` from the flags), so the fixture writes the raw positional fields.
     */
    @Test
    fun aRefusalWithNoNamedMismatchesIsStillSkew() {
        val skew = refusedWith().toSchemaSkew("app.test")

        assertEquals(SchemaSkew.Direction.BOTH, skew?.direction)
        assertEquals(emptyList(), skew?.serviceIds)
    }

    // MARK: - hand-built admissions

    private fun refusedWith(): SessionAdmission {
        val ctx = testProxy.tContext
        val empty = VersionVector.create(ctx, coreVersion = 0, services = emptyList())
        return ctx.create(
            type = SessionAdmission.ID,
            fields = listOf(
                false.toTBoolean(ctx), // admitted
                false.toTBoolean(ctx), // updateProxy
                false.toTBoolean(ctx), // updateClient
                empty,
                empty,
                SimpleTList(emptyList<ServiceVersionMismatch>()),
                "".toTString(ctx),
                "".toTString(ctx),
            ),
        )
    }
}
