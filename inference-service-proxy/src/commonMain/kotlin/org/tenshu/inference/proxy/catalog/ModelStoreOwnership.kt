package org.tenshu.inference.proxy.catalog

/**
 * Whether **this process** is the one allowed to mutate and sweep a [DefaultModelStore].
 *
 * ⚠⚠ The store's own lock is a monitor, so it serialises threads and nothing else. Two processes
 * over one models directory each hold their own, and a sweep in one deletes what the other has
 * declared and not yet indexed — or has re-installed since the first one read the list. Whoever
 * constructs the store therefore says how the one-writer rule is met on its platform; there is no
 * default, so a new call site cannot inherit an answer by accident.
 *
 * ⚠ Asked **once**, when the store is constructed, and never again. A process that was refused
 * holds an index it read while another process could still write; letting it take over later would
 * have it sweep against that stale read.
 */
fun interface ModelStoreOwnership {

    /** `null` when this process owns the store, else why it does not. */
    fun refusal(): ModelStoreRefusal?

    companion object {
        /**
         * For a platform where at most one process can reach the store **by construction**, so there
         * is nothing to take. ⚠ A claim about the platform, not a fallback: Android (Control is the
         * only process that loads `proxyModule`, and its manifest declares no `android:process`) and
         * iOS (one app, Photos, owns inference and keeps the store in its own container; no other
         * graph is offered a model, so nothing else commits, evicts or declares). Desktop is not
         * such a platform — two Controls can run — and takes a file lock instead.
         */
        val SoleProcess: ModelStoreOwnership = ModelStoreOwnership { null }
    }
}

/**
 * Why a process may not write or sweep the model store.
 *
 * ⚠⚠ Two kinds, because a person can act on one and not the other in the same way. Telling
 * someone to quit "the other Control" when the lock file simply could not be created
 * sends them looking for a process that does not exist.
 */
sealed interface ModelStoreRefusal {

    /** The whole sentence, for a log line or a typed error's message. */
    val message: String

    /** Another process holds the lock. Quitting it and restarting this one is what changes that. */
    data class OwnedElsewhere(override val message: String) : ModelStoreRefusal

    /**
     * The lock could not be created or taken at all — permissions, an I/O failure, a filesystem
     * with no locks — so this process cannot tell whether it is alone.
     *
     * [detail] is what failed and where: the lock path and the cause. ⚠ No remedy is implied,
     * because none is known here.
     */
    data class LockUnavailable(override val message: String, val detail: String) : ModelStoreRefusal
}

/** A write reached a [DefaultModelStore] this process does not own. See [ModelStoreOwnership]. */
class ModelStoreNotOwnedException(val refusal: ModelStoreRefusal) : IllegalStateException(refusal.message)
