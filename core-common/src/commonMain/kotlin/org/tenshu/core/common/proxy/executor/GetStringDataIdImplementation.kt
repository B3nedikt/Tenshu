package org.tenshu.core.common.proxy.executor

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.GetStringDataId
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.isSubtypeOf

internal class GetStringDataIdImplementation : PureOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        context: TContext,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val fieldRef = (params!![GetStringDataId.Fields.FIELD_REF] as TString).get()

        // Use getOptionalField to avoid the "Field not found" error if it was selected out
        val value = when (caller) {
            is Entity<*> -> with(caller) { getFieldValue<TString>(fieldRef) }
            is Composite -> caller.getField(fieldRef)
            else -> error("Caller must be a composite or entity. Caller: $caller")
        } ?: error("Field $fieldRef not found in caller $caller")

        val schema = if (caller is Entity<*>) {
            context.schemaProvider().getSchema(caller.type.get()) ?: caller.schema
        } else {
            (caller as Data).schema
        }

        val type = findIdentityRoot(schema)

        return DataId.StringDataId.create(
            context = context,
            type = type,
            keyValue = value.get()
        ) as T
    }
}

/**
 * The type a keyed entity is *stored* under: the topmost ancestor schema still
 * carrying a [DataIdProvider] trait. Subtypes share their root's key space —
 * e.g. `Folder` rows live under `StringDataId(File.ID, path)` because `Folder`
 * extends `File` and `File` declares the provider. Shared by the DataId write
 * path (above) and the by-key search fast path
 * ([org.tenshu.core.common.proxy.trySearchByKey]) — the two MUST derive the
 * same type or by-key lookups miss rows the write path stored.
 */
internal fun findIdentityRoot(schema: DataSchema): String {
    var current = schema
    while (true) {
        val parent = current.parent ?: break
        if (parent is CompositeSchema && parent.traits.toList()
                .any { it.isSubtypeOf(DataIdProvider.ID) }
        ) {
            current = parent
        } else {
            break
        }
    }
    return current.id.get()
}
