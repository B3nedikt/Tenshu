package org.tenshu.core.place

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.schema.timestamp
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * "[placeId] is the user's **home**" (or **work**, or explicitly *neither*) — the standing role a
 * place plays in the user's life, as opposed to the incidental fact that they were there.
 *
 * One row per place, keyed by [placeId], so recording a role twice folds rather than accumulating.
 *
 * ## Inference vs. the user's own answer
 *
 * [roleSource] carries which of the two wrote this row, and the distinction is the whole point:
 *
 *  - [RoleSource.INFERRED] — something *derived* the role (say, from where the user spends nights).
 *    It is a **suggestion**, to be offered for confirmation, never asserted as fact.
 *  - [RoleSource.USER] — the user answered. It is the truth, full stop.
 *
 * ⚠⚠ **Inference must never overwrite a `USER` row.** This is exactly the shipped
 * [Place.labelSource] marker (`geocoded` vs `user`, §4.4) applied one concept over: a machine guess
 * may be improved by a better guess, but it may not talk over the person it is guessing about. The
 * write path is expected to honour it, and `PlaceRoleMerger` enforces it across devices.
 *
 * ⚠ **A dismissal is not a separate concept.** "No, this is not my home" is
 * `role = `[PlaceRoles.NONE]`, roleSource = `[RoleSource.USER] — *"I am telling you this place has
 * no special role"*. Modelling dismissal as its own flag would have made "dismissed" and "answered
 * NONE" two states that must be kept consistent forever; as one row they cannot disagree, and the
 * merge rule that protects a user's `HOME` protects a user's `NONE` for free.
 *
 * ## Why strings, not enums
 *
 * [role] and [roleSource] are plain strings, following [FingerprintProvenance] / `MovementModes`.
 * The schema DSL has no enum field kind, and — more importantly — a newer peer can introduce `gym`
 * or `school` with **no schema change at all**: this build stores and syncs the unfamiliar value
 * verbatim and simply does not offer it in the UI, rather than failing to parse the row. An
 * unrecognised [roleSource] degrades to [RoleSource.INFERRED], the *weaker* one, so a stale or
 * garbled value can never wrongly outrank something the user actually said.
 *
 * ## ⚠⚠ Why this is a sibling row and not a property on [Place]
 *
 * Two independent reasons, either one sufficient:
 *
 *  1. **`Place` cannot gain a field.** It rides as a composite parameter of the `recordPlace`
 *     operation, so its bytes inline into every embedding row, and E2's chain verify rejects the
 *     nested reshape outright ("Nested reshapes are not yet supported") until overlay orchestration
 *     lands. The general rule is on `SchemaMigrationRegistry.verify`.
 *  2. **A `Place` save replaces the whole row.** `RecordPlaceImplementation` saves the **bare
 *     `Place` composite** read out of the op params — a composite has no `properties` map, so
 *     anything attached to the entity is discarded along with it. Three callers do exactly that
 *     (geocoder enrichment, the user rename, `PlaceReconciler`), so a role hung off `Place` would
 *     be silently erased by the next unrelated write to the place — deletion by omission, a
 *     replace-write dropping what it does not know (the same class `MoveFileImplementation` guards).
 *
 * A separately-keyed row is structurally immune to both — which is why [PlaceMerge] and
 * [PlaceNetworkFingerprint] are rows rather than properties too.
 */
interface PlaceRole : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The place this is about — the primary key, so a place has at most one role row. */
    val placeId: TString
        get() = getField(Fields.PLACE_ID)

    /**
     * [PlaceRoles.HOME], [PlaceRoles.WORK] or [PlaceRoles.NONE] — or, from a newer peer, a value
     * this build has never heard of, which it stores and syncs verbatim. See the string-vs-enum
     * note in the type KDoc.
     */
    val role: TString
        get() = getField(Fields.ROLE)

    /**
     * [RoleSource.INFERRED] (a suggestion awaiting confirmation) or [RoleSource.USER] (the user's
     * own answer). ⚠⚠ Inference must never overwrite a `USER` row — see the type KDoc.
     */
    val roleSource: TString
        get() = getField(Fields.ROLE_SOURCE)

    /** When this assignment was made — the tie-break between two rows of equal [roleSource]. */
    val decidedAt: UnixInstant
        get() = getField(Fields.DECIDED_AT)

    companion object {
        const val ID = "tenshu/place/placeRole"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                PlaceRoleDataIdProvider.Schema,
                // Eager: the rows are tiny and one per *labelled* place, and a role is exactly the
                // kind of thing a second device should already know before it renders a feed.
                // Merge: two devices can decide different roles for one place — an inference here
                // racing the user's answer there — and last-writer-wins would let the guess win
                // (`PlaceRoleMerger`).
                Syncable(SyncStrategy.Eager, ConflictStrategy.Merge),
            ),
            fields = {
                string(Fields.PLACE_ID)
                string(Fields.ROLE)
                string(Fields.ROLE_SOURCE)
                timestamp(Fields.DECIDED_AT)
            }
        )

        /**
         * ⚠ [fields] is **positional** and must match the `fields { }` declaration order above
         * exactly — it is the declaration order that decides the wire layout, not this function's
         * parameter list. They are deliberately kept identical here so the two can be read side by
         * side.
         *
         * [roleSource] defaults to the *weaker* [RoleSource.INFERRED] for the same reason
         * [Place.create] defaults `labelSource` to `geocoded`: a caller that forgot to say must
         * never accidentally mint something that outranks the user.
         */
        fun create(
            context: TContext,
            placeId: String,
            role: String,
            roleSource: String = RoleSource.INFERRED,
            decidedAt: UnixInstant,
        ): PlaceRole = context.create(
            type = ID,
            fields = listOf(placeId, role, roleSource, decidedAt)
        )
    }

    object Fields {
        const val PLACE_ID = "$ID/placeId"
        const val ROLE = "$ID/role"
        const val ROLE_SOURCE = "$ID/roleSource"
        const val DECIDED_AT = "$ID/decidedAt"
    }
}

/**
 * The roles a place can play. Open by construction — see the string-vs-enum note on [PlaceRole]:
 * a newer peer may sync `gym` or `school`, and this build must carry those through untouched rather
 * than choke on them.
 */
object PlaceRoles {
    /** Where the user lives. */
    const val HOME = "home"

    /** Where the user works. */
    const val WORK = "work"

    /**
     * No special role. ⚠ Paired with [RoleSource.USER] this is a **dismissal** — "I am telling you
     * this place has no special role" — not an absence of information. An absence of information is
     * the absence of a row.
     */
    const val NONE = "none"
}

/**
 * How a [PlaceRole] came to be — the same two-value, string-encoded provenance shape as
 * [LabelSource] and [FingerprintProvenance], for the same reason: an unrecognised or empty value
 * degrades to the *weaker* one, so a stale value can never wrongly outrank something the user
 * actually said.
 */
object RoleSource {
    /** Derived by inference — a suggestion the UI offers for confirmation, never an assertion. */
    const val INFERRED = "inferred"

    /** The user's own answer — including a [PlaceRoles.NONE] dismissal. Never machine-overwritten. */
    const val USER = "user"

    /** True when [source] denotes a user-authored role (anything not exactly [USER] is not). */
    fun isUser(source: String): Boolean = source == USER
}

val Entity<out PlaceRole>.role: TString
    get() = getFieldValue(PlaceRole.Fields.ROLE)

/** Content-addresses a role by the place it is about — at most one role row per place. */
interface PlaceRoleDataIdProvider : CompositeDataIdProvider<PlaceRole> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/place/placeRole/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PlaceRole.Fields.PLACE_ID
        )
    }
}
