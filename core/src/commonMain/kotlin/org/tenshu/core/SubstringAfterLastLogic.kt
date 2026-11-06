package org.tenshu.core

/**
 * Evaluation logic behind [substringAfterLast]. Defined only for a [TString]
 * receiver; the separator may be a [TString] (the wire shape the proxy hands back)
 * or a raw [String] (direct in-memory calls). A non-string receiver errors — the
 * op is only ever dispatched against a string, and silently returning something
 * would hide a wiring bug.
 */
fun substringAfterLastLogic(receiver: Data, separator: Any): TString {
    val value = (receiver as? TString)?.get()
        ?: error("substringAfterLast receiver must be a TString, was ${receiver::class.simpleName}")

    val sep = when (separator) {
        is TString -> separator.get()
        is String -> separator
        else -> error("substringAfterLast separator must be a String, was ${separator::class.simpleName}")
    }

    return value.substringAfterLast(sep).toTString()
}
