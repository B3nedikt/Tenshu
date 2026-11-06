package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.common.ContactCard
import org.tenshu.contact.common.LabeledValue
import org.tenshu.contact.common.mergeIntoVCard
import org.tenshu.contact.common.newVCard
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.deletePerson
import org.tenshu.contact.importContact
import org.tenshu.contact.saveContactSource
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Source records end to end (`contact-service-identity-model.md` §4.3).
 *
 * The two things worth proving at this level: an import produces a real contact whose detail
 * survives the round trip through the store, and `DeletePerson` sweeps sources as Q7a says.
 */
class ContactSourceTest {

    private fun serviceFor(harness: ContactTestHarness): ContactService {
        val executor = LocalTaskExecutor(harness.proxy.executionContext)
        return TenshuEnvironment(
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

    private fun harness() = createContactProxy(FakeInviteRelay(), identityPhrase = "alice phrase")

    private val imported = """
        BEGIN:VCARD
        VERSION:3.0
        N:Okonkwo;Mira;;;
        FN:Mira Okonkwo
        TEL;TYPE=CELL:+44 7700 900123
        CATEGORIES:Climbing
        X-VENDOR-THING:keep me
        END:VCARD
    """.trimIndent()

    private fun sources(harness: ContactTestHarness): List<ContactSource> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = harness.proxy.tContext,
                type = TList.ID,
                relationId = ContactService.Relations.SOURCES,
            ),
            isEntity = false,
            elementSchemaId = ContactSource.ID,
        )
        return harness.proxy.executionContext.database.getArrayData(ref)
            ?.mapNotNull { it as? ContactSource }
            ?.toList()
            .orEmpty()
    }

    @Test
    fun importingCreatesAContactNamedFromTheRecord() = runTest {
        val harness = harness()
        val service = serviceFor(harness)

        val personId = service.importContact(imported).expect("importContact").get()

        assertEquals("Mira Okonkwo", PersonLocalView(harness).displayNameOf(personId))
        val source = sources(harness).single()
        assertEquals(personId, source.personId.get())
        assertTrue(source.sourceKey.get().startsWith("${ContactSource.Origin.VCARD}:"))
        // The payload is stored verbatim, which is what makes export free and edits safe.
        assertTrue(source.rawVcard.get().contains("X-VENDOR-THING:keep me"))
    }

    @Test
    fun aRecordWithNoNameFallsBackRatherThanGoingBlank() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val nameless = "BEGIN:VCARD\r\nVERSION:3.0\r\nTEL:+44 7700 900123\r\nEND:VCARD\r\n"

        val personId = service.importContact(nameless, fallbackName = "Unnamed card")
            .expect("importContact").get()

        assertEquals("Unnamed card", PersonLocalView(harness).displayNameOf(personId))
    }

    @Test
    fun anEditedRecordKeepsWhatTheEditorNeverSaw() = runTest {
        // ⚠⚠ Q6 through the whole stack: read the stored payload, merge an edit into it,
        // write it back, read it again. The vendor line has to still be there.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.importContact(imported).expect("importContact").get()
        val source = sources(harness).single()

        val card = readContactCard(source.rawVcard.get())
        val edited = mergeIntoVCard(
            source.rawVcard.get(),
            card.copy(phones = card.phones + LabeledValue("home", "+44 20 7946 0958")),
        )
        service.saveContactSource(source.sourceKey.get(), personId, edited)
            .expect("saveContactSource")

        val stored = sources(harness).single().rawVcard.get()
        assertEquals(
            listOf("+44 7700 900123", "+44 20 7946 0958"),
            readContactCard(stored).phones.map { it.value },
        )
        assertTrue(stored.contains("X-VENDOR-THING:keep me"), "the unmodelled line survives")
        assertTrue(stored.contains("CATEGORIES:Climbing"))
    }

    @Test
    fun aHandTypedCardIsJustAnotherSource() = runTest {
        // D5: manual entry is not a third path.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.importContact(newVCard(ContactCard(formattedName = "Dad")))
            .expect("importContact").get()

        assertEquals("Dad", PersonLocalView(harness).displayNameOf(personId))
    }

    @Test
    fun deletingAContactSweepsItsSources() = runTest {
        // ⚠ Q7a: "sources *and* identities" — this pins the sources half.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.importContact(imported).expect("importContact").get()
        assertEquals(1, sources(harness).size)

        service.deletePerson(personId).expect("deletePerson")

        assertNull(PersonLocalView(harness).displayNameOf(personId))
        assertTrue(sources(harness).isEmpty(), "the contact card must not outlive the contact")
    }

    @Test
    fun deletingOneContactLeavesAnothersSourcesAlone() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val doomed = service.importContact(imported).expect("importContact").get()
        service.importContact(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Jonas Weber\r\nEND:VCARD\r\n"
        ).expect("importContact")

        service.deletePerson(doomed).expect("deletePerson")

        val remaining = sources(harness).single()
        assertTrue(remaining.rawVcard.get().contains("Jonas Weber"))
    }
}
