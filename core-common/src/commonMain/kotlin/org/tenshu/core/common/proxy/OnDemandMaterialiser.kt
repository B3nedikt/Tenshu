package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.reference.DynamicPropertyTypeReference

/**
 * Shared "resolve every [PendingOnDemandProperty] that [SelectImplementation]
 * left behind" pass. Terminal operations call this on their outgoing value
 * so the placeholder never crosses the IPC boundary — `Get` invokes it on
 * the unwrapped `Maybe.Some` value, the stream observer in
 * [ExecutionContext.setDatabaseListener] invokes it on each emitted item
 * before [ClientConnection.send].
 *
 * Suspend because [OnDemandResolution.resolve] is suspend (it goes through
 * the sync relay + `SyncProcessor`). The walk is shallow-recursive: lists
 * map element-wise; entities are checked for placeholders directly.
 * Returns the input unchanged when no resolver is registered on the
 * context (e.g. test harnesses that don't wire the sync layer) — callers
 * already tolerate placeholders surviving as inert data, and this matches
 * [MaybeGetImplementation]'s "no resolver → return value as is" behaviour.
 *
 * Failure handling mirrors `Get`'s: a property that fails to resolve
 * stays as a placeholder. [PendingOnDemandProperty] round-trips across IPC,
 * so that is wire-safe; the downstream consumer sees the marker and can
 * detect the gap.
 */
internal suspend fun materialisePendingOnDemandProperties(
    executionContext: ExecutionContext,
    value: Data?
): Data? {
    if (value == null) return value
    return when (value) {
        is TList<*> -> {
            @Suppress("UNCHECKED_CAST")
            val items = (value as TList<Data>).toList()
            if (items.isEmpty()) return value
            val mapped = items.map { item ->
                materialisePendingOnDemandProperties(executionContext, item)
                    ?: item
            }
            mapped.toTList(executionContext.context)
        }
        is Entity<*> -> materialiseEntityPlaceholders(executionContext, value)
        else -> value
    }
}

private suspend fun materialiseEntityPlaceholders(
    executionContext: ExecutionContext,
    entity: Entity<*>
): Data {
    val pending = entity.properties.entries.mapNotNull { (k, v) ->
        (v as? PendingOnDemandProperty)?.let { k.get() to it }
    }
    if (pending.isEmpty()) return entity

    val resolver = executionContext.context.onDemandResolution() ?: return entity
    val resolvedByKey = mutableMapOf<String, Data>()

    for ((keyRef, marker) in pending) {
        val propertyId = marker.propertyId.get()
        val fetch = resolver.resolve(
            executionContext = executionContext,
            entityId = marker.entityId,
            propertyId = propertyId,
            params = marker.params
        )
        if (fetch is org.tenshu.core.TResult.Failure<*>) continue

        val parentData = executionContext.database.getData(marker.entityId) ?: continue
        val refSchema = executionContext.context.schemaProvider()
            .getSchema(propertyId) ?: continue
        val typeRef = executionContext.context.schemaProvider()
            .getReference(refSchema.id.get()) as? DynamicPropertyTypeReference ?: continue

        val materialised = executionContext.resolveDynamicProperty(
            data = parentData,
            propertyReference = typeRef,
            params = marker.params
        ) ?: continue

        resolvedByKey[keyRef] = materialised
    }

    if (resolvedByKey.isEmpty()) return entity

    val mergedProperties = entity.properties.mapKeys { it.key.get() }.toMutableMap()
    for ((k, v) in resolvedByKey) mergedProperties[k] = v
    val fields = entity.fields.mapKeys { it.key.get() }.toMutableMap()

    return executionContext.context.dataFactory().createEntity<Composite>(
        type = entity.type.get(),
        fields = fields,
        relations = mergedProperties
    )
}
