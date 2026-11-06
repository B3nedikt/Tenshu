package org.tenshu.core.relation

/**
 * Proxy-side metadata for an **entity relation** — a list-valued `Property` whose
 * elements are not stored inline on the receiver but live in a per-entity array
 * keyed by the receiver's own [DataId][org.tenshu.core.entity.DataId]
 * (`docs/proposals/core-nested-projection.md` §3.3).
 *
 * Example: `File.FileHistory` is the file's `FileModification` log. The descriptor
 * records `elementTypeId = FileModification.ID`; resolution builds
 * `EntityPropertyDataId(<the file's DataId>, propertyId)` and reads its array via
 * the existing `Database.getArrayData` mechanism. A maintained reverse-index
 * (`FileHistoryRelationMaintainer`, the `NotificationRelationMaintainer` pattern)
 * populates that array.
 *
 * This is a **local** registration detail (held in the `ModuleConfiguration`,
 * never serialised) — the call-site wire selector stays the uniform
 * `StaticPropertyRelation`. The per-entity `EntityPropertyDataId` keying is an
 * implementation concern, deliberately kept off the wire format. The reverse
 * (single, forward) direction `element → receiver` is a plain
 * [ReferencePropertyDescriptor]; this is its one-to-many counterpart.
 */
class EntityRelationDescriptor(
    /** Schema id of the array's element type (e.g. `FileModification.ID`). */
    val elementTypeId: String,
)
