package app.tenshu.control.ui.screens.sync

import org.tenshu.identity.Device

/**
 * Roster rows that are **this device's own earlier identities**: same public key, different
 * `deviceId` (`drive-upload-stop-and-reclamation.md` §4.23, D14).
 *
 * ## Why this surface exists
 *
 * A device id and the keypair behind it have different lifetimes. `DesktopSecureStorageDelegate`'s
 * KDoc records the original mechanism — an in-memory delegate minted a fresh `DEVICE_ID` on every
 * launch while the persistent `objects.db` kept the old rows, so the roster "accumulated one
 * duplicate desktop entry per restart". Making the identity durable stopped *new* orphans; it never
 * reaped the ones already minted, and nothing since does.
 *
 * ⚠⚠ **They are not cosmetic.** `Device` rows are the realm roster, and the reclamation barrier
 * waits for every rostered device to publish an HLC tip past a tombstone
 * (`ReclamationBarrier`/`RealmConvergence`). An identity nothing runs as can never publish one, so
 * a single orphan blocks content reclamation for the whole realm, permanently. Measured on the live
 * realm 2026-09-06: `1 candidate(s), would reclaim 0 … waiting on 5 device(s)`, unchanged across 100
 * sweeps, one of the five being exactly this.
 *
 * ⚠⚠ **Nothing here revokes anything, deliberately.** The natural-looking fix — have the keypair's
 * owner silently revoke ids that are not its current one — is unsafe, because *two machines can hold
 * one keypair*: a restore from backup carries the secrets, a case this codebase handles explicitly
 * (`RealmRegistrar.reregisterThisDevice`, `SyncHealthFigures.deviceUnrecognized`, `sync/design.md`).
 * The restored machine would revoke the original, and `Device` rows have **no regeneration path**
 * (`tech-debt.md`), so the mistake is unrecoverable. A person can tell "my old desktop entry" from
 * "my other laptop" in a second; this code cannot. So it reports, and the existing confirm dialog
 * asks.
 *
 * ⚠ Only the group containing [currentDeviceId] is reported. Two *peers* sharing a key is the same
 * shape but a different claim — this replica cannot know which of them is live — and calling one of
 * those "previous" would be a guess presented as a fact.
 */
fun previousIdentitiesOfThisDevice(
    devices: List<Device>,
    currentDeviceId: String,
): Set<String> {
    if (currentDeviceId.isEmpty()) return emptySet()
    val current = devices.firstOrNull { it.deviceId.get() == currentDeviceId } ?: return emptySet()
    val currentKey = current.publicKey.get()
    // An absent/empty key identifies nothing — every row would match every other one.
    if (currentKey.isEmpty()) return emptySet()

    return devices
        .filter { it.deviceId.get() != currentDeviceId && it.publicKey.get().contentEquals(currentKey) }
        .map { it.deviceId.get() }
        .toSet()
}
