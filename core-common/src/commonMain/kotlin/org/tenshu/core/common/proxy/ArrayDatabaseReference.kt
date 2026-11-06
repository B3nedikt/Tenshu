package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.DataId
import org.tenshu.core.schema.CompositeSchema

/**
 * Reference to a DB-backed array.
 *
 * @param arrayId the identity of the array in the database.
 * @param isEntity whether elements are entities (affects how they are loaded / listened to).
 * @param elementSchemaId the fully-qualified schema id of elements in this array (e.g.
 *   `"tenshu/file/service/file"`), when known at construction time. `null` for arrays whose
 *   element type is not determinable at the point of construction (e.g. some proxy-side
 *   manual constructions). Populated by [EvaluateCallstack] when resolving a list field on
 *   an entity, where the schema is fully known.
 */
data class ArrayDatabaseReference(
    val arrayId: DataId.ReferencedDataId,
    val isEntity: Boolean,
    val elementSchemaId: String? = null,
    val executionStack: List<Data> = emptyList()
) : Data, DatabaseReference {
    override val schema: CompositeSchema
        get() = TList.Schema

    override fun getOperationContext(): OperationContext {
        return ArrayOperationContext(
            arrayDatabaseReference = this,
            executionStack = executionStack
        )
    }
}
