package org.tenshu.core

/**
 * Evaluation logic behind [lowercase]. Defined only for a [TString] receiver; a
 * non-string receiver errors — the op is only ever dispatched against a string,
 * and silently returning something would hide a wiring bug. Uses Kotlin's
 * locale-invariant `String.lowercase()` (no Turkish-İ surprises across devices).
 */
fun lowercaseLogic(receiver: Data): TString {
    val value = (receiver as? TString)?.get()
        ?: error("lowercase receiver must be a TString, was ${receiver::class.simpleName}")
    return value.lowercase().toTString()
}
