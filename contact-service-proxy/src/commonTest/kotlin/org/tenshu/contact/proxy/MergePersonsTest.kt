package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.createPerson
import org.tenshu.contact.importContact
import org.tenshu.contact.linkTenshuIdentity
import org.tenshu.contact.mergePersons
import org.tenshu.contact.setPersonAvatar
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.DataId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merge (`docs/proposals/contacts-app.md` D7), driven over a real client against a real
 * proxy — which is also what proves the client-module `roFactory` registrations exist.
 *
 * Merge is where a wrong move loses data permanently: there is no un-merge, and the
 * rows that moved carry no memory of where they came from. Most of what follows is therefore
 * about what must **survive** rather than about what merge does.
 */
class MergePersonsTest {

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

    private fun card(name: String) =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\nTEL;TYPE=CELL:+44 7700 900123\r\n" +
            "X-VENDOR-THING:keep me\r\nEND:VCARD\r\n"

    /** Writes a key straight in — linking is normally the handshake's job. */
    private fun seedIdentity(
        harness: ContactTestHarness,
        fingerprint: String,
        personId: String,
        verification: String = TenshuIdentity.Verification.UNVERIFIED,
        verifiedAt: Long = 0L,
        source: String = TenshuIdentity.Source.INVITE_LINK,
    ) {
        harness.proxy.executionContext.saveData(
            TenshuIdentity.create(
                context = harness.proxy.tContext,
                fingerprint = fingerprint,
                publicKey = ByteArray(32) { 1 },
                personId = personId,
                selfAssertedName = "claimed-$fingerprint",
                verification = verification,
                verifiedAt = verifiedAt,
                source = source,
                grantSeed = ByteArray(0),
            )
        )
    }

    private fun identities(harness: ContactTestHarness): List<TenshuIdentity> =
        rows(harness, ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .mapNotNull { it as? TenshuIdentity }

    private fun sources(harness: ContactTestHarness): List<ContactSource> =
        rows(harness, ContactService.Relations.SOURCES, ContactSource.ID)
            .mapNotNull { it as? ContactSource }

    private fun rows(harness: ContactTestHarness, relationId: String, schemaId: String) =
        harness.proxy.executionContext.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = harness.proxy.tContext,
                    type = TList.ID,
                    relationId = relationId,
                ),
                isEntity = false,
                elementSchemaId = schemaId,
            )
        )?.toList().orEmpty()

    @Test
    fun `merging moves the keys and deletes the source contact`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira Okonkwo").expect("target").get()
        val fold = service.createPerson("Mira").expect("source").get()
        seedIdentity(harness, "aaa", fold)

        service.mergePersons(sourcePersonId = fold, targetPersonId = keep).expect("merge")

        assertEquals(keep, identities(harness).single().personId.get())
        assertEquals(1, PersonLocalView(harness).count())
        assertEquals("Mira Okonkwo", PersonLocalView(harness).displayNameOf(keep))
    }

    @Test
    fun `the target keeps its own petname`() = runTest {
        // The user picked the target, so they picked the name. Anything else would need a
        // second question at merge time to answer a decision already made.
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mum").expect("target").get()
        val fold = service.createPerson("Margaret Okonkwo").expect("source").get()

        service.mergePersons(fold, keep).expect("merge")

        assertEquals("Mum", PersonLocalView(harness).displayNameOf(keep))
    }

    @Test
    fun `a moved key keeps its verification and its timestamp and its source`() = runTest {
        // ⚠⚠ Verification is a fact about the KEY, not about the contact holding it.
        // Re-stamping any of these on a merge would fabricate history — and silently
        // upgrading a key's provenance is how a checkmark ends up on something nobody
        // compared.
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira").expect("target").get()
        val fold = service.createPerson("Mira O").expect("source").get()
        seedIdentity(
            harness,
            fingerprint = "bbb",
            personId = fold,
            verification = TenshuIdentity.Verification.VERIFIED,
            verifiedAt = 1_753_000_000_000,
            source = TenshuIdentity.Source.QR_SAS,
        )

        service.mergePersons(fold, keep).expect("merge")

        val moved = identities(harness).single()
        assertEquals(keep, moved.personId.get())
        assertEquals(TenshuIdentity.Verification.VERIFIED, moved.verification.get())
        assertEquals(1_753_000_000_000, moved.verifiedAt.get())
        assertEquals(TenshuIdentity.Source.QR_SAS, moved.source.get())
        assertEquals("claimed-bbb", moved.selfAssertedName.get(), "the claim stays a claim")
    }

    @Test
    fun `merging unions the keyrings rather than choosing between them`() = runTest {
        // The merged contact then reads as "key changed" under Q2b — a verified key beside
        // an unverified one — which is the right answer for someone who just acquired an
        // unfamiliar key, and is why merge needs no trust logic of its own.
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira").expect("target").get()
        val fold = service.createPerson("Mira O").expect("source").get()
        seedIdentity(harness, "verified", keep, TenshuIdentity.Verification.VERIFIED, 1L)
        seedIdentity(harness, "unverified", fold)

        service.mergePersons(fold, keep).expect("merge")

        val keyring = identities(harness)
        assertEquals(2, keyring.size)
        assertTrue(keyring.all { it.personId.get() == keep })
    }

    @Test
    fun `both contacts keep every source record byte for byte`() = runTest {
        // ⚠ A person legitimately has several records and the detail screen renders their
        // union. Combining the payloads here would be the regenerate-don't-merge
        // mistake Q6 forbids — and `X-VENDOR-THING` is the canary for it.
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.importContact(card("Mira Okonkwo"), "").expect("target").get()
        val fold = service.importContact(card("Mira"), "").expect("source").get()

        service.mergePersons(fold, keep).expect("merge")

        val records = sources(harness)
        assertEquals(2, records.size, "neither record is discarded")
        assertTrue(records.all { it.personId.get() == keep })
        assertTrue(records.all { it.rawVcard.get().contains("X-VENDOR-THING:keep me") })
    }

    @Test
    fun `the target's photo survives a merge`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira").expect("target").get()
        val fold = service.createPerson("Mira O").expect("source").get()
        service.setPersonAvatar(keep, byteArrayOf(1, 1, 1)).expect("target photo")
        service.setPersonAvatar(fold, byteArrayOf(2, 2, 2)).expect("source photo")

        service.mergePersons(fold, keep).expect("merge")

        val avatars = PersonAvatarLocalView(harness).rows()
        assertEquals(1, avatars.size, "the folded contact's photo row is swept")
        assertContentEquals(byteArrayOf(1, 1, 1), avatars.single().second)
    }

    @Test
    fun `a contact with no photo inherits the other's`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira").expect("target").get()
        val fold = service.createPerson("Mira O").expect("source").get()
        service.setPersonAvatar(fold, byteArrayOf(2, 2, 2)).expect("source photo")

        service.mergePersons(fold, keep).expect("merge")

        assertContentEquals(byteArrayOf(2, 2, 2), PersonAvatarLocalView(harness).bytesOf(keep))
    }

    @Test
    fun `merging a contact into itself is refused`() = runTest {
        // It would delete the person it had just moved everything onto.
        val harness = harness()
        val service = serviceFor(harness)
        val person = service.createPerson("Mira").expect("createPerson").get()
        seedIdentity(harness, "aaa", person)

        val result = service.mergePersons(person, person)

        assertTrue(result is TResult.Failure<*>)
        assertEquals(1, PersonLocalView(harness).count())
        assertEquals(person, identities(harness).single().personId.get())
    }

    @Test
    fun `merging into the empty person is refused`() = runTest {
        // ⚠ `personId == ""` is the not-yet-linked marker. A merge involving it would sweep
        // every unlinked key in the realm onto one contact.
        val harness = harness()
        val service = serviceFor(harness)
        val person = service.createPerson("Mira").expect("createPerson").get()
        seedIdentity(harness, "unlinked", personId = "")

        assertTrue(service.mergePersons(person, "") is TResult.Failure<*>)
        assertTrue(service.mergePersons("", person) is TResult.Failure<*>)
        assertEquals("", identities(harness).single().personId.get(), "the candidate is untouched")
    }

    @Test
    fun `merging into a contact that does not exist is refused`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val person = service.createPerson("Mira").expect("createPerson").get()

        val result = service.mergePersons(person, "no-such-person")

        assertTrue(result is TResult.Failure<*>)
        assertEquals(1, PersonLocalView(harness).count(), "nothing was deleted")
    }

    @Test
    fun `re-pointing one key moves only that key`() = runTest {
        // The mis-link remedy: the completion prompt offers "link to an existing contact",
        // and picking the wrong one otherwise has no fix short of unlinking and re-doing the
        // whole handshake.
        val harness = harness()
        val service = serviceFor(harness)
        val wrong = service.createPerson("Jonas").expect("wrong").get()
        val right = service.createPerson("Mira").expect("right").get()
        seedIdentity(harness, "moved", wrong)
        seedIdentity(harness, "stays", wrong)

        service.linkTenshuIdentity("moved", right).expect("link")

        assertEquals(right, identities(harness).single { it.fingerprint.get() == "moved" }.personId.get())
        assertEquals(wrong, identities(harness).single { it.fingerprint.get() == "stays" }.personId.get())
        assertEquals(2, PersonLocalView(harness).count(), "both contacts survive")
    }

    @Test
    fun `re-pointing an unknown key changes nothing`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val person = service.createPerson("Mira").expect("createPerson").get()

        val moved = service.linkTenshuIdentity("no-such-key", person).expect("link")

        assertEquals(false, moved.get())
        assertTrue(identities(harness).isEmpty())
    }

    @Test
    fun `re-pointing preserves the grant seed`() = runTest {
        // ⚠ `grantSeed` is an OPTIONAL field, so a replace-write that
        // reads it with the plain accessor throws — and one that forgets it silently makes a
        // re-pointed contact un-grantable.
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira").expect("target").get()
        val fold = service.createPerson("Mira O").expect("source").get()
        harness.proxy.executionContext.saveData(
            TenshuIdentity.create(
                context = harness.proxy.tContext,
                fingerprint = "seeded",
                publicKey = ByteArray(32) { 1 },
                personId = fold,
                selfAssertedName = "Mira",
                verification = TenshuIdentity.Verification.UNVERIFIED,
                verifiedAt = 0L,
                source = TenshuIdentity.Source.INVITE_LINK,
                grantSeed = byteArrayOf(9, 9, 9),
            )
        )

        service.mergePersons(fold, keep).expect("merge")

        val moved = identities(harness).single()
        // ⚠ Read optionally here too — `moved.grantSeed` throws on the very rows this test
        // exists to protect. The trap caught this assertion before it caught anything else.
        val seed = moved.getOptionalField<TByteArray>(TenshuIdentity.Field.GRANT_SEED)?.get()
        assertContentEquals(byteArrayOf(9, 9, 9), seed)
    }

    @Test
    fun `merging a contact with nothing on it just removes it`() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val keep = service.createPerson("Mira").expect("target").get()
        val fold = service.createPerson("Mira duplicate").expect("source").get()

        service.mergePersons(fold, keep).expect("merge")

        assertEquals(1, PersonLocalView(harness).count())
        assertNull(PersonLocalView(harness).displayNameOf(fold))
    }
}
