package app.tenshu.control.inference

/**
 * Whether this process may change the model store, as the Models screen reads it: the read side
 * of the proxy's `ModelStoreAccess`.
 *
 * ⚠ An interface for the same reason as [IndexCoverageSource]: the answer is made in
 * `:control-shared-proxy`, from a store type this module must not see.
 */
interface ModelStoreAccessSource {
    /** Why this process reads what is installed and changes nothing, or `null` when it may write. */
    val refusal: Refusal?

    /** For [Refusal.LockUnavailable]: what failed and where — the lock path and the cause. */
    val detail: String

    /**
     * ⚠⚠ Two kinds, kept apart all the way to the sentence: quitting "the other Control" is the
     * answer to one and no answer at all to the other.
     */
    enum class Refusal {
        /** Another Control process holds the store's lock. */
        OwnedElsewhere,

        /** The lock could not be created or taken at all, so this Control cannot tell if it is alone. */
        LockUnavailable,
    }
}
