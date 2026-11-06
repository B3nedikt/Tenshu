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
import org.tenshu.identity.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire + schema coverage for [Member] (D5 in-realm member record). Mirrors
 * [SharedRealmMembershipSerializationTest]: a serialize→parse round-trip (the
 * wire-registration gap — the Dto + traits must be registered in
 * `identityServiceCommonModule` or the parser fails), the `Syncable` trait, and
 * keying by `memberId`.
 */
class MemberSerializationTest {

    private val registry = createCoreTestRegistry().also {
        it.registerConfig(identityServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun member_roundTrips() {
        val original = Member.create(
            context = context,
            realmId = "realm-1",
            memberId = "member-abc",
            displayName = "Bob",
            contactFingerprint = "fp-bob",
            role = Member.Role.MEMBER,
            joinedAt = 1_900_000_000_000L, // ms since epoch, beyond Int range
        )

        val parsed = parser.parse(serializer.serialize(original)) as Member

        assertEquals("realm-1", parsed.realmId.get())
        assertEquals("realm-1::member-abc", parsed.compositeKey.get())
        assertEquals("member-abc", parsed.memberId.get())
        assertEquals("Bob", parsed.displayName.get())
        assertEquals("fp-bob", parsed.contactFingerprint.get())
        assertEquals("member", parsed.role.get())
        assertEquals(1_900_000_000_000L, parsed.joinedAt.get())
    }

    @Test
    fun member_isSyncable() {
        assertTrue(Member.Schema.hasTrait(Syncable.ID), "Member must be Syncable")
    }

    @Test
    fun member_dataIdDerivesFromCompositeKey() {
        val proxy = TestProxy().apply {
            schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        val member = Member.create(
            context = proxy.tContext,
            realmId = "realm-xyz",
            memberId = "member-xyz",
            displayName = "Bob",
            contactFingerprint = "",
            role = Member.Role.MEMBER,
            joinedAt = 0L,
        )
        val entity = Entity.create<Member>(context = proxy.tContext, composite = member)
        val dataId = proxy.executionContext.getDataId(entity)
        assertTrue(dataId is DataId.StringDataId)
        assertEquals(Member.ID, dataId.type.get())
        // The realm-prefixed key routes the row into the shared realm (SharedFile precedent).
        assertEquals("realm-xyz::member-xyz", dataId.keyValue.get())
    }
}
