package org.tenshu.core.common.app

import org.tenshu.core.app.AppService
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.app.proxy.AnnounceSessionImplementation
import org.tenshu.core.common.schema.ProxyHostIdentity
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.SessionAdmission
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.Data
import org.tenshu.core.toTString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `AnnounceSessionImplementation` (`core-schema-evolution.md` §6.8, `core-version-compatibility.md`
 * §4.7): the symmetric admission compare against the walked module graph, each direction judged on
 * its own client number, and the §6.4 session recording. A refusal is a SUCCESSFUL typed reply —
 * these tests never expect an exception.
 */
class AnnounceSessionImplementationTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy()
    }

    private fun announce(
        current: VersionVector,
        requires: VersionVector = current,
        appId: String = "app.test",
        proxy: TestProxy = testProxy,
    ): SessionAdmission {
        val params = mapOf<String, Data>(
            AnnounceSession.Params.APP_ID to appId.toTString(proxy.tContext),
            AnnounceSession.Params.CURRENT to current,
            AnnounceSession.Params.REQUIRES to requires,
        )
        return AnnounceSessionImplementation().execute(
            executionContext = proxy.executionContext,
            operationContext = null,
            implementationFactory = proxy.executionContext.implementationFactory,
            caller = null,
            params = params,
        )
    }

    /**
     * ⚠⚠ **Derived, never a literal.** An earlier version of this file announced a hard-coded `2`
     * with the note "AppService is registered at version 1" — and the day AppService was bumped the
     * "ahead" client became an exactly-in-step one. Offsets from the live version stay right
     * whatever the live version becomes.
     */
    private fun appVector(appServiceVersion: Int, core: Int = TENSHU_STORAGE_FORMAT_VERSION) =
        VersionVector.create(
            testProxy.tContext,
            coreVersion = core,
            services = listOf(ServiceVersion.create(testProxy.tContext, AppService.ID, appServiceVersion)),
        )

    private val live get() = AppService.Schema.version

    @Test
    fun matchingVectorIsAdmittedAndRecordedOnTheSession() {
        val current = testProxy.schemaRegistry.currentVersionVector(testProxy.tContext)

        val admission = announce(current, appId = "app.tenshu.drive")

        assertTrue(admission.admitted.get())
        assertFalse(admission.updateProxy.get())
        assertFalse(admission.updateClient.get())
        assertEquals(0, admission.mismatches.toList().size)
        assertEquals(current.toMap(), admission.proxyVector.toMap())
        val session = testProxy.executionContext.sessionAnnouncement
        assertTrue(session.announced)
        assertEquals("app.tenshu.drive", session.announcedAppId)
        assertEquals(current.toMap(), session.announcedVector!!.toMap())
        assertEquals(current.toMap(), session.announcedRequirement!!.toMap())
    }

    @Test
    fun aRequirementAboveTheProxyMeansUpdateProxy() {
        val admission = announce(current = appVector(live + 1), requires = appVector(live + 1))

        assertFalse(admission.admitted.get())
        assertTrue(admission.updateProxy.get())
        assertFalse(admission.updateClient.get())
        val mismatch = admission.mismatches.toList().single()
        assertEquals(AppService.ID, mismatch.serviceId.get())
        assertEquals((live + 1).toLong(), mismatch.clientRequires.get())
        assertEquals(live.toLong(), mismatch.proxyCurrent.get())
        assertFalse(testProxy.executionContext.sessionAnnouncement.announced)
        assertNull(testProxy.executionContext.sessionAnnouncement.announcedVector)
    }

    @Test
    fun aCurrentBelowTheProxyFloorMeansUpdateClient() {
        val admission = announce(current = appVector(0), requires = appVector(0))

        assertFalse(admission.admitted.get())
        assertFalse(admission.updateProxy.get())
        assertTrue(admission.updateClient.get())
        val mismatch = admission.mismatches.toList().single()
        assertEquals(0L, mismatch.clientCurrent.get())
        assertEquals(live.toLong(), mismatch.proxyFloor.get())
    }

    /**
     * ⭐ The reason the envelope carries two vectors (`core-version-compatibility.md` §4.7): a client
     * *built* ahead of this proxy whose *requirement* the proxy still meets is compatible. Compared
     * on one number, `current > proxyCurrent` would refuse it as "update Control".
     */
    @Test
    fun aClientAheadOfTheProxyIsAdmittedWhenItsRequirementIsMet() {
        val admission = announce(current = appVector(live + 1), requires = appVector(live))

        assertTrue(admission.admitted.get(), "requires ≤ proxyCurrent and current ≥ floor ⇒ admitted")
        assertEquals(0, admission.mismatches.toList().size)
    }

    /**
     * ⭐ The mirror case: a requirement below the proxy's floor is irrelevant to the floor check —
     * only the client's current version is held against the floor.
     */
    @Test
    fun aRequirementBelowTheFloorDoesNotRefuseACurrentClient() {
        val admission = announce(current = appVector(live), requires = appVector(0))

        assertTrue(admission.admitted.get())
    }

    @Test
    fun opposingDirectionsInOneAnnounceSetBothFlags() {
        val admission = announce(
            current = appVector(0, core = TENSHU_STORAGE_FORMAT_VERSION),
            requires = appVector(0, core = TENSHU_STORAGE_FORMAT_VERSION + 1),
        )

        assertFalse(admission.admitted.get())
        assertTrue(admission.updateProxy.get(), "core requirement above the proxy")
        assertTrue(admission.updateClient.get(), "app service current below the floor")
        assertEquals(
            setOf(AnnounceSessionImplementation.CORE_SERVICE_ID, AppService.ID),
            admission.mismatches.toList().map { it.serviceId.get() }.toSet(),
        )
    }

    @Test
    fun unknownServiceIsRefusedWithZeroFloorAndCurrent() {
        val unknown = VersionVector.create(
            testProxy.tContext,
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
            services = listOf(
                ServiceVersion.create(testProxy.tContext, "tenshu/imaginary/service", 1)
            ),
        )

        val admission = announce(unknown)

        assertFalse(admission.admitted.get())
        assertTrue(admission.updateProxy.get())
        val mismatch = admission.mismatches.toList().single()
        assertEquals("tenshu/imaginary/service", mismatch.serviceId.get())
        assertEquals(0L, mismatch.proxyFloor.get())
        assertEquals(0L, mismatch.proxyCurrent.get())
    }

    @Test
    fun coreMismatchReportsTheCoreSentinelId() {
        val futureCore = VersionVector.create(
            testProxy.tContext,
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION + 1,
            services = emptyList(),
        )

        val admission = announce(futureCore)

        assertFalse(admission.admitted.get())
        val mismatch = admission.mismatches.toList().single()
        assertEquals(AnnounceSessionImplementation.CORE_SERVICE_ID, mismatch.serviceId.get())
        assertEquals((TENSHU_STORAGE_FORMAT_VERSION + 1).toLong(), mismatch.clientRequires.get())
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION.toLong(), mismatch.proxyCurrent.get())
    }

    @Test
    fun servicesTheClientDoesNotAnnounceAreNotMismatches() {
        // A vector announcing NOTHING but a matching core is admitted — the
        // client simply doesn't use the proxy's other services.
        val minimal = VersionVector.create(
            testProxy.tContext,
            coreVersion = TENSHU_STORAGE_FORMAT_VERSION,
            services = emptyList(),
        )

        val admission = announce(minimal)

        assertTrue(admission.admitted.get())
    }

    @Test
    fun theReplyNamesTheHostBinaryWhenTheProxyRegistersOne() {
        val hosted = TestProxy(
            additionalComponents = mapOf(
                ProxyHostIdentity.COMPONENT_ID to ProxyHostIdentity("app.tenshu.control", "1.4.0"),
            ),
        )

        val admission = announce(
            current = hosted.schemaRegistry.currentVersionVector(hosted.tContext),
            proxy = hosted,
        )

        assertEquals("app.tenshu.control", admission.proxyAppId.get())
        assertEquals("1.4.0", admission.proxyAppVersion.get())
    }

    @Test
    fun anUnhostedProxyRepliesWithEmptyIdentityRatherThanAGuess() {
        val admission = announce(testProxy.schemaRegistry.currentVersionVector(testProxy.tContext))

        assertEquals("", admission.proxyAppId.get())
        assertEquals("", admission.proxyAppVersion.get())
    }
}
