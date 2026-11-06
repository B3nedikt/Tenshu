package org.tenshu.core.relation

/**
 * Proxy-side metadata for a **reference value property** — a `Property.Value<T>`
 * whose value is not stored on the receiver but resolved live from a *foreign*
 * entity keyed by one of the receiver's own fields
 * (`docs/proposals/core-reference-property.md`).
 *
 * Example: `FileModification.Device` keeps a plain `deviceId: TString` field;
 * the descriptor records `keyFieldRef = FileModification.Field.DEVICE_ID`,
 * `targetTypeId = Device.ID`, and resolution dereferences
 * `StringDataId(targetTypeId, <deviceId>)` via the existing `Database.getData`.
 *
 * This is a **local** registration detail (held in the `ModuleConfiguration`,
 * never serialised) — the wire selector stays the uniform `StaticPropertyRelation`.
 * Resolution-by-dataId is an implementation concern, deliberately kept off the
 * wire format.
 */
class ReferencePropertyDescriptor(
    /** Field on the *receiver* that holds the target's key (e.g. `deviceId`). */
    val keyFieldRef: String,
    /** Schema id of the foreign entity to resolve (e.g. `Device.ID`). */
    val targetTypeId: String,
)
