package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId

/**
 * A locally-derived snapshot of the proxy's sync health, pushed to connected
 * clients (Drive) over IPC so they can render the sync-status banner's transient
 * states (`docs/proposals/drive-connectivity-service.md` §2). Carries what the
 * Drive UI cannot otherwise observe from its side of the IPC boundary:
 *
 *  - [connectivity] — whether the relay was reachable on the last attempt.
 *  - [isSyncing] — a drain/pull is in flight this tick.
 *  - [pendingBlockCount] — size of the personal realm's outbound backlog.
 *  - [lastSyncedAtMillis] — wall-clock of the last successful pull (0 = never).
 *
 * **Locally-derived, NOT `Syncable`.** Exactly like [org.tenshu.storage.StorageStatus]:
 * each device's proxy maintains its own single row, keyed on the constant
 * [SINGLETON_KEY] so the relation upsert replaces rather than appends. The row
 * never leaves the device — it crosses only the already-authenticated Drive↔Control
 * IPC channel and adds no relay surface (House Rule #1 intact).
 *
 * All four fields are present even where a banner consumes only [connectivity] +
 * [isSyncing]: this is a **positional composite**, so adding a field later would
 * shift the layout and break a version-skewed Drive↔Control pair (two
 * separately-installed apps). Reserving the fields keeps the wire stable.
 */
interface SyncHealthStatus : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable single-row key; always [SINGLETON_KEY]. */
    val key: TString
        get() = getField(Field.KEY)

    /** [CONNECTIVITY_ONLINE] or [CONNECTIVITY_OFFLINE] — relay reachable last attempt. */
    val connectivity: TString
        get() = getField(Field.CONNECTIVITY)

    /** True while a drain/pull is in flight this tick. */
    val isSyncing: TBoolean
        get() = getField(Field.IS_SYNCING)

    /** Personal-realm outbound backlog size (pending blocks awaiting push). */
    val pendingBlockCount: TInteger
        get() = getField(Field.PENDING_BLOCK_COUNT)

    /** Wall-clock millis of the last successful pull; 0 if never synced. */
    val lastSyncedAtMillis: TInteger
        get() = getField(Field.LAST_SYNCED_AT_MILLIS)

    companion object {
        const val ID = "tenshu/sync/health/status"

        /** The one and only row's key. */
        const val SINGLETON_KEY = "current"

        const val CONNECTIVITY_ONLINE = "online"
        const val CONNECTIVITY_OFFLINE = "offline"

        /**
         * The relay is reachable but rejecting this device's *writes* (`402`, entitlement
         * enforcement) — the "needs attention on this device" case (Track A,
         * `docs/proposals/enforcement-followup-tracks.md`). Carried in the existing
         * [Field.CONNECTIVITY] string (a **new value**, not a new field) so the positional
         * composite layout is unchanged and a version-skewed Drive still parses the row — it
         * simply doesn't match [CONNECTIVITY_ONLINE] and degrades to its "offline" banner rather
         * than crashing. A current Drive maps it to a distinct "storage needs attention" banner.
         */
        const val CONNECTIVITY_NEEDS_ATTENTION = "needs_attention"

        /**
         * Push is withheld by the user's own policy (`WifiOnly` on a metered network — the
         * `SyncGate` is closed) while an outbound backlog waits. A benign, self-inflicted
         * *deferral*, NOT an error: Drive renders it as "Waiting for Wi-Fi. N items" with a
         * "Sync now over cellular" escape hatch, never the red warning
         * (`sync-metered-deferred-status.md` §2). Carried in the existing [Field.CONNECTIVITY]
         * string (a **new value**, not a new field) so the positional composite is unchanged and
         * a version-skewed Drive degrades to its "offline" banner rather than crashing.
         */
        const val CONNECTIVITY_DEFERRED = "deferred"

        /**
         * The realm is **locked** on this device — the proxy holds no derived keys, so it can
         * neither sync nor read the owner's entitlement. Outranks every other value: while
         * locked, connectivity is unknowable and irrelevant (the sync loop never reaches the
         * relay), and the storage figures collapse to `hasStorage = false` because
         * `currentOwnerId` is null. Without this value Drive cannot tell "no subscription"
         * from "sealed", and mis-renders a locked realm as *"Local only — set up storage"*,
         * pointing the user at a purchase instead of the unlock screen.
         *
         * Carried in the existing [Field.CONNECTIVITY] string (a **new value**, not a new
         * field) so the positional composite is unchanged and a version-skewed Drive degrades
         * to its "offline" banner rather than crashing.
         */
        const val CONNECTIVITY_LOCKED = "locked"

        /**
         * The relay is reachable but **does not recognise this device** (`401`): it holds no
         * registration for it — a restore from a backup predating this device's admission, an
         * operator data wipe, a move to a different relay host — or the device was revoked from
         * another of the user's devices.
         *
         * Outranks [CONNECTIVITY_NEEDS_ATTENTION] (an unrecognised device's writes are refused
         * before entitlement is ever consulted) and must never be reported as
         * [CONNECTIVITY_OFFLINE]: the relay answered. Saying "Offline" points the user at their
         * network while the actual state — nothing will ever sync until this device is re-admitted —
         * goes unsaid, and the device keeps emitting rejected requests for hours with no UI
         * anywhere reflecting it.
         *
         * A device holding the master identity repairs itself within a backoff window
         * (`SyncScheduler.recoverRegistration`), so in practice this value is what a pairing
         * **joiner** shows — it cannot re-admit itself and needs re-pairing.
         *
         * Carried in the existing [Field.CONNECTIVITY] string (a **new value**, not a new field) so
         * the positional composite layout is unchanged and a version-skewed Drive degrades to its
         * "offline" banner rather than crashing.
         */
        const val CONNECTIVITY_DEVICE_UNRECOGNIZED = "device_unrecognized"

        /**
         * The relay refused this device's file **content** with `507`: the owner is at their
         * storage quota, so nothing more will upload until bytes are freed or the tier rises.
         *
         * ⚠⚠ **Why this is not left to the client-side "storage full" check.** Drive also
         * derives `SyncBannerState.StorageFull` from `StorageStatusModel.isFull`, which asks
         * `remainingBytes <= 0`. That test is *false* for a realm with a sliver of room — a few
         * hundred bytes free on a 5 GiB tier — while the relay refuses every 2 MiB chunk. It also
         * lags — usage re-fetches on a 60 s backstop — so room consumed by **another of the
         * owner's devices** is invisible to it for up to a minute. So the two disagree exactly
         * when it matters, and the upload banner sits on "Uploading N of M" indefinitely while
         * every `PUT` is rejected. The relay's refusal is the ground truth.
         *
         * ⚠ Metadata sync is unaffected — the quota gate is on the content plane only — so this
         * must not be reported as offline or as a general sync failure.
         *
         * Carried in the existing [Field.CONNECTIVITY] string (a **new value**, not a new field) so
         * the positional composite layout is unchanged and a version-skewed Drive degrades to no
         * banner rather than crashing — the same escape hatch
         * [CONNECTIVITY_DEVICE_UNRECOGNIZED] uses.
         */
        const val CONNECTIVITY_STORAGE_FULL = "storage_full"

        /**
         * The relay is reachable but has been failing this device's file-**content** writes —
         * several `PUT`s in a row answered `5xx`/`429` or failed in transport — so uploads wait a
         * while before trying again (`control-background-work-off-fgs.md` §5.7). Metadata keeps
         * syncing. Self-resolving: the wait ends on its own, on the user's next upload, on a network
         * change, or when the app comes to the front.
         *
         * ⚠ Without it, a banner reads *"Uploading 0 of 12"* for up to 15 minutes with no reason.
         *
         * Carried in the existing [Field.CONNECTIVITY] string (a **new value**, not a new field), the
         * escape hatch [CONNECTIVITY_STORAGE_FULL] uses: the composite layout is unchanged, and a
         * version-skewed Drive reads it as "not online" and degrades to its plain banner.
         */
        const val CONNECTIVITY_RELAY_RETRYING = "relay_retrying"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            traits = listOf(
                SyncHealthStatusDataIdProvider.Schema,
            ),
            fields = {
                string(Field.KEY)
                string(Field.CONNECTIVITY)
                boolean(Field.IS_SYNCING)
                int(Field.PENDING_BLOCK_COUNT)
                int(Field.LAST_SYNCED_AT_MILLIS)
            },
        )

        fun create(
            context: TContext,
            connectivity: String,
            isSyncing: Boolean,
            pendingBlockCount: Long,
            lastSyncedAtMillis: Long,
        ): SyncHealthStatus = context.create(
            type = ID,
            fields = listOf(
                SINGLETON_KEY,
                connectivity,
                isSyncing,
                pendingBlockCount,
                lastSyncedAtMillis,
            ),
        )
    }

    object Field {
        const val KEY = "$ID/key"
        const val CONNECTIVITY = "$ID/connectivity"
        const val IS_SYNCING = "$ID/isSyncing"
        const val PENDING_BLOCK_COUNT = "$ID/pendingBlockCount"
        const val LAST_SYNCED_AT_MILLIS = "$ID/lastSyncedAtMillis"
    }
}

/**
 * Stable identity for the single [SyncHealthStatus] row — keyed on
 * [SyncHealthStatus.Field.KEY] (always [SyncHealthStatus.SINGLETON_KEY]) so the
 * proxy's relation upsert dedupes to one row. Mirrors `StorageStatusDataIdProvider`.
 */
interface SyncHealthStatusDataIdProvider : CompositeDataIdProvider<SyncHealthStatus> {

    override val schema: StringDataIdProviderSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/sync/health/status/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = SyncHealthStatus.Field.KEY,
        )
    }
}
