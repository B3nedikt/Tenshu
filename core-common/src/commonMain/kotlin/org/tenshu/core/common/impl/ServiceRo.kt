package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.id
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.listType

abstract class ServiceRo(
    override val requestChain: RequestChain,
    override val context: TContext,
) : Service, TContext, RequestObject {

    override val components: Map<String, TComponent>
        get() = context.components

    /**
     * The client-side accessor for one of this service's `serviceDataRelation`s
     * (`core-relation-system.md` §9.7): resolves [relationId] to its list request
     * object, so a typed property collapses to one line —
     *
     * ```kotlin
     * override val notifications
     *     get() = serviceRelation<Notification>(Relations.NOTIFICATIONS, Notification.Schema)
     * ```
     *
     * ⚠ **Worth doing for correctness, not brevity.** Hand-pairing
     * `listType(elementSchema)` with `roProvider().createRequestObject(...)` is a
     * known footgun class here: a registration that reads fine and dies at read
     * time as an opaque `error/internal` (the `Workout.Streams` and TS-4
     * incidents). One helper, one place to get it right.
     *
     * Phase 5.3 of `docs/adr/0009-operation-schema-typing.md` is why the parameterised
     * `listType(...)` matters: it binds the element type at construction, so the
     * `ListRo` factory does not have to infer it.
     */
    protected fun <E : Data> serviceRelation(
        relationId: String,
        elementSchema: CompositeSchema,
    ): TList<E> = context.roProvider().createRequestObject(
        type = listType(elementSchema),
        requestChain = requestChain.addReference(id).addReference(relationId),
        context = context,
    )
}
