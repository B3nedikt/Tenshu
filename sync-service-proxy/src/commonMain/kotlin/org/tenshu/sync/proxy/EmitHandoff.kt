package org.tenshu.sync.proxy

/**
 * Whether a save's blocks reached a durable outbound queue (`ios-shared-store-sqlite.md` B.20.15).
 * `SyncObserver` logs a save whose blocks were not queued, and the lane path keeps its journal on it.
 *
 * ⚠⚠ A type, not a `Boolean`, so that no path can answer with a constant: before S0b-4's round 7 the
 * lane branch answered `true` whatever its unopened-lane fallback had done, so a save made while a
 * sibling had locked the realm read as queued with nothing queued. Every producer of this
 * value is a function that did (or did not) make the durable write; a caller combines them with
 * [and] and hands the result on. [Queued] is a durable write that has happened — the pending-blocks
 * row, or the lane journal row a later checkpoint or replay seals — never an intention.
 */
internal sealed interface EmitHandoff {
    /** Durably queued: the pending-blocks queue or the lane journal holds it. */
    data object Queued : EmitHandoff

    /** Nothing durable was written for at least one block the save owes. */
    data class NotQueued(val why: String) : EmitHandoff

    val queued: Boolean get() = this === Queued

    /** Both halves queued; else the first that was not. */
    infix fun and(other: EmitHandoff): EmitHandoff = if (this is NotQueued) this else other
}
