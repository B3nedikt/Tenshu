package app.tenshu.contacts.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The duplicate heuristic (D7) — the half that suggests, and never acts. */
class MergeCandidateTest {

    private fun contact(
        id: String,
        name: String,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        connected: Boolean = false,
    ) = Contact(
        personId = id,
        displayName = name,
        identities = if (connected) {
            listOf(
                ContactIdentity(
                    fingerprint = "fp-$id",
                    selfAssertedName = name,
                    verification = Verification.UNVERIFIED,
                )
            )
        } else {
            emptyList()
        },
        details = ContactDetails(
            phones = phones.map { ContactField("mobile", it) },
            emails = emails.map { ContactField("home", it) },
        ),
    )

    @Test
    fun `a shared phone number is the strongest signal`() {
        val candidates = findMergeCandidates(
            listOf(
                contact("a", "Mira Okonkwo", phones = listOf("+44 7700 900123")),
                contact("b", "M. Okonkwo", phones = listOf("07700 900123")),
            )
        )
        assertEquals(1, candidates.size)
        assertEquals(MergeReason.SharedPhone, candidates.single().headlineReason)
    }

    @Test
    fun `the same number written three ways is one number`() {
        // A naive string compare finds none of these. Nine digits is long enough that a
        // collision is implausible and short enough to survive trunk and international
        // prefixes.
        val forms = listOf("+44 7700 900123", "07700 900123", "00447700900123")
        val normalised = forms.map { normalisePhone(it) }.toSet()
        assertEquals(1, normalised.size, "got $normalised")
    }

    @Test
    fun `a short number is compared whole`() {
        // Truncating a five-digit short code to its last nine digits is a no-op, but the
        // rule has to be stated or a future "always take nine" would silently match every
        // short number against every other.
        assertEquals("12345", normalisePhone("12345"))
    }

    @Test
    fun `a shared email address matches case-insensitively`() {
        val candidates = findMergeCandidates(
            listOf(
                contact("a", "Mira", emails = listOf("Mira@Example.com")),
                contact("b", "Mira O", emails = listOf(" mira@example.com ")),
            )
        )
        assertEquals(MergeReason.SharedEmail, candidates.single().headlineReason)
    }

    @Test
    fun `the same name alone is a weaker suggestion and says so`() {
        val candidates = findMergeCandidates(
            listOf(contact("a", "Mira Okonkwo"), contact("b", "mira  okonkwo"))
        )
        assertEquals(MergeReason.SameName, candidates.single().headlineReason)
        assertTrue(candidates.single().isNameOnly, "the UI has to be able to hedge on this one")
    }

    @Test
    fun `two connected contacts are never suggested on name alone`() {
        // ⚠ Two people who both completed a handshake are two people the user separately
        // confirmed exist. A name collision between them is far more likely to be a
        // coincidence than a duplicate, and the cost of merging is higher: an unfamiliar
        // key silently joins a known contact's keyring.
        val candidates = findMergeCandidates(
            listOf(
                contact("a", "Mira Okonkwo", connected = true),
                contact("b", "Mira Okonkwo", connected = true),
            )
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `but evidence still surfaces two connected contacts`() {
        // A shared phone number is evidence, not coincidence — the name rule above is about
        // weak signals, not about exempting connected contacts from review.
        val candidates = findMergeCandidates(
            listOf(
                contact("a", "Mira", phones = listOf("+44 7700 900123"), connected = true),
                contact("b", "Mira", phones = listOf("+44 7700 900123"), connected = true),
            )
        )
        assertEquals(MergeReason.SharedPhone, candidates.single().headlineReason)
    }

    @Test
    fun `unrelated contacts suggest nothing`() {
        val candidates = findMergeCandidates(
            listOf(
                contact("a", "Mira", phones = listOf("+44 7700 900123")),
                contact("b", "Dad", phones = listOf("+44 7700 111222")),
                contact("c", "Jonas"),
            )
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `blank names never match each other`() {
        // An imported card can have no FN at all; two of them are not the same person.
        val candidates = findMergeCandidates(listOf(contact("a", ""), contact("b", "")))
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a pair is suggested once rather than twice`() {
        val candidates = findMergeCandidates(
            listOf(contact("a", "Mira"), contact("b", "Mira"))
        )
        assertEquals(1, candidates.size)
    }

    @Test
    fun `evidence outranks coincidence in the list order`() {
        // Otherwise the pairs worth acting on are buried under name collisions.
        val candidates = findMergeCandidates(
            listOf(
                contact("a", "Jonas"),
                contact("b", "Jonas"),
                contact("c", "Mira", phones = listOf("+44 7700 900123")),
                contact("d", "Mira O", phones = listOf("+44 7700 900123")),
            )
        )
        assertEquals(
            listOf(MergeReason.SharedPhone, MergeReason.SameName),
            candidates.map { it.headlineReason },
        )
    }

    @Test
    fun `several reasons are all recorded strongest first`() {
        val candidate = findMergeCandidates(
            listOf(
                contact("a", "Mira", phones = listOf("0770 090 0123"), emails = listOf("m@e.com")),
                contact("b", "Mira", phones = listOf("+44 770 090 0123"), emails = listOf("M@E.com")),
            )
        ).single()
        assertEquals(
            listOf(MergeReason.SharedPhone, MergeReason.SharedEmail, MergeReason.SameName),
            candidate.reasons,
        )
        assertTrue(!candidate.isNameOnly)
    }
}
