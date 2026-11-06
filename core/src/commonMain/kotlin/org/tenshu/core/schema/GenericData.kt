package org.tenshu.core.schema

import org.tenshu.core.Data

/**
 * Marker schema for a **generic value** field — one whose concrete runtime `Data`
 * type isn't known statically from the schema (declared via [genericData]).
 *
 * The DB layer stores such a field as a self-describing
 * `ObjectStore.DbValue.GenericValue` (concrete type id + payload) rather than
 * guessing value-vs-reference from the field schema, so it can carry a primitive
 * (`TBoolean`, `TString`, ...) **or** a composite interchangeably — see
 * `DbEntryMapper`. Used by `Setting.value` (preference-service), where the value
 * type varies per preference.
 *
 * Deliberately distinct from a plain `composite(id, Data.Schema)` field (e.g.
 * `DataBlockPayload.value`): those stay on the existing composite-by-reference
 * storage, unchanged.
 */
object GenericData {
    const val ID = "tenshu/core/schema/genericData"

    val Schema: CompositeSchema = composite(id = ID, parent = Data.Schema)
}
