package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId

/**
 * Interface for observing data changes in an [ExecutionContext].
 */
interface DataObserver {

    /**
     * Called when data has been saved to the database. [origin] carries why
     * the save happened — see [WriteOrigin]. Observers that want to short-
     * circuit on ingest-driven saves (e.g. `SyncObserver` skipping block
     * generation) read this parameter.
     */
    fun onDataSaved(data: Data, origin: WriteOrigin)

    /**
     * Called when data has been deleted from the database. [origin] mirrors
     * [onDataSaved] — `SyncProcessor` deletes during tombstone ingest pass
     * [WriteOrigin.SYNC_INGEST]; user-initiated deletes pass [WriteOrigin.USER].
     */
    fun onDataDeleted(dataId: DataId, origin: WriteOrigin)

    /**
     * Called inside [ExecutionContext.deleteData]'s transaction, **before** the row goes — the delete's
     * twin of the pre-write lane decision saves have ([LaneAwareDataObserver.admitsToLane]).
     *
     * ⚠⚠ The only moment the row being removed is still readable. CR-2 B4 is the first user:
     * `SyncObserver` records which content a removed holder named, so the drain can hold its removal
     * until every queued add of that content is acknowledged (`content-plane-cr2-b4-spec.md` §2.3).
     * Cascaded and reaped descendants pass through here too, each by its own `deleteData`.
     *
     * Default: nothing.
     */
    fun beforeDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit
}

/**
 * A [DataObserver] that additionally receives the **sync-lane id** a save was
 * issued under (task-scoped sync lane,
 * `docs/proposals/core-background-processing.md` §10.3 D3). The lane id is
 * threaded from [ExecutionContext.saveData]'s `syncLaneId` parameter; ordinary
 * observers never see it — [ObserverRegistry] dispatches the three-arg
 * overload only to implementors of this interface, so the 15+ existing
 * [DataObserver]s are untouched. `SyncObserver` is the intended (and v1 only)
 * implementor: a lane-tagged save stages into the lane's delta batch instead
 * of emitting a per-save `SyncBlock`.
 */
interface LaneAwareDataObserver : DataObserver {

    /** Lane-aware variant; `syncLaneId == null` is an ordinary save. */
    fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?)

    /**
     * Asked on the **saving thread, before [data] is written**, and only for a save tagged with a
     * lane: may it be staged into [syncLaneId]'s batch? `false` turns it into an ordinary
     * standalone save, which [onDataSaved] then receives with a `null` lane.
     *
     * ⚠⚠ Before the write, because the answer may depend on the row this save **replaces**, which
     * is unreadable once [data] lands. CR-2's §10.12 Q7 is the first user: a save that names content,
     * or clears content the stored row named, must reach the relay standalone with its edges, never
     * inside an opaque `DeltaBatchPayload` (`content-plane-cr2-quorumless.md` §10.12).
     *
     * Default: yes — lanes stage exactly as before.
     */
    fun admitsToLane(data: Data, origin: WriteOrigin, syncLaneId: String): Boolean = true

    /**
     * Asked on the **saving thread, before [data] is written**, for every save: what this observer
     * wants to know about the row the save **replaces**, handed back to the four-argument [onDataSaved].
     *
     * ⚠⚠ A token rather than observer state, because saves of one row on two threads would otherwise
     * pair one save's observation with the other's write. CR-2 B4 is the first user: the content ids a
     * save clears go into its block's edge sidecar (`content-plane-cr2-b4-spec.md` §2.3).
     *
     * Default: nothing to observe.
     */
    fun prepareSave(data: Data, origin: WriteOrigin): Any? = null

    /** [onDataSaved] with the [prepared] token [prepareSave] returned for this save. */
    fun onDataSaved(data: Data, origin: WriteOrigin, syncLaneId: String?, prepared: Any?) =
        onDataSaved(data, origin, syncLaneId)

    override fun onDataSaved(data: Data, origin: WriteOrigin) =
        onDataSaved(data, origin, syncLaneId = null)
}
