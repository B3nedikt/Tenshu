package org.tenshu.core.common.transport

import java.util.concurrent.ConcurrentHashMap

/**
 * Which Binder callers Control serves: first-party Tenshu apps only (threat model T-C-20).
 *
 * ⚠⚠ Control's `ProxyService` is exported with no `android:permission`. Every first-party app has to
 * bind it, and no manifest permission covers ten apps with ten Play signing keys on API 24
 * ([FirstPartySigning]). So the check is made here, in code, before anything is dispatched. Before
 * it, any installed app could bind, announce, and read and write the whole realm.
 *
 * The caller is the kernel's `Binder.getCallingUid()`, answered through the package manager. A
 * caller cannot choose either. The uid is admitted when one of its packages is first-party
 * ([isFirstParty]). The answer is that package, which then serves as the session's client id.
 * Control's own uid is admitted without asking.
 *
 * ⚠ Answers are cached per uid, with the same windows as the app side's `ClientCallbackGuard`. A
 * call must not cost a package-manager lookup each time, and a stranger's calls must not either. An
 * admission holds for at most [ADMITTED_TTL_NANOS] and is dropped at [forgetAdmitted] (the last
 * binding went away): below API 33 an uninstalled app's uid can be reused by another app, so "this
 * uid was Drive" must not outlive the Drive it was. A refusal holds for [REFUSED_TTL_NANOS], per
 * uid, in a bounded table that is reset when it fills.
 *
 * Third-party apps (Rei, `tenshu-rei.md`) are not added to the first-party set. When they come, they
 * get a second way in: a uid whose package the user granted in Control and whose signer matches the
 * digest in its signed Rei publication. Until then every other caller is refused.
 */
class BinderCallerAdmission(
    private val selfUid: Int,
    private val selfPackage: String,
    private val packagesForUid: (uid: Int) -> Array<String>?,
    private val isFirstParty: (packageName: String) -> Boolean,
    private val onRefused: (uid: Int, packages: List<String>) -> Unit = { _, _ -> },
    private val nanoTime: () -> Long = System::nanoTime,
) {

    private class Admitted(val packageName: String, val at: Long)

    private val admitted = ConcurrentHashMap<Int, Admitted>()
    private val refused = ConcurrentHashMap<Int, Long>()

    /** The first-party package [callingUid] is serving as, or `null` when it is refused. */
    fun admittedPackage(callingUid: Int): String? {
        if (callingUid == selfUid) return selfPackage
        val now = nanoTime()
        admitted[callingUid]?.let { if (now - it.at < ADMITTED_TTL_NANOS) return it.packageName }
        refused[callingUid]?.let { if (now - it < REFUSED_TTL_NANOS) return null }

        val packages = packagesForUid(callingUid).orEmpty().toList()
        val firstParty = packages.firstOrNull { it in FirstPartySigning.PACKAGES && isFirstParty(it) }
        if (firstParty != null) {
            admitted[callingUid] = Admitted(firstParty, now)
            refused.remove(callingUid)
            return firstParty
        }
        admitted.remove(callingUid)
        if (refused.size >= MAX_REFUSED) refused.clear()
        refused[callingUid] = now
        onRefused(callingUid, packages)
        return null
    }

    /**
     * [admittedPackage], or a [SecurityException] back to the caller. Binder carries that exception
     * to the calling app, where it becomes a typed failure rather than a crash.
     */
    fun requireAdmitted(callingUid: Int, method: String): String =
        admittedPackage(callingUid)
            ?: throw SecurityException("$method: uid $callingUid is not a first-party Tenshu app")

    /** Every binding went away: no uid stays admitted on the strength of an earlier lookup. */
    fun forgetAdmitted() {
        admitted.clear()
    }

    companion object {
        /** 60 s: how long an admitted uid is served without asking again. */
        const val ADMITTED_TTL_NANOS = 60_000_000_000L

        /** 10 s: how long a refused uid is refused without asking again. */
        const val REFUSED_TTL_NANOS = 10_000_000_000L

        private const val MAX_REFUSED = 64
    }
}
