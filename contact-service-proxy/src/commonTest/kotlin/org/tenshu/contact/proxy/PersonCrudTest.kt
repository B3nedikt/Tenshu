package org.tenshu.contact.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactService
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.createPerson
import org.tenshu.contact.deletePerson
import org.tenshu.contact.renamePerson
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.contact.unlinkTenshuIdentity
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contact-CRUD ops through a real client against a real proxy
 * (`docs/proposals/contacts-app.md` §6).
 *
 * Driving them over the client rather than calling the implementations directly is
 * deliberate: it is the only thing that proves the **client-module `roFactory`
 * registration** exists. Without it every call dies as an opaque `error/internal` and
 * nothing else in the suite would notice.
 */
class PersonCrudTest {

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

    /** A key back-linked to [personId], written straight in — linking is the handshake's job. */
    private fun seedIdentity(
        harness: ContactTestHarness,
        fingerprint: String,
        personId: String,
    ) {
        harness.proxy.executionContext.saveData(
            TenshuIdentity.create(
                context = harness.proxy.tContext,
                fingerprint = fingerprint,
                publicKey = ByteArray(32) { it.toByte() },
                personId = personId,
                selfAssertedName = "claimed",
                verification = TenshuIdentity.Verification.UNVERIFIED,
                verifiedAt = 0L,
                source = TenshuIdentity.Source.INVITE_LINK,
            )
        )
    }

    @Test
    fun createPerson_writesAThinContactWithNoKey() = runTest {
        val harness = harness()
        val service = serviceFor(harness)

        val personId = service.createPerson("Dad").expect("createPerson").get()

        assertTrue(personId.isNotEmpty(), "the op returns the generated id")
        assertEquals("Dad", PersonLocalView(harness).displayNameOf(personId))
        assertEquals(1, PersonLocalView(harness).count())
    }

    @Test
    fun renamePerson_changesTheNameAndKeepsTheId() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Dad").expect("createPerson").get()

        service.renamePerson(personId, "Papa").expect("renamePerson")

        assertEquals("Papa", PersonLocalView(harness).displayNameOf(personId))
        assertEquals(1, PersonLocalView(harness).count(), "rename must not fork a second row")
    }

    @Test
    fun renamePerson_refusesAnUnknownContact() = runTest {
        val service = serviceFor(harness())

        val result = service.renamePerson("person-gone", "Whoever")

        assertTrue(result is TResult.Failure<*>, "renaming a row that isn't there is an error")
    }

    @Test
    fun deletePerson_takesTheKeysWithIt() = runTest {
        // ⚠ Q7a: delete means delete. Leaving the keyring behind would strand a peer's
        // public key on every device in rows no surface lists.
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Mira").expect("createPerson").get()
        seedIdentity(harness, fingerprint = "aaaa", personId = personId)
        seedIdentity(harness, fingerprint = "bbbb", personId = personId)

        service.deletePerson(personId).expect("deletePerson")

        assertNull(PersonLocalView(harness).displayNameOf(personId))
        assertNull(TenshuIdentityLocalView(harness).byFingerprint("aaaa"))
        assertNull(TenshuIdentityLocalView(harness).byFingerprint("bbbb"))
    }

    @Test
    fun deletePerson_leavesEveryoneElseAlone() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val doomed = service.createPerson("Mira").expect("createPerson").get()
        val keeper = service.createPerson("Jonas").expect("createPerson").get()
        seedIdentity(harness, fingerprint = "aaaa", personId = doomed)
        seedIdentity(harness, fingerprint = "bbbb", personId = keeper)

        service.deletePerson(doomed).expect("deletePerson")

        assertEquals("Jonas", PersonLocalView(harness).displayNameOf(keeper))
        assertNotNull(TenshuIdentityLocalView(harness).byFingerprint("bbbb"))
    }

    @Test
    fun deletePerson_withAnEmptyIdDoesNotSweepUnlinkedKeys() = runTest {
        // ⚠ `personId == ""` means "not yet linked", not "belongs to the empty person".
        // A caller should never pass it, but an upstream bug must not wipe the candidates.
        val harness = harness()
        val service = serviceFor(harness)
        seedIdentity(harness, fingerprint = "candidate", personId = "")

        service.deletePerson("").expect("deletePerson")

        assertNotNull(TenshuIdentityLocalView(harness).byFingerprint("candidate"))
    }

    @Test
    fun unlinkTenshuIdentity_dropsTheKeyAndKeepsTheContact() = runTest {
        val harness = harness()
        val service = serviceFor(harness)
        val personId = service.createPerson("Sara").expect("createPerson").get()
        seedIdentity(harness, fingerprint = "old-key", personId = personId)
        seedIdentity(harness, fingerprint = "new-key", personId = personId)

        service.unlinkTenshuIdentity("old-key").expect("unlinkTenshuIdentity")

        assertNull(TenshuIdentityLocalView(harness).byFingerprint("old-key"))
        assertNotNull(TenshuIdentityLocalView(harness).byFingerprint("new-key"))
        assertEquals("Sara", PersonLocalView(harness).displayNameOf(personId), "the contact stays")
    }
}
