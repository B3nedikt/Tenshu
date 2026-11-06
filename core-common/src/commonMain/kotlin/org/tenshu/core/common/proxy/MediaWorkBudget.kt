package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * **The process's one CPU lane for media work** — thumbnail and preview renders, and the index
 * pass's decode and inference (`media-processing-pipeline-sweep.md` §4.3, decision D4, phase P3).
 *
 * ⚠⚠ Before this, all of that ran on bare `Dispatchers.Default`, which is also where Control serves
 * every app's IPC (`ProxyModule`'s `serviceScope`). A backfill or an *Index now* pass could occupy
 * every core, and the apps' reads waited behind it (F7). The lane caps media work at
 * [PARALLELISM] threads, so the rest of `Default` stays free to serve IPC.
 *
 * ⚠ **One instance per process, and this object is that instance.** Two lanes of 2 would be a cap of
 * 4, which is no cap. Every consumer defaults to [lane] rather than having it threaded through the
 * file-op constructors. The minter is shared by upload, backfill and mint-on-view, so it is the one
 * seam for renders. A test passes its own dispatcher only to observe the bound, never to escape it.
 *
 * ⚠ **IPC serving must never run on it.** A read that waited for the lane would queue behind a
 * library's worth of renders, which recreates the starvation this object exists to remove, only
 * the other way round. That is why `OnnxInferenceBackend.embedText` (a search query) stays off it.
 *
 * ⚠ It is a *view* of `Default` (`limitedParallelism`), not a new pool. Media threads still come
 * from `Default`'s workers, but at most [PARALLELISM] of them at a time. Priority between upload
 * minting, derive and backlog (§4.3's second bullet) is a later phase; this lane is first come,
 * first served.
 */
object MediaWorkBudget {

    /** D4: k = 2. ⚠ Raise it only on a re-measure (§5 D4); a phone has 8 cores and IPC needs some. */
    const val PARALLELISM: Int = 2

    val lane: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(PARALLELISM, "media-work")
}
