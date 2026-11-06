package org.tenshu.core.common.proxy.db

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TConstant
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.CompositeDatabaseReference
import org.tenshu.core.common.proxy.Projection
import org.tenshu.core.common.proxy.arrayReferenceOrNull
import org.tenshu.core.common.proxy.executePure
import org.tenshu.core.common.util.isCompositeField
import org.tenshu.core.common.util.isConstantField
import org.tenshu.core.common.util.isGenericDataField
import org.tenshu.core.common.util.isParameterized
import org.tenshu.core.common.util.isValueField
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.get
import org.tenshu.core.id
import org.tenshu.core.number.TInteger
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.params
import org.tenshu.core.trait.TraitResolver

class DbEntryMapper(
    private val context: TContext,
    private val schemaProvider: SchemaProvider,
    private val dataFactory: DataFactory,
    private val traitResolver: TraitResolver
) {

    fun deSerialize(
        dbEntry: ObjectStore.DbEntry,
        /**
         * Read the row as an [Entity] (fields + properties) rather than a plain [Composite].
         *
         * ⚠ Forced on when [projection] keeps properties: a composite has no properties map to
         * keep them ON, so a kept property is only reachable on an entity. The ordered-window
         * fusion relies on this to read a value-property sort key over a composite-typed array
         * (`root.files`) in one pass instead of re-reading every row as an entity
         * (`drive-sorted-folder-paging.md` §3 B2). Every other caller passes no kept
         * properties and sees exactly the shape it asked for.
         */
        asEntity: Boolean = false,
        dataId: DataId? = null,
        /**
         * Read-time projection — a *tree*. When non-null the read materialises only what a
         * downstream `select` will keep: all composite relations are skipped, and
         * composite scalar fields outside [Projection.keepFields] are skipped when
         * [Projection.skipUnprojectedComposites]. A *kept* composite field is recursed with
         * its [Projection.children] entry (absent ⇒ `null` ⇒ that child loads fully), so
         * pruning applies at every level instead of resolving in depth below the top. See
         * [Projection] for why the skips are safe.
         */
        projection: Projection? = null,
        /**
         * Resolves a **member of a nested list field**, and may return `null` to skip it — see
         * [deserializeArray]'s parameter of the same name. Threaded through every recursion so the
         * tolerance a top-level read opts into reaches a composite's list fields too.
         *
         * ⚠ Deliberately NOT used for anything else. A nested *composite* or a `genericData` child
         * still resolves through the strict [getEntry]: a hole there is a malformed entity, which is
         * a different risk from a list that is simply short.
         *
         * ⚠ Declared BEFORE [getEntry] on purpose: [getEntry] must stay the last parameter, because
         * several callers pass it as a trailing lambda, which this one would silently capture if
         * it came after.
         */
        getMemberEntry: ((dataIdHash: Long) -> ObjectStore.DbEntry?)? = null,
        getEntry: (dataIdHash: Long) -> ObjectStore.DbEntry,
    ): Data {
        val memberEntry: (Long) -> ObjectStore.DbEntry? = getMemberEntry ?: getEntry
        val asEntity = asEntity || (projection?.keepProperties?.isNotEmpty() == true)
        val type = dbEntry.type
        val schema = schemaProvider.getSchema(type) as CompositeSchema
        val fields = dbEntry.fields

        val schemaValues = schema.values.toList()
        return if (fields.size == 1 && schemaValues.isNotEmpty() && schemaValues.first().isCompositeField) {
            dataFactory.createValue(type, fields[0])
        } else {

            val deserializedFields: Map<FieldReference, Data?> =
                schemaValues.mapIndexed { index: Int, field: Field ->
                    // A row stored before an *optional* field was appended to its
                    // schema has fewer stored slots than the schema declares —
                    // read the missing tail as null instead of failing the whole
                    // row (e.g. `FileAddedNotification.count`, added by
                    // notification-burst-aggregation §2.1). Mandatory fields keep
                    // the hard check: a short row there is genuine corruption.
                    if (index > fields.lastIndex && field.isOptional.get()) {
                        return@mapIndexed field.id to null
                    }
                    checkCorrectFieldCount(index, fields, schema, type)

                    val value = if (fields[index] is ObjectStore.DbValue.NullValue) {
                        null
                    } else {
                        when {
                            field.isGenericDataField -> {
                                // Self-describing slot (genericData field): the
                                // GenericValue carries the concrete type id, so we
                                // recover a primitive inline or a composite by
                                // reference without guessing from the schema.
                                val slot = fields[index] as? ObjectStore.DbValue.GenericValue
                                    ?: error("genericData field ${field.id.get()} expected a GenericValue")
                                when (val valueSchema = schemaProvider.getSchema(slot.typeId)) {
                                    is ValueSchema -> dataFactory.createValue(slot.typeId, slot.payload.value)
                                    // A constant is written as a StringValue of its schema id
                                    // (see writeValuesToDb's `is TConstant` branch + the
                                    // property-relation ConstantSchema branch below), so recover
                                    // it by that id rather than the composite-by-reference path.
                                    is ConstantSchema -> dataFactory.createConstant(
                                        (slot.payload as ObjectStore.DbValue.StringValue).value
                                    )
                                    // ⚠ `null` (an unregistered type id) must NOT fall in with the
                                    // composite case: a genericData slot is self-describing, so a
                                    // type this build does not know is a *routine* thing to receive
                                    // from a peer — and its payload is then just as likely to be an
                                    // inline primitive as a child-row reference. Folded in here,
                                    // that payload would be cast to LongValue and throw a bare
                                    // ClassCastException naming neither the field nor the type.
                                    null -> error(
                                        "genericData field '${field.id.get()}' carries type id " +
                                            "'${slot.typeId}', which is not registered in this " +
                                            "build's schema; payload is " +
                                            "${slot.payload::class.simpleName}"
                                    )
                                    else -> deSerialize(
                                        dbEntry = getEntry(
                                            slot.payload.asChildRowRef(
                                                what = "genericData field '${field.id.get()}'",
                                                typeId = slot.typeId,
                                            )
                                        ),
                                        asEntity = valueSchema.isSubtypeOf(Entity.ID),
                                        projection = projection?.children?.get(field.id.get()),
                                        getEntry = getEntry,
                                        getMemberEntry = memberEntry,
                                    )
                                }
                            }

                            field.isValueField -> {
                                if (field.type is ReferenceSchema) {
                                    schemaProvider.getReference(field.type.id) as Data
                                } else {
                                    dataFactory.createValue(
                                        type = field.type.baseSchema?.id?.get()
                                            ?: error("Field $index has no base schema"),
                                        value = fields[index].value
                                    )
                                }
                            }

                            field.isParameterized || type == TList.ID -> {
                                val arrayDataIdHash = fields[index].asChildRowRef(
                                    what = "list field '${field.id.get()}' of $type",
                                )

                                val itemSchema =
                                    field.type.params.toList().firstOrNull()?.type?.baseSchema
                                val itemAsEntity = itemSchema?.isSubtypeOf(Entity.ID) ?: false

                                if (dataId is DataId.EntityDataId && type != TList.ID) {
                                    ArrayDatabaseReference(
                                        arrayId = DataId.EntityFieldDataId.create(
                                            context = context,
                                            type = TList.ID,
                                            parentEntity = dataId,
                                            fieldRef = field.id.get()
                                        ),
                                        isEntity = itemAsEntity,
                                        elementSchemaId = itemSchema?.id?.get()
                                    )
                                } else {
                                    dataFactory.createList<Data>(
                                        deserializeArray(
                                            arrayDataIdHash,
                                            getEntry,
                                            itemAsEntity,
                                            getMemberEntry = memberEntry,
                                        ).toList()
                                    )
                                }
                            }

                            field.isConstantField -> {
                                // A constant-typed field (e.g. `composite(id, type = X.Schema)`
                                // where X.Schema is a ConstantSchema). ConstantSchema is NOT a
                                // CompositeSchema, so this must be handled before falling through
                                // to the `else` error. The write side stores it as a StringValue
                                // of the constant's schema id (writeValuesToDb's `is TConstant`
                                // branch), matching the property-relation read below.
                                dataFactory.createConstant(
                                    (fields[index] as ObjectStore.DbValue.StringValue).value
                                )
                            }

                            field.isCompositeField -> {
                                val compositeDataIdHash = fields[index].asChildRowRef(
                                    what = "composite field '${field.id.get()}' of $type",
                                )

                                val fieldAsEntity =
                                    field.type.baseSchema?.isSubtypeOf(Entity.ID) ?: false

                                if (projection != null &&
                                    projection.skipUnprojectedComposites &&
                                    field.id.get() !in projection.keepFields
                                ) {
                                    // Projection skip: a downstream `select` drops
                                    // this composite scalar field, so don't load it
                                    // (null ⇒ omitted from the entity). Safe only
                                    // under `skipUnprojectedComposites` — a direct
                                    // `<array>.select` with no intervening
                                    // field-reading op. See [Projection].
                                    null
                                } else {
                                    deSerialize(
                                        dbEntry = getEntry(compositeDataIdHash),
                                        asEntity = fieldAsEntity,
                                        // Tree projection: descend with THIS field's child
                                        // projection instead of always resolving in depth.
                                        // Absent child ⇒ null ⇒ full load.
                                        // See [Projection] / core-nested-projection.md.
                                        projection = projection?.children?.get(field.id.get()),
                                        getEntry = getEntry,
                                        getMemberEntry = memberEntry,
                                    )
                                }
                            }

                            else -> error("Unknown field type")
                        }
                    }

                    field.id to value
                }.toMap()

            val relations: Map<String, Data> = if (projection != null) {
                // Projection present ⇒ a downstream `select` re-resolves any
                // relation it keeps via `resolveProperty` (a fresh re-load) and
                // drops the rest — and no engine path reads relations off this
                // loaded entity's `properties` map. So skip the eager relation
                // walk entirely: it is the bulk of a metadata listing's
                // over-fetch. See [Projection].
                //
                // Exception: [Projection.keepProperties] — the ordered-window
                // fusion DOES read its sort key off the loaded row (once per
                // element; a per-row `resolveProperty` re-load would defeat the
                // point). Only cheap scalar shapes are honoured: a composite
                // relation never appears here (the fusion rejects composite keys
                // before the read), and an unknown shape is simply not kept.
                if (projection.keepProperties.isEmpty()) {
                    emptyMap()
                } else {
                    dbEntry.properties
                        .filterKeys { it in projection.keepProperties }
                        .entries
                        .mapNotNull { relationEntry ->
                            val value: Data? = when (
                                val relationSchema = schemaProvider.getSchema(relationEntry.key)
                            ) {
                                is ValueSchema -> dataFactory.createValue(
                                    type = relationSchema.id.get(),
                                    value = relationEntry.value.value,
                                )

                                is ConstantSchema -> dataFactory.createConstant(
                                    (relationEntry.value as ObjectStore.DbValue.StringValue).value,
                                )

                                else -> null
                            }
                            value?.let { relationEntry.key to it }
                        }.toMap()
                }
            } else {
                dbEntry.properties
                    .mapNotNull { relationEntry ->
                        val relationSchema = schemaProvider.getSchema(relationEntry.key)

                        val relationValue: Data? = when (relationSchema) {
                            is ValueSchema -> {
                                dataFactory.createValue(
                                    type = relationSchema.id.get(),
                                    value = relationEntry.value.value
                                )
                            }

                            is ConstantSchema -> {
                                val type =
                                    (relationEntry.value as ObjectStore.DbValue.StringValue).value
                                dataFactory.createConstant(type)
                            }

                            is CompositeSchema -> {
                                // ⚠⚠ A relation is stored as a child-row reference, but a row
                                // written while this same property id was a `valueProperty` holds
                                // the value INLINE — and `RelationSchema : CompositeSchema`, so
                                // that stale slot lands here. Dropping it is the only available
                                // answer (an inline value cannot become a child row at read time)
                                // and the correct one: the property resolves on demand, so the
                                // leftover eager copy is superseded garbage.
                                //
                                // ⚠ Not a theoretical case — `File.Thumbnail` and
                                // `File.ThumbnailLarge` moved exactly this way. Casting instead
                                // throws `ClassCastException: ByteArrayValue cannot be cast to
                                // LongValue` on every read of an existing File row, which on the
                                // ingest path leaves the pull cursor unadvanced ⇒ sync wedges
                                // permanently. ⚠⚠ And for a Long-shaped property it does not throw
                                // at all: the stored *value* is dereferenced as a row id. See
                                // [ValueToDynamicPropertyMigrationTest].
                                val compositeDataIdHash =
                                    (relationEntry.value as? ObjectStore.DbValue.LongValue)?.value

                                compositeDataIdHash?.let { hash ->
                                    deSerialize(
                                        dbEntry = getEntry(hash),
                                        getEntry = getEntry,
                                        getMemberEntry = memberEntry,
                                    )
                                }
                            }

                            else -> error("Unknown relation schema type ${relationSchema?.id}")
                        }

                        relationValue?.let { relationEntry.key to it }
                    }.toMap()
            }

            if (asEntity) {
                dataFactory.createEntity<Composite>(
                    type = type,
                    fields = deserializedFields.filterValues { it != null }
                        .mapKeys { it.key.get() } as Map<String, Data>,
                    relations = relations
                )
            } else {
                dataFactory.createComposite<Composite>(
                    type = type,
                    fields = deserializedFields.values.toList()
                )
            }
        }
    }

    fun serialize(
        data: Data,
        fallbackDataId: DataId? = null,
        saveEntry: (entry: ObjectStore.DbEntry) -> Unit,
        /**
         * Optional read-side lookup for the entry being overwritten.
         *
         * When provided, parameterised list fields (e.g. `Folder.files`) are
         * written *additively* — the new refs are unioned with whatever the
         * caller's array DataId already holds. This preserves entries added
         * by side-channel writers (notably `ParentFilesRelationMaintainer`
         * via `Database.addToArray`) when an incoming sync block carries a
         * `Folder` whose embedded `files` list is shorter than the current
         * local array.
         *
         * Without this only-grow invariant, the joiner can lose a folder/file
         * if a stale `Folder` SyncBlock is merged after an unrelated add:
         * `FolderMerger.merge` returns `Folder.create(files = mergedList)`,
         * `SyncProcessor.resolveConflictAndSave` calls `saveData(merged)`, and
         * the resulting `writeValuesToDb` overwrites the array DataId with
         * exactly `mergedList`. Any concurrent `addToArray` from the
         * maintainer running on a different thread races with this overwrite.
         *
         * Removals still propagate via `deleteData` →
         * `ParentFilesRelationMaintainer.onDataDeleted` → `removeFromArray`,
         * which is the documented path for shrinking a folder's children
         * (`DeleteFile`). Sync emits a tombstone separately so deletes
         * converge across realm devices.
         *
         * Defaults to `null`, a plain overwrite, for call sites that don't
         * need merge semantics (none in production; tests may use the
         * simple-overwrite shape).
         */
        getEntry: ((dataIdHash: Long) -> ObjectStore.DbEntry?)? = null
    ) {
        val type = if (data is Entity<*>) data.type.get() else data.id
        val schema = schemaProvider.getSchema(type) as? CompositeSchema
            ?: error("Schema not found for $type")

        val fieldsList = schema.values.toList()

        val dataId = try {
            getDataId(data)
        } catch (e: Exception) {
            fallbackDataId
        } ?: error("No DataIdProvider found for ${data.id} and no fallback provided.")

        val values = fieldsList.map { field ->
            val fieldId = field.id.get()
            val fieldValue = if (data is Entity<*>) {
                data.getFieldValue<Data?>(fieldId)
            } else {
                val composite = data as Composite
                if (field.isOptional.get()) {
                    composite.getOptionalField(fieldId)
                } else {
                    composite.getField(fieldId)
                }
            }

            if (fieldValue == null) {
                ObjectStore.DbValue.NullValue
            } else {
                val payload = anyValueToDbValue(
                    writeValuesToDb(
                        data = data,
                        fieldId = fieldId,
                        fieldValue = fieldValue,
                        saveEntry = saveEntry,
                        getEntry = getEntry
                    )
                )
                if (field.isGenericDataField) {
                    // Tag the slot with the concrete type so it reads back
                    // unambiguously. `payload` is an inline DbValue for a
                    // primitive, or a LongValue reference for a composite.
                    val typeId = if (fieldValue is Entity<*>) fieldValue.type.get() else fieldValue.id
                    ObjectStore.DbValue.GenericValue(typeId = typeId, payload = payload)
                } else {
                    payload
                }
            }
        }

        var relations = emptyMap<String, ObjectStore.DbValue>()
        if (data is Entity<*>) {
            relations = data.properties
                .mapKeys { it.key.get() }
                .mapValues { relationEntry ->
                    val dbValue = writeValuesToDb(
                        data = data,
                        fieldId = relationEntry.key,
                        fieldValue = relationEntry.value,
                        saveEntry = saveEntry,
                        getEntry = getEntry
                    )
                    anyValueToDbValue(dbValue)
                }
        }

        saveEntry(
            ObjectStore.DbEntry(
                type = type,
                dataIdHash = dataId.getDataIdHash(),
                fields = values,
                properties = relations
            )
        )
    }

    fun serialize(
        data: Data,
        saveEntry: (entry: ObjectStore.DbEntry) -> Unit
    ) {
        serialize(data, null, saveEntry, getEntry = null)
    }

    fun deserializeArray(
        arrayDataIdHash: Long,
        getEntry: (dataIdHash: Long) -> ObjectStore.DbEntry,
        asEntity: Boolean = false,
        getDataId: (hash: Long) -> DataId? = { null },
        /**
         * Projection applied to each *element* — the elements of a top-level list
         * are the entities a downstream `select` projects. Defaults to `null`
         * (full element load) so nested/eager array reads are unchanged.
         */
        projection: Projection? = null,
        /**
         * Member window in insertion order: only entries in
         * `[offset, offset + limit)` are deserialised. Clamped — an offset past
         * the end yields an empty sequence. Defaults cover the full array.
         */
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        /**
         * Resolve one top-level **member** of this array. Returning `null` **skips** that member —
         * the sequence is shorter, and nothing throws.
         *
         * ⚠ Defaults to [getEntry], i.e. the strict behaviour: a caller that does not opt in keeps
         * throwing `Entry not found` on a dangling member.
         *
         * ⚠⚠ The *nested* array read (a composite's list field) forwards this resolver too.
         * Dangling members do occur there: one in a subfolder's `tenshu/file/folder/files`, read
         * strictly, throws `Entry not found` and kills the **root** folder listing, not merely that
         * subfolder — while the identical dangling row at top level is skipped and reported. A
         * one-file hole is not a reason to make a whole tree unreadable, and the failure would be
         * unrecoverable from the UI: the stream re-evaluates on every write and re-throws, so it
         * renders as an endlessly reloading error.
         *
         * ⚠ Still deliberately separate from [getEntry]: this resolves **list members only**, at any
         * depth. A nested *composite* or `genericData` child keeps resolving through the strict
         * [getEntry], because skipping must mean "this member's row is gone", never "some grandchild
         * of it is" — a hole inside an entity is a malformed entity, not a shorter list.
         */
        getMemberEntry: (dataIdHash: Long) -> ObjectStore.DbEntry? = getEntry,
    ): Sequence<Data> {
        val entry = getEntry(arrayDataIdHash)
        val fields = if (offset == 0 && limit == Int.MAX_VALUE) {
            entry.fields
        } else {
            val from = offset.coerceIn(0, entry.fields.size)
            // Long arithmetic: offset + limit overflows Int for limit = MAX_VALUE.
            val to = (from.toLong() + limit.toLong()).coerceAtMost(entry.fields.size.toLong()).toInt()
            entry.fields.subList(from, to)
        }
        return fields.asSequence().mapNotNull { hashId ->
            val hash = (hashId as ObjectStore.DbValue.LongValue).value
            val arrayEntry = getMemberEntry(hash) ?: return@mapNotNull null

            deSerialize(
                dbEntry = arrayEntry,
                asEntity = asEntity,
                getEntry = getEntry,
                dataId = getDataId(hash),
                projection = projection,
                getMemberEntry = getMemberEntry,
            )
        }
    }

    private fun writeValuesToDb(
        data: Data,
        fieldId: String,
        fieldValue: Data,
        saveEntry: (ObjectStore.DbEntry) -> Unit,
        getEntry: ((dataIdHash: Long) -> ObjectStore.DbEntry?)? = null
    ): Any {
        // A loaded composite's list field arrives as `ArrayDatabaseReference` (or
        // a `DatabaseReference` wrapping one) — the array entry is already persisted
        // at `arrayId`'s hash, with whatever members `Database.addToArray` /
        // `removeFromArray` have written to it. Keep the reference: record the hash
        // as the field value so re-saving the parent composite doesn't disturb the
        // existing array.
        //
        // Without this branch, serialising a composite whose list field was loaded
        // from disk (e.g. a nested `Folder` inside a `FolderMerger.merge(…)` output,
        // where each child `Folder` came back from the DB with
        // `FILES = ArrayDatabaseReference`) throws "Unsupported data type" — and the
        // partial recursion before the throw can leave the parent composite's array
        // entry stale, manifesting as an "empty root list" after a multi-device
        // folder sync round-trip.
        //
        // The detection goes through `arrayReferenceOrNull` so the
        // `DatabaseReference + ArrayOperationContext` case (no producer, but the
        // resolver handles it) cannot crash here either.
        arrayReferenceOrNull(fieldValue)?.let { return it.arrayId.getDataIdHash() }

        // A composite scalar field loaded lazily arrives as a
        // `CompositeDatabaseReference` (see `deSerialize`'s composite-field arm).
        // The child entry is already persisted at `dataIdHash`; re-saving the
        // parent must keep pointing at it without materialising or rewriting it.
        // Same shape as the `arrayReferenceOrNull` guard above, for the
        // single-composite case.
        if (fieldValue is CompositeDatabaseReference) return fieldValue.dataIdHash

        return when (fieldValue) {
            is TListDto<*> -> {
                val parentDataId = try {
                    getDataId(data)
                } catch (e: Exception) {
                    error("Cannot determine parent ID for list $fieldId")
                }

                // Propagate `getEntry` into the recursive save so the *child*
                // composite's `writeValuesToDb` sees the existing entries on any
                // of its own nested-array fields and applies the same only-grow
                // merge. Without this, a fresh child whose own list field is an
                // empty `TListDto` would erase any side-channel `addToArray`
                // entries on its (parent-entity, fieldRef) array — same race as
                // the outer-level shrink, one level deeper.
                // **Create-only.** A list member resolves through `getDataId`, i.e. it has a
                // `DataIdProvider` and therefore an identity — and an entity of its own is a
                // sync unit of its own: it gets its own blocks, carrying its own HLC and its
                // own `Entity.properties`. A member reached *through a parent* has none of
                // that. A `SyncBlock` payload deserialises to plain composites, so writing one
                // over an existing row is an unversioned overwrite that silently drops
                // everything the row knew and the parent didn't — every value property
                // (`File.ContentId`, `contentHash`, `size`, the timestamps, the synced
                // thumbnails), because `serialize` only carries properties for an `Entity<*>`.
                //
                // Concretely, without this guard: ingesting a `Folder` block (`Folder.FILES` is
                // `list(of = File.Schema)`) blanks every file row inside it. Recovery is
                // incidental — a *later* write happening to carry the full entity — so where
                // none follows, the loss is permanent on that device. The visible symptom is
                // `ShareFolderIntoRealm` refusing with "no shareable content yet" immediately
                // after an upload that reported success.
                //
                // Writing the row when it is **absent** is kept, and is the point: it is how a
                // device first learns of a member it has never seen (a peer's folder block
                // naming a file whose own block has not arrived yet), so the array never holds
                // a ref to a row that does not exist. Updates then come from the member's own
                // block, which is the only writer that carries its full state and an HLC to
                // order it by.
                //
                // This completes the two guards already above: [writeValuesToDb] returns early
                // for `ArrayDatabaseReference` / `CompositeDatabaseReference` ("already
                // persisted … keep pointing at it without materialising or rewriting it") —
                // which is exactly this rule for the loaded-from-disk case. A wire block hits
                // neither, because its list arrives *materialised* rather than as a reference.
                //
                // ⚠ `getEntry == null` (test call sites, per its KDoc) keeps the plain
                // overwrite, same as the only-grow merge below.
                val arrayRefs = fieldValue.toList().map {
                    val memberHash = getDataId(it).getDataIdHash()

                    if (getEntry?.invoke(memberHash) == null) {
                        serialize(it, fallbackDataId = null, saveEntry = saveEntry, getEntry = getEntry)
                    }

                    memberHash
                }

                val entityFieldDataId = DataId.EntityFieldDataId.create(
                    context = context,
                    type = TList.ID,
                    parentEntity = parentDataId,
                    fieldRef = fieldId
                )
                val dataIdHash = entityFieldDataId.getDataIdHash()

                // Only-grow merge: when [getEntry] is wired (the production save
                // path via `DatabaseImpl.saveData`), union the existing array's
                // refs with the new ones, preserving anything already added by
                // a side-channel writer such as `ParentFilesRelationMaintainer`'s
                // `Database.addToArray`. Without this invariant a stale `Folder`
                // SyncBlock processed via `FolderMerger` → `saveData(merged)`
                // races with concurrent `addToArray`s and can erase folders/files
                // that the maintainer just added.
                //
                // Removals propagate via the dedicated `deleteData` →
                // `ParentFilesRelationMaintainer.onDataDeleted` → `removeFromArray`
                // path, which sync covers separately via tombstones. Field writes
                // on `Folder` therefore only ever need to *add*; they should not
                // shrink the children array.
                val existingRefs = getEntry?.invoke(dataIdHash)?.fields
                    ?.mapNotNull { (it as? ObjectStore.DbValue.LongValue)?.value }
                    ?: emptyList()
                val mergedRefs = LinkedHashSet<Long>(existingRefs.size + arrayRefs.size)
                    .also { it.addAll(existingRefs); it.addAll(arrayRefs) }
                    .toList()

                saveEntry(
                    ObjectStore.DbEntry(
                        type = TList.ID,
                        dataIdHash = dataIdHash,
                        fields = mergedRefs.map { ObjectStore.DbValue.LongValue(it) },
                        properties = emptyMap()
                    )
                )

                dataIdHash
            }

            is TInteger -> fieldValue.get()
            is TString -> fieldValue.get()
            is TByteArray -> fieldValue.get()
            is TBoolean -> fieldValue.get()
            is TConstant -> fieldValue.schema.id.get()
            is Composite -> {
                // Whether `getDataId` resolves is exactly the ownership question: a child with
                // its own `DataIdProvider` has its own identity (and its own sync blocks); one
                // that falls back to `EntityFieldDataId` is *owned by this parent* and has no
                // other writer.
                var ownsItsIdentity = true
                val dataId = try {
                    getDataId(fieldValue)
                } catch (e: Exception) {
                    ownsItsIdentity = false
                    DataId.EntityFieldDataId.create(
                        context = context,
                        type = fieldValue.id,
                        parentEntity = getDataId(data),
                        fieldRef = fieldId
                    )
                }

                // Create-only for a child that owns its identity — same rule, and the same
                // reasoning, as the `TListDto` branch above. A parent-owned child keeps being
                // written by its parent, because nothing else ever will.
                //
                // Propagate `getEntry` — see the matching note in the `TListDto` branch above.
                // Same shrink-race protection applies recursively.
                if (!ownsItsIdentity || getEntry?.invoke(dataId.getDataIdHash()) == null) {
                    serialize(
                        data = fieldValue,
                        fallbackDataId = dataId,
                        saveEntry = saveEntry,
                        getEntry = getEntry,
                    )
                }
                dataId.getDataIdHash()
            }
            else -> {
                error("Unsupported data type for DB serialization: ${fieldValue::class}")
            }
        }
    }

    private fun getDataId(data: Data): DataId.EntityDataId {
        val typeId = if (data is Entity<*>) data.type.get() else data.id
        val dataIdProvider = traitResolver.getTrait<DataIdProvider<Data>>(
            typeId = typeId,
            traitId = DataIdProvider.ID
        ) ?: error("No DataIdProvider found for ${data.id}")

        val requirement = dataIdProvider.traitRequirements.toList().find {
            it.schema.isSubtypeOf(DataIdProvider.Requirements.DATA_ID)
        } ?: error("DATA_ID requirement not found in provider for ${data.id}")

        return requirement.executePure<DataId.EntityDataId>(
            context = context,
            caller = data
        )
    }

    private fun anyValueToDbValue(value: Any): ObjectStore.DbValue = when (value) {
        is String -> ObjectStore.DbValue.StringValue(value)
        is ByteArray -> ObjectStore.DbValue.ByteArrayValue(value)
        is Boolean -> ObjectStore.DbValue.BooleanValue(value)
        else -> ObjectStore.DbValue.LongValue(value as Long)
    }

    private fun checkCorrectFieldCount(
        index: Int,
        fields: List<Any>,
        schema: CompositeSchema,
        protocolId: String
    ) {
        check(index <= fields.lastIndex) {
            "Expected number of fields for protocol $protocolId " +
                    "should be: ${schema.values.toList().size}, " +
                    "but got: ${fields.size}"
        }
    }
}

/**
 * The child-row id a composite / list / generic slot is stored as, or a failure that **names what
 * disagreed**.
 *
 * Store rows are positional `DbValue` lists with no per-field tag, so reading one under a schema
 * that shapes that position differently is not detectable in general — the best the reader can do
 * is fail loudly at the first slot whose *kind* is impossible. A bare
 * `as ObjectStore.DbValue.LongValue` throws a `ClassCastException` naming only two DbValue
 * subclasses: not the field, not the type, not the row. That is the whole diagnosis missing, and
 * it surfaces deep inside a sync pull where the stack is 40 frames of coroutine plumbing. The
 * sibling property-relation branch errors by name the same way.
 */
private fun ObjectStore.DbValue.asChildRowRef(what: String, typeId: String? = null): Long =
    (this as? ObjectStore.DbValue.LongValue)?.value
        ?: error(
            "$what expected a LongValue child-row reference but found " +
                "${this::class.simpleName}" +
                (typeId?.let { " (type id '$it')" } ?: "") +
                " — the row was written under a different shape than this build reads."
        )
