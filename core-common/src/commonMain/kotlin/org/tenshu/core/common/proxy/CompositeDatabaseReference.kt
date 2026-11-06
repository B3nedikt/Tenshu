package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.schema.CompositeSchema

/**
 * Reference to a DB-backed composite that has **not** been materialised yet.
 *
 * The dbEntryMapper ([org.tenshu.core.common.proxy.db.DbEntryMapper.deSerialize])
 * emits one of these for a composite-typed scalar field instead of recursing into
 * the child entry eagerly. Resolution happens on demand through the lock-guarded
 * [Database.getComposite] — the single-composite analogue of the established
 * [ArrayDatabaseReference] / [Database.getArrayData] seam.
 *
 * **Not** a [DatabaseReference]: unlike a list, a single composite carries no
 * lazy-chain semantics, so it does not participate in the array-style deferral
 * machinery (execution-stack accumulation in [evaluateCallstack]). Instead it is
 * resolved *eagerly on first use* — the moment an operation or reference is applied
 * to it, or the moment a projection ([SelectImplementation]) asks for the field.
 * The saving is purely that an unused deferred field is never read: a `select`
 * that does not name the field leaves the reference untouched and the child graph
 * unread (the over-fetch this fixes).
 *
 * Carries the stored [dataIdHash] directly (it is the `LongValue` already sitting
 * in the parent entry's field slot), so resolution needs no DataId re-derivation:
 * [org.tenshu.core.common.proxy.db.DatabaseImpl] looks the entry up by hash and
 * deserialises it. The declared [schema] travels along so receiver typing and
 * `schema.id` reads work without touching storage.
 *
 * Holds no materialised state — every resolution re-reads from storage — so it is
 * House-Rule-6 safe, exactly like [ArrayDatabaseReference].
 *
 * @param dataIdHash hash of the child composite's entry in the object store.
 * @param schema the *declared* composite schema of the field. A supertype of the
 *   stored concrete type is fine; resolution reads the concrete type from the entry.
 * @param isEntity whether the child should be deserialised as an entity.
 */
data class CompositeDatabaseReference(
    val dataIdHash: Long,
    override val schema: CompositeSchema,
    val isEntity: Boolean,
) : Data
