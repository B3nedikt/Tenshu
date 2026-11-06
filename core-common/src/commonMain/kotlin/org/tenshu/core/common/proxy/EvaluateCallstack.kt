package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.InvalidCallerError
import org.tenshu.core.Operation
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.Count
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.Filter as FilterOp
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.SortBy as SortByOp
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.Traverse as TraverseOp
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.impl.MissingParameterDto
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.common.util.isParameterized
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.get
import org.tenshu.core.id
import org.tenshu.core.isTerminal
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.EntityListReference
import org.tenshu.core.reference.EntityReference
import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.Type
import org.tenshu.core.schema.TypeParameter
import org.tenshu.core.schema.TypeVariable
import org.tenshu.core.schema.Variance
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.params
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.isStream

/**
 * Phase 4 of `docs/proposals/core-error-channel.md`: returns `Data?` directly;
 * typed failure travels as a thrown [TenshuException]. The wire-side
 * [LocalTaskExecutor] catch-all converts that into the
 * `TResult.Failure(error)` payload the wire still ships.
 */
fun evaluateCallstack(
    executionContext: ExecutionContext,
    contextData: Any? = null,
    callstack: List<Data>,
    allowDeferring: Boolean = true
): Data? {

    var currentData: Any? = contextData

    if (currentData is DatabaseReference) {
        val opContext = currentData.getOperationContext()
        if (allowDeferring) {
            // Deferring is allowed, we don't evaluate the stack yet
        } else {
            // If deferring is not allowed, we must evaluate the existing stack first
            if (opContext.executionStack.isNotEmpty()) {
                val resultData = evaluateCallstack(
                    executionContext = executionContext,
                    contextData = when (currentData) {
                        is ArrayDatabaseReference -> currentData.copy(executionStack = emptyList())
                        is EntityDatabaseReference -> currentData.copy(executionStack = emptyList())
                        else -> currentData
                    },
                    callstack = opContext.executionStack,
                    allowDeferring = false
                )
                if (resultData is AsyncReference) {
                    // INTERRUPT: Propagate the async reference up, appending the CURRENT callstack to it
                    return AsyncReference.create(
                        asyncCall = resultData.asyncCall,
                        remainingCallstack = resultData.remainingCallstack + callstack
                    ) as Data
                }
                currentData = resultData
            }
        }
    }

    val callstackList = callstack.toMutableList()
    while (callstackList.isNotEmpty()) {
        // A cancellable read stops between steps — and, because a filter evaluates its predicate
        // through here, between rows of a filter walk. Null (no check) for every other request.
        executionContext.requestCancellation?.checkpoint()
        val protocol = callstackList.removeAt(0)
        val remaining = callstackList.toList()

        // A deferred composite field ([CompositeDatabaseReference]) resolves
        // eagerly the moment the chain applies anything to it. Unlike
        // [ArrayDatabaseReference] it accumulates no execution stack, so we
        // materialise it up-front here instead of threading it through the
        // deferral `when`-arms below. (An *unused* deferred field is never made
        // the chain head, so it is never resolved — that is the over-fetch win.)
        if (currentData is CompositeDatabaseReference) {
            currentData = executionContext.getComposite(currentData as CompositeDatabaseReference)
        }

        if (protocol is Operation) {
            val params = protocol.params
            val isTerminal = protocol.isTerminal()
            val operationContext = (currentData as? DatabaseReference)?.getOperationContext()
            val isStreamOp = protocol.isStream()

            // Window fusion: a `drop`/`take` prefix applied directly to a raw
            // (no pending ops) array reference is consumed into a single
            // windowed read — only the `[offset, offset+limit)` members are
            // deserialised, instead of the whole array followed by in-memory
            // windowing. Gated on `!allowDeferring`: in the deferring walk a
            // chain like `list.drop(n)` must still return a deferred
            // *reference* (callers may append ops, including `stream`); the
            // one-shot path re-enters here with `allowDeferring = false`
            // anyway. A pending `executionStack` (e.g. `filter` before the
            // window) or a downstream stream op (needs the reference, not a
            // materialised window) disables fusion — behaviour unchanged.
            if (!allowDeferring &&
                currentData is ArrayDatabaseReference &&
                operationContext != null &&
                operationContext.executionStack.isEmpty() &&
                (protocol.id == Drop.ID || protocol.id == Take.ID) &&
                remaining.none { (it as? Operation)?.isStream() == true }
            ) {
                val fold = foldWindowPrefix(protocol, remaining)
                if (fold != null) {
                    repeat(fold.consumedFromRemaining) { callstackList.removeAt(0) }
                    val nextOps = callstackList.toList()
                    val projection = nextOps.firstOrNull()
                        ?.let { captureProjection(it, nextOps.drop(1)) }
                    currentData = executionContext.getArrayDataWindow(
                        reference = currentData as ArrayDatabaseReference,
                        projection = projection,
                        offset = fold.offset,
                        limit = fold.limit,
                    ) ?: emptySequence<Data>()
                    continue
                }
            }

            // Count short-circuit: `count()` on a raw array is the member-hash
            // list's size — no element deserialisation. Null hashes (stub
            // store / missing array) fall through to the materialise-and-count
            // fallback, which preserves today's semantics.
            if (!allowDeferring &&
                currentData is ArrayDatabaseReference &&
                operationContext != null &&
                operationContext.executionStack.isEmpty() &&
                protocol.id == Count.ID
            ) {
                val hashes = executionContext.getArrayMemberHashes(
                    currentData as ArrayDatabaseReference
                )
                if (hashes != null) {
                    currentData = hashes.size.toTInteger(executionContext.context)
                    continue
                }
            }

            // By-key search short-circuit: `search { keyField equalTo K }` on a
            // raw array resolves via a content-addressed point read instead of
            // materialise-everything + linear scan. Same gate rationale as the
            // window fusion above; a null result (non-key predicate, missing
            // trait, membership miss, …) falls through to the unchanged
            // materialise-and-scan path — which is load-bearing for nested-list
            // descent (see [trySearchByKey]).
            if (!allowDeferring &&
                currentData is ArrayDatabaseReference &&
                operationContext != null &&
                operationContext.executionStack.isEmpty() &&
                protocol.id == Search.ID &&
                remaining.none { (it as? Operation)?.isStream() == true }
            ) {
                val byKey = trySearchByKey(
                    executionContext = executionContext,
                    reference = currentData as ArrayDatabaseReference,
                    searchOp = protocol,
                    projection = captureProjection(protocol, remaining),
                )
                if (byKey != null) {
                    currentData = byKey
                    continue
                }
            }

            // Ordered-window fusion: `sortBy(key)` on a raw array whose remaining
            // ops begin with a finite `take`/`drop` window and end in a `select`
            // executes as ONE projected pass + bounded stable top-N, instead of
            // materialise-everything-unprojected → sort → discard. Same gate
            // rationale as the fusions above; any miss inside
            // [tryOrderedWindowFusion] (an unrecognised key, unbounded window,
            // no select, …) falls through to the unchanged in-memory sort.
            // A `thenBy` fold arrives as a RUN of `SortBy` ops (each extending
            // the last); [foldSortByRun] collapses it to the last one first, so
            // the window is found where it actually is. See
            // `docs/proposals/core-ordered-window-fusion.md`.
            if (!allowDeferring &&
                currentData is ArrayDatabaseReference &&
                operationContext != null &&
                operationContext.executionStack.isEmpty() &&
                protocol.id == SortByOp.ID &&
                remaining.none { (it as? Operation)?.isStream() == true }
            ) {
                val run = foldSortByRun(protocol, remaining)
                val fused = tryOrderedWindowFusion(
                    executionContext = executionContext,
                    reference = currentData as ArrayDatabaseReference,
                    sortOp = run.op,
                    remaining = remaining.drop(run.consumedFromRemaining),
                )
                if (fused != null) {
                    repeat(run.consumedFromRemaining + fused.consumedFromRemaining) {
                        callstackList.removeAt(0)
                    }
                    currentData = fused.rows
                    continue
                }
            }

            // Filter-aware ordered-window fusion (F4): `[traverse()] · filter* · sortBy ·
            // window · select` on a raw array — the shape every whole-realm feed takes
            // (Photos' pages, Drive Home's recents) — runs as one thin walk + bounded
            // top-N instead of materialise → filter → full sort. Gated here, on the
            // op that would otherwise *defer* (and so disarm gate 2 of the sort fusion
            // above for everything after it). Any unrecognised predicate, key or window
            // inside [tryFilteredOrderedWindowFusion] falls through unchanged.
            if (!allowDeferring &&
                currentData is ArrayDatabaseReference &&
                operationContext != null &&
                operationContext.executionStack.isEmpty() &&
                (protocol.id == TraverseOp.ID || protocol.id == FilterOp.ID) &&
                remaining.none { (it as? Operation)?.isStream() == true }
            ) {
                val fused = tryFilteredOrderedWindowFusion(
                    executionContext = executionContext,
                    reference = currentData as ArrayDatabaseReference,
                    first = protocol,
                    remaining = remaining,
                )
                if (fused != null) {
                    repeat(fused.consumedFromRemaining) { callstackList.removeAt(0) }
                    currentData = fused.rows
                    continue
                }
            }

            if (operationContext != null && !isTerminal) {
                // Return a new deferred reference
                val deferred = when (currentData) {
                    is ArrayDatabaseReference -> currentData.copy(executionStack = operationContext.executionStack + protocol)
                    is EntityDatabaseReference -> currentData.copy(executionStack = operationContext.executionStack + protocol)
                    else -> error("Invalid deferred state for $currentData")
                }

                if (remaining.isNotEmpty()) {
                    return evaluateCallstack(
                        executionContext,
                        deferred,
                        remaining,
                        allowDeferring
                    )
                }

                if (allowDeferring) {
                    return deferred as Data
                }
            }

            // If we hit here, we are about to EXECUTE an operation.
            // We MUST evaluate the deferred stack first unless it's a stream operation.
            if (operationContext != null && !isStreamOp) {
                val resultData = evaluateCallstack(
                    executionContext = executionContext,
                    contextData = when (currentData) {
                        is ArrayDatabaseReference -> currentData.copy(executionStack = emptyList())
                        is EntityDatabaseReference -> currentData.copy(executionStack = emptyList())
                        else -> currentData
                    },
                    callstack = operationContext.executionStack,
                    allowDeferring = false
                )
                if (resultData is AsyncReference) {
                    return AsyncReference.create(
                        asyncCall = resultData.asyncCall,
                        remainingCallstack = resultData.remainingCallstack + protocol + remaining
                    ) as Data
                }
                currentData = resultData
            }

            // Ensure we have real data if we are not deferring anymore
            // UNLESS it's a stream operation which wants the reference.
            if (currentData is ArrayDatabaseReference && !isStreamOp) {
                currentData = executionContext.getArrayData(
                    currentData as ArrayDatabaseReference,
                    captureProjection(protocol, remaining),
                )
            }

            // Sequence materialization (sync)
            if (currentData is Sequence<*> && !isStreamOp) {
                @Suppress("UNCHECKED_CAST")
                currentData =
                    (currentData as Sequence<Data>).toList().toTList(executionContext.context)
            }

            // Phase 5.6 of `docs/adr/0009-operation-schema-typing.md` + Phase 3.1 of
            // `docs/adr/0005-composite-schema-typing.md`: validate the receiver before executing.
            // Walks the expected receiver and the actual chain-head type pairwise,
            // descending into [GenericType] params so `TList<File>` vs `TList<Folder>`
            // is caught (today's check only saw `TList`). [TypeVariable] in expected
            // accepts any actual type — variance / bound enforcement still deferred.
            checkReceiverCompatibility(protocol, currentData, executionContext)

            // Phase 2 of `docs/proposals/core-error-channel.md` (the §3.0 structural
            // lift): every required schema-declared param must be present in
            // `protocol.params` at execute time. Partial chain entries are filtered
            // out by the deferral branch above (`operationContext != null && !isTerminal`)
            // — they return before
            // reaching here. `params.require(KEY)` in impl bodies is gone (Phase 2
            // mechanical replacement); impls now `params.read<T>(KEY)` against a
            // dispatcher-guaranteed-present, dispatcher-type-checked map.
            checkRequiredParameters(protocol, params, executionContext)

            // `Data?`, not `Data` — a void op resolves the type argument to `null`, and a
            // non-null pin makes Kotlin/Native emit a checked downcast that throws NPE.
            val resultData = executionContext.execute<Data?>(
                operationId = protocol.id,
                caller = currentData,
                params = params,
                operationContext = if (currentData is DatabaseReference) (currentData as DatabaseReference).getOperationContext() else null
            )

            if (resultData is AsyncReference) {
                return AsyncReference.create(
                    asyncCall = resultData.asyncCall,
                    remainingCallstack = resultData.remainingCallstack + remaining
                ) as Data
            }

            currentData = resultData
            continue
        }

        if (protocol is Reference) {
            val operationContext = (currentData as? DatabaseReference)?.getOperationContext()

            if (allowDeferring && operationContext != null) {
                val deferred = when (currentData) {
                    is ArrayDatabaseReference -> currentData.copy(executionStack = operationContext.executionStack + protocol)
                    is EntityDatabaseReference -> currentData.copy(executionStack = operationContext.executionStack + protocol)
                    else -> error("Invalid deferred state for $currentData")
                }

                if (remaining.isEmpty()) {
                    return deferred as Data
                }

                return evaluateCallstack(
                    executionContext,
                    deferred,
                    remaining,
                    allowDeferring
                )
            }

            // We must execute now. Run any deferred stack against the array data first,
            // mirroring the Operation branch.
            if (operationContext != null && operationContext.executionStack.isNotEmpty()) {
                val resultData = evaluateCallstack(
                    executionContext = executionContext,
                    contextData = when (currentData) {
                        is ArrayDatabaseReference -> currentData.copy(executionStack = emptyList())
                        is EntityDatabaseReference -> currentData.copy(executionStack = emptyList())
                        else -> currentData
                    },
                    callstack = operationContext.executionStack,
                    allowDeferring = false
                )
                if (resultData is AsyncReference) {
                    return AsyncReference.create(
                        asyncCall = resultData.asyncCall,
                        remainingCallstack = resultData.remainingCallstack + protocol + remaining
                    ) as Data
                }
                currentData = resultData
            }

            if (currentData is ArrayDatabaseReference) {
                currentData = executionContext.getArrayData(
                    currentData,
                    captureProjection(protocol, remaining),
                )
            }
            if (currentData is Sequence<*>) {
                @Suppress("UNCHECKED_CAST")
                currentData =
                    (currentData as Sequence<Data>).toList().toTList(executionContext.context)
            }

            currentData = resolveReference(protocol, currentData, executionContext)
            continue
        }

        if (currentData == null) {
            currentData = protocol
            continue
        }

        error("Could not handle protocol: ${protocol.id} (type: ${protocol::class.simpleName})")
    }

    // Final Materialization (Sync)
    if (currentData is ArrayDatabaseReference) {
        currentData = executionContext.getArrayData(currentData)
    }
    if (currentData is CompositeDatabaseReference) {
        currentData = executionContext.getComposite(currentData)
    }

    @Suppress("UNCHECKED_CAST")
    return when (currentData) {
        is Sequence<*> -> (currentData as Sequence<Data>).toList()
            .toTList(executionContext.context)

        is Data -> currentData
        null -> null
        else -> error("Invalid final result: $currentData")
    }
}

/**
 * Phase 2 of `docs/proposals/core-error-channel.md` (the §3.0 structural lift):
 * enforces dispatch-time presence of every non-optional param the operation's
 * schema declares. Throws [TenshuException] wrapping a typed [org.tenshu.core.MissingParameter]
 * when a required field is absent from [params] — landing the structural-error
 * channel before the impl body sees the map, so impl bodies stop doing their own
 * presence-check via the old `params.require(KEY)` helper.
 *
 * Type-check + unknown-param enforcement live one layer up, at
 * [org.tenshu.core.common.impl.OperationDto] construction time (parser boundary).
 * Required-presence has to live here, not there, because operation ROs construct
 * partial `OperationDto`s incrementally as the chain DSL accretes params — only
 * by the time `evaluateCallstack` reaches dispatch does the platform know "this
 * call is happening now".
 *
 * Unparameterised / runtime schemas that don't surface as [OperationSchema] are
 * a no-op (no params to enforce). Conservative: matches the receiver-check
 * info-gap policy in [checkReceiverCompatibility].
 */
private fun checkRequiredParameters(
    protocol: Operation,
    params: Map<String, Data>,
    executionContext: ExecutionContext
) {
    val schema = executionContext.context.schemaProvider().getSchema(protocol.id)
        as? OperationSchema ?: return
    for (field in schema.params.toList()) {
        if (field.isOptional.get()) continue
        val fieldId = field.id.get()
        if (!params.containsKey(fieldId)) {
            throw TenshuException(MissingParameterDto(name = fieldId))
        }
    }
}

/**
 * Phase 5.6 of `docs/adr/0009-operation-schema-typing.md` + Phase 3.1 of
 * `docs/adr/0005-composite-schema-typing.md`: validates that [currentData] satisfies the
 * operation's declared receiver shape. Returns silently when compatible (or no
 * receiver is declared), or throws `TenshuException(InvalidCallerError)` when
 * the receiver shape mismatches — Phase 4 of
 * `docs/proposals/core-error-channel.md`.
 *
 * Walks the expected receiver ([OperationSchema.receiver]) and the [Type] derived
 * from [currentData] in parallel: `baseSchema` subtype check at each level, then
 * pairwise descent into [org.tenshu.core.schema.GenericType] params so nested generics
 * (`TList<TMap<K, V>>`) are checked end-to-end instead of bailing at the outer
 * `TList`. A [TypeVariable] in the expected slot accepts any actual type —
 * binding-collection isn't used by the validator yet (invariant in v1 per proposal
 * §2.3); variance and bound enforcement remain deferred.
 *
 * Conservative on info gaps: when the actual side carries no params (runtime
 * composites that haven't surfaced their parameterisation yet), only the base
 * schema is constrained — preserving today's behaviour for those receivers.
 * Receiver-less operations and unmatchable [currentData] (services, plain values)
 * skip the check entirely.
 */
private fun checkReceiverCompatibility(
    protocol: Operation,
    currentData: Any?,
    executionContext: ExecutionContext
) {
    val schema = executionContext.context.schemaProvider().getSchema(protocol.id)
        as? OperationSchema
        ?: return
    val expected = schema.receiver ?: return
    val actual = actualReceiverType(currentData, executionContext) ?: return

    if (matchesReceiver(expected, actual)) return

    throw TenshuException(
        InvalidCallerError.create(
            context = executionContext.context,
            message = "Operation '${protocol.id}' expected receiver of type " +
                    "'${describeType(expected)}' but got '${describeType(actual)}'."
        )
    )
}

/**
 * Best-effort [Type] for [currentData]; `null` when the validator should skip the
 * check (services, plain values, anything without schema info). [ArrayDatabaseReference]
 * surfaces its `elementSchemaId` as the single type parameter, giving the walker enough
 * shape to catch `TList<File>` vs `TList<Folder>` mismatches. Runtime composites and
 * entity references don't carry parameterisation today — they yield an unparameterised
 * [Type], which the walker treats as a base-schema-only constraint.
 */
private fun actualReceiverType(currentData: Any?, executionContext: ExecutionContext): Type? {
    val schemaProvider = executionContext.context.schemaProvider()
    return when (currentData) {
        null -> null
        is ArrayDatabaseReference -> {
            val listSchema = schemaProvider.getSchema(TList.ID) ?: return null
            val elementId = currentData.elementSchemaId
            val params = if (elementId != null) {
                val elementSchema = schemaProvider.getSchema(elementId) ?: return null
                listOf(
                    TypeParameter.create(
                        context = executionContext.context,
                        type = elementSchema.toType(),
                        variance = Variance.Invariant,
                    )
                )
            } else {
                emptyList()
            }
            Type.create(executionContext.context, baseSchema = listSchema, params = params)
        }
        is EntityDatabaseReference ->
            schemaProvider.getSchema(currentData.schema.id.get())?.toType()
        is Composite ->
            schemaProvider.getSchema(currentData.schema.id.get())?.toType()
        else -> null
    }
}

/**
 * Parallel walk over [expected] and [actual]. At each level a base-schema subtype
 * check, then pairwise descent into params. [TypeVariable] in [expected] accepts any
 * [actual] (no variance enforcement in v1). When [actual] lacks params, only the base
 * is constrained — matches the conservative info-gap policy documented on
 * [checkReceiverCompatibility].
 *
 * `internal` for direct unit-testing of the recursive-walk logic without chain-walk
 * setup; the full integration path runs through [checkReceiverCompatibility].
 */
internal fun matchesReceiver(expected: Type, actual: Type): Boolean {
    if (expected is TypeVariable) return true
    val expectedBase = expected.baseSchema ?: return true
    val actualBase = actual.baseSchema ?: return false
    if (!actualBase.isSubtypeOf(expectedBase.id.get())) return false
    val expectedParams = expected.params.toList()
    val actualParams = actual.params.toList()
    if (expectedParams.isEmpty() || actualParams.isEmpty()) return true
    if (expectedParams.size != actualParams.size) return false
    return expectedParams.zip(actualParams).all { (e, a) -> matchesReceiver(e.type, a.type) }
}

private fun describeType(type: Type): String {
    if (type is TypeVariable) return "<${type.name.get()}>"
    val base = type.baseSchema?.id?.get() ?: "<unknown>"
    val params = type.params.toList()
    return if (params.isEmpty()) base
    else "$base<${params.joinToString(", ") { describeType(it.type) }}>"
}

private fun resolveReference(
    reference: Reference,
    currentData: Any?,
    executionContext: ExecutionContext
): Data? {
    var result: Data?

    val referenceId = reference.get()
    val composite = currentData as? Composite
    val isField = reference is FieldReference || (if (composite is Entity<*>) {
        val typeId = composite.type.get()
        val schema = executionContext.context.schemaProvider().getSchema(typeId) as? CompositeSchema
        schema?.values?.toList()?.any { it.id.get() == referenceId } ?: false
    } else {
        composite?.schema?.values?.toList()?.any { it.id.get() == referenceId } ?: false
    })

    if (isField) {
        result = when (currentData) {
            is Entity<*> -> {
                val typeId = currentData.type.get()
                val schema =
                    executionContext.context.schemaProvider()
                        .getSchema(typeId) as? CompositeSchema
                val field = schema?.values?.toList()?.find { it.id.get() == referenceId }

                if (field?.isParameterized == true) {
                    val parentDataId =
                        executionContext.getDataId(currentData) as DataId.EntityDataId
                    val itemType = field.type.params.toList().firstOrNull()?.type?.baseSchema
                    val isEntity = itemType?.isSubtypeOf(Entity.ID) ?: false

                    ArrayDatabaseReference(
                        arrayId = DataId.EntityFieldDataId.create(
                            context = executionContext.context,
                            type = TList.ID,
                            parentEntity = parentDataId,
                            fieldRef = referenceId
                        ),
                        isEntity = isEntity,
                        elementSchemaId = itemType?.id?.get()
                    )
                } else {
                    currentData.fields[FieldReferenceDto(referenceId)]
                }
            }

            is Composite -> {
                currentData.getField(referenceId)
            }

            else -> {
                error("Field reference $referenceId needs to be called on a composite. Caller: $currentData")
            }
        }
        return result
    }

    when (reference) {
        is PropertyTypeReference -> {
            checkNotNull(currentData) {
                "Property reference $reference needs to be called on an entity"
            }

            result = executionContext.resolveProperty(
                data = currentData as Data,
                propertyReference = reference,
            )

            if (result == null) {
                // A stored value property this row simply does not carry is an ABSENCE, and a
                // predicate reads it as "no match" (`evaluatePredicateOn`). Everything else that
                // cannot resolve is still an error.
                if (isStoredValueProperty(executionContext, reference)) throw AbsentPropertyException(reference)
                error("Could not resolve property reference $reference")
            }
        }

        is ServiceTypeReference -> {

            val currentReference = (currentData as? ServiceTypeReference)?.get()
            result = executionContext.resolveServiceType(
                currentServiceId = currentReference,
                referencedServiceId = reference.get()
            )

            checkNotNull(result) {
                "Could not resolve service with reference $reference"
            }
        }

        is EntityReference, is EntityListReference -> {
            if (currentData !is ServiceTypeReference) {
                error("Entity reference $reference needs to be called on a service")
            }

            result = executionContext.resolveServiceData(
                service = currentData,
                ref = reference,
            )
        }

        else -> {
            error("Unsupported reference type: ${reference::class.simpleName}")
        }
    }
    return result
}

/**
 * Build the read-time [Projection] for a `select` about to consume the array being
 * materialised. Two safe cases (see [Projection] for why):
 *
 *  - [protocol] **is** the `select` — a direct `<array>.select { … }` with no
 *    intervening field-reading op. Skip relations *and* composite scalar fields the
 *    select omits.
 *  - a `select` sits **downstream** in [remaining] (e.g. `searchAll { … }.select`).
 *    Skip relations only — an intervening predicate may read a composite scalar
 *    field directly off the entity, so keep them all.
 *
 * `null` when no `select` consumes this array → full load (unchanged behaviour).
 *
 * `internal` so the capture logic can be unit-tested directly without a full
 * chain + database harness.
 */
/**
 * The result of folding a contiguous `drop`/`take` prefix into one window:
 * read `[offset, offset + limit)` of the raw array. [consumedFromRemaining]
 * counts how many ops *beyond the first* were folded — the caller must remove
 * exactly that many from its callstack so they don't also run in-memory.
 */
internal data class WindowFold(
    val offset: Int,
    val limit: Int,
    val consumedFromRemaining: Int,
)

/**
 * Fold the maximal `drop`/`take` prefix of `first :: remaining` into a single
 * `(offset, limit)` window, composing in encounter order (ops execute
 * left→right, so `take(5).drop(2)` ≠ `drop(2).take(5)`):
 *
 *  - `drop(n)`: `offset += n`; a finite limit shrinks by `n` (floored at 0).
 *  - `take(m)`: `limit = min(limit, m)`.
 *
 * `null` when [first] is not foldable (non-constant or negative param) —
 * no fusion, today's in-memory path runs unchanged (including its throw on
 * negative counts). A non-foldable op *later* in the prefix just ends the
 * fold; it executes in-memory on the windowed result, which composes to the
 * same list.
 */
internal fun foldWindowPrefix(first: Operation, remaining: List<Data>): WindowFold? {
    var offset = 0L
    var limit = Long.MAX_VALUE

    fun apply(op: Operation): Boolean {
        val paramKey = if (op.id == Drop.ID) Drop.Params.N else Take.Params.N
        val n = (op.params?.get(paramKey) as? TInteger)?.get() ?: return false
        if (n < 0) return false
        when (op.id) {
            Drop.ID -> {
                offset += n
                if (limit != Long.MAX_VALUE) limit = (limit - n).coerceAtLeast(0)
            }
            Take.ID -> limit = minOf(limit, n)
        }
        return true
    }

    if (!apply(first)) return null

    var consumed = 0
    for (protocol in remaining) {
        val op = protocol as? Operation ?: break
        if (op.id != Drop.ID && op.id != Take.ID) break
        if (!apply(op)) break
        consumed++
    }

    return WindowFold(
        offset = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        limit = limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        consumedFromRemaining = consumed,
    )
}

internal fun captureProjection(protocol: Data, remaining: List<Data>): Projection? {
    selectorsOf(protocol)?.let { selectors ->
        return projectionOf(selectors, skipUnprojectedComposites = true)
    }
    if (remaining.any { selectorsOf(it) != null }) {
        return Projection(keepFields = emptySet(), skipUnprojectedComposites = false)
    }
    return null
}

/**
 * Build a (possibly nested) [Projection] from a selector list: [fieldSelectorIds] for the
 * scalar fields, plus a child projection per relation selector carrying a `nested` block,
 * keyed by the relation ref. Recurses to arbitrary depth (`core-nested-projection.md`).
 */
private fun projectionOf(
    selectors: List<Selector<*>>,
    skipUnprojectedComposites: Boolean,
): Projection {
    val children = mutableMapOf<String, Projection>()
    selectors.filterIsInstance<Selector.PropertySelector<*>>().forEach { sel ->
        sel.nested?.let { nested ->
            children[sel.propertyReference.get()] =
                projectionOf(nested.toList(), skipUnprojectedComposites = true)
        }
    }
    return Projection(
        keepFields = fieldSelectorIds(selectors),
        skipUnprojectedComposites = skipUnprojectedComposites,
        children = children,
    )
}

/** The selector list of a `select` operation, or `null` if [protocol] is not one. */
private fun selectorsOf(protocol: Data): List<Selector<*>>? {
    val op = protocol as? Operation ?: return null
    @Suppress("UNCHECKED_CAST")
    val selectors = op.params?.get(Select.Params.SELECTORS) as? TList<Selector<*>> ?: return null
    return selectors.toList()
}

/** Field ids named by `FieldSelector`s — the composite scalar fields to keep. */
private fun fieldSelectorIds(selectors: List<Selector<*>>): Set<String> =
    selectors.filterIsInstance<Selector.FieldSelector<*>>()
        .map { it.fieldReference.get() }
        .toSet()

/**
 * A stored **value** property ([org.tenshu.core.property.Property.Value]) that the row does not
 * carry — a file uploaded before its content hash was stamped, a folder asked for a file's hash.
 *
 * ⚠ Still an [IllegalStateException] with the message it always had, so every caller outside a
 * predicate sees exactly the error it saw before. Only [evaluatePredicateOn] treats it differently:
 * there it means the row does not match (`photos-app.md` §40), which is what `inRange` and
 * `File.contentHash` have always documented — *absent is never in range*, *no predicate over it
 * matches such a row* — and what the evaluator did not do: it raised, and took the whole read down.
 */
internal class AbsentPropertyException(reference: PropertyTypeReference) :
    IllegalStateException("Could not resolve property reference $reference")

private fun isStoredValueProperty(executionContext: ExecutionContext, reference: PropertyTypeReference): Boolean {
    if (reference is DynamicPropertyTypeReference) return false
    val schemas = executionContext.context.schemaProvider()
    return schemas.getReferenceProperty(reference.get()) == null && schemas.getEntityRelation(reference.get()) == null
}
