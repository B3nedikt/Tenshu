package app.tenshu.control.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tenshu.core.common.proxy.db.HlcWatermarkFile
import org.tenshu.core.common.proxy.db.sharedReplicaDirectory
import org.tenshu.sync.common.HlcWatermark
import org.tenshu.sync.common.HlcWatermarkStore

/**
 * The iOS [HlcWatermarkStore] (`sync-hlc-tip-device-identity.md` TC-2b): a type adapter over
 * [HlcWatermarkFile], which is where the mechanism and its tests live.
 *
 * ## ⚠⚠ The one decision this class makes
 *
 * The directory. [sharedReplicaDirectory] is the *same resolver the object store uses* — the
 * App-Group container when the entitlement grants one, app-local Documents when it does not — so
 * the watermark shares exactly when the replica shares, in both configurations. That is the
 * interface's invariant: a watermark describes **the store it sits beside**.
 *
 * ⚠⚠ Do not substitute a `SecureStorageDelegate`-backed store back in, which is what TC-2a had
 * here. `IosSecureStorageDelegate` sets no `kSecAttrAccessGroup`, so it is app-private while the
 * replica may be shared — the two disagree in both directions. A watermark **wider** than its store
 * makes this device claim it observed HLCs the store never saw, forging the tombstone-GC ack the
 * relay acts on (`MailboxStore.acknowledgementSet`) and dropping bodies it never applied; a
 * **narrower** one is merely the bug TC-2b exists to fix.
 *
 * ⚠ Deliberately thin, because `-proxy` modules have their iOS tests disabled at the root build
 * (the test link is the heaviest K/N step; enabling it across ~19 modules caused `Java heap space`
 * OOMs). Everything with behaviour is in [HlcWatermarkFile], covered by `HlcWatermarkFileTest` in
 * a module `iosTestCheck` runs. Keep it that way — logic added here is logic nothing runs.
 */
class IosSharedHlcWatermarkStore(
    directory: String = sharedReplicaDirectory(HlcWatermarkFile.SUBDIRECTORY),
) : HlcWatermarkStore {

    private val file = HlcWatermarkFile(directory)

    // `HlcWatermarkFile` is blocking (a small read, an atomic write, a bounded lock retry) and the
    // sync tick may run anywhere, so keep it off whatever thread called us.
    override suspend fun read(): HlcWatermark =
        withContext(Dispatchers.Default) { file.read().toWatermark() }

    override suspend fun raiseTo(candidate: HlcWatermark): HlcWatermark =
        withContext(Dispatchers.Default) { file.raiseTo(candidate.toStamp()).toWatermark() }

    private fun HlcWatermarkFile.Stamp.toWatermark() = HlcWatermark(physicalTime, logicalCounter)

    private fun HlcWatermark.toStamp() = HlcWatermarkFile.Stamp(physicalTime, logicalCounter)
}
