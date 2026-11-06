package app.tenshu.diagnostics

/**
 * The one wire format for a persisted [LogItem]: `timestampMs\tLEVEL\ttag\tcode\tmessage`.
 *
 * Tabs and newlines are stripped/escaped in the fields so a line always splits into exactly five
 * parts and one item is always one line.
 *
 * ⚠ Shared deliberately. This lived privately inside `FileDiagnosticSink`, and the web sink would
 * have been the second reader of a format only one of them could see — the shape that produced
 * `drive-web-parity.md` §1.2e. One definition, every sink.
 */
internal object DiagnosticLine {

    private const val SEP = "\t"

    fun encode(item: LogItem): String = listOf(
        item.timestampMs.toString(),
        item.level.name,
        item.tag.clean(),
        item.code?.clean().orEmpty(),
        item.message.clean(),
    ).joinToString(SEP)

    /** `null` for anything that is not a well-formed line — a corrupt entry is skipped, never fatal. */
    fun decode(line: String): LogItem? {
        val p = line.split(SEP, limit = 5)
        if (p.size < 5) return null
        val ts = p[0].toLongOrNull() ?: return null
        val level = runCatching { LogLevel.valueOf(p[1]) }.getOrNull() ?: return null
        return LogItem(ts, level, p[2], p[4].unescape(), p[3].ifEmpty { null })
    }

    private fun String.clean(): String = replace(SEP, " ").replace("\n", "\\n")
    private fun String.unescape(): String = replace("\\n", "\n")
}
