package org.tenshu.contact.common

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pull the embedded image out of a vCard's `PHOTO` property (`contacts-app.md` D1).
 *
 * `PHOTO` is one of the properties the codec carries **opaquely** — it is not editable, so
 * it round-trips byte-exact in `rawVcard` and nothing here consumes it. This is the one place
 * that reads it, and it only ever reads: promoting a photo to a [org.tenshu.contact.PersonAvatar]
 * row never rewrites the card it came from, so the source stays the lossless record it is
 * meant to be.
 *
 * Handles the three encodings exporters actually emit:
 *
 *  - vCard 3.0 `PHOTO;ENCODING=b;TYPE=JPEG:<base64>`
 *  - vCard 2.1 `PHOTO;ENCODING=BASE64;TYPE=JPEG:<base64>` (the same thing, spelled out)
 *  - vCard 4.0 `PHOTO:data:image/jpeg;base64,<base64>` (a data URI, which Android's exporter
 *    emits even in cards it labels 3.0)
 *
 * Returns `null` for anything else — most importantly a `PHOTO;VALUE=uri:https://…`, which
 * is a **URL, not an image**. Fetching it would be an egress the contact graph has no business
 * making (House Rule #1 puts all network behind Control, and this would be network on behalf
 * of data the user imported from elsewhere), so a URI photo is simply not promoted.
 *
 * Also `null` when the decoded image exceeds [maxBytes]: see
 * [org.tenshu.contact.PersonAvatar.MAX_IMAGE_BYTES] for why a ceiling exists at all. The
 * card keeps its `PHOTO` either way.
 */
@OptIn(ExperimentalEncodingApi::class)
fun readContactPhoto(rawVcard: String, maxBytes: Int): ByteArray? {
    val photo = parseVCard(rawVcard).firstOrNull { it.name.equals("PHOTO", ignoreCase = true) }
        ?: return null

    val raw = photo.value.trim()
    if (raw.isEmpty()) return null

    val encoded = when {
        // A data URI: everything up to the comma is the media type + encoding.
        raw.startsWith("data:", ignoreCase = true) -> {
            val header = raw.substringBefore(',', missingDelimiterValue = "")
            if (!header.contains("base64", ignoreCase = true)) return null
            raw.substringAfter(',', missingDelimiterValue = "")
        }

        // ⚠ Both spellings, and `VALUE=uri` explicitly excluded: a card can say
        // `ENCODING=b` and still hand over a URL, and treating that as base64 would
        // decode a web address into noise and store it as an image.
        photo.hasParam("ENCODING", "b") || photo.hasParam("ENCODING", "BASE64") ->
            if (photo.hasParam("VALUE", "uri")) return null else raw

        else -> return null
    }

    // Folding already stripped the continuation spaces; some 2.1 exporters also wrap with
    // tabs and blank lines inside the value, which are not base64 alphabet.
    val cleaned = encoded.filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return null

    // A base64 length ceiling before decoding, so a 20 MB embedded portrait never becomes a
    // 20 MB ByteArray just to be rejected. 4 base64 chars per 3 bytes, rounded generously.
    if (cleaned.length > (maxBytes / 3 + 1) * 4 + 8) return null

    val decoded = runCatching { Base64.decode(cleaned) }.getOrNull() ?: return null
    return when {
        decoded.isEmpty() || decoded.size > maxBytes -> null
        else -> decoded
    }
}
