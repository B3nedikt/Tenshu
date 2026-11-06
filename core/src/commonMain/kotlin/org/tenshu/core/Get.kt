package org.tenshu.core

import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.maybeType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation


/**
 * The `Maybe<T>` terminal operation.
 *
 * ⚠ The name is **wire-visible** — `tenshu/core/maybe/get` is in the schema baseline and in every
 * peer's vocabulary — so it stayed `Get` when the Kotlin-side extension became
 * `Maybe.resolve()` (2026-09-01). Renaming it would be a schema change for a readability win.
 * The extension that drives it lives in `Resolve.kt`, beside the value terminal it now matches.
 */
interface Get<out T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun get(): RequestObject

    companion object {
        const val ID = "tenshu/core/maybe/get"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = maybeType("E"),
            typeParams = { type("E") },
            params = {},
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
