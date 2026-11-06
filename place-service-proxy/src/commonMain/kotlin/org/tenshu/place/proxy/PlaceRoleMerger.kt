package org.tenshu.place.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.RoleSource
import org.tenshu.core.time.UnixInstant
import org.tenshu.sync.common.SemanticMerger

/**
 * Converges two devices' role rows for one place.
 *
 * The rule that matters is the first one; the second is only the tie-break underneath it:
 *
 *  - ⚠⚠ **[RoleSource.USER] beats [RoleSource.INFERRED], whichever side it is on.** This is the
 *    cross-device half of "inference must never overwrite the user" (`PlaceRole`'s KDoc). The local
 *    write path can honour that rule by reading before it writes; across devices nothing can — a
 *    phone that inferred `home` last night has no idea the laptop was told otherwise this morning,
 *    and under last-writer-wins whichever block happens to arrive second wins. So the guarantee has
 *    to be re-stated here, or it holds on one device and quietly fails on the others. Note it
 *    protects a **dismissal** (`role = none, roleSource = user`) exactly as strongly as a `home`:
 *    both are the user answering.
 *  - **Same provenance → the newest [PlaceRole.decidedAt] wins.** Two inferences, or two user
 *    answers, are the same *kind* of statement, so the later one is simply the more current one.
 *
 * Side-independent by construction, which is the property a merger actually needs: it fires only
 * when the incoming block is strictly newer, so any rule phrased as "keep mine" / "take theirs"
 * resolves by arrival order and two devices can land on different answers. Both rules above are
 * symmetric in `existing`/`incoming`, and the residual tie (same provenance, same instant, different
 * role) is broken by the lexicographically smaller role — arbitrary, but *identically* arbitrary
 * everywhere, which is the point.
 */
class PlaceRoleMerger : SemanticMerger {
    override val typeId: String = PlaceRole.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingIsUser = RoleSource.isUser(string(existing, PlaceRole.Fields.ROLE_SOURCE))
        val incomingIsUser = RoleSource.isUser(string(incoming, PlaceRole.Fields.ROLE_SOURCE))

        val existingAt = timestamp(existing).toEpochMilliseconds()
        val incomingAt = timestamp(incoming).toEpochMilliseconds()

        val winner = when {
            // The user's answer outranks a machine guess regardless of which side holds it, and
            // regardless of which is newer — a later inference does not get to revise a decision.
            existingIsUser != incomingIsUser -> if (existingIsUser) existing else incoming
            existingAt != incomingAt -> if (existingAt > incomingAt) existing else incoming
            // Same provenance, same instant: deterministic so peers agree. See the class KDoc.
            else -> if (string(existing, PlaceRole.Fields.ROLE) <=
                string(incoming, PlaceRole.Fields.ROLE)
            ) existing else incoming
        }

        return Entity.create<PlaceRole>(
            context = executionContext.context,
            composite = PlaceRole.create(
                context = executionContext.context,
                // The key, so both sides carry the same value by definition — read it off the
                // incoming row for consistency with the sibling mergers.
                placeId = string(incoming, PlaceRole.Fields.PLACE_ID),
                role = string(winner, PlaceRole.Fields.ROLE),
                roleSource = string(winner, PlaceRole.Fields.ROLE_SOURCE),
                decidedAt = timestamp(winner),
            ),
        )
    }

    private fun string(data: Data, fieldId: String): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
        is Composite -> data.getField<TString>(fieldId).get()
        else -> ""
    }

    private fun timestamp(data: Data): UnixInstant = when (data) {
        is Entity<*> -> data.getFieldValue(PlaceRole.Fields.DECIDED_AT)
        is Composite -> data.getField(PlaceRole.Fields.DECIDED_AT)
        else -> error("PlaceRole has no ${PlaceRole.Fields.DECIDED_AT}")
    }
}
