package app.tenshu.control.inference

import kotlinx.coroutines.flow.Flow

/** Where the running index pass has got to — the proxy holder's snapshot, in this module's terms. */
data class IndexPassPosition(
    /** Images visited so far — ⚠ including the ones skipped as already current. */
    val done: Int,
    /** Images the pass was handed — the library's size, not the count that will be indexed. */
    val total: Int,
)

/**
 * The read side of the proxy's `IndexProgressHolder`, for the Models screen's index bar.
 *
 * ⚠⚠ Exists because the `IndexProgress` stream op is **bounded**: it ends with the pass it was
 * watching, or after one idle tick, so a pass that started later — *Index now* pressed on the screen
 * itself — was never reported to a screen already open until the user pressed Refresh. That
 * bound is right for a cross-process subscriber (staying open would strand a proxy-side collector per
 * subscribe); Control's UI shares the proxy's process, so it reads the holder itself and needs none.
 *
 * ⚠ An interface for the same reason as [IndexCoverageSource]: the holder lives in
 * `:inference-service-proxy`, which this module must not see. `:control-shared-proxy` adapts it and
 * binds it under this type.
 */
interface IndexProgressSource {
    /** The running pass, or `null` when none is. ⚠ Never completes. */
    val progress: Flow<IndexPassPosition?>
}
