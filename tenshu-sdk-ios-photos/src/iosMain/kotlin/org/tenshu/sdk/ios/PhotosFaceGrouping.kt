package org.tenshu.sdk.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import org.tenshu.inference.proxy.AiReadiness
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.MediaIndexRunner
import org.tenshu.inference.proxy.faceChangingPasses

/**
 * Whether this phone groups faces, for People (`ios-face-grouping.md` F7, §17) — what the installed
 * graph says, asked each time the Collections tab lays out, and when it changes.
 *
 * @property runsHere ⚠⚠ `true` only over a graph that binds the face backend, whose face readiness is
 *   not retired (a teardown between installs) and whose preflight has not found the phone unsuitable:
 *   People's lines there would send the user to download a model this phone cannot run.
 * @property modelPending the face model is installed and still being prepared or probed — the proxy
 *   answers `NO_BACKEND` then, and "download the face model" would ask for one the phone already has.
 * @property changes emits whenever either answer may have moved, so a screen can lay out again: the
 *   tab otherwise recomputes only when the Collections state publishes.
 * @property passChanges emits when an index pass of this graph ended having changed the face
 *   template store (`MediaIndexRunner.faceChanges`, through `faceChangingPasses`) — ⚠⚠ what People is re-read for (`ios-face-grouping.md`
 *   §17.6): the templates are device-local and the Core ML backend writes no label, so the library
 *   watch never sees a pass.
 */
internal class PhotosFaceGrouping(
    val runsHere: () -> Boolean,
    val modelPending: () -> Boolean,
    val changes: Flow<Any>,
    val passChanges: Flow<Unit> = emptyFlow(),
) {
    companion object {
        /**
         * Over the graph's face readiness ([org.tenshu.control.di.PhotosOnDeviceAi.FACE_READINESS])
         * and its index runner, which [runner] resolves.
         *
         * ⚠ [runner] is asked on [Dispatchers.Default], when the tab first collects: the runner's
         * construction opens the inference stores, which is not work for the main thread. A graph
         * that cannot answer (torn down) emits nothing — its successor's install replaces it.
         * ⚠ Emits once as soon as the runner is resolved: what changed before that is unknowable.
         */
        fun over(readiness: AiReadiness, runner: () -> MediaIndexRunner?): PhotosFaceGrouping = PhotosFaceGrouping(
            runsHere = { !readiness.isRetired() && readiness.current() != AiReadinessState.Unsuitable },
            modelPending = {
                !readiness.isRetired() && readiness.current().let {
                    it == AiReadinessState.Preparing || it == AiReadinessState.AwaitingVerdict
                }
            },
            changes = combine(readiness.state, readiness.retiredNow) { state, retired -> state to retired }
                .distinctUntilChanged(),
            passChanges = flow {
                val faceChanges = withContext(Dispatchers.Default) { runCatching { runner() }.getOrNull() }?.faceChanges
                    ?: return@flow
                // ⚠⚠ ONE invalidation the moment the count is first read, never a silent baseline.
                // The reader's first walk began BEFORE this lookup returned (it is off-main, and the
                // view model's first refresh does not wait for it), so a pass that ended in between
                // is inside the baseline — and later passes over the rows it wrote move nothing.
                // Cost: at most one extra walk per subscription (a Collections construction, a rebuild).
                emit(Unit)
                emitAll(faceChangingPasses(faceChanges))
            },
        )

        /** The `-photosFixture` stand-in: a phone that groups faces, whose readiness never moves. */
        val FIXTURE: PhotosFaceGrouping = PhotosFaceGrouping({ true }, { false }, emptyFlow())
    }
}
