package org.tenshu.calendar.common.ics

/**
 * The iCalendar content-line layer (RFC 5545 §3.1) — CA-6.
 *
 * ⭐ Deliberately a near-twin of `:contact-service-common`'s `VCard.kt`, because
 * vCard and iCalendar share this layer exactly: `NAME;PARAM=v:value`, folding
 * with a leading space, and the same backslash escapes. Written rather than
 * depended on for the same reason the maintainer settled the vCard question in
 * 2026-06-11 — every JVM library here is JVM-only, and platform import code is
 * needed regardless.
 *
 * ⚠⚠ **[IcsProperty.value] holds the value still escaped, exactly as it
 * arrived.** That is what makes a round trip byte-exact for every property
 * nobody edited — `X-` extensions, `ATTACH`, `ORGANIZER`, alarm shapes we do not
 * model. Decoding happens only when a value becomes a native field, re-encoding
 * only when we write one back. It is the mechanism behind `EventSource`'s
 * promise: *native fields win, unknown properties survive*.
 *
 * ⚠ No `QUOTED-PRINTABLE`. That is vCard 2.1's, and iCalendar has never had it —
 * carrying the decoder over would be dead code that looks load-bearing.
 */
data class IcsProperty(
    val name: String,
    val params: List<IcsParam> = emptyList(),
    /** ⚠ Still escaped. [decodeText] to read, [encodeText] to write. */
    val value: String,
) {
    /** The first value of [name], case-insensitively; `null` when absent. */
    fun param(name: String): String? =
        params.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /** ⚠ `VALUE=DATE` is how an all-day `DTSTART` announces itself. */
    val isDateOnly: Boolean get() = param("VALUE").equals("DATE", ignoreCase = true)

    val tzid: String? get() = param("TZID")?.takeIf { it.isNotBlank() }
}

/** `TZID=Europe/Berlin` → `("TZID", "Europe/Berlin")`. */
data class IcsParam(val name: String, val value: String)

/**
 * One `BEGIN:X` … `END:X` block, with its properties and nested components.
 *
 * ⚠ Nesting is real and matters: a `VEVENT` holds `VALARM`s, and a `VCALENDAR`
 * holds `VTIMEZONE`s we do not interpret but must not lose. A flat property list
 * would fold an alarm's `TRIGGER` in beside the event's own properties, where it
 * would look like the event had one.
 */
data class IcsComponent(
    val name: String,
    val properties: List<IcsProperty> = emptyList(),
    val components: List<IcsComponent> = emptyList(),
) {
    fun first(propertyName: String): IcsProperty? =
        properties.firstOrNull { it.name.equals(propertyName, ignoreCase = true) }

    fun all(propertyName: String): List<IcsProperty> =
        properties.filter { it.name.equals(propertyName, ignoreCase = true) }

    fun children(componentName: String): List<IcsComponent> =
        components.filter { it.name.equals(componentName, ignoreCase = true) }
}

/**
 * Parses an `.ics` payload into its component tree.
 *
 * ⚠ Tolerant by design, because real exports are. An unterminated component
 * closes at end of input, a stray `END` without a matching `BEGIN` is ignored,
 * and a line with no colon is skipped — a single malformed line must never cost
 * the user the rest of their calendar. ⚠ Returns a synthetic root, so a payload
 * with no `BEGIN:VCALENDAR` at all (some exporters emit bare `VEVENT`s) still
 * yields its events.
 */
fun parseIcs(text: String): IcsComponent {
    val root = MutableComponent("ROOT")
    val stack = ArrayDeque<MutableComponent>()
    stack.addLast(root)

    for (line in unfoldIcs(text)) {
        if (line.isBlank()) continue
        val property = parseIcsLine(line) ?: continue
        when {
            property.name.equals("BEGIN", ignoreCase = true) -> {
                val child = MutableComponent(property.value.trim().uppercase())
                stack.last().components += child
                stack.addLast(child)
            }
            property.name.equals("END", ignoreCase = true) -> {
                // ⚠ Never pop the root: a stray END would otherwise empty the
                // stack and every following line would land nowhere.
                if (stack.size > 1) stack.removeLast()
            }
            else -> stack.last().properties += property
        }
    }
    return root.toComponent()
}

/** Serialises a component tree, folded and CRLF-terminated per RFC 5545. */
fun serializeIcs(component: IcsComponent): String = buildString {
    appendComponent(component)
}

private fun StringBuilder.appendComponent(component: IcsComponent) {
    append(foldIcs("BEGIN:${component.name}")).append(CRLF)
    component.properties.forEach { append(foldIcs(renderIcsLine(it))).append(CRLF) }
    component.components.forEach { appendComponent(it) }
    append(foldIcs("END:${component.name}")).append(CRLF)
}

// ── text escaping ────────────────────────────────────────────────────────────

/**
 * Decodes a value for use as a native field.
 *
 * ⚠ `\N` as well as `\n`: RFC 5545 §3.3.11 allows either for a newline, and
 * Outlook emits the capital.
 */
fun decodeText(value: String): String = buildString {
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

/**
 * Escapes a native field for writing.
 *
 * ⚠ `;` and `,` are escaped even in values that are not structured. They are
 * separators wherever a property *is* structured, and a parser that guesses per
 * property is how a location containing a comma becomes two locations.
 */
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

// ── line handling ────────────────────────────────────────────────────────────

internal const val CRLF = "\r\n"
private const val FOLD_AT = 75

/**
 * Unfolds continuation lines.
 *
 * ⚠ A continuation begins with **a space or a tab**, and the single leading
 * whitespace character is what gets dropped — not all of it. A line legitimately
 * continuing with an indented value would otherwise silently lose its
 * indentation.
 */
internal fun unfoldIcs(text: String): List<String> {
    val result = mutableListOf<String>()
    for (line in text.split("\r\n", "\n", "\r")) {
        if (result.isNotEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
            result[result.lastIndex] = result.last() + line.substring(1)
        } else {
            result += line
        }
    }
    return result
}

/**
 * Folds a line at 75 characters with a leading space.
 *
 * ⚠ RFC 5545 counts **octets**, not characters, and this counts characters —
 * so a line of multi-byte text folds later than the letter of the spec. Chosen
 * deliberately: splitting on an octet boundary can cut a UTF-8 sequence in half,
 * which corrupts the value outright, whereas folding a little late is something
 * every parser in existence copes with. The vCard codec made the same call.
 */
internal fun foldIcs(line: String): String {
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

internal fun parseIcsLine(line: String): IcsProperty? {
    val colon = indexOfValueColon(line)
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)

    val segments = splitParams(head)
    val name = segments.first().trim()
    if (name.isEmpty()) return null

    val params = segments.drop(1).mapNotNull { segment ->
        if (segment.isBlank()) return@mapNotNull null
        val eq = segment.indexOf('=')
        // ⚠ A parameter with no `=` is malformed; kept with an empty name rather
        // than dropped, so it survives a round trip of a file we did not write.
        if (eq < 0) IcsParam(name = "", value = segment.trim())
        else IcsParam(
            name = segment.substring(0, eq).trim(),
            value = segment.substring(eq + 1).trim().removeSurrounding("\""),
        )
    }
    return IcsProperty(name = name, params = params, value = value)
}

internal fun renderIcsLine(property: IcsProperty): String = buildString {
    append(property.name)
    property.params.forEach { param ->
        append(';')
        if (param.name.isNotEmpty()) append(param.name).append('=')
        // ⚠ Re-quote a value containing a separator, or the line cannot be
        // re-parsed — `TZID="GMT+01:00"` is a real Outlook parameter.
        val needsQuotes = param.value.any { it == ':' || it == ';' || it == ',' }
        if (needsQuotes) append('"').append(param.value).append('"') else append(param.value)
    }
    append(':').append(property.value)
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

/** Splits the pre-colon head on `;` that are not inside quotes. */
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

private class MutableComponent(val name: String) {
    val properties = mutableListOf<IcsProperty>()
    val components = mutableListOf<MutableComponent>()

    fun toComponent(): IcsComponent = IcsComponent(
        name = name,
        properties = properties.toList(),
        components = components.map { it.toComponent() },
    )
}
