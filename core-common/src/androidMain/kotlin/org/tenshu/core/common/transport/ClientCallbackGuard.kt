package org.tenshu.core.common.transport

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

    @Volatile
    private var controlUid: Int? = null

    @Volatile
    private var controlVerifiedAt = 0L

    /** UID → when it was last found not to be Control. Bounded; a stranger is one app. */
    private val refused = HashMap<Int, Long>()

    fun admits(callingUid: Int): Boolean {
        val now = nanoTime()
        if (callingUid == controlUid && now - controlVerifiedAt < CONTROL_TTL_NANOS) return true
        synchronized(refused) {
            val at = refused[callingUid]
            if (at != null && now - at < RECHECK_INTERVAL_NANOS) return false
        }
        if (isControlUid(callingUid)) {
            controlUid = callingUid
            controlVerifiedAt = now
            synchronized(refused) { refused.remove(callingUid) }
            return true
        }
        if (controlUid == callingUid) controlUid = null
        synchronized(refused) {
            if (refused.size >= MAX_REFUSED) refused.clear()
            refused[callingUid] = now
        }
        return false
    }

    /** The binding to Control died or disconnected: its UID is no longer known to be Control's. */
    fun forgetControl() {
        controlUid = null
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
