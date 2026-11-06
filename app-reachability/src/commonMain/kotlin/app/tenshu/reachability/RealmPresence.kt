package app.tenshu.reachability

/**
 * Reads two **successful** answers from Control — `syncService.getUnlockStatus().isLocked` and
 * `syncService.getSyncStatus().ownerId` — as the `controlHasRealm` verdict [ControlReachability]
 * takes (`ADR 0025` §Decision 8). The same two reads, in the same order, as Control's own
 * landing router (`TenshuLandingStateRepository.detect`): Locked first, then owner, else
 * first-run.
 *
 * - `isLocked` → `true`: an unlock wrapper exists and is sealed, so something *was* set up here.
 *   ⚠ Checked first because the router checks it first: a device whose wrapper predates the
 *   persisted owner slot reports no owner while locked, and reading that as "not set up" would
 *   wall a set-up user out of their own library. And a locked realm is never walled anyway —
 *   the maintainer's decision of 2026-10-03 (§Decision 8.1): locked is temporary, and unlocking
 *   is the existing locked surface's job.
 * - `ownerId == null` → `false`: the read succeeded and named no owner. That is the proxy's
 *   *resident* owner — the persisted membership marker (`SyncManager.residentOwnerId`) — so it is
 *   not the transient blank of a cold-start autoload, and it is exactly what Control's router
 *   reads as `FirstTime`.
 * - blank → `null`: an answer that is present and says nothing is not evidence of absence.
 * - anything else → `true`.
 *
 * ⚠⚠ **Only for reads that succeeded.** A failed read never reaches here: callers use
 * `TResult.get()`, which throws, and the engine reads a throw as *cannot tell*. Mapping a failure
 * to `false` would wall a set-up user out of their own library on any transient error — the
 * mirror of telling them to install an app they already have (§Decision 4).
 *
 * ⚠ Here rather than in each app because the judgement is the same nine times and is the part
 * worth pinning; the reads themselves stay at each app's wiring site, which is where
 * `:sync-service` is registered (an unregistered one fails at dispatch, which is also a throw,
 * which is also *cannot tell*).
 */
fun realmPresence(isLocked: Boolean, ownerId: String?): Boolean? = when {
    isLocked -> true
    ownerId == null -> false
    ownerId.isBlank() -> null
    else -> true
}
