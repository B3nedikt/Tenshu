package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.core.schema.baseSchema

internal class SearchImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val predicateCallstack = (params?.get(Search.Params.PREDICATE) as? TList<*>)
            ?.toList()
            ?: error("No predicate provided")

        val array = executionContext.resolveList(caller)
            ?: error("Search must be called on a list. Caller: $caller")

        val result = walkAndSearch(
            executionContext = executionContext,
            items = array.toList(),
            predicateCallstack = predicateCallstack
        )

        return result as T
    }

    private fun walkAndSearch(
        executionContext: ExecutionContext,
        items: List<Data>,
        predicateCallstack: List<Data>
    ): Maybe<Data> {
        for (item in items) {
            val elementWithContext = if (item is Entity<*>) {
                val dataId = executionContext.getDataId(item)
                if (dataId != null) {
                    EntityDatabaseReference(item, dataId)
                } else item
            } else item

            val predicateResult = evaluateCallstack(
                executionContext = executionContext,
                contextData = elementWithContext as Composite,
                callstack = predicateCallstack,
                allowDeferring = false
            )

            if (predicateResult is TBoolean && predicateResult.get()) {
                return Maybe.Some.create(executionContext.context, item)
            }

            // Recursive walk into nested lists
            val nestedFound = searchInNested(executionContext, item, predicateCallstack)
            if (nestedFound is Maybe.Some<*>) {
                return nestedFound as Maybe<Data>
            }
        }

        return Maybe.Empty.create(executionContext.context)
    }

    private fun searchInNested(
        executionContext: ExecutionContext,
        item: Data,
        predicateCallstack: List<Data>
    ): Maybe<Data> {
        val dataId = executionContext.getDataId(item)
        val elementToTraverse = if (dataId != null) {
            executionContext.database.getData(dataId, asEntity = true) as? Composite
                ?: item as? Composite
        } else item as? Composite

        if (elementToTraverse != null) {
            val typeId =
                if (elementToTraverse is Entity<*>) elementToTraverse.type.get() else elementToTraverse.id
            val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema
            if (schema != null) {
                val allFields = collectFields(schema)
                for (fieldSchema in allFields) {
                    val fieldId = fieldSchema.id.get()
                    if (fieldSchema.type.baseSchema?.id?.get() == TList.ID) {
                        val fieldData = if (fieldSchema.isOptional.get()) {
                            elementToTraverse.getOptionalField<Data>(fieldId)
                        } else {
                            if (elementToTraverse is Entity<*>) {
                                elementToTraverse.getFieldValue<Data>(fieldId)
                            } else {
                                elementToTraverse.getField<Data>(fieldId)
                            }
                        }

                        val nestedArray = executionContext.resolveList(fieldData)
                        if (nestedArray != null) {
                            val found = walkAndSearch(
                                executionContext,
                                nestedArray.toList(),
                                predicateCallstack
                            )
                            if (found is Maybe.Some<*>) return found
                        }
                    }
                }
            }
        }
        return Maybe.Empty.create(executionContext.context)
    }

    // See `docs/schema-dsl.md` §2 "Field Inheritance Invariant".
    private fun collectFields(schema: CompositeSchema): List<Field> =
        schema.values.toList()
}
