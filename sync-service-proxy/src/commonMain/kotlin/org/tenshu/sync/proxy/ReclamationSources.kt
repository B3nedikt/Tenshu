package org.tenshu.sync.proxy

import org.tenshu.core.file.sync.DeclarableContent

/**
 * What the content-declaration backfill needs that this module cannot reach
 * for itself, supplied by whoever wires the proxy.
 *
 * ⚠ Both live in `:file-service-proxy`, which `:sync-service-proxy` does not depend on and should
 * not. The composition point is the DI graph, which already sees both.
 *
 * ⭐ CR-2 B6 (`content-plane-cr2-b6-spec.md` §2) removed the device-side sweeps this used to feed —
 * ST-3b's `ContentReclamationSweep` and CR-5's `ContentEnumerationSweep` — with their candidate list
 * and live-set veto. ⚠⚠ **No device frees relay content any more**: the relay's own sweep
 * (`content-plane-cr2-b5-spec.md`, deployed `armed`) is the only freeing path. What is left here is
 * what the inventory found coupled to things that stay. The name is kept so the wiring at the three
 * platforms and the system-test module did not have to move with it.
 */
class ReclamationSources(
    /**
     * The backfill's enumeration (`content-plane-refcount.md` §9.7, CR-4d): every
     * `(holderHash, propertyId, contentId)` this realm's live content still carries.
     *
     * ⚠ It carries `(holder, property)` and not just the content id because
     * `DataId.getDataIdHash()` is one-way — a client can never recover a holder from an edge it
     * already sent, so provenance comes forward from the walk or not at all.
     *
     * ⚠⚠ A short walk declares fewer edges, which leaves them unreachable — a leak. So this returns
     * what it walked *and whether the walk finished*, and the pass declares the former while
     * refusing to latch on the latter.
     *
     * ⚠ `null` — the default — means no backfill exists on this platform: inert by absence.
     */
    val declarableContent: (suspend () -> DeclarableContent.Result)? = null,
    /** Whether a previous backfill pass recorded itself complete, and how to record it. */
    val readBackfillComplete: suspend () -> Boolean = { false },
    val writeBackfillComplete: suspend (Boolean) -> Unit = { },
)
