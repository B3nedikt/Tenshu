package org.tenshu.contact.common

import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower

/**
 * Big `PHOTO` lines leave the card for the content plane (`contacts-app.md` §9.10), so a stored
 * card fits the iOS store's `ROW_MAX` (`ios-shared-store-sqlite.md` §4.2.1).
 *
 * A stored card ([org.tenshu.contact.ContactSource.rawVcard]) keeps every line but the big photos.
 * Each moved `PHOTO` content line is replaced, at its own position, by one placeholder line
 *
 * ```
 * X-TENSHU-PHOTO-REF;SHA-256=<hex of the moved span>:<index>
 * ```
 *
 * and the moved spans travel together as one framed blob ([org.tenshu.contact.ContactSource.Photos]).
 * [reassembleVCard] puts each span back where its placeholder is, so
 * `reassembleVCard(relocatePhotos(card)) == card` **byte for byte** — every fold, every line break.
 *
 * ## Why a placeholder *line*
 *
 * It is an `X-` property, which every vCard reader is required to carry or ignore, so the stored
 * card stays a well-formed card: [parseVCard] and [readContactCard] read it (the editor never sees
 * it), and [mergeIntoVCard] passes it through like any property it does not model — which is what
 * lets an offline edit of a contact with a moved photo save without touching the photo.
 *
 * ⚠⚠ **A stored card is never what leaves the device.** Export, share and re-encode reassemble
 * first, and a card whose photos are not local fails ([Reassembly.PhotosUnavailable]); it is never
 * exported without them.
 *
 * ⚠ The `SHA-256` parameter is what makes a placeholder *ours*: a reassembly whose span does not
 * hash to it refuses ([Reassembly.PhotosMismatch]) rather than splicing the wrong bytes in.
 */
object ContactPhotoContent {

    /** The placeholder's property name. Reserved: a card that arrives carrying it is refused. */
    const val REF_PROPERTY = "X-TENSHU-PHOTO-REF"

    /** The placeholder's digest parameter. */
    const val REF_DIGEST_PARAM = "SHA-256"

    /**
     * A `PHOTO` content line larger than this (UTF-8 bytes of the raw line, folds included) moves.
     *
     * 64 KiB of base64 is ≈ 48 KiB of image: every phone-exported avatar (96–720 px JPEG) stays
     * inline, and a desktop address book's multi-MB portrait moves. Measured against the iOS
     * store's `ROW_MAX` (512 KiB), an inline photo is at most an eighth of a row.
     */
    const val RELOCATE_ABOVE_BYTES = 64 * 1024

    /**
     * The card budget: after the big photos move, a card still larger than this moves its
     * remaining `PHOTO` lines too, largest first — so many medium photos cannot add up to a row
     * the store refuses. Half of `ROW_MAX`, leaving the other half for the row's other fields, its
     * encoding and the sync block envelope.
     *
     * ⚠ It bounds only what is a `PHOTO`: a card whose *text* alone is over the budget (a giant
     * `NOTE`, `LOGO` or `SOUND`) is stored as it is. Only photos move, by decision;
     * `contacts-app.md` §9.10 keeps text-heavy cards as an open item.
     */
    const val CARD_INLINE_BUDGET_BYTES = 256 * 1024

    /** The blob's leading bytes; a blob without them is not ours. */
    internal val MAGIC: ByteArray = "TCPHOTOS1".encodeToByteArray()
}

/**
 * A card after [relocatePhotos]: the card to store, and the blob of moved spans — `null` when
 * nothing moved, in which case [card] is the input unchanged.
 */
class RelocatedCard(val card: String, val photos: ByteArray?, val moved: Int)

/**
 * One placeholder line of a stored card. [line] is its raw text; compare two cards' references by
 * [key], never by [line] — [mergeIntoVCard] re-serialises a card and folds a placeholder longer
 * than 75 octets, so the same reference can be spelled two ways.
 */
data class PhotoRef(val index: Int, val digest: String, val line: String) {
    val key: String get() = "$index:$digest"
}

/** What [reassembleVCard] could do. */
sealed interface Reassembly {
    /** The card as it was before [relocatePhotos], byte-exact. */
    data class Complete(val card: String) : Reassembly

    /** The card has placeholders and the photos are not here. ⚠ Never export the stored card instead. */
    data object PhotosUnavailable : Reassembly

    /** A placeholder names a span the blob does not hold (another version of the photos). */
    data object PhotosMismatch : Reassembly
}

/** The placeholder lines of [card], in document order. Empty for a card that moved nothing. */
fun photoRefsIn(card: String): List<PhotoRef> = logicalLines(card).mapNotNull { refOf(card, it) }

private fun refOf(card: String, line: LogicalLine): PhotoRef? {
    val property = parseVCardLine(line.unfolded) ?: return null
    if (!property.name.equals(ContactPhotoContent.REF_PROPERTY, ignoreCase = true)) return null
    val digest = property.params
        .firstOrNull { it.name.equals(ContactPhotoContent.REF_DIGEST_PARAM, ignoreCase = true) }
        ?.value
        ?.lowercase()
        ?: return null
    val index = property.value.trim().toIntOrNull() ?: return null
    return PhotoRef(index = index, digest = digest, line = card.substring(line.start, line.end))
}

/**
 * Move [card]'s big `PHOTO` lines out (see [ContactPhotoContent] for the rule).
 *
 * ⚠ [card] must carry no placeholder: placeholders index an *existing* blob, and a second blob
 * cannot be merged into it here. A caller holding a stored card reassembles it first.
 */
fun relocatePhotos(card: String): RelocatedCard {
    require(photoRefsIn(card).isEmpty()) { "relocatePhotos: the card already carries photo references" }
    val lines = logicalLines(card)
    val moving = movingLines(card, lines)
    if (moving.isEmpty()) return RelocatedCard(card, null, 0)

    val spans = mutableListOf<ByteArray>()
    val out = StringBuilder()
    var cursor = 0
    lines.filter { it in moving }.forEach { line ->
        val span = card.substring(line.start, line.end).encodeToByteArray()
        out.appendRange(card, cursor, line.start)
        out.append(ContactPhotoContent.REF_PROPERTY)
            .append(';').append(ContactPhotoContent.REF_DIGEST_PARAM).append('=')
            .append(sha256(span).toHexLower())
            .append(':').append(spans.size)
        spans += span
        cursor = line.end
    }
    out.appendRange(card, cursor, card.length)
    return RelocatedCard(out.toString(), encodePhotoBlob(spans), spans.size)
}

/**
 * How many `PHOTO` lines of [card] the rule would move. Placeholders already in the card are not
 * photos and count only toward its size — so `0` on a stored card means an edit added no photo
 * that would have to join its blob.
 */
fun relocatablePhotoCount(
    card: String,
    budgetBytes: Int = ContactPhotoContent.CARD_INLINE_BUDGET_BYTES,
): Int = movingLines(card, logicalLines(card), budgetBytes).size

/**
 * [relocatablePhotoCount], not counting a `PHOTO` that [existing] already carries inline (the same
 * property once unfolded — parameters and value). For a save that cannot reach its stored photos:
 * a photo that was inline in the stored row is not new, however the merge re-folded it, so only a
 * photo the edit *added* can refuse the save.
 */
fun relocatablePhotosNotIn(
    card: String,
    existing: String,
    budgetBytes: Int = ContactPhotoContent.CARD_INLINE_BUDGET_BYTES,
): Int {
    val inlineBefore = logicalLines(existing).mapNotNullTo(mutableSetOf()) { line ->
        parseVCardLine(line.unfolded)?.takeIf { it.name.equals("PHOTO", ignoreCase = true) }
    }
    return movingLines(card, logicalLines(card), budgetBytes).count { line ->
        parseVCardLine(line.unfolded) !in inlineBefore
    }
}

/** The `PHOTO` lines the rule moves: every one over the threshold, then the largest until the card fits. */
private fun movingLines(
    card: String,
    lines: List<LogicalLine>,
    budgetBytes: Int = ContactPhotoContent.CARD_INLINE_BUDGET_BYTES,
): Set<LogicalLine> {
    val photos = lines.filter { line ->
        parseVCardLine(line.unfolded)?.name?.equals("PHOTO", ignoreCase = true) == true
    }
    if (photos.isEmpty()) return emptySet()
    val size = { line: LogicalLine -> card.substring(line.start, line.end).encodeToByteArray().size }
    val moving = photos.filter { size(it) > ContactPhotoContent.RELOCATE_ABOVE_BYTES }.toMutableSet()
    // A placeholder is ~100 bytes; counted as such so the budget is measured on the stored card.
    val placeholderBytes = 100
    var stored = card.encodeToByteArray().size - moving.sumOf { size(it) - placeholderBytes }
    photos.filter { it !in moving }.sortedByDescending(size).forEach { line ->
        if (stored > budgetBytes) {
            moving += line
            stored -= size(line) - placeholderBytes
        }
    }
    return moving
}

/**
 * The card before [relocatePhotos], from a stored [card] and its [photos] blob (`null` when the
 * photos are not local). A card with no placeholder is returned as it is, whatever [photos] holds.
 */
fun reassembleVCard(card: String, photos: ByteArray?): Reassembly {
    val lines = logicalLines(card)
    val refs = lines.mapNotNull { line -> refOf(card, line)?.let { line to it } }
    if (refs.isEmpty()) return Reassembly.Complete(card)
    if (photos == null) return Reassembly.PhotosUnavailable
    val spans = decodePhotoBlob(photos) ?: return Reassembly.PhotosMismatch

    val out = StringBuilder()
    var cursor = 0
    refs.forEach { (line, ref) ->
        val span = spans.getOrNull(ref.index) ?: return Reassembly.PhotosMismatch
        if (sha256(span).toHexLower() != ref.digest) return Reassembly.PhotosMismatch
        out.appendRange(card, cursor, line.start)
        out.append(span.decodeToString())
        cursor = line.end
    }
    out.appendRange(card, cursor, card.length)
    return Reassembly.Complete(out.toString())
}

// ── the blob ─────────────────────────────────────────────────────────────────

/** `MAGIC`, a 4-byte big-endian count, then per span a 4-byte big-endian length and its bytes. */
internal fun encodePhotoBlob(spans: List<ByteArray>): ByteArray {
    val total = ContactPhotoContent.MAGIC.size + 4 + spans.sumOf { 4 + it.size }
    val out = ByteArray(total)
    var at = 0
    ContactPhotoContent.MAGIC.copyInto(out, at); at += ContactPhotoContent.MAGIC.size
    fun putInt(value: Int) {
        out[at] = (value ushr 24).toByte(); out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte(); out[at + 3] = value.toByte()
        at += 4
    }
    putInt(spans.size)
    spans.forEach { span -> putInt(span.size); span.copyInto(out, at); at += span.size }
    return out
}

/** The spans of a blob [encodePhotoBlob] wrote, or `null` for anything else. */
internal fun decodePhotoBlob(blob: ByteArray): List<ByteArray>? {
    val magic = ContactPhotoContent.MAGIC
    if (blob.size < magic.size + 4 || !blob.copyOfRange(0, magic.size).contentEquals(magic)) return null
    var at = magic.size
    fun getInt(): Int? {
        if (at + 4 > blob.size) return null
        val value = ((blob[at].toInt() and 0xff) shl 24) or ((blob[at + 1].toInt() and 0xff) shl 16) or
            ((blob[at + 2].toInt() and 0xff) shl 8) or (blob[at + 3].toInt() and 0xff)
        at += 4
        return value
    }
    val count = getInt() ?: return null
    if (count < 0) return null
    val spans = ArrayList<ByteArray>(minOf(count, 64))
    repeat(count) {
        val length = getInt() ?: return null
        if (length < 0 || at + length > blob.size) return null
        spans += blob.copyOfRange(at, at + length)
        at += length
    }
    return if (at == blob.size) spans else null
}

// ── raw lines ────────────────────────────────────────────────────────────────

/**
 * One content line of the raw text: [start]–[end] spans its physical lines **without** the final
 * line break, so replacing that range leaves every terminator where it was. [unfolded] is the
 * line as [parseVCard] sees it.
 */
private class LogicalLine(val start: Int, val end: Int, val unfolded: String)

/**
 * The raw text's content lines, folded the way [parseVCard]'s `unfold` folds them — RFC folding
 * (a line starting with a space or tab continues the previous one) and 2.1's quoted-printable soft
 * break — so a span found here is exactly one property there.
 */
private fun logicalLines(text: String): List<LogicalLine> {
    val result = mutableListOf<LogicalLine>()
    var start = -1
    var end = -1
    var unfolded = StringBuilder()
    var at = 0
    while (at <= text.length) {
        // One physical line: [at, lineEnd), then its terminator.
        var lineEnd = at
        while (lineEnd < text.length && text[lineEnd] != '\r' && text[lineEnd] != '\n') lineEnd++
        val line = text.substring(at, lineEnd)
        val next = when {
            lineEnd >= text.length -> text.length + 1
            text[lineEnd] == '\r' && lineEnd + 1 < text.length && text[lineEnd + 1] == '\n' -> lineEnd + 2
            else -> lineEnd + 1
        }
        val continuesFold = line.startsWith(" ") || line.startsWith("\t")
        val previousSoftBreak = start >= 0 && unfolded.endsWith("=") &&
            unfolded.toString().uppercase().contains("QUOTED-PRINTABLE")
        when {
            start >= 0 && continuesFold -> { unfolded.append(line.substring(1)); end = lineEnd }
            start >= 0 && previousSoftBreak -> { unfolded.setLength(unfolded.length - 1); unfolded.append(line); end = lineEnd }
            else -> {
                if (start >= 0) result += LogicalLine(start, end, unfolded.toString())
                start = at; end = lineEnd; unfolded = StringBuilder(line)
            }
        }
        at = next
    }
    if (start >= 0) result += LogicalLine(start, end, unfolded.toString())
    return result
}
