package org.tenshu.sync.proxy.impl

import org.tenshu.sync.common.SyncManager

/**
 * Why this device cannot approve a pairing, told apart rather than lumped together.
 *
 * ## The bug this fixes
 *
 * All three approval paths reported `currentOwnerId == null` as *"no account is set up on this
 * device"*. That is one of two very different states, and the common one is the other:
 *
 * - **Locked** — the realm is set up and its data is right here, but the master keys are not
 *   derived, so [SyncManager.currentOwnerId] (an in-memory field, populated only on unlock) reads
 *   null. **One tap fixes it.**
 * - **Not set up** — genuinely no realm on this device.
 *
 * ⚠⚠ Observed 2026-08-19 on the maintainer's desktop: a sealed Control refused to approve an iPhone
 * pairing with "no account is set up on this device" while its store held three `Device` rows, an
 * entitlement and an admission grant. The message sent the reader looking for a missing *account* —
 * a commerce-shaped problem — when the fix was to unlock. The device's own error log had it right
 * all along: 37 × *"realm locked — unlock this device to check for shared folders"* from
 * `pollPendingGrants`, which is the wording this now matches.
 *
 * ⚠ [SyncManager.UnlockStatus.isSetUp] is the discriminator, not `isLocked`: a device whose wrapper
 * exists is set up whether or not it happens to be sealed at this instant, and it is the *existence*
 * of the wrapper that tells us an account is there to unlock.
 */
internal fun SyncManager.pairingAuthorityFailure(): String {
    val unlock = runCatching { getUnlockStatus() }.getOrNull()
    return if (unlock?.isSetUp == true) {
        "Cannot approve pairing: realm locked — unlock this device to approve"
    } else {
        "Cannot approve pairing: no account is set up on this device"
    }
}
