package org.tenshu.ingest.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.tenshu.core.ingest.IngestSource

/**
 * Which background-ingest runs are in flight **on this device**, as an in-memory, per-proxy signal
 * (`drive-upload-progress.md` §14.1). One entry per `IngestSource.key` currently reporting
 * [IngestSource.STATUS_RUNNING].
 *
 * **Why this exists at all.** An app that ingests in bulk (Drive's Synced-Folders drain) is a
 * *producer* on the same upload queue the proxy is draining, and it narrates the run itself with
 * figures the proxy cannot derive — the drain's total is known before the first upload, where the
 * proxy only ever sees its queue's current size. Control's foreground notification therefore needs
 * to know that *somebody else owns this story*, which is exactly the reconciliation
 * `drive-upload-progress.md` P6 made between the banner and Drive's folder-upload bar.
 *
 * ⚠⚠ **Not the `IngestSource` row, deliberately.** The obvious carrier is the registry row's
 * `lastStatus` / `pendingCount`, and it is the wrong one twice over:
 *  - the row is `Syncable(SyncStrategy.Eager)`, so driving live progress through it would push a
 *    sync block **per photo** — which is why `SyncedFolderController` reports only when its status
 *    *code* changes, leaving `pendingCount` frozen at whatever the run started with;
 *  - the relation is synced, so it also carries **other devices'** rows. A backup running on the
 *    user's tablet must not silence this phone's notification.
 *
 * Updating from the op implementation instead gets the device scoping for free: a row arriving in a
 * `SyncBlock` never runs an op, so everything this registry sees was reported by an app *here*.
 *
 * ⚠ In-memory, so a proxy restart mid-run forgets the run: the notification falls back to its own
 * pass figures until the app reports its next status change (in Drive's case, the end of the drain).
 * The cost is the pre-existing behaviour for the remainder of one run, which is why it is not worth
 * a durable row — see §14.1 for the alternative that was weighed.
 */
class IngestRunRegistry {

    private val _runningSources = MutableStateFlow<Set<String>>(emptySet())

    /** `IngestSource.key`s (`"sourceId@deviceId"`) whose last locally-reported status is RUNNING. */
    val runningSources: StateFlow<Set<String>> = _runningSources.asStateFlow()

    /** Whether any locally-driven ingest run is in flight right now. */
    val anyRunning: Boolean get() = _runningSources.value.isNotEmpty()

    /**
     * Record a status an app just reported for [key]. Every status other than
     * [IngestSource.STATUS_RUNNING] — up-to-date, paused, out of space, error, idle — ends the run,
     * so a paused drain hands the story back rather than silencing the notification indefinitely.
     */
    fun onStatusReported(key: String, status: String) {
        _runningSources.update { running ->
            if (status == IngestSource.STATUS_RUNNING) running + key else running - key
        }
    }
}
