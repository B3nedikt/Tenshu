package app.tenshu.contacts.data

import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.model.Verification
import org.tenshu.contact.TenshuIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `Person` × `TenshuIdentity` join and the ladder mapping — the two places where a
 * mistake would be invisible in a screenshot and wrong in a way that matters.
 */
class ContactJoinTest {

    private fun person(id: String, name: String) = Contact(personId = id, displayName = name)

    private fun identity(
        fingerprint: String,
        verification: Verification = Verification.UNVERIFIED,
    ) = ContactIdentity(
        fingerprint = fingerprint,
        selfAssertedName = "claimed",
        verification = verification,
    )

    @Test
    fun `keys attach to their person by the back-link`() {
        val contacts = joinContacts(
            persons = listOf(person("p1", "Mira"), person("p2", "Jonas")),
            identities = listOf(
                "p1" to identity("aaaa", Verification.VERIFIED),
                "p2" to identity("bbbb"),
                "p1" to identity("cccc", Verification.VERIFIED),
            ),
        )

        assertEquals(listOf("Jonas", "Mira"), contacts.map { it.displayName })
        assertEquals(2, contacts.single { it.personId == "p1" }.identities.size)
        assertEquals(TrustState.Verified, contacts.single { it.personId == "p1" }.trust)
    }

    @Test
    fun `an unlinked identity attaches to nobody`() {
        // ⚠ `personId == ""` is a not-yet-linked candidate, not a person. Grouping it like
        // any other back-link would collect every unlinked key under one phantom contact.
        val contacts = joinContacts(
            persons = listOf(person("p1", "Mira")),
            identities = listOf("" to identity("orphan"), "p1" to identity("aaaa")),
        )

        assertEquals(1, contacts.size)
        assertEquals(listOf("aaaa"), contacts.single().identities.map { it.fingerprint })
    }

    @Test
    fun `an identity pointing at an unknown person is dropped rather than turned into a contact`() {
        val contacts = joinContacts(
            persons = listOf(person("p1", "Mira")),
            identities = listOf("p-gone" to identity("aaaa")),
        )

        assertEquals(listOf("Mira"), contacts.map { it.displayName })
        assertTrue(contacts.single().identities.isEmpty())
    }

    @Test
    fun `a person with no keys survives the join`() {
        // D5: the ordinary case once import lands — a contact is not defined by having a key.
        val contacts = joinContacts(persons = listOf(person("p1", "Dad")), identities = emptyList())

        assertEquals(TrustState.NotConnected, contacts.single().trust)
    }

    @Test
    fun `the list comes back sorted case-insensitively`() {
        val contacts = joinContacts(
            persons = listOf(person("a", "zoe"), person("b", "Alex"), person("c", "bo")),
            identities = emptyList(),
        )

        assertEquals(listOf("Alex", "bo", "zoe"), contacts.map { it.displayName })
    }

    @Test
    fun `the ladder maps from the stored strings`() {
        assertEquals(Verification.VERIFIED, verificationOf(TenshuIdentity.Verification.VERIFIED))
        assertEquals(Verification.ATTESTED, verificationOf(TenshuIdentity.Verification.ATTESTED))
        assertEquals(Verification.UNVERIFIED, verificationOf(TenshuIdentity.Verification.UNVERIFIED))
    }

    @Test
    fun `an unrecognised ladder value fails closed`() {
        // A client that does not understand a rung must under-claim trust. Being wrong this
        // way costs a re-comparison; the other way puts a checkmark on an unchecked key.
        assertEquals(Verification.UNVERIFIED, verificationOf("SOME_FUTURE_RUNG"))
        assertEquals(Verification.UNVERIFIED, verificationOf(""))
    }
}
