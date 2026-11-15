package org.tenshu.contact.common

/**
 * A minimal vCard codec (`contact-service-identity-model.md` §5), written here rather than
 * taken as a dependency: ez-vcard is JVM-only, and platform-specific import code is needed
 * regardless.
 *
 * **Scope, deliberately narrow.** Reads 2.1 and 3.0, writes 3.0. It understands the handful
 * of properties the UI edits and treats everything else as opaque lines to carry.
 *
 * ⚠⚠ **The load-bearing property: [VCardProperty.value] holds the value still
 * escaped/encoded, exactly as it arrived.** Round-tripping is therefore byte-exact for every
 * property nobody edited, including `X-` extensions, `CATEGORIES` and vendor lines nothing
 * here models. Decoding happens only when a value is shown to a user, and re-encoding only
 * when they change one. This is what makes [mergeIntoVCard] safe.
 */

/** One `NAME;PARAM=v:value` line, unfolded, with the value left as it arrived. */
data class VCardProperty(
    /** The `item1` in `item1.TEL:…` — Apple's grouping, meaningless to us but preserved. */
    val group: String? = null,
    val name: String,
    val params: List<VCardParam> = emptyList(),
    /** ⚠ Still escaped/encoded. Use [decodeText] to display, [encodeText] to write. */
    val value: String,
) {
    /** Values of `TYPE=` params plus bare params (2.1 writes `TEL;CELL:` with no `TYPE=`). */
    val types: List<String>
        get() = params.filter { it.name == null || it.name.equals("TYPE", ignoreCase = true) }
            .map { it.value }

    fun hasParam(name: String, value: String): Boolean =
        params.any { it.name.equals(name, true) && it.value.equals(value, true) }
}

/** `TYPE=CELL` → `("TYPE", "CELL")`; a bare `CELL` → `(null, "CELL")`. */
data class VCardParam(val name: String?, val value: String)

/**
 * Parse a vCard payload into its properties, in document order.
 *
 * `BEGIN`/`END`/`VERSION` are dropped — they are re-emitted on write, and keeping them would
 * mean a merge could duplicate or contradict them. Everything else survives, including lines
 * this codec does not understand.
 *
 * Multi-card payloads: only the first card is parsed. Importing a multi-card `.vcf` splits
 * upstream (see `splitVCards`), because one [org.tenshu.contact.ContactSource] is one record.
 */
fun parseVCard(text: String): List<VCardProperty> {
    val result = mutableListOf<VCardProperty>()
    var started = false
    for (line in unfold(text)) {
        if (line.isBlank()) continue
        val upper = line.uppercase()
        if (upper.startsWith("BEGIN:VCARD")) {
            if (started) break // second card — not ours to read
            started = true
            continue
        }
        if (upper.startsWith("END:VCARD")) break
        if (upper.startsWith("VERSION:")) continue
        parseLine(line)?.let(result::add)
    }
    return result
}

/**
 * Serialize properties as a vCard 3.0 card.
 *
 * Long lines are folded at 75 octets with a leading space, per RFC 2426 — many parsers cope
 * without it, but an unfolded 4 KB `PHOTO` line is the kind of thing that breaks the strict
 * ones.
 */
fun serializeVCard(properties: List<VCardProperty>): String = buildString {
    append("BEGIN:VCARD").append(CRLF)
    append("VERSION:3.0").append(CRLF)
    properties.forEach { property ->
        append(fold(renderLine(property))).append(CRLF)
    }
    append("END:VCARD").append(CRLF)
}

/** Split a `.vcf` file that may hold several cards into one payload each. */
fun splitVCards(text: String): List<String> {
    val cards = mutableListOf<String>()
    val current = StringBuilder()
    var inCard = false
    for (line in text.lineSequence()) {
        val upper = line.trim().uppercase()
        if (upper.startsWith("BEGIN:VCARD")) {
            inCard = true
            current.clear()
        }
        if (inCard) current.append(line).append('\n')
        if (upper.startsWith("END:VCARD") && inCard) {
            cards += current.toString()
            inCard = false
        }
    }
    return cards
}

// ── text escaping ────────────────────────────────────────────────────────────

/**
 * Decode a stored value for display: vCard escapes, plus `QUOTED-PRINTABLE` when the
 * property says so (2.1 exporters use it heavily for anything non-ASCII).
 */
fun decodeText(property: VCardProperty): String {
    val raw = if (property.hasParam("ENCODING", "QUOTED-PRINTABLE")) {
        decodeQuotedPrintable(property.value)
    } else {
        property.value
    }
    return unescapeValue(raw)
}

/** Escape a user-entered value for storage. Always plain 3.0 escaping — never QP. */
fun encodeText(value: String): String = buildString {
    value.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            ',' -> append("\\,")
            ';' -> append("\\;")
            '\n' -> append("\\n")
            '\r' -> Unit
            else -> append(c)
        }
    }
}

/** Split a structured value (`N`, `ADR`) on unescaped `;`, keeping components escaped. */
fun splitComponents(value: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    value.forEach { c ->
        when {
            escaped -> { current.append('\\').append(c); escaped = false }
            c == '\\' -> escaped = true
            c == ';' -> { parts += current.toString(); current.clear() }
            else -> current.append(c)
        }
    }
    if (escaped) current.append('\\')
    parts += current.toString()
    return parts
}

private fun unescapeValue(value: String): String = buildString {
    var escaped = false
    value.forEach { c ->
        when {
            escaped -> {
                when (c) {
                    'n', 'N' -> append('\n')
                    else -> append(c)
                }
                escaped = false
            }
            c == '\\' -> escaped = true
            else -> append(c)
        }
    }
}

private fun decodeQuotedPrintable(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '=' && i + 2 < value.length -> {
                val hex = value.substring(i + 1, i + 3)
                val parsed = hex.toIntOrNull(16)
                if (parsed == null) {
                    bytes += c.code.toByte()
                    i++
                } else {
                    bytes += parsed.toByte()
                    i += 3
                }
            }
            // A trailing `=` is a soft line break; the unfolder already joined the lines.
            c == '=' -> i++
            else -> {
                bytes += c.code.toByte()
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

// ── line handling ────────────────────────────────────────────────────────────

private const val CRLF = "\r\n"
private const val FOLD_AT = 75

/**
 * Unfold continuation lines.
 *
 * Two rules, both needed by real exports: RFC folding (a line starting with a space or tab
 * continues the previous one), and 2.1's `QUOTED-PRINTABLE` soft break (a line ending in `=`
 * continues onto the next, with the `=` dropped).
 *
 * ⚠ Linear in the card's size, and must stay so: a big `PHOTO` is megabytes of base64 folded into
 * tens of thousands of lines. Re-concatenating (or re-scanning for the QP marker) the whole logical
 * line per continuation made a 2 MiB photo cost ~10¹¹ character operations — 15–30 s per import on
 * a desktop JVM. So each logical line is built in one [StringBuilder], and the marker is looked for
 * only in the text each continuation adds (plus an overlap, so a marker split by a fold is found).
 */
private fun unfold(text: String): List<String> {
    val lines = text.split("\r\n", "\n", "\r")
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var open = false
    // Whether the logical line in [current] names QUOTED-PRINTABLE anywhere. Appending never
    // removes the marker once present, so it only ever turns true.
    var quotedPrintable = false

    fun append(part: CharSequence) {
        val scanFrom = maxOf(0, current.length - QP_MARKER.length + 1)
        current.append(part)
        if (!quotedPrintable) {
            quotedPrintable = current.indexOf(QP_MARKER, scanFrom, ignoreCase = true) >= 0
        }
    }

    for (line in lines) {
        val continuesFold = line.startsWith(" ") || line.startsWith("\t")
        val previousSoftBreak = open && quotedPrintable && current.endsWith("=")
        when {
            open && continuesFold -> append(line.subSequence(1, line.length))
            open && previousSoftBreak -> {
                current.deleteAt(current.lastIndex)
                append(line)
            }
            else -> {
                if (open) result += current.toString()
                current.clear()
                quotedPrintable = false
                open = true
                append(line)
            }
        }
    }
    if (open) result += current.toString()
    return result
}

private const val QP_MARKER = "QUOTED-PRINTABLE"

private fun fold(line: String): String {
    if (line.length <= FOLD_AT) return line
    return buildString {
        append(line.substring(0, FOLD_AT))
        var index = FOLD_AT
        while (index < line.length) {
            val end = minOf(index + FOLD_AT - 1, line.length)
            append(CRLF).append(' ').append(line.substring(index, end))
            index = end
        }
    }
}

/** One unfolded content line as a [VCardProperty] — `null` when it is not one. */
internal fun parseVCardLine(line: String): VCardProperty? = parseLine(line)

private fun parseLine(line: String): VCardProperty? {
    val colon = indexOfValueColon(line)
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)

    val segments = splitParams(head)
    var nameSegment = segments.first()
    var group: String? = null
    val dot = nameSegment.indexOf('.')
    if (dot > 0) {
        group = nameSegment.substring(0, dot)
        nameSegment = nameSegment.substring(dot + 1)
    }
    if (nameSegment.isBlank()) return null

    val params = segments.drop(1).mapNotNull { segment ->
        if (segment.isBlank()) return@mapNotNull null
        val eq = segment.indexOf('=')
        if (eq < 0) {
            VCardParam(name = null, value = segment.trim())
        } else {
            VCardParam(
                name = segment.substring(0, eq).trim(),
                value = segment.substring(eq + 1).trim().removeSurrounding("\""),
            )
        }
    }
    return VCardProperty(group = group, name = nameSegment.trim(), params = params, value = value)
}

/** The first `:` that is not inside a quoted parameter value. */
private fun indexOfValueColon(line: String): Int {
    var quoted = false
    line.forEachIndexed { index, c ->
        when {
            c == '"' -> quoted = !quoted
            c == ':' && !quoted -> return index
        }
    }
    return -1
}

/** Split the pre-colon head on `;` that are not inside quotes. */
private fun splitParams(head: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    head.forEach { c ->
        when {
            c == '"' -> { quoted = !quoted; current.append(c) }
            c == ';' && !quoted -> { parts += current.toString(); current.clear() }
            else -> current.append(c)
        }
    }
    parts += current.toString()
    return parts
}

private fun renderLine(property: VCardProperty): String = buildString {
    property.group?.let { append(it).append('.') }
    append(property.name)
    property.params.forEach { param ->
        append(';')
        if (param.name != null) append(param.name).append('=')
        append(param.value)
    }
    append(':').append(property.value)
}
