package app.tenshu.control.inference

import org.tenshu.inference.proxy.catalog.ModelStoreRefusal

/**
 * Whether **this** Control may change the model store, for Control's Models screen — process-local,
 * like [LastIndexCoverage].
 *
 * ⚠⚠ Why it exists: a second desktop Control is refused the store's file lock for as long as it
 * lives (`photos-people-and-pets.md` §12.12, *one process at a time*). That is a standing state, not
 * a per-call failure, so the screen should say it before anyone taps and take the buttons away —
 * not let a download or a removal fail and then explain.
 *
 * ⚠ Process-local on purpose: Control's UI runs in the proxy's process, so a Koin single needs no
 * op and no schema change. The typed `ModelUnavailable.Reasons.STORE_NOT_OWNED` is the backstop for
 * a caller that does not read this.
 */
class ModelStoreAccess(
    override val refusal: ModelStoreAccessSource.Refusal?,
    override val detail: String = "",
) : ModelStoreAccessSource

/** The store's own refusal, as the Models screen reads it. ⚠ Each kind stays its own kind. */
fun modelStoreAccessOf(refusal: ModelStoreRefusal?): ModelStoreAccess = when (refusal) {
    null -> ModelStoreAccess(refusal = null)
    is ModelStoreRefusal.OwnedElsewhere -> ModelStoreAccess(ModelStoreAccessSource.Refusal.OwnedElsewhere)
    is ModelStoreRefusal.LockUnavailable ->
        ModelStoreAccess(ModelStoreAccessSource.Refusal.LockUnavailable, detail = refusal.detail)
}
