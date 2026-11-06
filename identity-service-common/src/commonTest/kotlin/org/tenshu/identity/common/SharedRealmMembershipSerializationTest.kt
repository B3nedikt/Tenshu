package org.tenshu.identity.common

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import org.tenshu.identity.SharedRealmMembership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire + schema coverage for [SharedRealmMembership] (SR-2). Mirrors
 * [DeviceSerializationTest]: a serialize→parse round-trip (the
 * wire-registration gap — the Dto + traits must be registered in
 * `identityServiceCommonModule` or the parser fails), the `Syncable` trait, and
 * keying by `sharedRealmId`.
 */
class SharedRealmMembershipSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun membership_roundTrips() {
        // `wrappedMasterKey` is a real wrap-shaped blob length (ephemeralPub 32 +
        // nonce 24 + 32-byte key + tag 16 = 104) — the byte field must survive.
        val wrapped = ByteArray(104) { (it * 5 + 1).toByte() }
        val original = SharedRealmMembership.create(
            context = context,
            sharedRealmId = "realm-abc",
            wrappedMasterKey = wrapped,
            role = SharedRealmMembership.Role.OWNER,
        )

        val parsed = parser.parse(serializer.serialize(original)) as SharedRealmMembership

        assertEquals("realm-abc", parsed.sharedRealmId.get())
        assertEquals(wrapped.toList(), parsed.wrappedMasterKey.get().toList())
        assertEquals("owner", parsed.role.get())
    }

    @Test
    fun membership_isSyncable() {
        // Syncable(Eager) so a member's own devices converge on one row per realm.
        assertTrue(
            SharedRealmMembership.Schema.hasTrait(Syncable.ID),
            "SharedRealmMembership must be Syncable",
        )
    }

    @Test
    fun membership_dataIdDerivesFromSharedRealmId() {
        val proxy = TestProxy().apply {
            schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        val membership = SharedRealmMembership.create(
            context = proxy.tContext,
            sharedRealmId = "realm-xyz",
            wrappedMasterKey = byteArrayOf(1, 2, 3),
            role = SharedRealmMembership.Role.MEMBER,
        )
        val entity = Entity.create<SharedRealmMembership>(
            context = proxy.tContext,
            composite = membership,
        )
        val dataId = proxy.executionContext.getDataId(entity)
        assertTrue(dataId is DataId.StringDataId)
        assertEquals(SharedRealmMembership.ID, dataId.type.get())
        assertEquals("realm-xyz", dataId.keyValue.get())
    }
}
