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
import org.tenshu.identity.MemberAuthorityKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire + schema coverage for [MemberAuthorityKey] (A26 storage). The keypair
 * syncs across the member's own devices so every device self-binds (D2); the
 * round-trip proves the byte fields survive the parser and the row keys by
 * `sharedRealmId`.
 */
class MemberAuthorityKeySerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun authorityKey_roundTrips() {
        val pub = ByteArray(32) { (it + 1).toByte() }
        val priv = ByteArray(32) { (it + 100).toByte() }
        val cap = ByteArray(80) { (it + 200).toByte() }
        val original = MemberAuthorityKey.create(
            context = context,
            sharedRealmId = "realm-abc",
            publicKey = pub,
            privateKey = priv,
            capability = cap,
        )

        val parsed = parser.parse(serializer.serialize(original)) as MemberAuthorityKey

        assertEquals("realm-abc", parsed.sharedRealmId.get())
        assertEquals(pub.toList(), parsed.publicKey.get().toList())
        assertEquals(priv.toList(), parsed.privateKey.get().toList())
        assertEquals(cap.toList(), parsed.capability.get().toList())
    }

    @Test
    fun authorityKey_isSyncable() {
        assertTrue(MemberAuthorityKey.Schema.hasTrait(Syncable.ID), "MemberAuthorityKey must be Syncable")
    }

    @Test
    fun authorityKey_dataIdDerivesFromSharedRealmId() {
        val proxy = TestProxy().apply {
            schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        val key = MemberAuthorityKey.create(
            context = proxy.tContext,
            sharedRealmId = "realm-xyz",
            publicKey = byteArrayOf(1, 2, 3),
            privateKey = byteArrayOf(4, 5, 6),
            capability = byteArrayOf(7, 8, 9),
        )
        val entity = Entity.create<MemberAuthorityKey>(context = proxy.tContext, composite = key)
        val dataId = proxy.executionContext.getDataId(entity)
        assertTrue(dataId is DataId.StringDataId)
        assertEquals(MemberAuthorityKey.ID, dataId.type.get())
        assertEquals("realm-xyz", dataId.keyValue.get())
    }
}
