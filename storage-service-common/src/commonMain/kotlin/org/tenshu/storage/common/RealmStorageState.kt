package org.tenshu.storage.common

/**
 * Why the realm's relay traffic is blocked, if it is — the shared decision behind every app's
 * storage banner.
 *
 * ## Why this is not Drive's problem
 *
 * `StorageSyncGate` gates the **personal realm's push at the scheduler**, not per app and not per
 * endpoint: `storageAllowsSync(figures) = figures?.hasStorage == true`. So when it shuts, *every*
 * app's data stops leaving the device together — Fit workouts, Timeline moments, Calendar events,
 * Photos, Contacts. Only Drive says anything about it today, and Drive is the app where a user is
 * least surprised by storage talk; Fit silently not recording is where the confusion actually lands.
 *
 * ⭐ Measured 2026-08-24 (`ios-services-status-and-purchase.md` §3.5): a realm created on iOS sat at
 * **0 blocks** on the relay with two devices correctly paired and registered, because its host had
 * no subscription. The only message anywhere was *"your storage details aren't available yet"* —
 * which promises an outcome that cannot arrive. ⚠⚠ The rescue required signing in from a **second,
 * non-iOS device**; an iOS-only user has no in-app path out at all. That is what makes this a
 * correctness affordance rather than a monetisation prompt.
 *
 * ## ⚠⚠ The two states are different mechanisms, not degrees of the same one
 *
 * | | condition | what the gate does | consequence |
 * |---|---|---|---|
 * | [NoPlan] | `hasStorage == false` | **shut** | nothing pushes, from any app, ever |
 * | [Full] | `hasStorage == true`, over limit | **open** | pushes are attempted and the relay 402s |
 *
 * Reading them as "more and less broken" gets the copy wrong. [NoPlan] blocks *everything* and is
 * silent; [Full] blocks *new bytes* and at least produces a rejection. They also want different
 * sentences — "set up storage" versus "you are out of space".
 */
sealed interface RealmStorageState {

    /** Storage is bound and has room — say nothing. */
    data object Ok : RealmStorageState

    /**
     * No storage plan is bound, so the personal realm is local-only and **nothing syncs at all**.
     *
     * ⚠ The copy must not imply data loss: local reads and writes are unaffected and the blocks
     * stay queued, so this is "not leaving this device yet", never "lost".
     */
    data object NoPlan : RealmStorageState

    /**
     * Storage is bound but at or over its limit.
     *
     * ⚠ Carries the figures because "storage full" is indistinguishable from a bug to a user who
     * believes they have space; "10.9 GB of 5 GB used" is checkable.
     */
    data class Full(val usedBytes: Long, val limitBytes: Long) : RealmStorageState
}

/**
 * The pure decision, over whatever shape an app already holds its storage figures in.
 *
 * ⚠ **Unknown is [RealmStorageState.Ok], deliberately.** `hasStorage == null` means the figures have
 * not been derived yet, and banners that flash during the startup window are worse than a banner
 * that arrives a second late — the same optimistic-on-unknown convention Drive's `SyncBanner` and
 * the Tier-1/Tier-2 op guards already follow. ⚠⚠ Note this is the *inverse* of `StorageSyncGate`,
 * which fails **closed** on unknown so a starting device does not spam the relay. Both are right:
 * blocking traffic and accusing the user of a problem are not the same act.
 */
fun evaluateRealmStorage(
    hasStorage: Boolean?,
    usedBytes: Long,
    limitBytes: Long,
): RealmStorageState = when {
    hasStorage == null -> RealmStorageState.Ok
    !hasStorage -> RealmStorageState.NoPlan
    usedBytes >= limitBytes -> RealmStorageState.Full(usedBytes, limitBytes)
    else -> RealmStorageState.Ok
}
