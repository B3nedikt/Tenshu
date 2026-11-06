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
 * A **network → place** binding: "the Wi-Fi access point with this tag was seen at this place"
 * (`docs/proposals/timeline-recognition-and-place-naming.md` §9.3 / F4).
 *
 * Wi-Fi place recognition (§4.5) began device-local, which meant a new phone relearned every
 * binding from scratch — and, worse, **re-fragmented the user's places while it did**, because the
 * recognition steps can only see what *that* device has learned (§9.1b). Syncing the bindings makes
 * recognition a property of the *user*, not of one handset, and unlocks two things the device-local
 * form could not:
 *  - **cross-device corroboration** — two devices reporting the same tag are inside one access
 *    point's range, tens of metres, which beats either device's GPS indoors (§10.4);
 *  - **a place identity for a device with no GPS at all** — a laptop can recognise a place a phone
 *    taught it, with no location permission whatsoever (§10.5).
 *
 * ⚠⚠ **[networkTag] is a hash, never a raw BSSID** — `SHA-256(salt ‖ "bssid:" ‖ lowercase(bssid))`.
 * The lookup is exact-match, so a tag is functionally identical to the raw value, and the raw MACs of
 * the user's home, employer and friends' routers then never sit in plaintext in the realm where a
 * diagnostics export, an error report or a backup inspection could surface them. It is **not** a
 * defence against an attacker holding the realm plaintext (the salt must ride in the realm for other
 * devices to compute the same tag, and the BSSID space is small) — which is acceptable and written
 * down in §9.3: the realm already holds the exact coordinates of every place the user dwells and a
 * polyline of every trip, so a network identifier adds no new *class* of information. What syncing
 * changes is blast radius, one device to N.
 *
 * ⚠ **BSSID tags only** (§10.4, Q9 settled). One SSID can span a campus, a chain or a mesh, so it is
 * not colocation evidence; and SSIDs are user-authored strings that frequently contain a person's
 * name. The SSID index stays device-local.
 *
 * ⚠ **Personal realm only.** A shared-folder peer must never learn the owner's networks; nothing
 * writes these into a shared realm and nothing should.
 */
interface PlaceNetworkFingerprint : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The hashed network identifier — the primary key. See the ⚠⚠ note in the type KDoc. */
    val networkTag: TString
        get() = getField(Fields.NETWORK_TAG)

    /** The [Place.placeId] this network was seen at. */
    val placeId: TString
        get() = getField(Fields.PLACE_ID)

    /**
     * [FingerprintProvenance.LEARNED] (capture noticed this network at that place) or
     * [FingerprintProvenance.USER] (the user named the place while on it — §8.4 F2's rename-time
     * binding). The merger prefers a `user` binding, exactly as [LabelSource] governs labels.
     */
    val provenance: TString
        get() = getField(Fields.PROVENANCE)

    /** When this binding was last (re)affirmed — the tie-break between two equal provenances. */
    val observedAt: UnixInstant
        get() = getField(Fields.OBSERVED_AT)

    companion object {
        const val ID = "tenshu/place/networkFingerprint"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                PlaceNetworkFingerprintDataIdProvider.Schema,
                // Eager: a binding must already be present when the next dwell is written, and the
                // rows are tiny. Merge: two devices can bind one network to different places (§9.3).
                Syncable(SyncStrategy.Eager, ConflictStrategy.Merge),
            ),
            fields = {
                string(Fields.NETWORK_TAG)
                string(Fields.PLACE_ID)
                string(Fields.PROVENANCE)
                timestamp(Fields.OBSERVED_AT)
            }
        )

        fun create(
            context: TContext,
            networkTag: String,
            placeId: String,
            observedAt: UnixInstant,
            provenance: String = FingerprintProvenance.LEARNED,
        ): PlaceNetworkFingerprint = context.create(
            type = ID,
            fields = listOf(networkTag, placeId, provenance, observedAt)
        )
    }

    object Fields {
        const val NETWORK_TAG = "$ID/networkTag"
        const val PLACE_ID = "$ID/placeId"
        const val PROVENANCE = "$ID/provenance"
        const val OBSERVED_AT = "$ID/observedAt"
    }
}

/**
 * How a [PlaceNetworkFingerprint] came to be — the same two-value, string-encoded provenance shape as
 * [LabelSource], for the same reason: an unrecognised or empty value degrades to the *weaker* one, so
 * a stale value can never wrongly outrank something the user actually said.
 */
object FingerprintProvenance {
    /** Capture observed this network at that place. */
    const val LEARNED = "learned"

    /** The user named the place while connected to this network (§8.4 F2). */
    const val USER = "user"

    /** True when [provenance] denotes a user-authored binding (anything not exactly [USER] is not). */
    fun isUser(provenance: String): Boolean = provenance == USER
}

val Entity<out PlaceNetworkFingerprint>.networkTag: TString
    get() = getFieldValue(PlaceNetworkFingerprint.Fields.NETWORK_TAG)

/** Content-addresses a binding by its [PlaceNetworkFingerprint.networkTag] — one row per network. */
interface PlaceNetworkFingerprintDataIdProvider : CompositeDataIdProvider<PlaceNetworkFingerprint> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/place/networkFingerprint/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = PlaceNetworkFingerprint.Fields.NETWORK_TAG
        )
    }
}
