package org.tenshu.contact.common

import org.tenshu.contact.ContactInvite
import org.tenshu.contact.Person
import org.tenshu.contact.TenshuIdentity
import org.tenshu.core.common.TestProxy
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Syncable
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Schema test for the handshake composites
 * (`contact-invitation-handshake.md` §7.1/§7.3, contact doc §4.1/§4.2): [Person],
 * [TenshuIdentity] and [ContactInvite] materialise, fields round-trip, every row is
 * `Syncable(Eager)` (personal realm), and the DataIds derive from the
 * designed key fields.
 */
class ContactSchemaTest {

    private fun proxy() = TestProxy().apply {
        schemaRegistry.registerConfig(contactServiceCommonModule)
    }

    @Test
    fun allThreeCompositesAreSyncable() {
        assertTrue(Person.Schema.hasTrait(Syncable.ID))
        assertTrue(TenshuIdentity.Schema.hasTrait(Syncable.ID))
        assertTrue(ContactInvite.Schema.hasTrait(Syncable.ID))
    }

    @Test
    fun personFieldsRoundTripAndKeyIsPersonId() {
        val p = proxy()
        val person = Person.create(
            context = p.tContext,
            personId = "person-1",
            displayName = "Alice",
        )
        assertEquals("person-1", person.personId.get())
        assertEquals("Alice", person.displayName.get())

        val dataId = p.executionContext.getDataId(Entity.create<Person>(p.tContext, person))
        assertTrue(dataId is DataId.StringDataId)
        assertEquals(Person.ID, dataId.type.get())
        assertEquals("person-1", dataId.keyValue.get())
    }

    @Test
    fun tenshuIdentityFieldsRoundTripAndKeyIsFingerprint() {
        val p = proxy()
        val publicKey = ByteArray(32) { it.toByte() }
        val identity = TenshuIdentity.create(
            context = p.tContext,
            fingerprint = "fp-abc",
            publicKey = publicKey,
            personId = "person-1",
            selfAssertedName = "Alice Example",
            verification = TenshuIdentity.Verification.UNVERIFIED,
            verifiedAt = 0L,
            source = TenshuIdentity.Source.INVITE_LINK,
        )
        assertEquals("fp-abc", identity.fingerprint.get())
        assertContentEquals(publicKey, identity.publicKey.get())
        assertEquals("person-1", identity.personId.get())
        assertEquals("Alice Example", identity.selfAssertedName.get())
        assertEquals(TenshuIdentity.Verification.UNVERIFIED, identity.verification.get())
        assertEquals(0L, identity.verifiedAt.get())
        assertEquals(TenshuIdentity.Source.INVITE_LINK, identity.source.get())

        val dataId = p.executionContext.getDataId(Entity.create<TenshuIdentity>(p.tContext, identity))
        assertTrue(dataId is DataId.StringDataId)
        assertEquals(TenshuIdentity.ID, dataId.type.get())
        assertEquals("fp-abc", dataId.keyValue.get())
    }

    @Test
    fun contactInviteFieldsRoundTripAndKeyIsInviteId() {
        val p = proxy()
        val invite = ContactInvite.create(
            context = p.tContext,
            inviteId = "00112233445566778899aabbccddeeff",
            secretHash = "hash-hex",
            status = ContactInvite.Status.PENDING,
            createdAt = 1000L,
            expiresAt = 1000L + ContactInvite.DEFAULT_TTL_MILLIS,
            intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
            intentLabel = "Holiday photos",
        )
        assertEquals("00112233445566778899aabbccddeeff", invite.inviteId.get())
        assertEquals("hash-hex", invite.secretHash.get())
        assertEquals(ContactInvite.Status.PENDING, invite.status.get())
        assertEquals(1000L, invite.createdAt.get())
        assertEquals(1000L + ContactInvite.DEFAULT_TTL_MILLIS, invite.expiresAt.get())
        assertEquals(ContactInvite.IntentKind.SHARE_FOLDER, invite.intentKind.get())
        assertEquals("Holiday photos", invite.intentLabel.get())
        assertEquals("", invite.resultFingerprint.get())

        val dataId = p.executionContext.getDataId(Entity.create<ContactInvite>(p.tContext, invite))
        assertTrue(dataId is DataId.StringDataId)
        assertEquals(ContactInvite.ID, dataId.type.get())
        assertEquals("00112233445566778899aabbccddeeff", dataId.keyValue.get())
    }
}
