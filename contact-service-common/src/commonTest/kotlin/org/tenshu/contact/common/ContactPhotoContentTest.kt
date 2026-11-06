package org.tenshu.contact.common

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The codec half of `contacts-app.md` §9.10: which `PHOTO` lines leave the card, and that the card
 * comes back byte for byte. Every name is made up.
 */
@OptIn(ExperimentalEncodingApi::class)
class ContactPhotoContentTest {

    private fun image(bytes: Int, seed: Int = 7) = ByteArray(bytes) { i -> ((i * 31 + seed) % 251).toByte() }

    /** A `PHOTO` line folded at 75 octets the way desktop exporters write it. */
    private fun photoLine(image: ByteArray, newline: String = "\r\n"): String {
        val line = "PHOTO;ENCODING=b;TYPE=JPEG:" + Base64.encode(image)
        return buildString {
            append(line.take(75))
            var at = 75
            while (at < line.length) {
                append(newline).append(' ').append(line.substring(at, minOf(at + 74, line.length)))
                at += 74
            }
        }
    }

    private fun card(vararg photos: String, newline: String = "\r\n") = listOf(
        "BEGIN:VCARD", "VERSION:3.0", "N:Varga;Ilse;;;", "FN:Ilse Varga",
        "TEL;TYPE=CELL:+44 7700 900456", *photos, "X-VENDOR-THING:keep me", "END:VCARD",
    ).joinToString(newline) + newline

    private val rowMax = 512 * 1024

    @Test
    fun `a big photo leaves the card and the stored card is far below ROW_MAX`() {
        val original = card(photoLine(image(3 * 1024 * 1024)))
        assertTrue(original.encodeToByteArray().size > rowMax, "the fixture must be a card the iOS store refuses")

        val relocated = relocatePhotos(original)

        assertEquals(1, relocated.moved)
        assertNotNull(relocated.photos)
        assertTrue(relocated.card.encodeToByteArray().size < 4 * 1024, "stored: ${relocated.card.length}")
        assertEquals(1, photoRefsIn(relocated.card).size)
        assertTrue("PHOTO" !in parseVCard(relocated.card).map { it.name })
    }

    @Test
    fun `a small photo stays in the card untouched`() {
        val original = card(photoLine(image(20 * 1024)))
        val relocated = relocatePhotos(original)
        assertEquals(0, relocated.moved)
        assertNull(relocated.photos)
        assertEquals(original, relocated.card)
    }

    @Test
    fun `the threshold is on the encoded line - just over moves - just under stays`() {
        // 64 KiB of line ≈ 48 KiB of image; probe both sides of it.
        val under = card(photoLine(image(46 * 1024)))
        val over = card(photoLine(image(50 * 1024)))
        assertEquals(0, relocatePhotos(under).moved)
        assertEquals(1, relocatePhotos(over).moved)
    }

    @Test
    fun `reassembly is byte-exact for CRLF - LF and several photos at once`() {
        listOf("\r\n", "\n").forEach { newline ->
            val original = card(
                photoLine(image(200 * 1024, seed = 1), newline),
                "PHOTO;VALUE=uri:https://example.invalid/small.jpg",
                photoLine(image(90 * 1024, seed = 2), newline),
                newline = newline,
            )
            val relocated = relocatePhotos(original)
            assertEquals(2, relocated.moved)
            val back = reassembleVCard(relocated.card, relocated.photos)
            assertIs<Reassembly.Complete>(back)
            assertEquals(original, back.card, "newline ${newline.length}")
        }
    }

    @Test
    fun `without the photos a stored card does not reassemble - it never degrades to the stored card`() {
        val relocated = relocatePhotos(card(photoLine(image(200 * 1024))))
        assertEquals(Reassembly.PhotosUnavailable, reassembleVCard(relocated.card, photos = null))
    }

    @Test
    fun `a blob of other photos is refused - not spliced in`() {
        val mine = relocatePhotos(card(photoLine(image(200 * 1024, seed = 1))))
        val theirs = relocatePhotos(card(photoLine(image(200 * 1024, seed = 9))))
        assertEquals(Reassembly.PhotosMismatch, reassembleVCard(mine.card, theirs.photos))
        assertEquals(Reassembly.PhotosMismatch, reassembleVCard(mine.card, "not a blob".encodeToByteArray()))
    }

    @Test
    fun `many medium photos that add up past the budget move largest first`() {
        val photos = (1..6).map { photoLine(image(45 * 1024 + it * 100, seed = it)) }
        val original = card(*photos.toTypedArray())
        assertEquals(0, photos.count { it.encodeToByteArray().size > ContactPhotoContent.RELOCATE_ABOVE_BYTES })

        val relocated = relocatePhotos(original)

        assertTrue(relocated.moved > 0)
        assertTrue(relocated.card.encodeToByteArray().size <= ContactPhotoContent.CARD_INLINE_BUDGET_BYTES)
        assertEquals(original, (reassembleVCard(relocated.card, relocated.photos) as Reassembly.Complete).card)
    }

    @Test
    fun `the stored card reads the same as the full one - offline detail shows everything`() {
        val original = card(photoLine(image(200 * 1024)))
        assertEquals(readContactCard(original), readContactCard(relocatePhotos(original).card))
    }

    @Test
    fun `an edit merged into a stored card keeps the placeholder - so the photo survives`() {
        val relocated = relocatePhotos(card(photoLine(image(200 * 1024))))
        val edited = mergeIntoVCard(relocated.card, readContactCard(relocated.card).copy(note = "Met at the climbing wall"))

        // ⚠ The merge re-folds the long placeholder line, so the same reference is spelled differently:
        // references compare by index and digest, never by raw text.
        assertEquals(photoRefsIn(relocated.card).map { it.key }, photoRefsIn(edited).map { it.key })
        val back = reassembleVCard(edited, relocated.photos)
        assertIs<Reassembly.Complete>(back)
        assertNotNull(readContactPhoto(back.card, 256 * 1024))
        assertEquals("Met at the climbing wall", readContactCard(back.card).note)
    }

    @Test
    fun `a card already carrying references is not relocated again`() {
        val relocated = relocatePhotos(card(photoLine(image(200 * 1024))))
        assertFailsWith<IllegalArgumentException> { relocatePhotos(relocated.card) }
        assertEquals(0, relocatablePhotoCount(relocated.card))
    }

    @Test
    fun `the blob frame round-trips and rejects truncation`() {
        val spans = listOf("a".encodeToByteArray(), ByteArray(0), image(1000))
        val blob = encodePhotoBlob(spans)
        val back = assertNotNull(decodePhotoBlob(blob))
        spans.zip(back).forEach { (a, b) -> assertContentEquals(a, b) }
        assertNull(decodePhotoBlob(blob.copyOf(blob.size - 1)))
    }
}
