package org.tenshu.contact.common

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/** [readContactPhoto], the one reader of the `PHOTO` property the codec carries opaquely. */
@OptIn(ExperimentalEncodingApi::class)
class ContactPhotoTest {

    private val limit = 256 * 1024

    private fun card(photoLine: String) =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Mira\r\n$photoLine\r\nEND:VCARD\r\n"

    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test
    fun `a 3_0 card with ENCODING=b decodes`() {
        val text = card("PHOTO;ENCODING=b;TYPE=JPEG:${Base64.encode(image)}")
        assertContentEquals(image, readContactPhoto(text, limit))
    }

    @Test
    fun `a 2_1 card spelling it ENCODING=BASE64 decodes the same`() {
        val text = card("PHOTO;ENCODING=BASE64;TYPE=JPEG:${Base64.encode(image)}")
        assertContentEquals(image, readContactPhoto(text, limit))
    }

    @Test
    fun `a data URI decodes`() {
        // Android's exporter emits this even in cards it labels 3.0.
        val text = card("PHOTO:data:image/jpeg;base64,${Base64.encode(image)}")
        assertContentEquals(image, readContactPhoto(text, limit))
    }

    @Test
    fun `a folded photo line survives unfolding`() {
        // Real exports fold at 75 octets; an embedded image is always folded.
        val encoded = Base64.encode(ByteArray(300) { it.toByte() })
        val folded = encoded.chunked(60).joinToString("\r\n ")
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Mira\r\n" +
            "PHOTO;ENCODING=b;TYPE=PNG:$folded\r\nEND:VCARD\r\n"
        assertContentEquals(ByteArray(300) { it.toByte() }, readContactPhoto(text, limit))
    }

    @Test
    fun `a URI photo is never fetched`() {
        // ⚠ The important one. A URL is not an image, and resolving it would be egress the
        // contact graph has no business making on behalf of imported data (House Rule #1
        // puts all network behind Control).
        assertNull(readContactPhoto(card("PHOTO;VALUE=uri:https://example.com/me.jpg"), limit))
        assertNull(readContactPhoto(card("PHOTO;VALUE=URI;ENCODING=b:https://example.com/x"), limit))
    }

    @Test
    fun `a card with no photo yields nothing`() {
        assertNull(readContactPhoto("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Mira\r\nEND:VCARD\r\n", limit))
    }

    @Test
    fun `an oversized photo is refused before it is decoded`() {
        val big = Base64.encode(ByteArray(2048) { 7 })
        assertNull(readContactPhoto(card("PHOTO;ENCODING=b:$big"), maxBytes = 512))
    }

    @Test
    fun `a photo exactly at the limit is accepted`() {
        val exact = ByteArray(512) { 3 }
        val text = card("PHOTO;ENCODING=b:${Base64.encode(exact)}")
        assertContentEquals(exact, readContactPhoto(text, maxBytes = 512))
    }

    @Test
    fun `unparseable base64 yields nothing rather than garbage`() {
        assertNull(readContactPhoto(card("PHOTO;ENCODING=b:!!!not base64!!!"), limit))
    }

    @Test
    fun `an empty photo value yields nothing`() {
        assertNull(readContactPhoto(card("PHOTO;ENCODING=b:"), limit))
    }
}
