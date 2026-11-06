package app.tenshu.contacts.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The read-through union and the search it widens. */
class ContactDetailsTest {

    private fun details(vararg phones: String, org: String = "", note: String = "") =
        ContactDetails(
            phones = phones.map { ContactField("mobile", it) },
            organisation = org,
            note = note,
        )

    @Test
    fun `no sources means no detail`() {
        assertTrue(unionDetails(emptyList()).isEmpty)
    }

    @Test
    fun `multi-values concatenate in source order`() {
        val union = unionDetails(listOf(details("111"), details("222")))
        assertEquals(listOf("111", "222"), union.phones.map { it.value })
    }

    @Test
    fun `the same value from two sources appears once`() {
        // An import and a hand-typed record often overlap; showing a number twice is the
        // kind of thing that makes a merged view look broken.
        val union = unionDetails(listOf(details("111"), details("111", "222")))
        assertEquals(listOf("111", "222"), union.phones.map { it.value })
    }

    @Test
    fun `de-duplication ignores case and surrounding space`() {
        val union = unionDetails(
            listOf(
                ContactDetails(emails = listOf(ContactField("home", "Mira@Example.com"))),
                ContactDetails(emails = listOf(ContactField("work", " mira@example.com "))),
            )
        )
        assertEquals(1, union.emails.size)
        assertEquals("home", union.emails.single().label, "the first occurrence keeps its label")
    }

    @Test
    fun `single-valued fields take the first non-empty one`() {
        // There is no way to show two organisations in one row, and no basis for preferring
        // the later record.
        val union = unionDetails(
            listOf(details(org = "", note = ""), details(org = "Tenshu", note = "hi"))
        )
        assertEquals("Tenshu", union.organisation)
        assertEquals("hi", union.note)
    }

    @Test
    fun `search reaches numbers emails and organisation`() {
        // Searching for a number is how people find contacts they never named carefully.
        val contact = Contact(
            personId = "p1",
            displayName = "Dad",
            details = ContactDetails(
                phones = listOf(ContactField("mobile", "+44 7700 900123")),
                emails = listOf(ContactField("home", "dad@example.com")),
                organisation = "Tenshu",
            ),
        )

        assertTrue(contact.matches("900123"))
        assertTrue(contact.matches("dad@example"))
        assertTrue(contact.matches("tenshu"))
        assertTrue(contact.matches("Dad"))
    }
}
