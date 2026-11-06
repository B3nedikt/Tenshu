package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.ContentId
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.Photos
import org.tenshu.contact.client.FullCardRead
import org.tenshu.contact.client.fullVcard
import org.tenshu.contact.common.ContactPhotoContent
import org.tenshu.contact.common.mergeIntoVCard
import org.tenshu.contact.common.photoRefsIn
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.deletePerson
import org.tenshu.contact.importContact
import org.tenshu.contact.mergePersons
import org.tenshu.contact.createPerson
import org.tenshu.contact.saveContactSource
import org.tenshu.contact.proxy.impl.avatarOf
import org.tenshu.contact.test.ContactPhotoFixtures
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.content.contentEdgeOf
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A card's big `PHOTO` on the content plane, through the real ops (`contacts-app.md` §9.10).
 * Every name in the fixtures is made up.
 */
class ContactPhotoContentPlaneTest {

    /** Records the encoded size of every row it is handed, by type — the iOS store's `ROW_MAX` question. */
    private class SizingStore : InMemoryObjectStore() {
        val largest = mutableMapOf<String, Int>()
        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            val size = dbEntry.fields.sumOf(::sizeOf) + dbEntry.properties.entries.sumOf { it.key.length + sizeOf(it.value) }
            largest[dbEntry.type] = maxOf(largest[dbEntry.type] ?: 0, size)
            super.storeObject(dbEntry)
        }

        private fun sizeOf(value: ObjectStore.DbValue): Int = when (value) {
            is ObjectStore.DbValue.StringValue -> value.value.encodeToByteArray().size + 4
            is ObjectStore.DbValue.ByteArrayValue -> value.value.size + 4
            is ObjectStore.DbValue.GenericValue -> value.typeId.length + sizeOf(value.payload)
            else -> 9
        }
    }

    private class RecordingTombstones : TombstoneEmitter {
        val emitted = mutableListOf<Pair<String, String>>()
        override fun emit(targetEntityIdHash: String, targetEntityType: String, revokedDeviceId: String?): String? {
            emitted += targetEntityType to targetEntityIdHash
            return "block-${emitted.size}"
        }

        override fun emit(targetEntityId: DataId, targetEntityType: String, revokedDeviceId: String?): String? {
            emitted += targetEntityType to ((targetEntityId as? DataId.StringDataId)?.keyValue?.toString() ?: "?")
            return "block-${emitted.size}"
        }
    }

    private val store = SizingStore()
    private val tombstones = RecordingTombstones()
    private val harness = createContactProxy(
        FakeInviteRelay(),
        identityPhrase = "made-up phrase",
        objectStore = store,
        tombstoneEmitterHolder = TombstoneEmitterHolder().also { it.emitter = tombstones },
    )

    private val service: ContactService = run {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = harness.proxy.schemaRegistry,
            requestChainFactory = { ctx -> LocalRequestChain(executor, ctx) },
            tContext = harness.proxy.tContext,
        ).getService(ContactService.ID)
    }

    private fun <T : Data> TResult<out T?>.expect(what: String): T {
        if (this is TResult.Failure<*>) error("$what failed: $error")
        return checkNotNull((this as TResult.Success<out T?>).value) { "$what returned null" }
    }

    private val ctx get() = harness.proxy.executionContext
    private val files get() = harness.components.fileDatabase

    private fun sourceRows(): List<ContactSource> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = harness.proxy.tContext,
                type = TList.ID,
                relationId = ContactService.Relations.SOURCES,
            ),
            isEntity = false,
            elementSchemaId = ContactSource.ID,
        )
        return ctx.database.getArrayData(ref)?.mapNotNull { it as? ContactSource }?.toList().orEmpty()
    }

    private fun storedEntity(sourceKey: String): Entity<*> =
        ctx.database.getData(contactSourceDataId(ctx, sourceKey), asEntity = true) as Entity<*>

    private fun contentIdOf(sourceKey: String): String? = storedEntity(sourceKey).get(ContactSource.ContentId)?.get()?.takeIf { it.isNotBlank() }

    private val rowMax = 512 * 1024

    private val bigImage = ContactPhotoFixtures.image(2 * 1024 * 1024)
    private val bigCard = ContactPhotoFixtures.card(ContactPhotoFixtures.photoLine(bigImage))

    @Test
    fun `a big photo is stored on the content plane and the row stays under ROW_MAX`() = runTest {
        assertTrue(bigCard.encodeToByteArray().size > rowMax)

        service.importContact(bigCard).expect("importContact")

        val row = sourceRows().single()
        val contentId = assertNotNull(contentIdOf(row.sourceKey.get()), "the row names its photos")
        assertEquals(1, photoRefsIn(row.rawVcard.get()).size)
        assertNotNull(files.readBytes(contactPhotosPath(contentId)), "the photos are in the byte store")
        val largest = assertNotNull(store.largest[ContactSource.ID])
        assertTrue(largest < 16 * 1024, "a ContactSource row of $largest bytes")
    }

    @Test
    fun `a small photo stays in the card and names no content`() = runTest {
        val small = ContactPhotoFixtures.card(ContactPhotoFixtures.photoLine(ContactPhotoFixtures.image(12 * 1024)))

        service.importContact(small).expect("importContact")

        val row = sourceRows().single()
        assertEquals(small, row.rawVcard.get())
        assertNull(contentIdOf(row.sourceKey.get()))
    }

    @Test
    fun `export reassembles the imported card byte for byte`() = runTest {
        service.importContact(bigCard).expect("importContact")
        val row = sourceRows().single()

        val full = service.fullVcard(row.sourceKey.get(), row.rawVcard.get())

        assertEquals(FullCardRead.Ok(bigCard), full)
    }

    @Test
    fun `export with the photo bytes missing fails visibly`() = runTest {
        service.importContact(bigCard).expect("importContact")
        val row = sourceRows().single()
        files.deleteFile(contactPhotosPath(contentIdOf(row.sourceKey.get())!!))

        val full = service.fullVcard(row.sourceKey.get(), row.rawVcard.get())

        assertEquals(FullCardRead.PhotosUnavailable, full, "never the stored card in the photo's place")
    }

    @Test
    fun `avatar promotion still reads the photo from the card as imported`() = runTest {
        // Over the relocation threshold, under the avatar ceiling: moves AND is promoted.
        val image = ContactPhotoFixtures.image(120 * 1024)
        val card = ContactPhotoFixtures.card(ContactPhotoFixtures.photoLine(image))

        val personId = service.importContact(card).expect("importContact").get()

        assertNotNull(contentIdOf(sourceRows().single().sourceKey.get()), "the photo moved")
        assertContentEquals(image, avatarOf(ctx, personId))
    }

    @Test
    fun `an offline edit keeps the photos and re-uploads nothing`() = runTest {
        val personId = service.importContact(bigCard).expect("importContact").get()
        val row = sourceRows().single()
        val contentId = contentIdOf(row.sourceKey.get())!!
        // The photos are not on this device (another device imported the card).
        val blob = files.readBytes(contactPhotosPath(contentId))!!
        files.deleteFile(contactPhotosPath(contentId))

        val edited = mergeIntoVCard(row.rawVcard.get(), readContactCard(row.rawVcard.get()).copy(note = "Prefers email"))
        service.saveContactSource(row.sourceKey.get(), personId, edited).expect("saveContactSource")

        assertEquals(contentId, contentIdOf(row.sourceKey.get()), "same photos, same content id")
        files.writeByteArray(contactPhotosPath(contentId), blob)
        val full = assertIs<FullCardRead.Ok>(service.fullVcard(row.sourceKey.get(), sourceRows().single().rawVcard.get()))
        assertEquals("Prefers email", readContactCard(full.card).note)
        assertContentEquals(bigImage, org.tenshu.contact.common.readContactPhoto(full.card, 4 * 1024 * 1024))
    }

    @Test
    fun `a card naming photos the contact does not hold is refused`() = runTest {
        val personId = service.createPerson("Teodor Lindqvist").expect("createPerson").get()
        val forged = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Teodor\r\n" +
            "${ContactPhotoContent.REF_PROPERTY};SHA-256=00:0\r\nEND:VCARD\r\n"

        val result = service.saveContactSource("manual:$personId", personId, forged)

        assertIs<TResult.Failure<*>>(result)
        assertTrue(sourceRows().isEmpty())
    }

    @Test
    fun `deleting the contact tombstones its source and drops the photo bytes`() = runTest {
        val personId = service.importContact(bigCard).expect("importContact").get()
        val sourceKey = sourceRows().single().sourceKey.get()
        val contentId = contentIdOf(sourceKey)!!

        service.deletePerson(personId).expect("deletePerson")

        // ⚠ Not deleted at the delete — a candidate, gone at the sweep.
        assertNotNull(files.readBytes(contactPhotosPath(contentId)), "kept until the sweep")
        assertEquals(1, sweepSupersededContactPhotos(ctx, harness.components))
        assertNull(files.readBytes(contactPhotosPath(contentId)), "the local photo bytes, at the sweep")
        assertTrue(ContactSource.ID to sourceKey in tombstones.emitted, "the source's tombstone retires its edge: ${tombstones.emitted}")
        assertTrue(Person.ID to personId in tombstones.emitted)
        assertTrue(PersonAvatar.ID !in tombstones.emitted.map { it.first }, "a 2 MiB photo is never an avatar")
    }

    @Test
    fun `the row declares its photos as a content edge - and a photo-less save declares none`() = runTest {
        val personId = service.importContact(bigCard).expect("importContact").get()
        val sourceKey = sourceRows().single().sourceKey.get()
        val contentId = contentIdOf(sourceKey)!!

        val edge = assertNotNull(contentEdgeOf(harness.proxy.schemaRegistry, storedEntity(sourceKey), ContactSource.Photos.id))
        assertEquals(contentId, edge.contentId)

        // The user's card loses its photo (another app rewrote it): the edge goes to none, the
        // relay frees the content after its grace, and the local bytes go at the next sweep.
        service.saveContactSource(sourceKey, personId, ContactPhotoFixtures.card()).expect("saveContactSource")
        assertNull(contentEdgeOf(harness.proxy.schemaRegistry, storedEntity(sourceKey), ContactSource.Photos.id)?.contentId)
        assertNotNull(files.readBytes(contactPhotosPath(contentId)), "superseded bytes wait for the sweep")
        sweepSupersededContactPhotos(ctx, harness.components)
        assertNull(files.readBytes(contactPhotosPath(contentId)))
    }

    @Test
    fun `a merge carries the content id with the record it moves`() = runTest {
        val from = service.importContact(bigCard).expect("importContact").get()
        val into = service.createPerson("Ilse V.").expect("createPerson").get()
        val sourceKey = sourceRows().single().sourceKey.get()
        val contentId = contentIdOf(sourceKey)!!

        service.mergePersons(from, into).expect("mergePersons")

        assertEquals(into, sourceRows().single().personId.get())
        assertEquals(contentId, contentIdOf(sourceKey), "a bare re-save would have freed the photos")
    }
}
