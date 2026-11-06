package org.tenshu.contact.test

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Cards with embedded photos of a chosen size, for the v2 content-plane photo tests
 * (`contacts-app.md` §9.10). Every name is made up.
 */
@OptIn(ExperimentalEncodingApi::class)
object ContactPhotoFixtures {

    /** Deterministic "image" bytes: a JPEG-ish header, then a pattern no two sizes share. */
    fun image(bytes: Int, seed: Int = 7): ByteArray =
        ByteArray(bytes) { i -> if (i < 3) byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())[i] else ((i * 31 + seed) % 251).toByte() }

    /**
     * A `PHOTO;ENCODING=b;TYPE=JPEG:` line of [image], folded at 75 octets with [newline] + space
     * the way desktop exporters write it.
     */
    fun photoLine(image: ByteArray, newline: String = "\r\n"): String {
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

    /** A made-up contact card carrying [photoLines], in the given line-break style. */
    fun card(vararg photoLines: String, newline: String = "\r\n", name: String = "Ilse Varga"): String =
        listOf(
            "BEGIN:VCARD",
            "VERSION:3.0",
            "N:Varga;Ilse;;;",
            "FN:$name",
            "TEL;TYPE=CELL:+44 7700 900456",
            "EMAIL;TYPE=HOME:ilse@example.invalid",
            *photoLines,
            "X-VENDOR-THING:keep me",
            "END:VCARD",
        ).joinToString(newline) + newline
}
