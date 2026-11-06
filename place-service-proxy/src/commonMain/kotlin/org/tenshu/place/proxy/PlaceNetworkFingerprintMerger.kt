package org.tenshu.place.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.place.FingerprintProvenance
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.time.UnixInstant
import org.tenshu.sync.common.SemanticMerger

/**
 * Converges two devices' bindings for **one network** (`timeline-recognition-and-place-naming.md`
 * §9.3 / F4) — the case where the phone learned this access point at one place and the tablet
 * learned it at another, because one of them had a drifted fix at the time.
 *
 * One rule, and it is the same one [org.tenshu.core.place.LabelSource] applies to names: a
 * **`user`** binding — the user named the place while standing on this network (§8.4 F2) — beats a
 * **`learned`** one, on whichever side it sits. Between two bindings of equal provenance the newer
 * `observedAt` wins: a network genuinely can move (a router carried to a new flat), and the most
 * recent observation is the best evidence of where it is now.
 *
 * ⚠ Side-independent by construction, because a merger only fires when the incoming block is
 * strictly newer (`SyncProcessor`) — "prefer whichever side is `user`" survives that; "keep mine"
 * would not.
 */
class PlaceNetworkFingerprintMerger : SemanticMerger {
    override val typeId: String = PlaceNetworkFingerprint.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val winner = pick(existing, incoming)
        return Entity.create<PlaceNetworkFingerprint>(
            context = executionContext.context,
            composite = PlaceNetworkFingerprint.create(
                context = executionContext.context,
                networkTag = string(incoming, PlaceNetworkFingerprint.Fields.NETWORK_TAG),
                placeId = string(winner, PlaceNetworkFingerprint.Fields.PLACE_ID),
                provenance = string(winner, PlaceNetworkFingerprint.Fields.PROVENANCE),
                observedAt = timestamp(winner),
            ),
        )
    }

    private fun pick(existing: Data, incoming: Data): Data {
        val existingIsUser = FingerprintProvenance
            .isUser(string(existing, PlaceNetworkFingerprint.Fields.PROVENANCE))
        val incomingIsUser = FingerprintProvenance
            .isUser(string(incoming, PlaceNetworkFingerprint.Fields.PROVENANCE))
        return when {
            incomingIsUser != existingIsUser -> if (incomingIsUser) incoming else existing
            // Equal provenance → the more recent observation; a router really can move.
            timestamp(incoming).toEpochMilliseconds() >= timestamp(existing).toEpochMilliseconds() -> incoming
            else -> existing
        }
    }

    private fun string(data: Data, fieldId: String): String = when (data) {
        is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
        is Composite -> data.getField<TString>(fieldId).get()
        else -> ""
    }

    private fun timestamp(data: Data): UnixInstant = when (data) {
        is Entity<*> -> data.getFieldValue(PlaceNetworkFingerprint.Fields.OBSERVED_AT)
        is Composite -> data.getField(PlaceNetworkFingerprint.Fields.OBSERVED_AT)
        else -> error("PlaceNetworkFingerprint has no ${PlaceNetworkFingerprint.Fields.OBSERVED_AT}")
    }
}
