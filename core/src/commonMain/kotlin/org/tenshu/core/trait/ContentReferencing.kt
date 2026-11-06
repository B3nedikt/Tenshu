package org.tenshu.core.trait

import org.tenshu.core.schema.trait
import org.tenshu.core.schema.valueTraitRequirement

/**
 * Declares that a data type's rows hold **content-plane references**, and how to compute which.
 *
 * ## ⭐ One requirement (CR-2 B3a, 2026-10-01; B6, 2026-10-04)
 *
 * - [CONTENT_ID] — **the slot every content id of the row derives from** (`File.ContentId`,
 *   `Workout.ContentId`). Each `Syncable(OnDemand)` property of the type declares how its own id
 *   derives from that slot with a [ContentDerivation] trait, so *which content a row references* is
 *   a schema declaration, answered by one pure function (`contentEdgesOf` in `:core-common`) from
 *   the payload in hand. That is what candidate B's edge registers need
 *   (`content-plane-cr2-quorumless.md` §10.11).
 *
 * ⚠ B6 retired the second requirement, `REFERENCE_ROOT` — the random per-row root that reference
 * handles (`H(root ‖ propertyId)`, N1) derived from — with the root properties themselves
 * (`File.ReferenceRoot`, `SharedFile`'s root, `Workout.ReferenceRoot`) and the relay's
 * `content_reference` table. Candidate B's registers name content by `(dataId, property)`, so the
 * relay holds no handle and nothing derives one (`content-plane-cr2-b6-spec.md` §1, quorumless §10.8).
 *
 * ⚠ A type that declares this trait but does not bind [CONTENT_ID] answers "cannot say" from
 * `contentEdgesOf`, never "references nothing". `SharedFile` is that case on purpose: its content
 * id is a positional composite field and its content property is not registered, so it joins only
 * once shared realms have a tombstone path (§10.12 Q6).
 *
 * ## ⚠ Why this is a requirement rather than a fixed property id
 *
 * The binding is explicit, resolved through [org.tenshu.core.core.SchemaProvider.resolveValueRequirement],
 * so the content plane never names `File` or `Workout` — the same shape [RealmData]
 * uses for `CREATED_AT`. A convention ("every type calls it `contentId`") would be unenforceable
 * and would fail silently on the one type that spelled it differently.
 *
 * ⚠ The trait deliberately carries **no marker semantics**: which types bear content is already
 * derivable from `Syncable(SyncStrategy.OnDemand)` on their properties, which
 * `SchemaDrivenContentWalk` reads. This trait answers only *where the values live*.
 */
interface ContentReferencing : Trait {
    companion object {
        const val ID = "tenshu/core/trait/contentReferencing"

        /**
         * Requirement id for the value every content id of the row derives from — see
         * [ContentDerivation]. Fulfilled with a `valueProperty` — a **named slot**, never a positional
         * composite field, so adding it reshapes no stored row. A blank or
         * absent value means the row references no content, and every content-bearing property
         * answers `none`.
         */
        const val CONTENT_ID = "$ID/contentId"

        val Schema: TraitSchema = trait(
            id = ID,
            traitRequirements = listOf(
                valueTraitRequirement(CONTENT_ID),
            ),
        )
    }
}
