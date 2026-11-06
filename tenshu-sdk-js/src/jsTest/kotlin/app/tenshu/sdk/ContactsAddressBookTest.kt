@file:OptIn(DelicateCoroutinesApi::class)

package app.tenshu.sdk

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boundary-contract tests (`docs/testing.md` §9.3) for the address-book half of `Contacts` —
 * web-app-suite.md Wave 1, run A — against the **real** in-process runtime.
 *
 * ⚠ The jsTest suite shares one `JsRuntime` across classes and sibling suites clear storage
 * between tests, so every test drives its own state in-body and names it uniquely (a timestamp):
 * nothing here may rely on a row another test, or a previous run, left behind.
 *
 * They assert the keys `web/sdk/src/contact.ts` publishes and the values the native
 * `TenshuContactsRepository` would produce — not "returns something".
 */
class ContactsAddressBookTest : SdkRuntimeTest() {

    private fun stamp() = "${Date.now().toLong()}-${(0..99999).random()}"

    private suspend fun contactsNow(): Array<dynamic> {
        val result = Contacts.contacts().await()
        assertEquals(true, result.ok, "contacts() must succeed; got ${JSON.stringify(result)}")
        return result.contacts.unsafeCast<Array<dynamic>>()
    }

    private suspend fun contactNow(personId: String): dynamic {
        val result = Contacts.contact(personId).await()
        assertEquals(true, result.ok, "contact() must succeed; got ${JSON.stringify(result)}")
        return result.contact
    }

    private suspend fun create(name: String): String {
        val created = Contacts.createContact(name).await()
        assertEquals(true, created.ok, "createContact must succeed; got ${JSON.stringify(created)}")
        val personId = created.personId as String
        assertTrue(personId.isNotEmpty(), "createContact must return the new personId")
        return personId
    }

    private fun card(name: String, vararg lines: String): String =
        (listOf("BEGIN:VCARD", "VERSION:3.0", "FN:$name") + lines + "END:VCARD")
            .joinToString("\r\n", postfix = "\r\n")

    @Test
    fun create_appearsInContacts_withTheFullPublishedRowShape() = runTest {
        val name = "Ada Create ${stamp()}"
        val personId = create(name)

        val row = contactsNow().firstOrNull { it.personId == personId }
        assertNotNull(row, "a created contact must appear in contacts()")
        assertEquals(name, row.displayName)
        assertEquals("notConnected", row.trust, "a hand-typed contact has no key (D5)")
        assertEquals(0, row.identities.length)
        assertEquals(0, row.details.phones.length)
        assertEquals(0, row.details.emails.length)
        assertEquals(0, row.details.addresses.length)
        assertEquals("", row.details.organisation)
        assertEquals("", row.details.note)
        assertEquals("none", row.avatar)
        assertEquals(null, row.avatarDataUrl, "no photo is null, not an empty string")
    }

    @Test
    fun contacts_areSortedByPetname_caseInsensitively() = runTest {
        val s = stamp()
        val b = create("zz-sort $s b")
        val a = create("ZZ-SORT $s a")
        val ids = contactsNow().map { it.personId as String }.filter { it == a || it == b }
        assertEquals(listOf(a, b), ids)
    }

    @Test
    fun rename_changesThePetname() = runTest {
        val personId = create("Before ${stamp()}")
        val renamed = Contacts.renameContact(personId, "After rename").await()
        assertEquals(true, renamed.ok)
        assertEquals("After rename", contactNow(personId).displayName)
    }

    @Test
    fun renameOfAMissingPerson_isATypedFailure_neverASuccess() = runTest {
        val result = Contacts.renameContact("no-such-person-${stamp()}", "x").await()
        assertEquals(false, result.ok, "renaming nobody must not read as done")
        assertTrue((result.error.kind as String).startsWith("error_"), "kind is a catalogue key")
    }

    @Test
    fun delete_removesTheContact_andContactThenResolvesNull() = runTest {
        val personId = create("Delete me ${stamp()}")
        assertEquals(true, Contacts.deleteContact(personId).await().ok)
        assertTrue(contactsNow().none { it.personId == personId }, "a deleted contact must leave the list")
        assertNull(contactNow(personId), "a deleted contact resolves null")
    }

    @Test
    fun aMissingPersonId_resolvesOkWithNull_notAnEmptyObject() = runTest {
        val result = Contacts.contact("missing-${stamp()}").await()
        assertEquals(true, result.ok, "not-found is not a failed read")
        assertTrue(result.contact === null, "not-found is exactly null; got ${JSON.stringify(result.contact)}")
    }

    @Test
    fun importOfATwoCardFile_createsTwo_withTheirNames() = runTest {
        val s = stamp()
        val text = card("Grace Import $s", "TEL;TYPE=cell:+1 555 0101") + card("Linus Import $s")
        val result = Contacts.importVcards(text).await()
        assertEquals(true, result.ok, "import must succeed; got ${JSON.stringify(result)}")
        assertEquals(2, result.imported)
        assertEquals(2, result.cards)

        val names = contactsNow().map { it.displayName as String }.filter { it.endsWith(s) }
        assertEquals(listOf("Grace Import $s", "Linus Import $s"), names)
        val grace = contactsNow().first { it.displayName == "Grace Import $s" }
        assertEquals("+1 555 0101", grace.details.phones[0].value, "the record's fields reach the row")
    }

    @Test
    fun importOfTextWithNoCard_isTheNoneInFileError() = runTest {
        val result = Contacts.importVcards("not a vcard").await()
        assertEquals(false, result.ok)
        assertEquals("error_contacts_none_in_file", result.error.kind)
    }

    @Test
    fun export_roundTripsThroughImport() = runTest {
        val s = stamp()
        val name = "Margaret Export $s"
        Contacts.importVcards(card(name, "EMAIL;TYPE=work:m$s@example.org", "X-CUSTOM:kept")).await()
        val original = contactsNow().first { it.displayName == name }

        val exported = Contacts.exportVcard(original.personId as String).await()
        assertEquals(true, exported.ok, "export must succeed; got ${JSON.stringify(exported)}")
        val vcard = exported.vcard as String
        assertTrue("X-CUSTOM:kept" in vcard, "export is the stored record verbatim — nothing dropped")

        val reimported = Contacts.importVcards(vcard).await()
        assertEquals(1, reimported.imported)
        val copies = contactsNow().filter { it.displayName == name }
        assertEquals(2, copies.size, "import never folds into an existing contact (D7)")
        val copy = copies.first { it.personId != original.personId }
        assertEquals("m$s@example.org", copy.details.emails[0].value)
        assertEquals("work", copy.details.emails[0].label)
    }

    @Test
    fun exportOfAContactWithNoRecord_isTheNothingToExportError() = runTest {
        val personId = create("No record ${stamp()}")
        val result = Contacts.exportVcard(personId).await()
        assertEquals(false, result.ok)
        assertEquals("error_contacts_nothing_to_export", result.error.kind)
    }

    @Test
    fun ownProfile_reflectsSetOwnDisplayName() = runTest {
        val name = "Me ${stamp()}"
        assertEquals(true, Contacts.setOwnDisplayName(name).await().ok)
        val profile = Contacts.ownProfile().await()
        assertEquals(name, profile.displayName)
        assertEquals("", profile.fingerprint, "no client-readable own identity exists; absence, not a value")
    }

    @Test
    fun saveDetails_mergesIntoTheRecord_keepingWhatTheEditorDoesNotModel() = runTest {
        val s = stamp()
        val name = "Edit $s"
        Contacts.importVcards(card(name, "TEL;TYPE=home:111", "X-KEEP:me")).await()
        val personId = contactsNow().first { it.displayName == name }.personId as String

        val details: dynamic = js("({})")
        details.phones = arrayOf(field("mobile", "222"))
        details.emails = arrayOf(field("home", "e$s@example.org"))
        details.addresses = emptyArray<dynamic>()
        details.organisation = "Analytical Engines"
        details.note = "n"
        val saved = Contacts.saveContactDetails(personId, details).await()
        assertEquals(true, saved.ok, "save must succeed; got ${JSON.stringify(saved)}")

        val row = contactNow(personId)
        assertEquals(1, row.details.phones.length, "the edited set replaces the phones")
        assertEquals("222", row.details.phones[0].value)
        assertEquals("mobile", row.details.phones[0].label)
        assertEquals("Analytical Engines", row.details.organisation)
        assertEquals(name, row.displayName, "saving details never rewrites the petname")
        val vcard = Contacts.exportVcard(personId).await().vcard as String
        assertTrue("X-KEEP:me" in vcard, "Q6: merge, never regenerate — unmodelled properties survive")
    }

    @Test
    fun saveDetails_onAHandTypedContact_createsItsFirstRecord() = runTest {
        val personId = create("Fresh ${stamp()}")
        val details: dynamic = js("({ phones: [], emails: [], addresses: [{ label: 'home', value: '1 Loop' }], organisation: '', note: '' })")
        assertEquals(true, Contacts.saveContactDetails(personId, details).await().ok)
        val row = contactNow(personId)
        assertNotNull(row, "the contact must still exist")
        assertEquals("1 Loop", row.details.addresses[0].value, JSON.stringify(row))
        assertEquals(true, Contacts.exportVcard(personId).await().ok, "the new manual: record is exportable")
    }

    @Test
    fun saveDetails_withAMissingField_isRefused_notReadAsEmpty() = runTest {
        val s = stamp()
        val name = "Partial $s"
        Contacts.importVcards(card(name, "TEL:333")).await()
        val personId = contactsNow().first { it.displayName == name }.personId as String

        // `phones` absent: reading it as "no phones" would delete 333 on save.
        val details: dynamic = js("({ emails: [], addresses: [], organisation: '', note: '' })")
        val result = Contacts.saveContactDetails(personId, details).await()
        assertEquals(false, result.ok)
        assertEquals("error_contacts_invalid_details", result.error.kind)
        val row = contactNow(personId)
        assertEquals("333", row.details.phones[0].value, "nothing was written")
    }

    @Test
    fun merge_foldsSourceIntoTarget_andDeletesTheSource() = runTest {
        val s = stamp()
        Contacts.importVcards(card("Source $s", "TEL:444")).await()
        val source = contactsNow().first { it.displayName == "Source $s" }.personId as String
        val target = create("Target $s")

        val merged = Contacts.mergeContacts(source, target).await()
        assertEquals(true, merged.ok, "merge must succeed; got ${JSON.stringify(merged)}")
        assertNull(contactNow(source), "the source contact is gone")
        val row = contactNow(target)
        assertEquals("Target $s", row.displayName, "the target keeps its petname")
        assertEquals("444", row.details.phones[0].value, "the source's records moved")
    }

    @Test
    fun mergeIntoItself_isRefused() = runTest {
        val personId = create("Self ${stamp()}")
        assertEquals(false, Contacts.mergeContacts(personId, personId).await().ok)
        assertNotNull(contactNow(personId), "a refused self-merge must not delete the person")
    }

    @Test
    fun relinkOfAnUnknownKey_isTheUnknownKeyError_notASuccess() = runTest {
        val personId = create("Relink ${stamp()}")
        val result = Contacts.relinkIdentity("no-such-fingerprint-${stamp()}", personId).await()
        assertEquals(false, result.ok, "the service's `false` must not surface as done")
        assertEquals("error_contacts_unknown_key", result.error.kind)
    }

    @Test
    fun setVerifiedOnAnUnknownKey_fails() = runTest {
        val result = Contacts.setVerified("no-such-fingerprint-${stamp()}", true).await()
        assertEquals(false, result.ok)
    }

    @Test
    fun contactSasOfAnUnknownKey_isAnEmptyCode_notAPlaceholder() = runTest {
        val result = Contacts.contactSas("no-such-fingerprint-${stamp()}").await()
        assertEquals(true, result.ok, "got ${JSON.stringify(result)}")
        assertEquals("", result.sas)
    }

    @Test
    fun anImportedPhoto_isInlinedAsADataUrl_andClearAvatarRemovesIt() = runTest {
        val s = stamp()
        val name = "Photo $s"
        // A JPEG's magic bytes (FF D8 FF E0 …) — the service promotes PHOTO at import.
        Contacts.importVcards(card(name, "PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg==")).await()
        val row = contactsNow().first { it.displayName == name }
        assertEquals("present", row.avatar)
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", row.avatarDataUrl)

        val personId = row.personId as String
        assertEquals(true, Contacts.clearAvatar(personId).await().ok)
        val cleared = contactNow(personId)
        assertEquals("none", cleared.avatar)
        assertEquals(null, cleared.avatarDataUrl)
    }

    @Test
    fun inviteReads_answerTheEnvelope_withArrays() = runTest {
        val outstanding = Contacts.outstandingInvites().await()
        assertEquals(true, outstanding.ok)
        assertTrue(js("Array").isArray(outstanding.invites).unsafeCast<Boolean>())
        val pending = Contacts.pendingConfirmations().await()
        assertEquals(true, pending.ok)
        assertTrue(js("Array").isArray(pending.confirmations).unsafeCast<Boolean>())
    }

    /** Real time on purpose: the collector runs on `GlobalScope`, outside any virtual clock. */
    @Test
    fun observeContacts_pushesACreatedContact() = GlobalScope.promise {
        val name = "Observed ${stamp()}"
        // A holder, not a `var`: Kotlin smart-casts a `dynamic` var after a `== null` check.
        val box: dynamic = js("({ v: null })")
        val stop = Contacts.observeContacts({ box.v = it }, { error("observe failed: $it") })
        try {
            val personId = create(name)
            var i = 0
            while (i < 100 && (box.v == null ||
                    box.v.contacts.unsafeCast<Array<dynamic>>().none { it.personId == personId })
            ) {
                delay(20); i++
            }
            val latest: dynamic = box.v
            assertEquals(true, latest.ok)
            assertTrue(
                latest.contacts.unsafeCast<Array<dynamic>>().any { it.personId == personId && it.displayName == name },
                "the subscription must push the new contact without a refetch",
            )
        } finally {
            stop()
        }
    }

    @Test
    fun observeContact_pushesNullOnceDeleted() = GlobalScope.promise {
        val personId = create("Observed delete ${stamp()}")
        val seen = mutableListOf<dynamic>()
        val stop = Contacts.observeContact(personId, { seen.add(it) }, { error("observe failed: $it") })
        try {
            var i = 0
            while (i < 100 && seen.none { it.contact != null }) { delay(20); i++ }
            Contacts.deleteContact(personId).await()
            i = 0
            while (i < 100 && (seen.isEmpty() || seen.last().contact != null)) { delay(20); i++ }
            assertTrue(seen.any { it.ok == true && it.contact != null && it.contact.personId == personId })
            assertTrue(seen.last().ok == true && seen.last().contact === null, "deletion pushes `contact: null`")
        } finally {
            stop()
        }
    }

    @Test
    fun observeOwnProfile_emitsImmediately_thenTheNewName() = GlobalScope.promise {
        val seen = mutableListOf<String>()
        val stop = Contacts.observeOwnProfile({ seen.add(it.displayName) }, { error("observe failed: $it") })
        try {
            var i = 0
            while (i < 100 && seen.isEmpty()) { delay(20); i++ }
            assertTrue(seen.isNotEmpty(), "the seed read must emit even before any change")
            val name = "Observed me ${stamp()}"
            Contacts.setOwnDisplayName(name).await()
            i = 0
            while (i < 100 && seen.last() != name) { delay(20); i++ }
            assertEquals(name, seen.last())
        } finally {
            stop()
        }
    }

    @Test
    fun unsubscribing_stopsDelivery() = GlobalScope.promise {
        val seen = mutableListOf<dynamic>()
        val stop = Contacts.observeContacts({ seen.add(it) }, { error("observe failed: $it") })
        var i = 0
        while (i < 100 && seen.isEmpty()) { delay(20); i++ }
        assertTrue(seen.isNotEmpty(), "the subscription must emit before we stop it")
        stop()
        val countAtStop = seen.size

        val personId = create("After stop ${stamp()}")
        delay(500)
        assertEquals(countAtStop, seen.size, "nothing may be pushed after unsubscribe")
        assertTrue(seen.none { e -> e.contacts.unsafeCast<Array<dynamic>>().any { it.personId == personId } })
    }

    @Test
    fun aThrowingCallback_endsTheStreamWithATerminalError_notASilentFreeze() = GlobalScope.promise {
        val errors = mutableListOf<String>()
        var calls = 0
        val stop = Contacts.observeContacts({ calls++; throw IllegalStateException("could not clone") }, { errors.add(it) })
        try {
            var i = 0
            while (i < 100 && errors.isEmpty()) { delay(20); i++ }
            assertEquals(listOf("could not clone"), errors, "the throw must reach the subscriber, once")
            create("After failure ${stamp()}")
            delay(300)
            assertEquals(1, calls, "a failed stream pushes nothing more")
            assertEquals(1, errors.size, "the terminal error fires exactly once")
        } finally {
            stop()
        }
    }

    @Test
    fun observeJob_reportsAThrowingFlow_andACompletedOne_butNeverTheSubscribersCancel() = GlobalScope.promise {
        val errors = mutableListOf<String>()
        Contacts.observeJob({ errors.add(it) }) { throw RuntimeException("relation read failed") }
        Contacts.observeJob({ errors.add(it) }) { /* completes at once */ }
        val stop = Contacts.observeJob({ errors.add("cancelled: $it") }) { delay(10_000) }
        delay(50)
        stop()
        var i = 0
        while (i < 100 && errors.size < 2) { delay(20); i++ }
        delay(100)
        assertEquals(setOf("relation read failed", OBSERVE_ENDED), errors.toSet())
        assertEquals(2, errors.size, "an unsubscribe is not a failure")
    }

    private fun field(label: String, value: String): dynamic {
        val f: dynamic = js("({})")
        f.label = label
        f.value = value
        return f
    }
}
