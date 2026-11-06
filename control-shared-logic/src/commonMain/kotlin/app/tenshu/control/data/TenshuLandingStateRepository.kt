package app.tenshu.control.data

import kotlinx.coroutines.delay
import org.tenshu.core.TResult
import org.tenshu.core.getOrNull

/**
 * `LandingStateRepository` backed by the proxy's `SyncStatus` +
 * `UnlockStatus` ops. Two synchronous (proxy-local) round-trips:
 * `getUnlockStatus` reveals whether a wrapper is persisted and whether
 * it's currently sealed; `getSyncStatus` reveals whether a realm exists.
 *
 * Detection order:
 *  - `UnlockStatus.isLocked == true`: a wrapper exists and the master
 *    keys aren't loaded → `Locked` (route to §3.1).
 *  - Otherwise `SyncStatus.ownerId != null`: this device belongs to a realm
 *    → `Active` (route to Home).
 *  - Otherwise → `FirstTime` (route to §4.0 chooser).
 *
 * Note: a wrapper-set-up device with the keys loaded (`isSetUp=true,
 * isLocked=false`) flows through to the `ownerId` check, which is the
 * intended Active path.
 *
 * ## ⚠⚠ Why `ownerId` must be the *resident* one
 *
 * `FirstTime` routes to "Welcome to Tenshu / **Start fresh on this device**", and the
 * decision is taken **once** per launch — nothing re-detects, so a wrong answer stands
 * until the user kills the app. That makes this the least forgiving reader of
 * `SyncStatus`, and it was reading the wrong field: `ownerId` used to carry
 * `SyncManager.currentOwnerId`, which is populated by an autoload that runs
 * *asynchronously* behind an Argon2id stretch on a cold start. For the ~2–3 s that
 * takes on a phone, a fully provisioned device reported no owner, and Control-on-Android
 * opened on the fresh-install onboarding screen on roughly every cold start
 * (measured 2026-08-25: Welcome at a 0.5 s and 1.5 s probe, Home at 3 s).
 * The proxy now answers with `SyncManager.residentOwnerId` — the persisted membership
 * marker, which cannot be transiently blank — so the window is closed at the source.
 *
 * `Active` therefore means "this device belongs to a realm", not "the keys are loaded":
 * the two coincide a moment later, and `SyncStatus.isEnabled` is the flag for the latter.
 *
 * ⚠ A *failed* status read is not an answer either — treating one as "no realm" would put
 * the same destructive screen in front of a set-up user — so a failure is retried a few
 * times before this falls through to `FirstTime`.
 */
class TenshuLandingStateRepository(
    private val syncRepository: SyncRepository,
) : LandingStateRepository {

    override suspend fun detect(): LandingState {
        val unlock = syncRepository.getUnlockStatus().getOrNull()
        if (unlock?.isLocked?.get() == true) return LandingState.Locked

        val ownerId = readOwnerId()
        return if (ownerId != null) LandingState.Active else LandingState.FirstTime
    }

    /**
     * Reads `SyncStatus.ownerId`, retrying only while the op itself *fails*. A successful
     * read of "no owner" is the genuine first-run answer and returns immediately, so a real
     * fresh install still lands on onboarding without a stall.
     */
    private suspend fun readOwnerId(): String? {
        repeat(STATUS_READ_ATTEMPTS) { attempt ->
            val status = syncRepository.getSyncStatus()
            if (status is TResult.Success<*>) return status.getOrNull()?.ownerId?.get()
            if (attempt < STATUS_READ_ATTEMPTS - 1) delay(STATUS_RETRY_DELAY_MS)
        }
        return null
    }

    private companion object {
        const val STATUS_READ_ATTEMPTS = 3
        const val STATUS_RETRY_DELAY_MS = 300L
    }
}
