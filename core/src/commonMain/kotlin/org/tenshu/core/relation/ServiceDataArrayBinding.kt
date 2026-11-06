package org.tenshu.core.relation

import org.tenshu.core.reference.Reference
import org.tenshu.core.reference.ServiceTypeReference

/**
 * The `(service, ref)` lookup key of one **collection-shaped** [ServiceDataRelation]
 * plus the element type its array holds — the answer to the reverse question
 * *"which service-data arrays hold rows of type X?"*
 * ([SchemaProvider.serviceDataArraysHolding][org.tenshu.core.core.SchemaProvider.serviceDataArraysHolding]).
 *
 * Why a key and not a resolved array reference: [service] + [ref] are exactly what
 * `ExecutionContext.resolveServiceData(service, ref)` already takes, so a consumer
 * hands the pair straight back to the existing resolver and gets the very same
 * `ArrayDatabaseReference` an ordinary chain read would. Re-deriving the reference
 * (relation id off `to`, element type off `type.params`, `isEntity` off the element
 * schema) would fork that logic — and a fork here silently reads the *wrong array*
 * rather than failing.
 *
 * [elementTypeId] is carried because it is the thing the reverse walk matched on and
 * consumers usually need it again (a tombstone names the deleted entity's type).
 *
 * A **local** registration detail — derived from what `ModuleConfiguration`
 * already holds, never serialised. Like [ReferencePropertyDescriptor].
 */
class ServiceDataArrayBinding(
    /** The owning service's reference — the first half of the `serviceDataRelations` key. */
    val service: ServiceTypeReference,
    /** The relation's target reference (an `EntityListReference`) — the second half. */
    val ref: Reference,
    /** Schema id of the array's element type (the relation's `TList<E>` parameterisation). */
    val elementTypeId: String,
)
