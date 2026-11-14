package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.db.StoreHandleFenced
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.NoOpDatabaseLock
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.WipeableObjectStore
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.common.util.Bip39InvalidMnemonicException
import org.tenshu.core.common.util.HLCManager
import org.tenshu.core.common.util.PairingUri
import org.tenshu.core.common.util.generateId
import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.common.util.pbkdf2Sha512
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.FirstPullMarker
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.common.encodeAdmissionCertChain

class SyncManagerImpl(
    private val cryptoManager: SyncCryptoManager,
    private val storageDelegate: SecureStorageDelegate,
    context: TContext,
    // Dispatcher for the off-thread legacy auto-load (see `init`). Defaults
    // to `Dispatchers.Default` in production; tests inject `Unconfined` to
    // make the auto-load run inline/deterministically.
    autoLoadDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // A1 (sync-cursor-store-lifecycle.md): the pull cursor persists here, in the object store,
    // so it shares the applied data's lifecycle — not in [storageDelegate] (Keychain on iOS),
    // whose independent survival across a container wipe caused the cursor/store desync.
    // ⚠ Production DI MUST inject the same [ObjectStore] the `Database` is built over (all three
    // `PlatformProxyModule`s do: `objectStore = get()`; so does the web's `JsRuntime`, which omitted
    // it until 2026-10 and re-pulled from 0 on every page load). Last param with a throwaway in-memory
    // default so the many unit tests that construct `SyncManagerImpl` positionally keep compiling
    // and get an isolated cursor; using the default in production would put the cursor in a store
    // that is NOT the data store, silently defeating A1.
    private val objectStore: ObjectStore = InMemoryObjectStore(),
    /**
     * Device-local state DERIVED from the realm, cleared when the realm's local copy is
     * ([wipeLocalStore]) — today the inference vector store, contributed by Control's proxy module.
     *
     * ⚠⚠ Derived state that outlives the realm is worse than stale, it is WRONG: vectors are keyed by
     * file path, and the next realm's `/Photos/IMG_0001.jpg` is a different photograph. A search
     * would answer with the old realm's picture and the new one would never be indexed.
     *
     * ⚠ A hook rather than a call, because `:sync-service-proxy` must not know what a vector is —
     * composition across services is Control's job (`inference-service.md` §26.4). ⚠ Its failure
     * cannot fail the wipe: the realm copy going is the irreversible half, and a derived store that
     * would not clear is reported by the caller's own note, never by stranding a half-wiped device.
     */
    private val onLocalStoreWiped: suspend () -> Unit = {},
    /**
     * The lock the `Database` over [objectStore] runs under. Every cursor row below is read and
     * written inside it.
     *
     * ⚠⚠ No [ObjectStore] is thread-safe on its own — each one's contract is "called under the
     * `DatabaseLock`" — and the cursor rows are the one access that does not come through
     * `DatabaseImpl`: the scheduler reaches them from its own coroutine. Unlocked, a cursor write
     * races every read an op makes: in the in-memory store a cursor insert that grows the map can
     * make a concurrent lookup land in the half-moved table and read a present row as absent.
     *
     * ⚠ Same trap as [objectStore], and the same reason for a default: production DI MUST pass the
     * `Database`'s own lock (`storeLock = get<DatabaseLock>()`), or this is silently inert.
     * `SyncManagerStoreLockWiringTest` audits the three `PlatformProxyModule`s. On the web,
     * `JsRuntime` passes its `Database`'s (no-op, single-threaded) lock; `JsSyncCursorDurabilityTest`
     * pins only the store half there, not the lock. The no-op default
     * pairs with the private in-memory store a unit test gets — a store nothing else touches.
     */
    private val storeLock: DatabaseLock = NoOpDatabaseLock(),
) : SyncManager {

    /** A cursor-row access: under [storeLock], like every other access to [objectStore]. */
    private inline fun <R> cursorRows(crossinline block: ObjectStore.() -> R): R =
        storeLock.withLock { objectStore.block() }

    /**
     * ⚠⚠ DERIVED from the A4 key, never minted (`deviceIdForPublicKey`, threat model A4 / workstream B):
     * the relay refuses any registration whose id is not the key's, so an id stored on its own could
     * only ever be wrong. [Keys.DEVICE_ID] is kept as a plaintext **cache** of the derived value for
     * the readers that run before this object exists (the store-migration context, the iOS route-1
     * hand-off); a stale value there (a pre-derivation UUID) is overwritten here, never trusted.
     *
     * ⚠ Read on first use, because it reads the A4 key from the *secret* bucket — construction
     * touches only the plaintext one, and an eager read would move a Keychain/Keystore access into
     * DI assembly. [hlcManager] is read on first use for the same reason.
     *
     * ⚠⚠ **Derived from the key held NOW, never memoised for the process.** This was `by lazy`, and
     * [forgetThisDevice] replaces the key in a running process: the lazy kept answering the
     * forgotten id, so a pairing offered straight after *Forget this device* presented the removed
     * identity and the relay's F2′ refused it (`403 device-removed`, seen 2026-11-11 on desktop) until
     * Control was restarted (`admission-revocation-names-the-capability.md` §7i.5). The id is held
     * beside its pair in one atomic ([identity]), so a new key is a new id at the very next read.
     */
    override val currentDeviceId: String
        get() = currentDeviceIdentity().deviceId

    private val hlcContext: TContext = context

    override var currentOwnerId: String? = null
        private set

    // The durable half of the pair (see `SyncManager.residentOwnerId`). `initialize` /
    // `initializeWithKeys` persist the owner id to `Keys.OWNER_ID` precisely so this
    // question survives a restart; reading it here is what keeps the async autoload
    // window below from looking like a factory-fresh device to every durable caller.
    // Falls back to the slot only when the session has not (yet) loaded — once the
    // autoload lands, the in-memory value wins and the two agree by construction.
    override val residentOwnerId: String?
        get() = currentOwnerId ?: storageDelegate.getSecret(Keys.OWNER_ID)

    /**
     * The clock stamps every local event with [currentDeviceId], so it follows the id: after
     * [forgetThisDevice] the next read is a clock under the new id, never the forgotten one.
     *
     * ⚠ The successor is seeded from its predecessor (a raise, never a reset): the HLC tip is this
     * process's clock (`HlcWatermarkStore`), and a fresh `(0,0)` would publish a tip below one the
     * process already published.
     */
    @OptIn(ExperimentalAtomicApi::class)
    override val hlcManager: HLCManager
        get() {
            val id = currentDeviceId
            while (true) {
                val held = hlcHolder.load()
                if (held != null && held.deviceId == id) return held
                val next = HLCManager(id, hlcContext)
                held?.current()?.let { tip -> next.seed(tip.physicalTime.get(), tip.logicalCounter.get()) }
                if (hlcHolder.compareAndSet(held, next)) return next
            }
        }

    @OptIn(ExperimentalAtomicApi::class)
    private val hlcHolder = AtomicReference<HLCManager?>(null)

    // A1: the cursor is a reserved row in the object store, so it is wiped with the data (a
    // container wipe / reinstall resets it to 0 → back-fill) and can never outlive the store —
    // the desync the Keychain-backed cursor allowed is structurally gone. `Keys.LAST_SYNC_TIMESTAMP`
    // is retired; `wipeDeviceState` still purges the stale Keychain item for a clean migration.
    override var lastSyncTimestamp: Long
        get() = cursorRows { loadSyncCursor() }
        set(value) { cursorRows { storeSyncCursor(value) } }

    // Per-lane tombstone cursor (`sync-lane-cursor-split.md` L1). Absent ⇒ 0: every writer moves
    // both lanes together, so an absent row means a fresh store, which back-fills from zero.
    override var lastTombstoneSyncTimestamp: Long
        get() = cursorRows { loadTombstoneCursor() ?: 0L }
        set(value) { cursorRows { storeTombstoneCursor(value) } }

    // C2 backfill floor. ⚠ No seed-from-a-sibling fallback: an absent row genuinely means "never walked", and inventing a floor would silently skip the
    // history the walk exists to fetch.
    override var backfillCursor: Long?
        get() = cursorRows { loadBackfillCursor() }
        set(value) { if (value != null) cursorRows { storeBackfillCursor(value) } }

    override fun reopenBackfill() = cursorRows { clearBackfillCursor() }

    // The first-pull marker rides the store beside the cursors, so a wipe resets it with them.
    // An unknown stored value reads as absent rather than as either state.
    override var firstPullMarker: FirstPullMarker?
        get() = when (cursorRows { loadFirstPullMarker() }) {
            ObjectStore.FIRST_PULL_STARTED -> FirstPullMarker.STARTED
            ObjectStore.FIRST_PULL_COMPLETED -> FirstPullMarker.COMPLETED
            else -> null
        }
        set(value) {
            val stored = when (value) {
                FirstPullMarker.STARTED -> ObjectStore.FIRST_PULL_STARTED
                FirstPullMarker.COMPLETED -> ObjectStore.FIRST_PULL_COMPLETED
                null -> return
            }
            cursorRows { storeFirstPullMarker(stored) }
        }

    override val firstPullMarkerUnrecognised: Boolean
        get() = when (cursorRows { loadFirstPullMarker() }) {
            null, ObjectStore.FIRST_PULL_STARTED, ObjectStore.FIRST_PULL_COMPLETED -> false
            else -> true
        }

    override fun resetPullProgress() = cursorRows {
        storeSyncCursor(0L)
        storeTombstoneCursor(0L)
        clearFirstPullMarker()
        clearBackfillCursor()
        clearBackfillTarget()
    }

    override var backfillTarget: Long?
        get() = cursorRows { loadBackfillTarget() }
        set(value) { if (value != null) cursorRows { storeBackfillTarget(value) } }

    // Persistent once-ever latch for the lazy realm genesis (ADR 0019). Non-secret,
    // so it rides the same plaintext store as DEVICE_ID / LAST_SYNC_TIMESTAMP.
    override var realmRelayRegistered: Boolean
        get() = storageDelegate.getString(Keys.REALM_RELAY_REGISTERED) == "true"
        set(value) = storageDelegate.storeString(Keys.REALM_RELAY_REGISTERED, value.toString())

    // Persistent latch: this device joined an already-registered (owner-funded)
    // realm via pairing (storage-entitlement-sync.md §3.3). Non-secret, same
    // plaintext store as REALM_RELAY_REGISTERED. Set by `installReceivedPairingPayload`
    // (both pairing directions) and by the iOS route-1 runtime.
    override var joinedFundedRealm: Boolean
        get() = storageDelegate.getString(Keys.JOINED_FUNDED_REALM) == "true"
        set(value) = storageDelegate.storeString(Keys.JOINED_FUNDED_REALM, value.toString())

    private var derivedKeys: SyncCryptoManager.DerivedKeys? = null

    private val pendingHandshakes = mutableMapOf<String, SyncManager.HandshakeContext>()

    // Defaults to 0 — `initSyncService` overwrites this to read from `TombstoneRegistry`
    // once the registry exists. Operations that read `currentRealmEpoch` before that
    // point (none today, but be defensive) get the safe genesis value.
    private var realmEpochProvider: () -> Long = { 0L }

    // Out-of-band realm-epoch seed (docs/adr/0007-sync-device-management.md "Recovery race against
    // revocation" carve-out). The recovering / pairing-claim device fetches this from
    // the relay and feeds it back via [seedCurrentRealmEpoch]; we max it with the
    // provider so once tombstones drain past the seed the provider wins again.
    // ⚠⚠ PERSISTED, not in-memory (F5a). This was `private var seededRealmEpoch: Long = 0L`, and a
    // process restart dropped it — which produced a live 403 on a real desktop: the device fell back
    // to the tombstone-derived count, minted its cert BELOW the revocation naming it, and the relay
    // answered "Admission capability has been revoked" on an ordinary pairing
    // (`admission-revocation-names-the-capability.md` §7b.1).
    //
    // ⚠⚠ §7e audited whether the seed could simply be DELETED once F1′ made revocations key-scoped.
    // It cannot. The seed feeds `Device.admittedAtEpoch` as well as `cert.mintedAtEpoch`, and
    // T-K-02 is enforced at MERGE by every peer (`SyncProcessor.isStaleDeviceBlock`) — a
    // client-side control F1′/F2′ do not replace. Deleting it would make a device admitted after a
    // revocation carry a stamp its peers drop, appearing as a device that pairs, syncs, then
    // intermittently vanishes from other rosters.
    //
    // Non-secret — an epoch is a counter of tombstones, not key material — so it rides the same
    // plaintext store as DEVICE_ID / LAST_SYNC_TIMESTAMP (House Rule #1 untouched).
    private var seededRealmEpoch: Long
        get() = storageDelegate.getString(Keys.SEEDED_REALM_EPOCH)?.toLongOrNull() ?: 0L
        set(value) = storageDelegate.storeString(Keys.SEEDED_REALM_EPOCH, value.toString())

    override val currentRealmEpoch: Long
        get() = maxOf(realmEpochProvider(), seededRealmEpoch)
    override fun setRealmEpochProvider(provider: () -> Long) {
        realmEpochProvider = provider
    }

    override fun seedCurrentRealmEpoch(epoch: Long) {
        if (epoch > seededRealmEpoch) seededRealmEpoch = epoch
    }

    // Default divergence-warning flow is perpetually null. `initSyncService` swaps in
    // the live `HlcDivergenceTracker.warning` flow once it exists.
    private var divergenceWarningSource: StateFlow<Any?> = MutableStateFlow(null)
    override val divergenceWarning: StateFlow<Any?> get() = divergenceWarningSource
    override fun setDivergenceWarningSource(source: StateFlow<Any?>) {
        divergenceWarningSource = source
    }

    // Asset A4 — device's persistent Ed25519 signing keypair and the id derived from it, read on
    // first use and persisted via SecureStorageDelegate. ⚠ One atomic for the PAIR, so no reader
    // ever sees one identity's key beside another's id ([currentDeviceIdentity]). Written only under
    // [identityLock], persisted before it is published: a key this process signs with is always the
    // key at rest, so no in-memory-only identity can be paired and then orphaned by a restart.
    @OptIn(ExperimentalAtomicApi::class)
    private val identity = AtomicReference<SyncManager.DeviceKeyIdentity?>(null)

    /** Serialises every writer of [identity]: first use, and [forgetThisDevice]'s re-mint. */
    private val identityLock = reentrantLock()

    // Asset A13 — the realm's X25519 encryption sub-key, held in memory on a JOINER
    // (delivered via the pairing key-blob, SR-3b.4). On a host it's derived on demand
    // from the recovery phrase instead, so this stays null there. See
    // [getRealmEncryptionKeyPair].
    private var realmEncryptionKeyPair: SyncCryptoManager.KeyPair? = null

    // Asset A33 — this device's admission keypair + capability-cert chain
    // (delegated-device-admission-authority.md §4.1), delivered via the pairing
    // key-blob v2 on a joiner. Null/empty on an A1-holder (admits with the master
    // key, needs no A33) and on pre-P3 joiners (non-admitters). Held like the A13
    // above: in memory + inside the joiner payload (J3) at rest.
    private var admissionKeyPair: SyncCryptoManager.KeyPair? = null
    private var admissionCertChain: List<AdmissionCapabilityCert> = emptyList()

    // Asset A1 — master identity keypair derived from the recovery phrase during
    // initialize. Held in memory only per threat-model T-K-04 (re-derived on next
    // app launch when the user re-enters the recovery code).
    override var currentMasterKeyPair: SyncCryptoManager.KeyPair? = null
        private set

    // Asset A1 plaintext — the recovery mnemonic for *this* unlocked session.
    // Mirrors `currentMasterKeyPair`'s in-memory-only lifecycle (T-K-04): set
    // whenever `initialize` runs (CreateRealm, RecoveryClaim, legacy auto-load,
    // or post-unlock re-derive) and naturally gone on process restart.
    //
    // It exists because `setUpLocalUnlock` / `setUpBiometricUnlock` destructively
    // delete the *on-disk* `RECOVERY_PHRASE` slot once the phrase is wrapped under
    // a credential. Without this field, `getRecoveryPhrase()` — which only ever
    // read the disk slot — returned null between completing onboarding (or
    // recovery setup) and the first lock/unlock cycle, so Settings → Recovery
    // Phrase could not reveal the code on a freshly set-up device. Reading from
    // memory while unlocked is strictly less at-rest exposure than the prior
    // behaviour, which re-seeded the plaintext to disk on every unlock.
    private var sessionRecoveryPhrase: String? = null

    // Set during pairing/recovery; consumed when Device B pushes its own Device
    // entity per docs/adr/0007-sync-device-management.md step 5.
    override var currentDeviceName: String? = null
        private set

    override fun setCurrentDeviceName(name: String) {
        currentDeviceName = name
    }

    override var activePairingContext: SyncManager.PairingContext? = null
        private set

    // Process-lifetime scope for the off-thread legacy auto-load below.
    private val autoLoadScope = CoroutineScope(SupervisorJob() + autoLoadDispatcher)

    init {
        // Auto-load on construction only when this device's A1 material sits
        // unwrapped in `SecureStorage`. Three "loaded" cases, all symmetric:
        //  - Host with plaintext phrase: wraps to
        //    `initialize(phrase)`. Argon2id-stretched on the autoLoadScope.
        //  - Joiner with plaintext payload: wraps to
        //    `initializeWithKeys(keys, ownerId)`. Keys are already derived;
        //    no Argon2.
        //  - Otherwise: stays uninitialised (sealed if a wrapper exists,
        //    first-run if not).
        //
        // The host autoload runs `initialize`, which Argon2id-stretches the
        // phrase (threat-model T-RC-02: m=64 MiB / t=3 / p=1 — parameters
        // are a protocol-version constant, intentionally NOT touched here).
        // On Android this constructor runs on the main thread inside
        // `ProxyService.onCreate` (Koin resolves the proxy graph there), and
        // `ProxyService` is `START_STICKY`, so a memory-pressured kill mid
        // folder-upload restarts the service and re-runs this 64 MiB
        // memory-hard KDF on the main thread under continued pressure — an
        // ANR-prone, OOM-amplifying restart-storm. Deferring the *legacy
        // auto-load* (only) onto a background dispatcher removes it from the
        // main thread / `onCreate` critical path. Readers of
        // `derivedKeys` / `currentOwnerId` / `currentMasterKeyPair` already
        // tolerate the not-yet-initialised window (`?: return`,
        // `isEnabled() == derivedKeys != null`, `SyncScheduler` polls on a
        // tick), so the brief async gap self-heals. Explicit `initialize()`
        // (CreateRealm / RecoveryClaim) stays synchronous. Keys remain
        // in-memory only per T-K-04 — deliberately NOT persisted (that would
        // be an asset-A1 at-rest change; see `core-background-processing.md`).
        // Both wrappers gate the autoload — once either passphrase
        // or biometric setup has run, the plaintext slot should be empty
        // anyway, but the gate also stays sealed if a stale plaintext
        // entry happens to coexist with a wrapper.
        //
        // Joiner autoload is synchronous on the construction thread — no
        // KDF, just an in-memory decode + ref assignment — so it does not
        // share the host-side ANR concern.
        if (storageDelegate.getSecret(Keys.UNLOCK_WRAPPER) == null &&
            storageDelegate.getSecret(Keys.BIOMETRIC_WRAPPER) == null
        ) {
            val phrase = storageDelegate.getSecret(Keys.RECOVERY_PHRASE)
            val joinerPayload = storageDelegate.getSecret(Keys.JOINER_PAYLOAD)
            val ownerId = storageDelegate.getSecret(Keys.OWNER_ID)
            when {
                phrase != null -> autoLoadScope.launch {
                    if (derivedKeys == null) initialize(phrase)
                }

                joinerPayload != null && ownerId != null -> {
                    val joiner = decodeJoinerPayload(joinerPayload)
                    if (joiner != null && derivedKeys == null) {
                        // Adopt the persisted ownerId rather than the one
                        // embedded in the payload — they should match (both
                        // came from the same `initializeWithKeys` call),
                        // but if they diverge the `OWNER_ID` slot wins by
                        // construction (it's what `getSyncStatus` already
                        // observed at last session-end).
                        initializeWithKeys(
                            joiner.keys,
                            ownerId,
                            joiner.realmEncryptionKeyPair,
                            joiner.admissionKeyPair,
                            joiner.admissionCertChain,
                        )
                    }
                }
            }
        }
    }

    override fun startHandshake(
        pairingSecret: String,
        myKeyPair: SyncCryptoManager.KeyPair,
        hostOwnerId: String
    ) {
        pendingHandshakes[pairingSecret] =
            SyncManager.HandshakeContext(keyPair = myKeyPair, hostOwnerId = hostOwnerId)
    }

    override fun getHandshake(pairingSecret: String): SyncManager.HandshakeContext? {
        return pendingHandshakes[pairingSecret]
    }

    override fun clearHandshake(pairingSecret: String) {
        pendingHandshakes.remove(pairingSecret)
    }

    // Offer flow (sync-pairing-inverted-direction.md) — offerer + responder sessions,
    // keyed by pairingSecretHash. Both zero the ephemeral private key on clear so privB/
    // privA never outlive their handshake (parity with stopPairing's A8 zeroing).
    private val offerSessions = mutableMapOf<String, SyncManager.OfferSession>()
    private val responderSessions = mutableMapOf<String, SyncManager.ResponderSession>()

    override fun startOfferSession(
        pairingSecretHash: String,
        ephemeralKeyPair: SyncCryptoManager.KeyPair,
        deviceName: String,
    ) {
        offerSessions[pairingSecretHash] =
            SyncManager.OfferSession(ephemeralKeyPair = ephemeralKeyPair, deviceName = deviceName)
    }

    override fun getOfferSession(pairingSecretHash: String): SyncManager.OfferSession? =
        offerSessions[pairingSecretHash]

    override fun pinAnnouncedOfferKey(pairingSecretHash: String, publicKeyA: ByteArray) {
        val session = offerSessions[pairingSecretHash] ?: return
        // ⚠ First announce wins. The relay already enforces one-per-hash, but a client that
        // re-pinned on every poll would quietly forgive a relay that stopped enforcing it — the
        // lock must not depend on the thing it is guarding against.
        if (session.announcedPublicKeyA != null) return
        offerSessions[pairingSecretHash] = session.copy(announcedPublicKeyA = publicKeyA)
    }

    override fun clearOfferSession(pairingSecretHash: String) {
        offerSessions.remove(pairingSecretHash)?.ephemeralKeyPair?.privateKey?.fill(0)
    }

    override fun startResponderSession(
        pairingSecretHash: String,
        ephemeralKeyPair: SyncCryptoManager.KeyPair,
        offererPublicKey: ByteArray,
        offererSigningKey: ByteArray,
        offererDeviceId: String,
        deviceName: String,
    ) {
        responderSessions[pairingSecretHash] = SyncManager.ResponderSession(
            ephemeralKeyPair = ephemeralKeyPair,
            offererPublicKey = offererPublicKey,
            offererSigningKey = offererSigningKey,
            offererDeviceId = offererDeviceId,
            deviceName = deviceName,
        )
    }

    override fun getResponderSession(pairingSecretHash: String): SyncManager.ResponderSession? =
        responderSessions[pairingSecretHash]

    override fun clearResponderSession(pairingSecretHash: String) {
        responderSessions.remove(pairingSecretHash)?.ephemeralKeyPair?.privateKey?.fill(0)
    }

    override fun getRecoveryPhrase(): String? =
        storageDelegate.getSecret(Keys.RECOVERY_PHRASE) ?: sessionRecoveryPhrase

    override fun initialize(recoveryPhrase: String) {
        // ⚠⚠ **Three phases, in this order: derive, persist, then seat.** Each boundary
        // is load-bearing and they pull in opposite directions, so neither can move.
        //
        // **Derive before persisting.** `deriveKeys` runs `Bip39.decode` and throws on a
        // phrase this build cannot read — a phrase minted under the retired wordlist is
        // the case that exists (threat model §11.2), a future wordlist change is the case
        // that will. Persisting `RECOVERY_PHRASE` first would land that throw *after* the
        // write: `unlockLocal` would leave A9 plaintext on disk for a device the user still
        // sees as locked, with no `OWNER_ID` beside it and no `clearPlaintextPayloadSlots`
        // to tidy it, since the throw escapes the function.
        //
        // ⚠⚠ **Persist before seating**, which is the half that is easy to get backwards.
        // `derivedKeys` is what `isEnabled()` reads, and both `CreateRealmImplementation`
        // and `RecoveryClaimImplementation` guard their `initialize` call with
        // `if (!syncManager.isEnabled())`. Seat first and a `storeSecret` failure — an
        // Android Keystore error, a desktop disk write — leaves this manager *claiming* to
        // hold custody it never wrote down, and the retry skips initialization instead of
        // re-attempting it. Persisting first means a storage failure leaves `isEnabled()`
        // false, so the flow retries; re-deriving is idempotent, so the retry is free.
        //
        // Nothing between the phases reads either slot, so the split costs nothing on the
        // success path. `InitializeAtomicityTest` pins both boundaries.
        val keys = cryptoManager.deriveKeys(recoveryPhrase)
        val masterKeyPair = cryptoManager.deriveMasterKeyPair(recoveryPhrase)
        // Per `docs/sync/threat-model.md` §A1, the realm's `ownerId` is deterministically
        // derived from the master public key. Using a hardcoded value here (the previous
        // `"test@example.com"` mock) silently broke JWT auth: the relay's
        // `KnownDevicesStore` is keyed by `(realOwnerId, deviceId)`, so a JWT carrying
        // a mismatched `iss` looked up nothing → 401. That broke pairing end-to-end —
        // the host's `getPairingRequests` polling 401'd silently and never surfaced
        // incoming requests.
        val ownerId = ownerIdForMasterPublicKey(masterKeyPair.publicKey)

        storageDelegate.storeSecret(Keys.RECOVERY_PHRASE, recoveryPhrase)
        // Mirror the joiner side and persist the derived ownerId so
        // `LandingStateRepository.detect()` sees `ownerId != null` on the
        // session immediately after `initialize` even before a wrapper is set
        // up. Re-deriving from `masterKeyPair.publicKey` on every restart
        // would also be correct but costs the Argon2 round; persisting is
        // cheap and consistent with the joiner path.
        storageDelegate.storeSecret(Keys.OWNER_ID, ownerId)

        sessionRecoveryPhrase = recoveryPhrase
        derivedKeys = keys
        currentMasterKeyPair = masterKeyPair
        currentOwnerId = ownerId
        // Genesis or recovery by this build: every content object it ever sees is described on
        // upload, so the content-declaration backfill has nothing to do.
        storageDelegate.storeString(Keys.CONTENT_DESCRIBED_FROM_GENESIS, "true")
    }

    override fun reseatMasterKeyPair(keyPair: SyncCryptoManager.KeyPair) {
        currentMasterKeyPair = keyPair
        // Mirrors [initialize]'s ownerId derivation. The unwrap path has
        // already proved possession of the keypair, so the derived ownerId
        // is the same one CreateRealm registered with the relay — JWTs
        // issued from this device verify against the existing
        // KnownDevicesStore entry.
        currentOwnerId = ownerIdForMasterPublicKey(keyPair.publicKey)
    }

    override fun initializeWithKeys(
        keys: SyncCryptoManager.DerivedKeys,
        ownerId: String,
        realmEncryptionKeyPair: SyncCryptoManager.KeyPair?,
        admissionKeyPair: SyncCryptoManager.KeyPair?,
        admissionCertChain: List<AdmissionCapabilityCert>,
    ) {
        derivedKeys = keys
        this.realmEncryptionKeyPair = realmEncryptionKeyPair
        this.admissionKeyPair = admissionKeyPair
        this.admissionCertChain = admissionCertChain
        // Persist the joiner payload + ownerId so `init` can
        // rehydrate `derivedKeys` and `currentOwnerId` on the next process
        // launch (the "Skip on SetupUnlock" path). Once `setUpLocalUnlock` or
        // `setUpBiometricUnlock` runs, the wrapper supplants this plaintext
        // slot and `JOINER_PAYLOAD` is cleared — same destructive-read shape
        // as `RECOVERY_PHRASE` on the host side.
        storageDelegate.storeSecret(
            Keys.JOINER_PAYLOAD,
            encodeJoinerPayload(
                JoinerPayload(
                    keys = keys,
                    ownerId = ownerId,
                    realmEncryptionKeyPair = realmEncryptionKeyPair,
                    admissionKeyPair = admissionKeyPair,
                    admissionCertChain = admissionCertChain,
                )
            ),
        )
        // The pairing handshake transmits the host realm's ownerId via the URL
        // (the pairing-code's `ownerId=...` query param); `GetPairingStatus` plumbs it
        // through to here so this device can issue JWTs that the relay's existing
        // `KnownDevicesStore` entry for `(ownerId, this.deviceId)` actually matches.
        currentOwnerId = ownerId
        storageDelegate.storeSecret(Keys.OWNER_ID, ownerId)
        // A join by this build: the realm's content was uploaded, and described, by seam-era
        // devices, so this device's backfill has nothing to do.
        storageDelegate.storeString(Keys.CONTENT_DESCRIBED_FROM_GENESIS, "true")
    }

    override var contentDescribedFromGenesis: Boolean
        get() = storageDelegate.getString(Keys.CONTENT_DESCRIBED_FROM_GENESIS) == "true"
        set(value) = storageDelegate.storeString(Keys.CONTENT_DESCRIBED_FROM_GENESIS, value.toString())

    override fun isEnabled(): Boolean = derivedKeys != null

    override fun getDerivedKeys(): SyncCryptoManager.DerivedKeys? = derivedKeys

    override fun getRealmEncryptionKeyPair(): SyncCryptoManager.KeyPair? {
        // Joiner: A13 was delivered via the pairing key-blob (held in memory +
        // persisted in the joiner payload). Host: derive on demand from the recovery
        // phrase (available while unlocked). Null when neither is available (a locked
        // host with no plaintext phrase) — callers then can't unwrap shared-realm keys.
        realmEncryptionKeyPair?.let { return it }
        return getRecoveryPhrase()?.let { cryptoManager.deriveRealmEncryptionKeyPair(it) }
    }

    override fun getAdmissionKeyPair(): SyncCryptoManager.KeyPair? = admissionKeyPair

    override fun getAdmissionCertChain(): List<AdmissionCapabilityCert> = admissionCertChain

    override fun installAdmissionMaterial(
        keyPair: SyncCryptoManager.KeyPair,
        chain: List<AdmissionCapabilityCert>,
    ) {
        admissionKeyPair = keyPair
        admissionCertChain = chain
        // Persist into the joiner payload (J3) when this device runs on one: keys +
        // ownerId + A13 must all be in memory (the J3 layout carries them), and a
        // phrase-holding host never needs A33 custody (it admits with A1). A legacy
        // `J:` joiner without A13 keeps the material in memory only and self-heals
        // next session by re-announcing (the P4 migration loop is idempotent).
        val keys = derivedKeys ?: return
        val ownerId = currentOwnerId ?: return
        val a13 = realmEncryptionKeyPair ?: return
        if (getRecoveryPhrase() != null) return
        storageDelegate.storeSecret(
            Keys.JOINER_PAYLOAD,
            encodeJoinerPayload(
                JoinerPayload(
                    keys = keys,
                    ownerId = ownerId,
                    realmEncryptionKeyPair = a13,
                    admissionKeyPair = keyPair,
                    admissionCertChain = chain,
                )
            ),
        )
    }

    override fun getDeviceSigningKeyPair(): SyncCryptoManager.KeyPair = currentDeviceIdentity().signingKeyPair

    @OptIn(ExperimentalAtomicApi::class)
    override fun currentDeviceIdentity(): SyncManager.DeviceKeyIdentity {
        identity.load()?.let { return it }
        return identityLock.withLock {
            identity.load()?.let { return@withLock it }
            // Asset A4 — Ed25519 device signing key. Distinct curve from
            // the X25519 ephemeral keypair generateKeyPair() now produces
            // for pairing. The default impl on `SyncCryptoManager`
            // delegates to generateKeyPair() so existing test fakes (which
            // override generateKeyPair to return canned 32 bytes) keep
            // working without an explicit override.
            //
            // Persisted at rest via SecureStorageDelegate (threat-model A4) so the device keeps
            // ONE stable signing identity across restarts. A key regenerated on every process start
            // would churn a fresh relay registration each launch — and, under the iOS in-process
            // collapse (§C4), fork the device identity between Control and Drive. Stored per-app
            // (the standard SecureStorageDelegate bucket); the shared-Keychain custody that lets two
            // processes read ONE key is route-1's.
            val loaded = storageDelegate.getSecret(Keys.DEVICE_SIGNING_KEY)
                ?.let(::decodeDeviceSigningKeyPair)
            // A persisted identity always wins over minting a new one.
            if (loaded != null) return@withLock publishIdentity(loaded)

            // ⚠⚠ A new key is a new DEVICE: the id is derived from it ([currentDeviceId]). That is also
            // what an unreadable blob above leads to — the device comes back as a stranger and must be
            // admitted again, which is the honest outcome for a key that is gone.
            mintIdentity()
        }
    }

    /**
     * Mint a new A4 pair, **persist it, then publish it** — under [identityLock], which is what
     * elects one identity among concurrent callers (the lock, not a CAS: a CAS has to publish
     * before persisting, and a key published but never written is the in-memory-only identity a
     * restart orphans). ⚠ A persist that throws publishes nothing and rethrows: the caller fails,
     * and no one signs with a key that is not at rest.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun mintIdentity(): SyncManager.DeviceKeyIdentity {
        val pair = cryptoManager.generateDeviceSigningKeyPair()
        storageDelegate.storeSecret(Keys.DEVICE_SIGNING_KEY, encodeDeviceSigningKeyPair(pair))
        return publishIdentity(pair)
    }

    /** Derive [pair]'s id, rewrite the plaintext id cache, and make the pair this process's A4. */
    @OptIn(ExperimentalAtomicApi::class)
    private fun publishIdentity(pair: SyncCryptoManager.KeyPair): SyncManager.DeviceKeyIdentity {
        val derived = SyncManager.DeviceKeyIdentity(deviceIdForPublicKey(pair.publicKey), pair)
        if (storageDelegate.getString(Keys.DEVICE_ID) != derived.deviceId) {
            storageDelegate.storeString(Keys.DEVICE_ID, derived.deviceId)
        }
        identity.store(derived)
        return derived
    }

    /** A4 keypair → `Base64(pub)|Base64(priv)`, for the SecureStorageDelegate secret bucket. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeDeviceSigningKeyPair(pair: SyncCryptoManager.KeyPair): String =
        Base64.encode(pair.publicKey) + DEVICE_SIGNING_KEY_SEPARATOR + Base64.encode(pair.privateKey)

    /** Inverse of [encodeDeviceSigningKeyPair]; null on a malformed blob → caller regenerates. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDeviceSigningKeyPair(encoded: String): SyncCryptoManager.KeyPair? {
        val parts = encoded.split(DEVICE_SIGNING_KEY_SEPARATOR)
        if (parts.size != 2) return null
        return try {
            SyncCryptoManager.KeyPair(
                publicKey = Base64.decode(parts[0]),
                privateKey = Base64.decode(parts[1]),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun generatePairingContext(): SyncManager.PairingContext {
        // Idempotent while a session is in flight. The QR/code the joiner scanned
        // encodes this context's `secret`, and the joiner's pending pairing request is
        // matched host-side by it (GetPendingPairingRequestsImplementation). Minting a
        // fresh secret on a second StartPairing — e.g. the host hides then reopens the
        // pairing dialog while the phone is already on "Confirm on your other device" —
        // would orphan that in-flight request: it carries the old secret, the new
        // context holds a new one, the poll matches nothing, and the approval dialog
        // never appears. The context is cleared only by stopPairing()/approve/deny, so
        // a non-null context reliably means "a handshake the joiner may be mid-way
        // through"; reuse it. A genuinely new session starts after stopPairing().
        activePairingContext?.let { return it }

        // A pairing context is meaningless without a realm-bound owner: the QR encodes
        // `ownerId`, and the host polls the relay under it. Callers (StartPairing) gate on
        // a non-null owner first, so reaching here without one is a programming error —
        // fail loudly rather than mint a placeholder-owner session the relay rejects.
        val ownerId = currentOwnerId
            ?: error("generatePairingContext requires a configured owner; gate on currentOwnerId before pairing")
        val keyPair = cryptoManager.generateKeyPair()
        val secret = generateId()

        return SyncManager.PairingContext(
            deviceId = currentDeviceId,
            ownerId = ownerId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
            secret = secret
        ).also { activePairingContext = it }
    }

    override fun getPairingString(): String? {
        // Canonical role-host form (sync-pairing-flow-improvements.md §8). The
        // legacy `tenshu://pair?…` emitter was removed in the same flag-day
        // change as every parser — a code scanned across the flag day fails
        // loudly on the retired host, never as a partial parse.
        val context = activePairingContext ?: return null
        return PairingUri.joinUri(
            ownerId = context.ownerId,
            hostDeviceId = context.deviceId,
            hostPublicKey = context.publicKey,
            pairingSecret = context.secret.encodeToByteArray(),
        )
    }

    override fun stopPairing() {
        // Zero the ephemeral X25519 private key before dropping the context
        // (control-pairing-ux.md §3.5 / §5: the on-teardown zeroing the spec flagged as
        // not-yet-implemented). This is the single teardown chokepoint — approve
        // (ApprovePairingRequestImplementation step 6), deny, and the bounded-window expiry
        // all route through here — so zeroing once here covers every path. A net security
        // improvement with no asset/threat-model change: the key still never persists.
        activePairingContext?.privateKey?.fill(0)
        activePairingContext = null
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun setUpLocalUnlock(passphrase: String): Boolean {
        // The plaintext A1 material must be persisted for setup to have
        // something to wrap. Two equivalent shapes (§10.6):
        // master device wraps the recovery phrase; joiner device wraps the
        // derived-keys + ownerId tuple. Calls before initialize() /
        // CreateRealm / RecoveryClaim / PairingCompletion are a programming
        // error from the UI; surface as `false` so the UI can show
        // "couldn't read phrase" rather than crash.
        val payloadBytes = readRoundTrippablePayload()?.encodeToByteArray() ?: return false
        val salt = nextSecureRandomBytes(SALT_BYTES)
        val key = deriveWrapperKey(passphrase, salt)
        val ciphertext = cryptoManager.encrypt(payloadBytes, key)
        storageDelegate.storeSecret(
            Keys.UNLOCK_WRAPPER,
            Base64.encode(salt) + WRAPPER_SEPARATOR + Base64.encode(ciphertext),
        )
        // Plaintext slots deleted last — if any of the encryption steps had
        // failed we'd have left the legacy plaintext intact.
        clearPlaintextPayloadSlots()
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun setUpUnlock(passphrase: String, biometricWrapKey: ByteArray?): Boolean {
        // Atomic passphrase-floor setup: wrap the SAME plaintext under the
        // passphrase (always) and, optionally, the biometric wrap-key, then
        // clear the plaintext once. Doing both here — rather than chaining
        // setUpLocalUnlock + setUpBiometricUnlock — is required because
        // setUpLocalUnlock clears the plaintext, which would starve a following
        // setUpBiometricUnlock of its payload. The mandatory passphrase is the floor.
        val payloadBytes = readRoundTrippablePayload()?.encodeToByteArray() ?: return false

        val salt = nextSecureRandomBytes(SALT_BYTES)
        val passphraseKey = deriveWrapperKey(passphrase, salt)
        storageDelegate.storeSecret(
            Keys.UNLOCK_WRAPPER,
            Base64.encode(salt) + WRAPPER_SEPARATOR +
                Base64.encode(cryptoManager.encrypt(payloadBytes, passphraseKey)),
        )

        if (biometricWrapKey != null) {
            storageDelegate.storeSecret(
                Keys.BIOMETRIC_WRAPPER,
                Base64.encode(cryptoManager.encrypt(payloadBytes, biometricWrapKey)),
            )
        }

        // Cleared last (same rationale as setUpLocalUnlock): a throw above leaves
        // the plaintext intact rather than orphaning a half-written wrapper set.
        clearPlaintextPayloadSlots()
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun unlockLocal(passphrase: String): Boolean {
        val wrapper = storageDelegate.getSecret(Keys.UNLOCK_WRAPPER) ?: return false
        val parts = wrapper.split(WRAPPER_SEPARATOR)
        if (parts.size != 2) return false
        val salt = try { Base64.decode(parts[0]) } catch (_: IllegalArgumentException) { return false }
        val ciphertext =
            try { Base64.decode(parts[1]) } catch (_: IllegalArgumentException) { return false }
        val key = deriveWrapperKey(passphrase, salt)
        val decrypted = try {
            cryptoManager.decrypt(ciphertext, key).decodeToString()
        } catch (_: Throwable) {
            return false
        }
        // The current BootstrapSyncCryptoManager XOR is symmetric and
        // always "succeeds" — wrong passphrase produces garbage that
        // happens to round-trip through encodeToByteArray. The payload
        // decoder's structural checks (BIP-39 mnemonic shape for master,
        // three pipe-separated base64 fields for joiner) act as the wrong-
        // key gate; real AEAD short-circuits this earlier via auth-tag.
        return applyDecodedPayload(decrypted)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun setUpBiometricUnlock(wrapKey: ByteArray): Boolean {
        // Passphrase-floor backstop: biometric may only be *added* on top of an
        // existing passphrase wrapper, never be the sole credential (so a lost
        // biometric credential can't lock the user out). First-time setup goes
        // through setUpUnlock(passphrase, wrapKey); this path is the Settings
        // "enable biometric later" case, where UNLOCK_WRAPPER already exists and
        // the plaintext was re-seeded by the unlock that opened this session.
        if (storageDelegate.getSecret(Keys.UNLOCK_WRAPPER) == null) return false
        val payloadBytes = readRoundTrippablePayload()?.encodeToByteArray() ?: return false
        val ciphertext = cryptoManager.encrypt(payloadBytes, wrapKey)
        storageDelegate.storeSecret(
            Keys.BIOMETRIC_WRAPPER,
            Base64.encode(ciphertext),
        )
        return true
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun unlockBiometric(wrapKey: ByteArray): Boolean {
        val wrapper = storageDelegate.getSecret(Keys.BIOMETRIC_WRAPPER) ?: return false
        val ciphertext = try {
            Base64.decode(wrapper)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val decrypted = try {
            cryptoManager.decrypt(ciphertext, wrapKey).decodeToString()
        } catch (_: Throwable) {
            return false
        }
        // Same wrong-key gate as `unlockLocal`: structural check on the
        // decoded payload rejects junk from a wrong wrap-key under the
        // current symmetric BootstrapSyncCryptoManager.
        return applyDecodedPayload(decrypted)
    }

    override fun clearBiometricSetup() {
        storageDelegate.remove(Keys.BIOMETRIC_WRAPPER)
    }

    override fun relaxToHeadless(): Boolean {
        // Inverse of setUpUnlock. Ensure the plaintext payload sits in the secret
        // store so `init`'s autoload gate fires on the next cold start and the realm
        // boots headless. It's already there right after a fresh unlock
        // (applyDecodedPayload re-seeds it), but `setUpUnlock` clears it — so when
        // disabling in the *same* session that enabled hard mode we must re-seed
        // from the in-memory (unlocked) keys. A locked realm has neither → refuse,
        // rather than strand it with no wrapper and no plaintext.
        if (readWrappablePayload() == null && !reseedPlaintextFromSession()) return false
        storageDelegate.remove(Keys.UNLOCK_WRAPPER)
        storageDelegate.remove(Keys.BIOMETRIC_WRAPPER)
        return true
    }

    /**
     * Re-persist the plaintext A1 payload from the current *unlocked* session so
     * [relaxToHeadless] can drop the wrappers without losing the way in. Host:
     * the in-memory recovery phrase → `RECOVERY_PHRASE`. Joiner: the in-memory
     * derived keys + ownerId → the encoded `JOINER_PAYLOAD` (same shape
     * [initializeWithKeys] writes). Returns false when the realm is locked (no
     * in-memory material).
     */
    private fun reseedPlaintextFromSession(): Boolean {
        getRecoveryPhrase()?.let { phrase ->
            storageDelegate.storeSecret(Keys.RECOVERY_PHRASE, phrase)
            return true
        }
        val keys = derivedKeys ?: return false
        val ownerId = currentOwnerId ?: return false
        storageDelegate.storeSecret(
            Keys.JOINER_PAYLOAD,
            encodeJoinerPayload(
                JoinerPayload(
                    keys = keys,
                    ownerId = ownerId,
                    realmEncryptionKeyPair = realmEncryptionKeyPair,
                    admissionKeyPair = admissionKeyPair,
                    admissionCertChain = admissionCertChain,
                )
            ),
        )
        return true
    }

    override suspend fun wipeLocalStore(): Boolean {
        val wipeable = objectStore as? WipeableObjectStore ?: return false
        wipeable.wipeAllObjects()
        // ⚠ After the rows, and never able to fail the wipe — see [onLocalStoreWiped].
        // ⚠⚠ NOT `runCatching`: it swallows `CancellationException` too, so a hook cancelled while
        // suspended would report a wipe that cleared nothing, from a coroutine that is no longer
        // running. A cancellation is the caller's to see; a failure of the derived store is not.
        try {
            onLocalStoreWiped()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            println("wipeLocalStore -> derived store did not clear: $failure")
        }
        return true
    }

    override fun forgetThisDevice() {
        // ⚠ The pairing sessions go in a `finally`: even a forget whose re-mint failed must not
        // leave a handshake opened by the identity it just stopped being.
        try {
            forgetCustodyAndRemint()
        } finally {
            // Every pairing session in flight was opened by the identity being forgotten: a host
            // context carries its device id, and an offer its signing key's announcement. Zeroed,
            // as their own teardowns do. After the swap, so none opened in between survives it.
            stopPairing()
            offerSessions.keys.toList().forEach(::clearOfferSession)
            responderSessions.keys.toList().forEach(::clearResponderSession)
            pendingHandshakes.clear()
        }
    }

    private fun forgetCustodyAndRemint() {
        // The full custody bucket. ⚠⚠ Shared with iOS `wipeDeviceState` by *construction*, not by
        // comment: two hand-maintained lists drift. One definition, both callers — see
        // [Keys.CUSTODY_SLOTS].
        Keys.CUSTODY_SLOTS.forEach { key -> runCatching { storageDelegate.remove(key) } }
        // ⚠⚠ The reset runs on every platform, a store wiped a moment ago included: between the
        // wipe and here a pull tick can write a non-zero cursor into the fresh store, and this is
        // what closes that window. Only a handle that no longer serves (the iOS shared store after
        // its own wipe, `ios-shared-store-sqlite.md` §5.1 step 4, B.14 (192)) refuses the write;
        // its cursor rows went with the wipe, so the refusal is told, never thrown past the purge.
        // No other store throws `StoreHandleFenced`: off iOS this is the reset as it always was.
        try {
            resetPullProgress()
        } catch (fenced: StoreHandleFenced) {
            println("forgetThisDevice -> the store's handle is fenced (${fenced.message}): its cursors went with the wipe")
        }

        // In-memory: the session must not keep signing with keys that no longer exist at rest.
        derivedKeys = null
        currentMasterKeyPair = null
        currentOwnerId = null
        realmEncryptionKeyPair = null
        sessionRecoveryPhrase = null
        admissionKeyPair = null
        admissionCertChain = emptyList()

        remintDeviceSigningKey()
    }

    /**
     * ⚠⚠ **The new identity, minted in this process before [forgetThisDevice] returns.** Removing
     * [Keys.DEVICE_SIGNING_KEY] at rest is not enough: the A4 pair is held in memory too (and the
     * id derived from it, and the clock stamped with it), and Desktop / Android Control keep their
     * one process-lifetime graph across a forget — the user goes straight from Welcome to pairing.
     * Before this, that pairing offered the forgotten id and its key, and the relay refused it as a
     * removed identity (F2′, `admission-revocation-names-the-capability.md` §7i.5) until a restart.
     *
     * Under [identityLock], so it is one step for every other writer: the old identity is
     * withdrawn, the stored slots cleared and the new pair persisted **before** it is published.
     * Two overlapping forgets therefore end with memory and disk on the same key, and a reader
     * meanwhile blocks on the lock and then sees the new identity, never the old one.
     *
     * ⚠⚠ **A persist that fails throws, and leaves no identity held** (MED-2, threat model A4
     * *lifecycle*). The old one stays withdrawn and the new one is never published, so the next
     * reader takes the first-use path, which persists before it publishes or throws again — a
     * pairing is refused rather than run under a key a restart would replace, orphaning the
     * admitted id at the relay (T-K (iii)).
     */
    @OptIn(ExperimentalAtomicApi::class)
    private fun remintDeviceSigningKey() {
        var forgotten: SyncManager.DeviceKeyIdentity? = null
        try {
            identityLock.withLock {
                forgotten = identity.load()
                identity.store(null)
                runCatching { storageDelegate.remove(Keys.DEVICE_SIGNING_KEY) }
                runCatching { storageDelegate.remove(Keys.DEVICE_ID) }
                val fresh = mintIdentity()
                println("forgetThisDevice -> this device is now ${fresh.deviceId.take(12)}…")
            }
        } finally {
            // ⚠ Zeroed once withdrawn, whether or not the new one persisted: every holder of a
            // copy is gone (shared-realm runtimes are unregistered by `ForgetThisDeviceImplementation`
            // before this runs; JWTs and tips read [currentDeviceIdentity] per call). A signer still
            // mid-flight with it fails closed.
            forgotten?.signingKeyPair?.privateKey?.fill(0)
        }
    }

    override fun discardSupersededRealm() {
        // Own-genesis A1 material: the phrase (plaintext) and any wrapper that wraps it.
        // Removing the wrappers drops the device to the "skip unlock" state for the joined
        // realm (the plaintext joiner payload still loads it); the user re-establishes a
        // lock bound to the new realm. A fresh joiner has none of these — no-op there.
        storageDelegate.remove(Keys.RECOVERY_PHRASE)
        storageDelegate.remove(Keys.UNLOCK_WRAPPER)
        storageDelegate.remove(Keys.BIOMETRIC_WRAPPER)
        // Own-realm relay latches. The joined realm starts its own pull cursor (else a
        // stale cursor skips the host's backlog) and re-flips `realmRelayRegistered` on
        // its first successful pull (a joiner never ran genesis for this realm). The cursor
        // now lives in the object store (A1), so reset it there; also purge the retired
        // Keychain item so an upgraded install carries no stale copy.
        storageDelegate.remove(Keys.REALM_RELAY_REGISTERED)
        storageDelegate.remove(Keys.LAST_SYNC_TIMESTAMP)
        // Every position in the old realm's log, not the forward cursor alone — see
        // [SyncManager.resetPullProgress].
        resetPullProgress()
        // Stale in-memory own-realm master (a joiner holds no A1). The joined realm's
        // derivedKeys + ownerId were just set by initializeWithKeys and are left intact.
        currentMasterKeyPair = null
        sessionRecoveryPhrase = null
    }

    override fun lock() {
        // Purge the session key material so getUnlockStatus() reports isLocked = true
        // again (isLocked = isSetUp && derivedKeys == null). The persisted unlock wrapper
        // and the device signing key (A4) are deliberately left intact — the device stays
        // a realm member; only this session's unwrapped keys are dropped. Readers of these
        // fields (SyncScheduler/SyncProcessor/SyncObserver) already tolerate the null window
        // (see the init() note), so the sync loop simply idles on the next tick.
        derivedKeys = null
        currentMasterKeyPair = null
        currentOwnerId = null
        realmEncryptionKeyPair = null
        sessionRecoveryPhrase = null
    }

    override fun getUnlockStatus(): SyncManager.UnlockStatus {
        val hasPassphraseWrapper = storageDelegate.getSecret(Keys.UNLOCK_WRAPPER) != null
        val hasBiometricWrapper = storageDelegate.getSecret(Keys.BIOMETRIC_WRAPPER) != null
        val isSetUp = hasPassphraseWrapper || hasBiometricWrapper
        // Gate on `derivedKeys` rather than `currentMasterKeyPair`
        // — the latter is only set on master devices, so on a joiner
        // device a check on it would leave the realm permanently "Locked" in
        // `LandingStateRepository`'s eyes even after a successful
        // `unlockBiometric` → `initializeWithKeys`. `derivedKeys != null`
        // is the cross-role equivalent of "this device can sync now".
        val isLocked = isSetUp && derivedKeys == null
        return SyncManager.UnlockStatus(
            isSetUp = isSetUp,
            isLocked = isLocked,
            hasPassphrase = hasPassphraseWrapper,
        )
    }

    private fun deriveWrapperKey(passphrase: String, salt: ByteArray): ByteArray =
        pbkdf2Sha512(
            password = passphrase.toCharArray(),
            salt = salt,
            iterations = WRAPPER_KDF_ITERATIONS,
            keyLengthBits = WRAPPER_KEY_BITS,
        )

    private fun looksLikeMnemonic(s: String): Boolean {
        if (s.isEmpty() || s.length > 1024) return false
        if (!s.all { it.isLetter() || it.isWhitespace() }) return false
        val words = s.trim().split(Regex("\\s+"))
        return words.size in MNEMONIC_WORD_COUNTS
    }

    /**
     * Read the device-local A1 plaintext, preferring whichever role
     * this device occupies. Returns the encoded wrapper-payload string ready
     * for `cryptoManager.encrypt`, or `null` when neither plaintext slot is
     * populated (the caller hasn't run `initialize` / `initializeWithKeys`).
     *
     * The master path keeps using the raw mnemonic string for backward
     * compatibility with the structural `looksLikeMnemonic` wrong-key gate;
     * the joiner path uses the tagged `J:…` encoding. Either side reads
     * cleanly from `applyDecodedPayload` on the unlock half of the round-trip.
     */
    private fun readWrappablePayload(): String? {
        val phrase = storageDelegate.getSecret(Keys.RECOVERY_PHRASE)
        if (phrase != null) return phrase
        val joiner = storageDelegate.getSecret(Keys.JOINER_PAYLOAD)
        if (joiner != null) return joiner
        return null
    }

    /**
     * [readWrappablePayload], narrowed to a payload that will still open once sealed —
     * what the three wrap sites want, and what `relaxToHeadless` deliberately does not
     * (it only asks whether *any* plaintext exists to fall back on).
     *
     * ⚠⚠ **Sealing is destructive**, which is why the check lives here rather than at the
     * unlock end: `setUpLocalUnlock` / `setUpUnlock` clear the plaintext slots on success,
     * so wrapping a payload that cannot be decoded converts a readable problem into an
     * unopenable wrapper and reports `true` while doing it. Refusing leaves the plaintext
     * intact and the device un-wrapped, which is recoverable.
     */
    private fun readRoundTrippablePayload(): String? =
        readWrappablePayload()?.takeIf { survivesUnwrap(it) }

    /**
     * Whether [applyDecodedPayload] would accept this payload on the unlock half of the
     * round trip. Mirrors its two arms — a tagged joiner payload, or a mnemonic — and adds
     * the one check the unlock side cannot make cheaply on its own.
     *
     * ⚠ `looksLikeMnemonic` counts words and checks they are letters; it consults neither
     * the wordlist nor the checksum. That is fine as the wrong-*key* gate it was written to
     * be, but it means a phrase this build cannot decode passes it and then throws out of
     * `initialize` — past `unlockLocal`'s `catch`, out of a `Boolean`-returning function.
     * Decoding here keeps that phrase from being sealed in the first place. The reachable
     * case is a phrase minted under the retired wordlist (threat model §11.2);
     * the next wordlist change is the case that will be.
     *
     * ⚠ All five BIP-39 lengths are accepted, not just 24. A short phrase is a separate
     * problem with its own `tech-debt.md` entry, and rejecting one here would lock existing
     * short-phrase realms out of unlock setup entirely.
     */
    private fun survivesUnwrap(payload: String): Boolean {
        if (decodeJoinerPayload(payload) != null) return true
        if (!looksLikeMnemonic(payload)) return false
        return try {
            Bip39.decode(payload)
            true
        } catch (_: Bip39InvalidMnemonicException) {
            false
        }
    }

    /**
     * Clear *all* plaintext A1 slots. Called after a successful wrapper
     * write so the on-disk shape post-setup is "wrapper only, no plaintext"
     * for both roles.
     */
    private fun clearPlaintextPayloadSlots() {
        storageDelegate.remove(Keys.RECOVERY_PHRASE)
        storageDelegate.remove(Keys.JOINER_PAYLOAD)
    }

    /**
     * Parse a decrypted wrapper plaintext and route it to the
     * matching reinitialise path. Three accepted shapes:
     *  - `J:<b64-identityKey>|<b64-volumeKey>|<ownerId>` → joiner →
     *    `initializeWithKeys`. Also re-persists the plaintext joiner slot so
     *    follow-up `setUpBiometricUnlock` calls (e.g. enabling a second
     *    credential) can re-wrap without re-pairing.
     *  - A BIP-39-shaped string → master → `initialize`. Covers both the
     *    untagged wrappers written before the role tags existed and the
     *    unprefixed master shape.
     *  - Anything else → returns `false` (wrong key or corrupted blob; the
     *    caller surfaces a generic "didn't work" UI without leaking which
     *    leg failed).
     */
    private fun applyDecodedPayload(decrypted: String): Boolean {
        val joiner = decodeJoinerPayload(decrypted)
        if (joiner != null) {
            // Re-seed the plaintext slot so subsequent `setUpBiometricUnlock`
            // (e.g. user enables biometric *after* having unlocked with
            // passphrase) finds something to wrap. Mirrors the master path
            // where `initialize` writes `RECOVERY_PHRASE`.
            storageDelegate.storeSecret(Keys.JOINER_PAYLOAD, decrypted)
            initializeWithKeys(
                joiner.keys,
                joiner.ownerId,
                joiner.realmEncryptionKeyPair,
                joiner.admissionKeyPair,
                joiner.admissionCertChain,
            )
            return true
        }
        if (looksLikeMnemonic(decrypted)) {
            initialize(decrypted)
            return true
        }
        return false
    }

    /**
     * Encode a [JoinerPayload] into the tagged string the wrapper
     * format stores. See [decodeJoinerPayload] for the inverse + format spec.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeJoinerPayload(payload: JoinerPayload): String {
        val identity = Base64.encode(payload.keys.sharedIdentityKey)
        val volume = Base64.encode(payload.keys.sharedVolumeKey)
        // SR-3b.4: when A13 is present use the V2 tag carrying two extra fields
        // (a13Pub|a13Priv) BEFORE the ownerId, so the "ownerId may contain '|'"
        // tolerance (ownerId is always the trailing field) is preserved. Without
        // A13, fall back to the legacy `J:` 3-field shape verbatim.
        //
        // P3 (delegated-device-admission-authority.md §4.2): when the device also
        // holds an admission capability (A33 keypair + cert chain — only ever
        // delivered on top of A13), use the V3 tag with three more fields
        // (a33Pub|a33Priv|chain) before the trailing ownerId. The chain is the
        // base64 of `encodeAdmissionCertChain` — base64 has no '|', so the field
        // split stays unambiguous. An EMPTY chain is legal (P4): a self-minted
        // keypair still awaiting its cert grant persists as J3 with a zero-cert
        // chain — the device is a non-admitter until the grant lands.
        val a13 = payload.realmEncryptionKeyPair
        val a33 = payload.admissionKeyPair
        return when {
            a13 != null && a33 != null ->
                PAYLOAD_TAG_JOINER_V3 + identity +
                        PAYLOAD_FIELD_SEPARATOR + volume +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(a13.publicKey) +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(a13.privateKey) +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(a33.publicKey) +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(a33.privateKey) +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(encodeAdmissionCertChain(payload.admissionCertChain)) +
                        PAYLOAD_FIELD_SEPARATOR + payload.ownerId

            a13 != null ->
                PAYLOAD_TAG_JOINER_V2 + identity +
                        PAYLOAD_FIELD_SEPARATOR + volume +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(a13.publicKey) +
                        PAYLOAD_FIELD_SEPARATOR + Base64.encode(a13.privateKey) +
                        PAYLOAD_FIELD_SEPARATOR + payload.ownerId

            else ->
                PAYLOAD_TAG_JOINER + identity +
                        PAYLOAD_FIELD_SEPARATOR + volume +
                        PAYLOAD_FIELD_SEPARATOR + payload.ownerId
        }
    }

    /**
     * Parse a tagged joiner payload back into typed form, or return
     * `null` when [s] is not a joiner-shape string (master phrase, garbage
     * from a wrong wrapper key, legacy unprefixed master wrapper, …).
     * Tolerates `ownerId` values containing the field separator — only the
     * first two `|` are treated as field delimiters.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeJoinerPayload(s: String): JoinerPayload? {
        // P3 V3 (`J3:`): identity|volume|a13Pub|a13Priv|a33Pub|a33Priv|chain|ownerId —
        // split the first seven separators; everything after the 7th is the ownerId.
        // The chain may be empty (P4: self-minted keypair awaiting its cert grant).
        // A payload whose admission tail fails to decode degrades to `null` like any
        // other corrupt payload (wrong wrapper key posture) — the J2/J shapes are
        // separate tags, so a valid pre-P3 payload never reaches this branch.
        if (s.startsWith(PAYLOAD_TAG_JOINER_V3)) {
            val body = s.substring(PAYLOAD_TAG_JOINER_V3.length)
            val parts = body.split(PAYLOAD_FIELD_SEPARATOR, limit = 8)
            if (parts.size < 8) return null
            val decoded = try {
                parts.take(7).map { Base64.decode(it) }
            } catch (_: IllegalArgumentException) {
                return null
            }
            val chain = try {
                decodeAdmissionCertChain(decoded[6])
            } catch (_: IllegalArgumentException) {
                return null
            }
            val ownerId = parts[7]
            if (ownerId.isEmpty()) return null
            return JoinerPayload(
                keys = SyncCryptoManager.DerivedKeys(sharedIdentityKey = decoded[0], sharedVolumeKey = decoded[1]),
                ownerId = ownerId,
                realmEncryptionKeyPair = SyncCryptoManager.KeyPair(publicKey = decoded[2], privateKey = decoded[3]),
                admissionKeyPair = SyncCryptoManager.KeyPair(publicKey = decoded[4], privateKey = decoded[5]),
                admissionCertChain = chain,
            )
        }
        // SR-3b.4 V2 (`J2:`): identity|volume|a13Pub|a13Priv|ownerId — split the first
        // four separators; everything after the 4th is the ownerId (preserves the
        // ownerId-may-contain-'|' tolerance).
        if (s.startsWith(PAYLOAD_TAG_JOINER_V2)) {
            val body = s.substring(PAYLOAD_TAG_JOINER_V2.length)
            val parts = body.split(PAYLOAD_FIELD_SEPARATOR, limit = 5)
            if (parts.size < 5) return null
            val identity = try { Base64.decode(parts[0]) } catch (_: IllegalArgumentException) { return null }
            val volume = try { Base64.decode(parts[1]) } catch (_: IllegalArgumentException) { return null }
            val a13Pub = try { Base64.decode(parts[2]) } catch (_: IllegalArgumentException) { return null }
            val a13Priv = try { Base64.decode(parts[3]) } catch (_: IllegalArgumentException) { return null }
            val ownerId = parts[4]
            if (ownerId.isEmpty()) return null
            return JoinerPayload(
                keys = SyncCryptoManager.DerivedKeys(sharedIdentityKey = identity, sharedVolumeKey = volume),
                ownerId = ownerId,
                realmEncryptionKeyPair = SyncCryptoManager.KeyPair(publicKey = a13Pub, privateKey = a13Priv),
            )
        }
        if (!s.startsWith(PAYLOAD_TAG_JOINER)) return null
        val body = s.substring(PAYLOAD_TAG_JOINER.length)
        val sepIdx1 = body.indexOf(PAYLOAD_FIELD_SEPARATOR)
        if (sepIdx1 < 0) return null
        val sepIdx2 = body.indexOf(PAYLOAD_FIELD_SEPARATOR, sepIdx1 + 1)
        if (sepIdx2 < 0) return null
        val identityB64 = body.substring(0, sepIdx1)
        val volumeB64 = body.substring(sepIdx1 + 1, sepIdx2)
        val ownerId = body.substring(sepIdx2 + 1)
        if (ownerId.isEmpty()) return null
        val identity = try { Base64.decode(identityB64) } catch (_: IllegalArgumentException) { return null }
        val volume = try { Base64.decode(volumeB64) } catch (_: IllegalArgumentException) { return null }
        return JoinerPayload(
            keys = SyncCryptoManager.DerivedKeys(
                sharedIdentityKey = identity,
                sharedVolumeKey = volume,
            ),
            ownerId = ownerId,
        )
    }

    /**
     * Carrier for the joiner-side wrapper payload. Not exported —
     * internal to the encode/decode helpers above.
     */
    private data class JoinerPayload(
        val keys: SyncCryptoManager.DerivedKeys,
        val ownerId: String,
        // SR-3b.4: the realm's A13 delivered via pairing. Null for legacy `J:`
        // payloads (no A13) — the device then can't unwrap shared-realm keys.
        val realmEncryptionKeyPair: SyncCryptoManager.KeyPair? = null,
        // P3: the device's A33 admission keypair + cert chain. Null/empty for
        // J:/J2: payloads — the device is then a non-admitter.
        val admissionKeyPair: SyncCryptoManager.KeyPair? = null,
        val admissionCertChain: List<AdmissionCapabilityCert> = emptyList(),
    )

    object Keys {
        /**
         * ⚠ A plaintext CACHE of `deviceIdForPublicKey(A4.publicKey)`, written by [currentDeviceId] —
         * not an identity of its own. It exists for readers that run before a `SyncManager` does.
         */
        const val DEVICE_ID = "sync_device_id"
        // C4c (Control-on-iOS): Asset A4 — the device's Ed25519 signing keypair, persisted at
        // rest (secret bucket, `Base64(pub)|Base64(priv)`) so the device keeps ONE stable relay
        // identity across restarts instead of regenerating it (and re-registering) each launch.
        const val DEVICE_SIGNING_KEY = "sync_device_signing_key"
        const val RECOVERY_PHRASE = "sync_recovery_phrase"
        const val UNLOCK_WRAPPER = "sync_unlock_wrapper"
        const val BIOMETRIC_WRAPPER = "sync_biometric_wrapper"
        // Joiner-side analogue of RECOVERY_PHRASE — the transient
        // plaintext slot holding the encoded derived-keys + ownerId tuple
        // between `initializeWithKeys` and the first setUp{Local,Biometric}Unlock.
        const val JOINER_PAYLOAD = "sync_joiner_payload"
        // Persisted `currentOwnerId`. Read by `init` to rehydrate the
        // joiner's identity across restarts when no wrapper has been set up yet
        // (the "Skip on SetupUnlock" path). Host-device sessions also write this
        // for symmetry, but the host's autoload path re-derives the ownerId
        // from the recovery phrase regardless.
        const val OWNER_ID = "sync_owner_id"
        const val LAST_SYNC_TIMESTAMP = "sync_last_timestamp"

        /**
         * Every slot a device discard must clear — the single definition both platforms purge from.
         *
         * ⚠⚠ One list for desktop and iOS's `wipeDeviceState`, because two hand-maintained lists
         * drift even while carrying comments saying they cannot. A slot one platform misses — the
         * F5a `SEEDED_REALM_EPOCH`, say — is carried into the next realm and would stamp a fresh
         * admission with a stranger's epoch. A new custody slot is forgotten on both platforms or
         * neither.
         */
        val CUSTODY_SLOTS: List<String> = listOf(
            DEVICE_ID,
            DEVICE_SIGNING_KEY,
            RECOVERY_PHRASE,
            UNLOCK_WRAPPER,
            BIOMETRIC_WRAPPER,
            JOINER_PAYLOAD,
            OWNER_ID,
            LAST_SYNC_TIMESTAMP,
            REALM_RELAY_REGISTERED,
            JOINED_FUNDED_REALM,
            SEEDED_REALM_EPOCH,
        )

        /**
         * The out-of-band realm-epoch seed (F5a). ⚠ Non-secret by nature — a tombstone count — and
         * cleared by `forgetThisDevice`, since a device that has left the realm must not carry its
         * epoch into the next one.
         */
        const val SEEDED_REALM_EPOCH = "sync_seeded_realm_epoch"
        // ADR 0019 lazy realm genesis: persistent once-ever "registered on relay" latch.
        const val REALM_RELAY_REGISTERED = "sync_realm_relay_registered"

        // Set when this build created, recovered or joined the realm: its backfill has nothing to do.
        const val CONTENT_DESCRIBED_FROM_GENESIS = "sync_content_described_from_genesis"
        // storage-entitlement-sync.md: persistent "joined an owner-funded realm via
        // pairing" latch — opens the personal-realm sync gate for the joiner bootstrap.
        const val JOINED_FUNDED_REALM = "sync_joined_funded_realm"
    }

    private companion object {
        const val SALT_BYTES = 16
        const val WRAPPER_KDF_ITERATIONS = 200_000
        const val WRAPPER_KEY_BITS = 256
        const val WRAPPER_SEPARATOR = ":"
        // Separator between Base64(pub) and Base64(priv) in the persisted A4 keypair blob.
        // Not in the Base64 alphabet, so it can never collide with the encoded halves.
        const val DEVICE_SIGNING_KEY_SEPARATOR = "|"
        // BIP-39 mnemonic word counts per `core-common/util/Bip39.kt`.
        val MNEMONIC_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

        // Wrapper-payload discriminators. The wrapped plaintext is a
        // UTF-8 string; the first two chars identify which device role wrote
        // it. Wrappers written before the tags existed have no prefix — `decodePayload`
        // falls back to a `looksLikeMnemonic` heuristic for those.
        const val PAYLOAD_TAG_MASTER = "M:"
        const val PAYLOAD_TAG_JOINER = "J:"
        // SR-3b.4: joiner payload carrying the realm's A13 (a13Pub|a13Priv before
        // the trailing ownerId). Checked before `J:` since `"J2:".startsWith("J:")`
        // is false but `"J:".startsWith` would mis-greedy if order were flipped.
        const val PAYLOAD_TAG_JOINER_V2 = "J2:"
        // P3 (delegated admission): joiner payload additionally carrying the A33
        // admission keypair + cert chain (a33Pub|a33Priv|chain before the trailing
        // ownerId). Checked before `J2:`/`J:` in `decodeJoinerPayload`.
        const val PAYLOAD_TAG_JOINER_V3 = "J3:"
        const val PAYLOAD_FIELD_SEPARATOR = "|"
    }
}
