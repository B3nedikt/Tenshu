package app.tenshu.sdk

import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.common.ContactCard
import org.tenshu.contact.common.LabeledValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rules `ContactsBook.kt` copies from `:contacts-shared-data` — pinned here because this is the
 * second reader of the same relations, and a copy that drifts drifts silently.
 */
class ContactsBookMappingTest {

    private fun identity(verification: String) = ContactIdentityJs("fp-$verification", "", verification, 0.0, "")

    @Test
    fun anUnknownVerificationRung_failsClosedToUnverified() {
        assertEquals("UNVERIFIED", verificationOf("SOMETHING_NEW"))
        assertEquals("UNVERIFIED", verificationOf(""))
        assertEquals("VERIFIED", verificationOf(TenshuIdentity.Verification.VERIFIED))
        assertEquals("ATTESTED", verificationOf(TenshuIdentity.Verification.ATTESTED))
    }

    @Test
    fun trust_followsTheQ2bRule() {
        assertEquals("notConnected", trustOf(emptyList()))
        assertEquals("unverified", trustOf(listOf(identity("UNVERIFIED"))))
        assertEquals("verified", trustOf(listOf(identity("VERIFIED"), identity("ATTESTED"))))
        assertEquals("needsReverification", trustOf(listOf(identity("VERIFIED"), identity("UNVERIFIED"))))
    }

    @Test
    fun anAttestedOnlyKeyring_isVerified_andAttestedPlusUnverified_needsReverification() {
        assertEquals("verified", trustOf(listOf(identity("ATTESTED"))))
        assertEquals("needsReverification", trustOf(listOf(identity("ATTESTED"), identity("UNVERIFIED"))))
    }

    /** `connectedContacts().verified` — what Drive's sharing picker shows a checkmark from. */
    @Test
    fun connectedVerified_isTheQ2bVerifiedAnswer_notAnyKeyVerified() {
        assertEquals(false, connectedVerified(listOf("VERIFIED", "UNVERIFIED")), "a mixed keyring is not verified")
        assertEquals(true, connectedVerified(listOf("ATTESTED")), "an ATTESTED-only keyring is verified")
        assertEquals(true, connectedVerified(listOf("VERIFIED", "ATTESTED")))
        assertEquals(false, connectedVerified(listOf("UNVERIFIED")))
        assertEquals(false, connectedVerified(listOf("SOMETHING_NEW")), "an unknown rung fails closed")
        assertEquals(false, connectedVerified(emptyList()))
    }

    @Test
    fun anUnlinkedKeyOrRecord_attachesToNobody_evenAPersonWithAnEmptyId() {
        val rows = joinContactRows(
            persons = listOf(PersonRow("", "phantom"), PersonRow("p1", "Real")),
            identities = listOf(IdentityRow("", identity("VERIFIED")), IdentityRow("p1", identity("UNVERIFIED"))),
            sources = listOf(SourceRow("", ContactCard(organisation = "stray")), SourceRow("p1", ContactCard(note = "mine"))),
            avatars = emptyMap(),
        )
        val phantom = rows.first { it.personId == "" }
        assertEquals(0, phantom.identities.size)
        assertEquals("", phantom.details.organisation)
        val real = rows.first { it.personId == "p1" }
        assertEquals(1, real.identities.size)
        assertEquals("mine", real.details.note)
        assertEquals("", real.details.organisation)
    }

    @Test
    fun detailsUnion_dedupesByValue_andTakesTheFirstOrganisation() {
        val details = unionDetails(
            listOf(
                ContactCard(phones = listOf(LabeledValue("cell", "+1 555")), organisation = "First"),
                ContactCard(phones = listOf(LabeledValue("home", " +1 555 ")), organisation = "Second"),
            ),
        )
        assertEquals(1, details.phones.size)
        assertEquals("cell", details.phones[0].label)
        assertEquals("First", details.organisation)
    }

    @Test
    fun avatarState_namesWhyAPhotoIsWithheld() {
        assertEquals("none", avatarStateOf(null))
        assertEquals("none", avatarStateOf(ByteArray(0)))
        assertEquals("unknownFormat", avatarStateOf(byteArrayOf(1, 2, 3, 4)))
        val oversized = ByteArray(PersonAvatar.MAX_IMAGE_BYTES + 1).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }
        assertEquals("tooLarge", avatarStateOf(oversized))
        assertEquals("present", avatarStateOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun imageSniff_recognisesTheFourWebFormats() {
        assertEquals("image/jpeg", imageMimeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("image/png", imageMimeOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals("image/gif", imageMimeOf("GIF89a".encodeToByteArray()))
        assertEquals("image/webp", imageMimeOf("RIFF\u0000\u0000\u0000\u0000WEBP".encodeToByteArray()))
        assertNull(imageMimeOf("RIFF\u0000\u0000\u0000\u0000WAVE".encodeToByteArray()))
    }
}
