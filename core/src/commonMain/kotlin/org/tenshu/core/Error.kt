package org.tenshu.core

import org.tenshu.core.collection.list.toList
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

interface TError : Composite {

    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/error"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema
        )
    }
}

/**
 * Best-effort message extraction across the concrete `TError` subtypes
 * (`GenericError`, `NotFoundError`, `InvalidCallerError`, …). Every error
 * defined in `:core` carries its message under `"${id}/message"`, so we
 * read by convention rather than depending on each subtype's specific
 * accessor. Returns `null` when the error has no message field (e.g. a
 * marker-only error type or a sentinel).
 *
 * Used by the IPC failure logs to surface the *why* of an error in
 * addition to its type id — the type alone is rarely actionable.
 *
 * The schema's `isOptional` flag is consulted because `getOptionalField` /
 * `getField` are strict by design (`CompositeDto` — a wrong-arity accessor
 * throws). Every concrete `TError` declares `message` mandatory (`GenericError` in
 * `:core`, `AlreadyExistsError` in `:file-service`, …), so calling `getOptionalField`
 * blindly would throw on every IPC failure log, crashing
 * `RemoteBinaryTaskExecutor.roundTrip` (uncaught) and the `ProxyGateway.call`
 * catch path. Branching keeps the "best-effort, never throw" contract this
 * helper is meant to provide.
 */
fun TError.messageOrNull(): String? {
    val fieldId = "${id}/message"
    val schemaField = schema.values.toList().find { it.id.get() == fieldId } ?: return null
    return if (schemaField.isOptional.get()) {
        getOptionalField<TString>(fieldId)?.get()
    } else {
        getField<TString>(fieldId).get()
    }
}

/**
 * Throws when this result is a [TResult.Failure] — for write call sites where a dropped
 * Failure means **silent data loss**. The executor deliberately folds transport failures
 * ("Proxy not connected") into a Failure rather than throwing, and Kotlin emits no
 * unused-result warning, so a bare op call compiles as success (tech-debt "`TResult`-returning
 * ops are silently discardable"). Call sites that are *genuinely*
 * fire-and-forget should say so with a comment instead of a bare call.
 */
fun TResult<*>.orThrow(what: String) {
    if (this is TResult.Failure) {
        throw IllegalStateException("$what failed: ${this.error.messageOrNull() ?: this.error.schema.id}")
    }
}
