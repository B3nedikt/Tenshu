package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.executor.findIdentityRoot
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.id
import org.tenshu.core.reference.Reference

/**
 * By-key short-circuit for `search { keyField equalTo <constant> }` on a raw
 * DB-backed array: entities are content-addressable by their key
 * (`StringDataId(identityRoot, keyValue)` → hash → point read), so a
 * key-equality search needs no full-array deserialisation + linear scan.
 * `navigateTo(parent)` and the per-row `getPreview`/`getRemoteFile` searches
 * are exactly this shape — each paid an O(array) scan per call.
 *
 * Returns the search result (`Maybe.Some`) **only on a top-level membership
 * hit**, where key uniqueness within an identity root makes the point read
 * scan-equivalent (two rows can't share a key — same key ⇒ same `DataId` ⇒
 * same row, so scan order can't observe a different match). Returns `null`
 * for *everything* else — non-key predicates, missing provider trait, member
 * hashes unavailable, and **membership miss** — so the caller falls back to
 * the full scan. The miss fallback is load-bearing: `SearchImplementation`
 * descends into nested lists, and a `navigateTo` target may only be findable
 * via that descent. Never fold a miss into `Maybe.Empty` here.
 *
 * The member-hash probe goes through [ExecutionContext.getArrayMemberHashes]
 * (resolver-routed), so the observed array still registers for stream
 * invalidation even though its contents are never read.
 */
internal fun trySearchByKey(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    searchOp: Operation,
    projection: Projection?,
): Maybe<Data>? {
    val elementSchemaId = reference.elementSchemaId ?: return null

    // The element type's key field, if it is string-keyed at all.
    val provider = executionContext.traitResolver.getTrait<DataIdProvider<out Data>>(
        typeId = elementSchemaId,
        traitId = DataIdProvider.ID,
    ) ?: return null
    val keyFieldRef = (provider.schema as? StringDataIdProviderSchema)
        ?.fieldRef?.get() ?: return null

    // Predicate must be exactly `keyField equalTo <string constant>`:
    // [Reference(keyField), EqualTo(OTHER = TString)].
    val predicate = (searchOp.params?.get(Search.Params.PREDICATE) as? TList<*>)
        ?.toList() ?: return null
    if (predicate.size != 2) return null
    val fieldRef = predicate[0] as? Reference ?: return null
    if (fieldRef.get() != keyFieldRef) return null
    val equalTo = predicate[1] as? Operation ?: return null
    if (equalTo.id != org.tenshu.core.EqualTo.ID) return null
    val constant = (equalTo.params?.get(org.tenshu.core.EqualTo.Params.OTHER) as? TString)
        ?.get() ?: return null

    val elementSchema = executionContext.schemaProvider.getSchema(elementSchemaId)
        ?: return null
    val dataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = findIdentityRoot(elementSchema),
        keyValue = constant,
    )

    // Membership probe — cheap (no deserialisation). null = hashes
    // unavailable (stub store) → unknown → scan. Miss → nested descent
    // might still find it → scan. NOTE: a deep-path search (e.g. a single
    // `search { path equalTo "/a/b/c" }` against the ROOT list) always
    // misses here — express navigation segment-wise (direct-child hops)
    // so every level hits; see `TenshuFilesRepository.navigateTo`.
    val memberHashes = executionContext.getArrayMemberHashes(reference) ?: return null
    if (dataId.getDataIdHash() !in memberHashes) return null

    val data = executionContext.database.getData(
        dataId = dataId,
        asEntity = reference.isEntity,
        projection = projection,
    ) ?: return null

    return Maybe.Some.create(executionContext.context, data)
}
