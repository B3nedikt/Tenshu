package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId

/**
 * Fans one [ExternalPropertyWriter] registration out over several
 * per-service writers, routing every call by [supports].
 *
 * The dispatcher looks up a **single** writer component on the
 * [org.tenshu.core.core.TContext] (see the interface KDoc), which was fine
 * while file bytes were the only externally-stored property. The moment a
 * second service stores bytes off the entity (`Workout.Streams`,
 * read-scaling option B), each proxy assembly composes its writers into
 * one of these instead of widening the lookup to a list — the registered
 * component stays the single, stable lookup point.
 *
 * First `supports(propertyId)` match wins; property IDs are namespaced by
 * service, so overlap would be a wiring bug, not a tie to break.
 */
class DelegatingExternalPropertyWriter(
    private val writers: List<ExternalPropertyWriter>,
) : ExternalPropertyWriter {

    constructor(vararg writers: ExternalPropertyWriter) : this(writers.toList())

    private fun writerFor(propertyId: String): ExternalPropertyWriter? =
        writers.firstOrNull { it.supports(propertyId) }

    override fun supports(propertyId: String): Boolean = writerFor(propertyId) != null

    override fun write(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
        value: Data,
    ) {
        writerFor(propertyId)?.write(executionContext, targetEntityId, propertyId, value)
    }

    override fun resolveExternalPath(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? = writerFor(propertyId)?.resolveExternalPath(executionContext, targetEntityId, propertyId)

    override fun resolveContentId(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? = writerFor(propertyId)?.resolveContentId(executionContext, targetEntityId, propertyId)

    override fun resolveContentHash(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? = writerFor(propertyId)?.resolveContentHash(executionContext, targetEntityId, propertyId)
}
