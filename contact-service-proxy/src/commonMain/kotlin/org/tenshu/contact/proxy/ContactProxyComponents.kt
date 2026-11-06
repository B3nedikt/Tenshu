package org.tenshu.contact.proxy

import kotlinx.coroutines.sync.Mutex
import org.tenshu.contact.common.InviteRelayClient
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.common.TombstoneEmitterHolder

/**
 * Dependency bundle for the contact-service proxy implementations
 * (`contact-invitation-handshake.md` §7.3). [identityKeyPair] is the narrow
 * A13 seam — production wires `syncManager.getRealmEncryptionKeyPair()`
 * without this module reaching into `SyncManager`; `null` means the
 * realm is locked / the device holds no A13 yet, and the interactive ops
 * fail with a typed error.
 */
class ContactProxyComponents(
    val relayClient: InviteRelayClient,
    val cryptoManager: SyncCryptoManager,
    val identityKeyPair: () -> SyncCryptoManager.KeyPair?,
    val redemptionStore: PendingRedemptionStore,
    val responseStash: InviteResponseStash = InviteResponseStash(),
    /** Invitee-side ack poll cadence (§6: slow poll; tests shrink it). */
    val ackPollIntervalMs: Long = 30_000L,
    /** Per-session ack-wait budget (attempts × interval ≈ 1 h by default). */
    val ackPollMaxAttempts: Int = 120,
    /**
     * The external byte store a card's relocated `PHOTO` lines live in (`contacts-app.md` §9.10),
     * shared with files and workout streams and namespaced `/.contacts/…`. ⚠ Required, like the
     * health factory's: an optional default would silently strand photo blobs.
     */
    val fileDatabase: FileDatabase,
    /**
     * The entity-then-bytes emit seam (`RecordWorkout`'s). `null` in harnesses without sync — the
     * photos then stay device-local, which local reads never notice.
     */
    val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    /**
     * ⚠ `DeletePerson`'s tombstones. `null` makes the delete **device-local** —
     * `SyncObserver.onDataDeleted` emits nothing by design, the tombstone is the op's duty — which
     * also leaves the photo's content edge alive on the relay. Acceptable only in a sync-less harness.
     */
    val tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
    /**
     * ⚠⚠ Serialises every [ContactSource][org.tenshu.contact.ContactSource] write and the photo sweep
     * on this runtime (`writeContactSource`): one per proxy, which is what
     * makes it per source key too. Held from the stored-row read to the journalled intent, never
     * across an upload.
     */
    val sourceWriteLock: Mutex = Mutex(),
    /** How long a photo save waits for the row's block before journalling anyway (the upload's bound). */
    val emitIdleBoundMs: Long = 30_000L,
)
