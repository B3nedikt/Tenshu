package org.tenshu.sync.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.sync.common.HlcWatermark
import org.tenshu.sync.common.HlcWatermarkStore

/**
 * [HlcWatermarkStore] over the platform [SecureStorageDelegate] — the implementation for
 * **single-process** platforms (`sync-hlc-tip-device-identity.md` TC-2a).
 *
 * ## Where this is correct, and where it is merely honest
 *
 * The interface's invariant is that a watermark describes *the store it sits beside*. On desktop
 * and Android that holds trivially: Control is the only process, so there is one runtime, one
 * store and one delegate, and "this process" and "this device" are the same thing. Desktop's
 * delegate is even literally co-located — `DesktopSecureStorageDelegate` writes
 * `local-store.properties` into the store directory.
 *
 * ⚠ On **iOS** this is durable but **app-private**, because `IosSecureStorageDelegate` sets no
 * `kSecAttrAccessGroup`. That is deliberate for TC-2a and it is *honest* — each app's watermark
 * describes that app's own store, and never claims progress the app has not made — but it does
 * **not** fix the multi-runtime false positive there, because Control-iOS and Drive-iOS still keep
 * separate watermarks under one shared deviceId. TC-2b replaces this on iOS with a file in
 * `sharedReplicaDirectory(…)` guarded by `FileAdvisoryLock`, whose sharing follows the replica's.
 * ⚠⚠ Do not "fix" iOS by giving this class a Keychain access group: the delegate would then be
 * shared even in builds where the *store* is not (no App-Group entitlement ⇒
 * `sharedReplicaDirectory` falls back to app-local Documents), and a watermark wider than its
 * store makes a device claim it applied tombstones it never saw.
 *
 * ⚠ On **web** this class is unsuitable for a different reason and is not used there:
 * `IndexedDbSecureStorageDelegate` fire-and-forgets its IDB puts, so two tabs racing a
 * read-modify-write would lose updates. `IndexedDbHlcWatermarkStore` does the merge inside one
 * `readwrite` transaction instead.
 *
 * The [Mutex] serialises in-process callers so a concurrent `raiseTo` cannot interleave its
 * read and write. It is *not* cross-process exclusion and does not pretend to be — on the
 * platforms this class is correct for, there is no second process to exclude.
 */
class SecureStorageDelegateHlcWatermarkStore(
    private val delegate: SecureStorageDelegate,
    private val key: String = DEFAULT_KEY,
) : HlcWatermarkStore {

    private val mutex = Mutex()

    override suspend fun read(): HlcWatermark = mutex.withLock { readUnlocked() }

    override suspend fun raiseTo(candidate: HlcWatermark): HlcWatermark = mutex.withLock {
        val current = readUnlocked()
        // Write-on-advance: a quiet device polls every 30 s and must not write every time.
        if (candidate <= current) return@withLock current
        delegate.storeString(key, encode(candidate))
        candidate
    }

    private fun readUnlocked(): HlcWatermark = decode(delegate.getString(key))

    companion object {
        /** Namespaced like the other `SyncManagerImpl.Keys` entries this delegate holds. */
        const val DEFAULT_KEY: String = "sync.hlc.watermark"

        /**
         * `<physicalTime>:<logicalCounter>`. A malformed or absent value decodes to
         * [HlcWatermark.NONE] rather than throwing — this is an *optimisation* over a clock that
         * still works without it, so a corrupt entry must degrade to "start from scratch", never
         * take sync down at startup.
         */
        internal fun encode(watermark: HlcWatermark): String =
            "${watermark.physicalTime}:${watermark.logicalCounter}"

        internal fun decode(raw: String?): HlcWatermark {
            val parts = raw?.split(':') ?: return HlcWatermark.NONE
            if (parts.size != 2) return HlcWatermark.NONE
            val physical = parts[0].toLongOrNull() ?: return HlcWatermark.NONE
            val counter = parts[1].toLongOrNull() ?: return HlcWatermark.NONE
            if (physical < 0L || counter < 0L) return HlcWatermark.NONE
            return HlcWatermark(physical, counter)
        }
    }
}
