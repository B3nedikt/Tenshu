package app.tenshu.control.inference

import org.tenshu.inference.proxy.ArrivalQueue
import org.tenshu.inference.proxy.EmbeddingStore
import org.tenshu.inference.proxy.FaceTemplateStore
import org.tenshu.inference.proxy.MediaIndexRunner

/**
 * What the realm's local copy going takes with it (`inference-service.md` §34.6) — every store
 * DERIVED from it, all keyed by file path, where a path means a different photograph in the next
 * realm. Every platform's `SyncManagerImpl.onLocalStoreWiped` calls this one function.
 *
 * - ⚠⚠ **The pass is stopped first**, then the stores cleared: a pass suspended in the model holds
 *   paths of the realm being wiped and would repopulate the stores with them after the clear.
 * - The vectors.
 * - ⚠⚠⚠ The **face templates** — biometric (threat model A40): left behind, the next face pass groups
 *   people from a realm this device no longer belongs to.
 * - ⚠⚠ **IN-D6a's arrived paths**, memory and file: names of the old realm's photographs, which the
 *   next realm's first batch would otherwise work.
 */
suspend fun wipeDerivedInferenceState(
    runner: MediaIndexRunner,
    embeddings: EmbeddingStore,
    faces: FaceTemplateStore,
    arrivals: ArrivalQueue,
) {
    runner.cancelRunningPass()
    embeddings.clear()
    faces.clear()
    arrivals.clear()
}
