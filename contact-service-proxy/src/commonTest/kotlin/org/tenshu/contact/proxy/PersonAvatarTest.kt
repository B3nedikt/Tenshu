package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.Person
import org.tenshu.contact.Avatar
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.clearPersonAvatar
import org.tenshu.contact.createPerson
import org.tenshu.contact.deletePerson
import org.tenshu.contact.importContact
import org.tenshu.contact.renamePerson
import org.tenshu.contact.setPersonAvatar
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.entity.get
import org.tenshu.core.relation.eager
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Avatars through a real client against a real proxy — which is what proves the
 * client-module `roFactory` registrations exist. Without them every call dies as an opaque
 * `error/internal` and nothing else in the suite would notice.
 */
@OptIn(ExperimentalEncodingApi::class)
class PersonAvatarTest {

    private fun serviceFor(harness: ContactTestHarness): ContactService {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        ).getService(ContactService.ID)
    }

    private fun <T : org.tenshu.core.Data> TResult<out T?>.expect(what: String): T {
        if (this is TResult.Failure<*>) error("$what failed: $error")
        return checkNotNull((this as TResult.Success<out T?>).value) { "$what returned null" }
    }

    private fun harness() =
        createContactProxy(FakeInviteRelay(), identityPhrase = "alice phrase")

    /** Avatar rows currently in the store, read straight off the relation. */
    private fun avatars(harness: ContactTestHarness) = PersonAvatarLocalView(harness).rows()

    private fun cardWithPhoto(name: String, photo: ByteArray): String =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\n" +
            "PHOTO;ENCODING=b;TYPE=JPEG:${Base64.encode(photo)}\r\nEND:VCARD\r\n"

    @Test
    fun `a photo is stored and read back byte for byte`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()
        val photo = byteArrayOf(1, 2, 3, 4, 5)

        service.setPersonAvatar(personId, photo).expect("setPersonAvatar")

        val row = avatars(harness).single()
        assertEquals(personId, row.first)
        assertContentEquals(photo, row.second)
    }

    @Test
    fun `setting twice replaces rather than accumulates`() = runTest {
        // The row is keyed on personId, which is what makes two devices converge on one photo
        // instead of the contact growing an album.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()

        service.setPersonAvatar(personId, byteArrayOf(1)).expect("first")
        service.setPersonAvatar(personId, byteArrayOf(2)).expect("second")

        assertContentEquals(byteArrayOf(2), avatars(harness).single().second)
    }

    @Test
    fun `an oversized photo is refused`() = runTest {
        // Refused, not truncated or downscaled: the row syncs eagerly to every device, and
        // re-encoding would need a platform image decoder inside a service module.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()

        val result = service.setPersonAvatar(
            personId,
            ByteArray(PersonAvatar.MAX_IMAGE_BYTES + 1),
        )

        assertTrue(result is TResult.Failure<*>, "an over-limit photo must not be stored")
        assertTrue(avatars(harness).isEmpty())
    }

    @Test
    fun `clearing removes the row and leaves the contact`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()
        service.setPersonAvatar(personId, byteArrayOf(1)).expect("setPersonAvatar")

        service.clearPersonAvatar(personId).expect("clearPersonAvatar")

        assertTrue(avatars(harness).isEmpty())
        assertEquals(1, PersonLocalView(harness).count(), "the contact stays")
    }

    @Test
    fun `clearing a contact who has no photo is not an error`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()

        service.clearPersonAvatar(personId).expect("clearPersonAvatar")

        assertTrue(avatars(harness).isEmpty())
    }

    @Test
    fun `renaming a contact does not lose their photo`() = runTest {
        // ⚠⚠ The reason the avatar is a sibling row at all. RenamePerson is a replace-write:
        // an avatar living on Person would be destroyed here, silently (deletion by omission).
        // If this test ever fails, someone moved the photo onto the person.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()
        service.setPersonAvatar(personId, byteArrayOf(7, 7, 7)).expect("setPersonAvatar")

        service.renamePerson(personId, "Mira Okonkwo").expect("renamePerson")

        assertContentEquals(byteArrayOf(7, 7, 7), avatars(harness).single().second)
    }

    @Test
    fun `deleting a contact sweeps their photo`() = runTest {
        // The photo is part of the Q7a aggregate: left behind, it is stranded in a row nothing
        // renders.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()
        service.setPersonAvatar(personId, byteArrayOf(1)).expect("setPersonAvatar")

        service.deletePerson(personId).expect("deletePerson")

        assertTrue(avatars(harness).isEmpty())
    }

    @Test
    fun `importing a card promotes its embedded photo`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val photo = byteArrayOf(9, 8, 7, 6)

        val personId = service.importContact(cardWithPhoto("Dad", photo), "").expect("import").get()

        val row = avatars(harness).single()
        assertEquals(personId, row.first)
        assertContentEquals(photo, row.second)
    }

    @Test
    fun `importing keeps the photo in the source record too`() = runTest {
        // Promotion is a read. The card is never rewritten, so `rawVcard` stays the lossless
        // record it is meant to be — the avatar is a second copy, not a move.
        val harness = harness()
        val service = serviceFor(harness)
        val card = cardWithPhoto("Dad", byteArrayOf(9, 8, 7, 6))

        service.importContact(card, "").expect("import")

        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = harness.proxy.tContext,
                type = TList.ID,
                relationId = ContactService.Relations.SOURCES,
            ),
            isEntity = false,
            elementSchemaId = ContactSource.ID,
        )
        val stored = harness.proxy.executionContext.database.getArrayData(ref)
            ?.mapNotNull { it as? ContactSource }?.toList().orEmpty()
        assertTrue(
            stored.single().rawVcard.get().contains("PHOTO"),
            "the imported card must keep its PHOTO",
        )
    }

    @Test
    fun `a card with no photo produces no avatar row`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)

        service.importContact("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Dad\r\nEND:VCARD\r\n", "")
            .expect("import")

        assertTrue(avatars(harness).isEmpty())
    }

    @Test
    fun `an oversized embedded photo is skipped rather than imported`() = runTest {
        // The contact still imports; only the photo is left behind, and it survives in the
        // card. Failing the whole import over a large picture would be the wrong trade.
        val harness = harness()
        val service = serviceFor(harness)
        val huge = ByteArray(PersonAvatar.MAX_IMAGE_BYTES + 1024) { 1 }

        val personId = service.importContact(cardWithPhoto("Dad", huge), "").expect("import").get()

        assertNull(PersonAvatarLocalView(harness).bytesOf(personId))
        assertEquals(1, PersonLocalView(harness).count(), "the contact still imports")
    }

    @Test
    fun `the deferred avatar property resolves through the chain`() = runTest {
        // ⚠⚠ A `dynamicProperty` dispatches through the CLIENT module's roFactory
        // registration, not `operation(Schema)` in common. Without `GetPersonAvatarRo`
        // registered, this select dies as an opaque `error/internal`. Nothing else in the
        // suite would notice, because
        // the repository reads the rows directly.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()
        service.setPersonAvatar(personId, byteArrayOf(4, 5, 6)).expect("setPersonAvatar")

        val rows = service.persons
            .select { relations(eager(Person.Avatar)) }
            .getAll()
            .expect("select Person.Avatar")

        // eager(...) forces inline resolution; a plain projection would read null, which is
        // what "deferred" means for a one-shot terminal.
        val bytes = rows.toList().single().get(Person.Avatar)?.get()
        assertContentEquals(byteArrayOf(4, 5, 6), bytes)
    }
}
