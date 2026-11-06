package app.tenshu.contacts.data

import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import app.tenshu.contacts.model.TrustState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.contact.ContactInvite
import org.tenshu.contact.ContactSource
import org.tenshu.contact.Person
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.test.FakeInviteRelay
import org.tenshu.contact.test.createContactProxy
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.request.LocalRequestChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Ph2 runtime exercise: stand `:contact-service` up in an in-process proxy with a real
 * `TenshuClient`, write `Person` / `TenshuIdentity` rows, and drive
 * [TenshuContactsRepository] end to end — proving the whole pipe (store → relation
 * maintainer → chain-DSL client read → join → Contacts UI model).
 *
 * This is the cheapest guard against a **missed registration**: the composites, their
 * `type(...)` registrations in the client module and the service relations all have to be
 * present for one read to survive the round trip, and each fails silently or as an opaque
 * `error/internal` on its own (the `Workout.Streams` / TS-4 class).
 *
 * No implementation factory is wired: this phase reads relations and calls no ops, which is
 * exactly the surface §2 says already ships.
 */
class ContactsPipelineIntegrationTest {

    @Test
    fun personsAndIdentitiesJoinThroughTheRealServiceRelations() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        seedPerson(proxy, "person-mira", "Mira Okonkwo")
        seedPerson(proxy, "person-dad", "Dad")
        seedIdentity(
            proxy,
            fingerprint = "3ab19c7f2e08d641",
            personId = "person-mira",
            verification = TenshuIdentity.Verification.VERIFIED,
            verifiedAt = 1_753_000_000_000,
            source = TenshuIdentity.Source.QR_SAS,
        )

        val contacts = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */).contacts().first()

        assertEquals(listOf("Dad", "Mira Okonkwo"), contacts.map { it.displayName })

        val mira = contacts.single { it.personId == "person-mira" }
        assertEquals(TrustState.Verified, mira.trust)
        assertEquals("3ab19c7f2e08d641", mira.identities.single().fingerprint)
        // The self-asserted name survives the round trip as a claim on the key.
        assertEquals("Mira", mira.identities.single().selfAssertedName)
        assertEquals(1_753_000_000_000, mira.identities.single().verifiedAtMillis)

        // D5: the keyless contact is a contact, and the join does not invent a key for it.
        assertEquals(TrustState.NotConnected, contacts.single { it.personId == "person-dad" }.trust)
    }

    @Test
    fun aRotatedKeyDemotesTheBadgeThroughTheRealReadPath() = runTest {
        // ⚠ Q2b end to end. `TenshuIdentity` is keyed by fingerprint, so the new key lands
        // as a SECOND row rather than replacing the first — this asserts that is really what
        // the store does, not just what the pure join assumes.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        seedPerson(proxy, "person-sara", "Sara Lindqvist")
        seedIdentity(
            proxy,
            fingerprint = "7e5f0c39a1bb24d8",
            personId = "person-sara",
            verification = TenshuIdentity.Verification.VERIFIED,
            verifiedAt = 1_740_000_000_000,
            source = TenshuIdentity.Source.QR_SAS,
        )
        seedIdentity(
            proxy,
            fingerprint = "20a7cc61d4e93b05",
            personId = "person-sara",
            verification = TenshuIdentity.Verification.UNVERIFIED,
            verifiedAt = 0,
            source = TenshuIdentity.Source.INVITE_LINK,
        )

        val sara = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */).contacts().first().single()

        assertEquals(2, sara.identities.size, "a rotated key is a new row, not a mutation")
        assertEquals(TrustState.NeedsReverification, sara.trust)
    }

    @Test
    fun anUnlinkedIdentityDoesNotBecomeAContact() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        seedPerson(proxy, "person-mira", "Mira Okonkwo")
        // A redeemed-but-not-yet-completed handshake leaves a key with no `personId`.
        seedIdentity(
            proxy,
            fingerprint = "c14d802fbb6e3971",
            personId = "",
            verification = TenshuIdentity.Verification.UNVERIFIED,
            verifiedAt = 0,
            source = TenshuIdentity.Source.INVITE_LINK,
        )

        val contacts = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */).contacts().first()

        assertEquals(1, contacts.size)
        assertTrue(contacts.single().identities.isEmpty())
    }

    @Test
    fun oneContactResolvesByPersonId() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        seedPerson(proxy, "person-mira", "Mira Okonkwo")

        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)

        assertEquals("Mira Okonkwo", repository.contact("person-mira").first()?.displayName)
        assertEquals(null, repository.contact("person-gone").first())
    }

    @Test
    fun onlyBareInvitesReachTheContactsApp() = runTest {
        // ⚠ The partition (`contacts-app.md` §9.1). Drive owns SHARE_FOLDER invites end to
        // end because its completion prompt chains into the GR-4 grant offer; Contacts owns
        // the bare ones. Getting this filter wrong puts the same invitation in front of the
        // user in two apps — and hands Contacts one it cannot correctly complete.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)

        seedInvite(proxy, "invite-bare-pending", ContactInvite.Status.PENDING, intentKind = "")
        seedInvite(proxy, "invite-bare-redeemed", ContactInvite.Status.REDEEMED, intentKind = "")
        seedInvite(
            proxy,
            "invite-folder",
            ContactInvite.Status.REDEEMED,
            intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
        )
        // A completed one is nobody's business any more.
        seedInvite(proxy, "invite-done", ContactInvite.Status.COMPLETED, intentKind = "")

        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)

        assertEquals(
            listOf("invite-bare-pending"),
            repository.outstandingInvites().first().map { it.inviteId },
        )
        val confirmations = repository.pendingConfirmations().first()
        assertEquals(listOf("invite-bare-redeemed"), confirmations.map { it.inviteId })
        // The claim survives the round trip — it is what the prompt renders, quoted.
        assertEquals("Bob", confirmations.single().claimedName)
        assertEquals("418902", confirmations.single().claimedSas)
    }

    @Test
    fun theCrudOpsRoundTripThroughTheRepository() = runTest {
        // The Ph4 ops driven the way the app drives them. This is the guard against a
        // missing client-module `roFactory` registration: without it every call dies as an
        // opaque `error/internal` and nothing else here would notice.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)

        val personId = when (val created = repository.createContact("Dad")) {
            is RepositoryResult.Success -> created.value
            is RepositoryResult.Failure -> error("createContact failed: ${created.copyKey}")
        }
        assertEquals("Dad", repository.contact(personId).first()?.displayName)

        repository.renameContact(personId, "Papa")
        assertEquals("Papa", repository.contact(personId).first()?.displayName)

        repository.deleteContact(personId)
        assertNull(repository.contact(personId).first())
    }

    @Test
    fun deletingAContactTakesItsKeysWithIt() = runTest {
        // ⚠ Q7a, proven against the store rather than the mock: the keyring must not
        // survive the contact.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        seedPerson(proxy, "person-mira", "Mira Okonkwo")
        seedIdentity(
            proxy,
            fingerprint = "3ab19c7f2e08d641",
            personId = "person-mira",
            verification = TenshuIdentity.Verification.VERIFIED,
            verifiedAt = 1_753_000_000_000,
            source = TenshuIdentity.Source.QR_SAS,
        )
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)

        repository.deleteContact("person-mira")

        assertTrue(repository.contacts().first().isEmpty())
        // And the key is really gone, not merely unreferenced: re-creating a contact with
        // the same name must not resurrect a keyring.
        val fresh = when (val created = repository.createContact("Mira Okonkwo")) {
            is RepositoryResult.Success -> created.value
            is RepositoryResult.Failure -> error("createContact failed: ${created.copyKey}")
        }
        assertTrue(repository.contact(fresh).first()?.identities.orEmpty().isEmpty())
    }

    @Test
    fun unlinkingAKeyKeepsTheContact() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        seedPerson(proxy, "person-sara", "Sara Lindqvist")
        seedIdentity(
            proxy,
            fingerprint = "old-key",
            personId = "person-sara",
            verification = TenshuIdentity.Verification.VERIFIED,
            verifiedAt = 1_740_000_000_000,
            source = TenshuIdentity.Source.QR_SAS,
        )
        seedIdentity(
            proxy,
            fingerprint = "new-key",
            personId = "person-sara",
            verification = TenshuIdentity.Verification.UNVERIFIED,
            verifiedAt = 0,
            source = TenshuIdentity.Source.INVITE_LINK,
        )
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)
        assertEquals(TrustState.NeedsReverification, repository.contacts().first().single().trust)

        repository.unlinkIdentity("old-key")

        val sara = repository.contacts().first().single()
        assertEquals(listOf("new-key"), sara.identities.map { it.fingerprint })
        assertEquals(TrustState.Unverified, sara.trust, "the key-changed demotion resolves")
    }

    @Test
    fun sourceRecordsSurfaceAsReadThroughDetail() = runTest {
        // Ph6: two records for one person — the detail screen shows the whole person, not
        // one record at a time.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)

        val personId = when (val r = repository.createContact("Mira Okonkwo")) {
            is RepositoryResult.Success -> r.value
            is RepositoryResult.Failure -> error("createContact: ${r.copyKey}")
        }
        seedSource(proxy, "vcard:1", personId, VCARD_ONE)
        seedSource(proxy, "vcard:2", personId, VCARD_TWO)

        val details = repository.contact(personId).first()?.details ?: error("no contact")
        assertEquals(
            listOf("+44 7700 900123", "+44 20 7946 0958"),
            details.phones.map { it.value },
            "the union spans both records, in source order",
        )
        assertEquals("Tenshu", details.organisation)
    }

    @Test
    fun theUnionDeduplicatesAValueTwoRecordsShare() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)
        val personId = when (val r = repository.createContact("Mira")) {
            is RepositoryResult.Success -> r.value
            is RepositoryResult.Failure -> error("createContact: ${r.copyKey}")
        }
        seedSource(proxy, "vcard:1", personId, VCARD_ONE)
        seedSource(proxy, "vcard:dup", personId, VCARD_ONE)

        assertEquals(1, repository.contact(personId).first()?.details?.phones?.size)
    }

    @Test
    fun editingDetailsMergesRatherThanRegenerates() = runTest {
        // ⚠⚠ Q6 through the app's own seam — the path a user actually takes.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)
        val personId = when (val r = repository.createContact("Mira")) {
            is RepositoryResult.Success -> r.value
            is RepositoryResult.Failure -> error("createContact: ${r.copyKey}")
        }
        seedSource(proxy, "vcard:1", personId, VCARD_ONE)

        val before = repository.contact(personId).first()?.details ?: error("no contact")
        repository.saveContactDetails(personId, before.copy(note = "Moved to Lisbon."))

        val after = repository.contact(personId).first()?.details ?: error("no contact")
        assertEquals("Moved to Lisbon.", after.note)
        assertEquals(listOf("+44 7700 900123"), after.phones.map { it.value })
        // And the property the editor never modelled is still in the stored payload.
        val exported = when (val r = repository.exportVcard(personId)) {
            is RepositoryResult.Success -> r.value
            is RepositoryResult.Failure -> error("exportVcard: ${r.copyKey}")
        }
        assertTrue(exported.contains("X-VENDOR-THING:keep me"))
    }

    @Test
    fun aContactWithNoRecordGetsAManualOne() = runTest {
        // D5: editing a hand-typed contact has to create the record it never had.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)
        val personId = when (val r = repository.createContact("Dad")) {
            is RepositoryResult.Success -> r.value
            is RepositoryResult.Failure -> error("createContact: ${r.copyKey}")
        }

        repository.saveContactDetails(
            personId,
            ContactDetails(phones = listOf(ContactField("mobile", "+44 7700 900321"))),
        )

        assertEquals(
            listOf("+44 7700 900321"),
            repository.contact(personId).first()?.details?.phones?.map { it.value },
        )
    }

    @Test
    fun importingAMultiCardFileMakesOneContactEach() = runTest {
        // ⚠ D7: no auto-merge. Two cards are two contacts, even if they are the same person —
        // recognising that is a user-assisted act, not an import-time guess.
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repository = TenshuContactsRepository(client, reachability = null /* test: no reachability engine */)

        val count = when (val r = repository.importVcards(VCARD_ONE + "\n" + VCARD_TWO)) {
            is RepositoryResult.Success -> r.value
            is RepositoryResult.Failure -> error("importVcards: ${r.copyKey}")
        }

        assertEquals(2, count)
        assertEquals(2, repository.contacts().first().size)
    }

    // ── seeding ──────────────────────────────────────────────────────────────
    //
    // Written straight into the store rather than through an op: every mutating op that is
    // not part of the handshake is Ph4, and driving the handshake here would need a relay.
    // The relation maintainer is what turns these writes into the readable relations, which
    // is the part under test.

    private fun seedPerson(proxy: TestProxy, personId: String, displayName: String) {
        proxy.executionContext.saveData(
            Person.create(
                context = proxy.tContext,
                personId = personId,
                displayName = displayName,
            )
        )
    }

    private fun seedInvite(
        proxy: TestProxy,
        inviteId: String,
        status: String,
        intentKind: String,
    ) {
        proxy.executionContext.saveData(
            ContactInvite.create(
                context = proxy.tContext,
                inviteId = inviteId,
                secretHash = "hash-$inviteId",
                status = status,
                createdAt = 1_000,
                expiresAt = 2_000,
                intentKind = intentKind,
                intentLabel = if (intentKind.isEmpty()) "" else "Holiday",
                claimedName = "Bob",
                claimedFingerprint = "c14d802fbb6e3971",
                claimedSas = "418902",
            )
        )
    }

    private fun seedSource(
        proxy: TestProxy,
        sourceKey: String,
        personId: String,
        rawVcard: String,
    ) {
        proxy.executionContext.saveData(
            ContactSource.create(
                context = proxy.tContext,
                sourceKey = sourceKey,
                personId = personId,
                rawVcard = rawVcard,
            )
        )
    }

    private fun seedIdentity(
        proxy: TestProxy,
        fingerprint: String,
        personId: String,
        verification: String,
        verifiedAt: Long,
        source: String,
    ) {
        proxy.executionContext.saveData(
            TenshuIdentity.create(
                context = proxy.tContext,
                fingerprint = fingerprint,
                publicKey = ByteArray(32) { it.toByte() },
                personId = personId,
                selfAssertedName = "Mira",
                verification = verification,
                verifiedAt = verifiedAt,
                source = source,
            )
        )
    }

    // ── one in-process proxy hosting :contact-service ────────────────────────

    /**
     * ⚠ Wired through `createContactProxy` rather than a bare [TestProxy] so the **operation
     * implementation factory** is installed. Ph2 read relations only and a bare proxy
     * sufficed; the moment Ph4's ops arrived, a bare proxy answered every call with
     * `No implementation found for operation: …` — which is exactly the failure this test
     * exists to catch, so it is worth stating why the harness looks heavier than it needs to.
     *
     * The relay and identity phrase are inert here: the CRUD ops are local writes that touch
     * neither.
     */
    private fun stackProxy(scope: CoroutineScope): TestProxy =
        createContactProxy(
            relay = FakeInviteRelay(),
            identityPhrase = "contacts app test",
            coroutineScope = scope,
        ).proxy

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }

    private companion object {
        val VCARD_ONE = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Mira Okonkwo
            ORG:Tenshu
            TEL;TYPE=CELL:+44 7700 900123
            X-VENDOR-THING:keep me
            END:VCARD
        """.trimIndent()

        val VCARD_TWO = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Mira Okonkwo
            TEL;TYPE=HOME:+44 20 7946 0958
            END:VCARD
        """.trimIndent()
    }
}
