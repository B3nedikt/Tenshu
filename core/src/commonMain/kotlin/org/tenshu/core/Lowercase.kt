package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType

/**
 * Locale-invariant lowercase — a pure, synchronous [TString] → [TString]
 * transform (`"Report.PDF".lowercase()` → `"report.pdf"`), the sibling of
 * [substringAfterLast].
 *
 * Its reason to exist is **case-insensitive ordering expressed in the
 * selector**: `sortBy { name.lowercase() }` folds the key at the call site,
 * Kotlin's own idiom (`sortedBy { it.name.lowercase() }`) — instead of a hidden
 * case-insensitivity flag inside the engine comparator (the policy decision in
 * `docs/proposals/core-operation-dispatch.md` §4: case-sensitive engine
 * defaults, explicit opt-in at the call site).
 *
 * Dual-dispatches like [substringAfterLast]/[contains]: against a recording
 * [RequestObject] it appends a serialisable `Lowercase` op to the chain
 * (resolved on the proxy via [lowercaseLogic]); against real in-memory [Data]
 * it evaluates directly. No IO/terminal/stream traits, so it composes
 * mid-chain and resolves synchronously in a scan.
 */
fun TString.lowercase(): TString {
    return if (this is RequestObject) {
        findOperation<Lowercase>(Lowercase.ID).lowercase()
    } else {
        lowercaseLogic(this)
    }
}

interface Lowercase : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun lowercase(): TString

    companion object {
        const val ID = "tenshu/core/protocol/lowercase"

        val Schema = operation(
            id = ID,
            receiver = TString.Schema.toType(),
            params = { }
        )
    }
}
