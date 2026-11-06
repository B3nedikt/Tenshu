package org.tenshu.identity.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.identity.IdentityService
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.getSharedRealmMemberships
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3d.2b of `docs/proposals/drive-shared-realm-file-model.md`: the
 * `SharedRealmMembershipRelationMaintainer` derives the flat
 * `IdentityService.Relations.SHARED_REALM_MEMBERSHIPS` array on every membership
 * save (mirroring `DeviceRelationMaintainer`/`PlacementRelationMaintainer`), and
 * `getSharedRealmMemberships` reads it. The inbound *Shared with me* read surface.
 */
class SharedRealmMembershipRelationMaintainerTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = createIdentityProxy()
        testProxy.executionContext.initIdentityService()
    }

    private fun membership(realmId: String, role: String): SharedRealmMembership =
        SharedRealmMembership.create(
            context = testProxy.tContext,
            sharedRealmId = realmId,
            wrappedMasterKey = byteArrayOf(0x01, 0x02),
            role = role,
        )

    private fun Data.field(id: String): String = when (this) {
        is Entity<*> -> getFieldValue<TString>(id).get()
        is Composite -> getField<TString>(id).get()
        else -> error("unexpected membership shape: $this")
    }

    private fun membershipsInArray(): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = IdentityService.Relations.SHARED_REALM_MEMBERSHIPS,
            ),
            isEntity = false,
            elementSchemaId = SharedRealmMembership.ID,
        )
        return testProxy.executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    @Test
    fun savingMemberships_derivesTheFlatArray() {
        testProxy.executionContext.saveData(membership("realm-owned", SharedRealmMembership.Role.OWNER))
        testProxy.executionContext.saveData(membership("realm-joined", SharedRealmMembership.Role.MEMBER))

        val byRealm = membershipsInArray().associate {
            it.field(SharedRealmMembership.Fields.SHARED_REALM_ID) to it.field(SharedRealmMembership.Fields.ROLE)
        }
        assertEquals(
            mapOf("realm-owned" to "owner", "realm-joined" to "member"),
            byRealm,
            "both memberships land in the array with their roles",
        )
    }

    @Test
    fun reSavingSameRealm_convergesToOneEntry() {
        testProxy.executionContext.saveData(membership("realm-1", SharedRealmMembership.Role.MEMBER))
        // A second save for the same realm (e.g. a sync re-delivery) must not duplicate.
        testProxy.executionContext.saveData(membership("realm-1", SharedRealmMembership.Role.MEMBER))

        assertEquals(
            listOf("realm-1"),
            membershipsInArray().map { it.field(SharedRealmMembership.Fields.SHARED_REALM_ID) },
            "one row per shared realm (keyed by sharedRealmId)",
        )
    }

    @Test
    fun getSharedRealmMembershipsOp_returnsTheMemberships() = runTest {
        testProxy.executionContext.saveData(membership("realm-a", SharedRealmMembership.Role.OWNER))
        testProxy.executionContext.saveData(membership("realm-b", SharedRealmMembership.Role.MEMBER))

        val executor = LocalTaskExecutor(testProxy.executionContext)
        val tenshu = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = testProxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = testProxy.tContext,
        )
        val identityService: IdentityService = tenshu.getService(IdentityService.ID)

        val result = identityService.getSharedRealmMemberships()
        assertTrue(result is TResult.Success, "getSharedRealmMemberships should succeed")
        val realms = result.value!!.toList().map { it.field(SharedRealmMembership.Fields.SHARED_REALM_ID) }.toSet()
        assertEquals(setOf("realm-a", "realm-b"), realms)
    }
}
