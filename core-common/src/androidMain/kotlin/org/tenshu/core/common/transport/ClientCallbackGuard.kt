package org.tenshu.core.common.transport

import java.util.concurrent.atomic.AtomicReference

/**
 * Who may call this app's `IClientInterface`, and how large a region it may hand over
 * (`core-client-resubscription.md` §4.8; threat model T-C-19).
 *
 * ⚠⚠ Every app's `ClientCallbackService` is exported with no permission — Control has to be able
 * to bind it — so any installed app can bind it too. Before `callbackShared` that let a stranger
 * feed fake stream elements and descriptors; with it, it could also make this app allocate
 * `ByteArray(region.size)` for a region of any size it chose. So every delivering method admits
 * **Control's UID only**, and a region over [BinderIpc.MAX_SHARED_MESSAGE_BYTES] is refused before
 * anything is allocated.
 *
 * The question asked is "does the CALLING uid belong to Control's package, and is that package
 * signed like the real Control" — the kernel's `Binder.getCallingUid()` answered through the package
 * manager, as Control answers it for its own callers (`ProxyService.admittedCallerPackage`), and
 * the signer checked against [FirstPartySigning]. Control's package is visible to every app through
 * its `<queries>` entry. Asked about the caller rather than about Control because Control's
 * UID can change under this process (uninstalled and reinstalled), and a UID resolved earlier would
 * then lock the real Control out; the smoke gate saw exactly that shape (2026-10-23).
 *
 * ⚠ A positive answer is cached for at most [CONTROL_TTL_NANOS], and dropped at once when the
 * binding to Control's `ProxyService` dies or disconnects ([forgetControl]): below API 33 a UID can
 * be reused after an uninstall, so "this UID was Control" must not outlive the Control it was. A
 * negative answer is cached for [RECHECK_INTERVAL_NANOS] **per UID**, so a stranger's calls are not
 * each a package-manager lookup (several UIDs — isolated processes — each get their own, and the
 * bounded table is reset when it fills).
 * ⚠ The signer check matters when Control is **not** installed. Then any sideloaded app can take
 * the name `app.tenshu.control.android`; without the check it could feed this app forged stream
 * elements and receive everything this app sends (threat model T-C-20).
 */
internal class ClientCallbackGuard(
    private val isControlUid: (uid: Int) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    /**
     * The cached positive answer and the [forgetControl] generation it belongs to, swapped as one
     * value so the UID and its verification time can never be read from two different answers.
     */
    private class Cache(val generation: Long, val uid: Int?, val verifiedAt: Long)

    private val cache = AtomicReference(Cache(generation = 0L, uid = null, verifiedAt = 0L))

    /** UID → when it was last found not to be Control. Bounded; a stranger is one app. */
    private val refused = HashMap<Int, Long>()

    /**
     * ⚠ The package-manager lookup runs unlocked on a Binder thread, and [forgetControl] can land
     * on main while it does. Its answer is therefore cached only if the generation it started under
     * is still current: a forget during the lookup must not be overwritten by a "this UID is
     * Control" from before it, or the forgotten UID would stay admitted for the whole TTL. The call
     * in flight is still admitted — the lookup was true when it ran — but the next one asks again.
     */
    fun admits(callingUid: Int): Boolean {
        val now = nanoTime()
        val seen = cache.get()
        if (callingUid == seen.uid && now - seen.verifiedAt < CONTROL_TTL_NANOS) return true
        synchronized(refused) {
            val at = refused[callingUid]
            if (at != null && now - at < RECHECK_INTERVAL_NANOS) return false
        }
        if (isControlUid(callingUid)) {
            cacheUnlessForgotten(since = seen.generation) { Cache(it.generation, callingUid, now) }
            synchronized(refused) { refused.remove(callingUid) }
            return true
        }
        cacheUnlessForgotten(since = seen.generation) {
            if (it.uid == callingUid) Cache(it.generation, uid = null, verifiedAt = 0L) else it
        }
        synchronized(refused) {
            if (refused.size >= MAX_REFUSED) refused.clear()
            refused[callingUid] = now
        }
        return false
    }

    /** The binding to Control died or disconnected: its UID is no longer known to be Control's. */
    fun forgetControl() {
        cache.updateAndGet { Cache(it.generation + 1, uid = null, verifiedAt = 0L) }
    }

    /**
     * Applies [next] unless a [forgetControl] has run since generation [since]. Retries only a race
     * with another `admits` in the same generation, so concurrent lookups both land; a forget wins.
     */
    private inline fun cacheUnlessForgotten(since: Long, next: (Cache) -> Cache) {
        while (true) {
            val current = cache.get()
            if (current.generation != since) return
            if (cache.compareAndSet(current, next(current))) return
        }
    }

    /** Throws back to the caller, which is never Control: Control's transactions always pass. */
    fun requireControl(callingUid: Int, method: String) {
        if (!admits(callingUid)) throw SecurityException("$method: uid $callingUid is not Control")
    }

    companion object {
        /** 10 s: how long a UID found not to be Control is refused without asking again. */
        const val RECHECK_INTERVAL_NANOS = 10_000_000_000L

        /** 60 s: how long a UID found to be Control is admitted without asking again. */
        const val CONTROL_TTL_NANOS = 60_000_000_000L

        private const val MAX_REFUSED = 64

        /** Checked before a byte of the region is allocated or mapped. */
        fun requireSharedMessageSize(size: Int) {
            if (size <= 0 || size > BinderIpc.MAX_SHARED_MESSAGE_BYTES) {
                throw SecurityException(
                    "callbackShared: a $size-byte region is outside 1..${BinderIpc.MAX_SHARED_MESSAGE_BYTES}"
                )
            }
        }
    }
}
