package org.tenshu.core

/**
 * Substring match, the evaluation logic behind [contains]. **Case-sensitive by
 * default** (matching Kotlin's `String.contains`); [ignoreCase] is the explicit
 * opt-in the `contains(other, ignoreCase = true)` overload rides in on.
 *
 * Defined only for a [TString] receiver against a string operand (either a
 * [TString] — the wire shape the proxy hands back — or a raw [String] for
 * direct in-memory calls). Any other pairing is `false`: `contains` is a text
 * predicate, so matching it against non-text values is meaningless and we
 * don't guess.
 */
fun containsLogic(receiver: Data, other: Any, ignoreCase: Boolean = false): TBoolean {
    if (receiver !is TString) return false.toTBoolean()

    val needle = when (other) {
        is TString -> other.get()
        is String -> other
        else -> return false.toTBoolean()
    }

    return receiver.get().contains(needle, ignoreCase = ignoreCase).toTBoolean()
}
