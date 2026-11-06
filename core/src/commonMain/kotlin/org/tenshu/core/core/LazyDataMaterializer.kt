package org.tenshu.core.core

import org.tenshu.core.Composite
import org.tenshu.core.schema.LazyDataCreator

/**
 * Component that manages the lifecycle and caching of materialized [LazyComposite] instances
 * for a specific [TContext].
 */
interface LazyDataMaterializer : TComponent {

    /**
     * Retrieves or creates the materialized instance of the given [creator] for this context.
     */
    fun <T : Composite> getOrCreate(creator: LazyDataCreator<T>): T

    companion object {
        const val ID = "core/LAZY_DATA_MATERIALIZER"
    }
}

/**
 * Convenience extension to access the materializer from a context.
 */
fun TContext.lazyDataMaterializer(): LazyDataMaterializer =
    components[LazyDataMaterializer.ID] as? LazyDataMaterializer
        ?: error("LazyDataMaterializer not found in context. Ensure it is registered.")
