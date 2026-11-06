package org.tenshu.identity

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Service for handling identity and authentication.
 */
interface IdentityService : Service {


    /**
     * The realm's device roster — the chain accessor for [Relations.DEVICES], read like any other
     * service relation. Prefer this over the one-shot `getDevices` op for anything that should
     * track the roster rather than sample it.
     */
    val devices: TList<Device>

    /**
     * Per-device "last heard from" observations ([DeviceLastSeen]) — read alongside [devices] and
     * joined by `deviceId`. One chain read for the whole set rather than an op per row, which is
     * why this is a relation and not a `getDeviceLastSeen(id)` operation.
     *
     * ⚠ Locally derived and **not synced**; see [DeviceLastSeen]'s KDoc for why that is the point
     * rather than a limitation.
     */
    val deviceLastSeen: TList<DeviceLastSeen>

    /**
     * Each device's self-published hardware model ([DeviceHardware]) — read alongside [devices]
     * and joined by `deviceId`, like [deviceLastSeen]. Synced, unlike it.
     */
    val deviceHardware: TList<DeviceHardware>

    companion object {
        const val ID = "tenshu/identity"

        // `maintained = true` opts a relation into generic maintenance and
        // `chainReadable` says whether it is readable through the chain DSL at all
        // (`core-relation-system.md` §9.2.1). The four op-read relations below are
        // relations like the rest; the flag states the choice their KDocs state in
        // prose.
        val Schema = service(
            id = ID,
            // v1 = release baseline (core-schema-evolution.md Appendix A).
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.DEVICES,
                    type = TList.typeOf(Device.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.SHARED_REALM_MEMBERSHIPS,
                    type = TList.typeOf(SharedRealmMembership.Schema),
                    maintained = true,
                    chainReadable = false,
                ),
                serviceDataRelation(
                    id = Relations.MEMBERS,
                    type = TList.typeOf(Member.Schema),
                    maintained = true,
                    chainReadable = false,
                ),
                serviceDataRelation(
                    id = Relations.PENDING_GRANTS,
                    type = TList.typeOf(PendingGrant.Schema),
                    maintained = true,
                    chainReadable = false,
                ),
                serviceDataRelation(
                    id = Relations.RETAINED_REALM_KEYS,
                    type = TList.typeOf(RetainedRealmKey.Schema),
                    maintained = true,
                    chainReadable = false,
                ),
                serviceDataRelation(
                    id = Relations.DEVICE_LAST_SEEN,
                    type = TList.typeOf(DeviceLastSeen.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DEVICE_HARDWARE,
                    type = TList.typeOf(DeviceHardware.Schema),
                    maintained = true,
                ),
            )
        )
    }

    object Operations {
        const val GET_DEVICES = GetDevices.ID
        const val GET_SHARED_REALM_MEMBERSHIPS = GetSharedRealmMemberships.ID
        const val GET_REALM_MEMBERS = GetRealmMembers.ID
    }

    object Relations {
        const val DEVICES = "$ID/devices"

        /**
         * The [DeviceLastSeen] observations, one row per device this replica has ingested a write
         * from. Chain-readable (unlike the op-read relations below) because the roster reads it as
         * an ordinary list and joins it to [DEVICES] client-side.
         *
         * ⚠ Derived from local ingest, never synced — the array is rebuilt by the same generic
         * maintainer as [DEVICES], but its member rows are written only by `SyncProcessor` on this
         * device.
         */
        // ⚠⚠ NOT `"$ID/deviceLastSeen"` — relation ids and composite type ids share ONE namespace
        // in the registry, so a relation named exactly after its element type silently REPLACES
        // that type's schema: `getSchema("tenshu/identity/deviceLastSeen")` then returns the
        // relation schema, whose `traits` are empty, and every `saveData` of the composite dies
        // with "No DataIdProvider found … and it is not a simple value". `DEVICES`/`Device` avoid
        // it only by the accident of English pluralisation.
        const val DEVICE_LAST_SEEN = "$ID/lastSeenObservations"

        /**
         * The [DeviceHardware] rows, one per device that has published its own. Chain-readable and
         * joined to [DEVICES] client-side, like [DEVICE_LAST_SEEN].
         *
         * ⚠⚠ NOT `"$ID/deviceHardware"` — the same one-namespace collision [DEVICE_LAST_SEEN]'s
         * note describes: a relation named after its element type replaces that type's schema.
         */
        const val DEVICE_HARDWARE = "$ID/hardwareDescriptions"

        /**
         * The member's flat collection of [SharedRealmMembership] rows — every shared
         * realm they belong to (`drive-shared-realm-file-model.md` §6). The
         * array key behind the `getSharedRealmMemberships` op; derived on every (local
         * or sync-arriving) membership save by the generic relation maintainer, exactly
         * as `DEVICES` is. No chain accessor — the dedicated op is the read surface
         * (same rationale as the file-service `PLACEMENTS` array), which the schema
         * above says with `chainReadable = false`.
         */
        const val SHARED_REALM_MEMBERSHIPS = "$ID/sharedRealmMemberships"

        /**
         * The flat collection of in-realm [Member] rows across every shared realm this
         * device holds (`shared-realm-membership-grant.md` §9.2). The array behind
         * `getRealmMembers(realmId)` (which filters by `realmId`); derived on every
         * (local or sync-arriving) Member save. Dedicated-op read surface like
         * [SHARED_REALM_MEMBERSHIPS] ⇒ `chainReadable = false`.
         */
        const val MEMBERS = "$ID/members"

        /**
         * The flat collection of [PendingGrant] rows — grants the inbound poll
         * (`pollPendingGrants`) has discovered but the user has not yet
         * accepted (`shared-realm-membership-grant.md` §12). Derived on every (local
         * or sync-arriving) `PendingGrant` save; the poll op reads it to return the
         * full pending set. Dedicated-op read surface like [MEMBERS] ⇒
         * `chainReadable = false`.
         */
        const val PENDING_GRANTS = "$ID/pendingGrants"

        /**
         * The flat collection of [RetainedRealmKey] rows — retired `Kr`'s across every
         * shared realm this device holds (`shared-realm-membership-grant.md` §13.4).
         * Derived on every (local or sync-arriving) save; the keyring builder reads it
         * (filtering by `sharedRealmId`) to populate `RealmContext.historicalDerivedKeys`.
         * Internal read surface (no dedicated op) like [PENDING_GRANTS] ⇒
         * `chainReadable = false`.
         */
        const val RETAINED_REALM_KEYS = "$ID/retainedRealmKeys"
    }
}
