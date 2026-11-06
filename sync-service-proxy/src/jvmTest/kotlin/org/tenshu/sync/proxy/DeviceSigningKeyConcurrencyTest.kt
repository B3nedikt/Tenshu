package org.tenshu.sync.proxy

import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asset A4 must not fork under concurrent first touch.
 *
 * `SyncManagerImpl.getDeviceSigningKeyPair()` is a lazy initialiser — check memo, read storage,
 * mint, persist, memo — and its KDoc promises the device "keeps ONE stable signing identity across
 * restarts". Nothing serialises those five steps, so two callers arriving before the first persist
 * can both pass the null checks, both mint, and both persist; each then returns *its own* pair and
 * the second `storeSecret` overwrites the first.
 *
 * The consequence is not a wasted keypair, it is a **split identity**: the relay registers whichever
 * public key `createRealm` carried and then rejects a signature made with the other one. That is
 * the `401 Unauthorized — Invalid offer-response signature` behind the pairing flake population in
 * docs/tech-debt.md, and it is reachable in production — genesis registers the key while the sync
 * scheduler concurrently mints JWTs through this same accessor.
 *
 * ⚠ Two fixture choices are load-bearing, and the test is worthless without either:
 *
 *  - **The crypto must return a distinct key per call.** `BootstrapSyncCryptoManager` returns
 *    *canned* bytes, so under it a fork produces two identical keys and this test can never fail —
 *    the same trap `DeviceSigningKeyPersistenceTest` documents. Production and the system tests use
 *    `DefaultSyncCryptoManager`, whose keys are random; [DistinctKeyCrypto] models exactly that
 *    property, deterministically.
 *  - **The storage delegate must be thread-safe.** `InMemorySecureStorageDelegate` wraps a plain
 *    `mutableMapOf`, so a failure against it could be the map's fault rather than the manager's.
 *    [RecordingStorage] is concurrent, and additionally counts what was written so the fork is
 *    provable from the persistence side even if the returned keys happened to agree.
 */
class DeviceSigningKeyConcurrencyTest {

    @Test
    fun concurrentFirstTouchYieldsOneIdentity() {
        // The window is between the null check and the persist, so a single attempt proves little;
        // rounds make an existing race near-certain to show while costing a fraction of a second.
        repeat(ROUNDS) { round ->
            val storage = RecordingStorage()
            val manager = SyncManagerImpl(
                cryptoManager = DistinctKeyCrypto(),
                storageDelegate = storage,
                context = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
                    it.schemaRegistry.registerConfig(syncServiceCommonModule)
                    it.schemaRegistry.registerConfig(identityServiceCommonModule)
                }.tContext,
                autoLoadDispatcher = Dispatchers.Unconfined,
            )

            val handedOut = ConcurrentLinkedQueue<String>()
            val pool = Executors.newFixedThreadPool(THREADS)
            val start = CountDownLatch(1)
            val done = CountDownLatch(THREADS)
            repeat(THREADS) {
                pool.execute {
                    // Release all threads at once — staggered starts would let each see the memo
                    // already populated and the race would never open.
                    start.await()
                    handedOut += manager.getDeviceSigningKeyPair().publicKey.toHex()
                    done.countDown()
                }
            }
            start.countDown()
            done.await(30, TimeUnit.SECONDS)
            pool.shutdown()

            assertEquals(
                1,
                handedOut.toSet().size,
                "round $round: every caller must receive the SAME A4 public key; " +
                    "got ${handedOut.toSet().size} distinct keys across $THREADS callers",
            )
            assertEquals(
                1,
                storage.distinctWrites(KEY),
                "round $round: exactly one A4 blob may ever be persisted — a second write means a " +
                    "losing caller overwrote the identity the winner is handing out",
            )
            assertEquals(
                handedOut.first(),
                storage.currentKeyHex(),
                "round $round: the persisted key must be the one callers hold, or the next process " +
                    "start loads an identity the relay never registered",
            )
        }
    }

    /** Distinct keypair per call — models real crypto's randomness without the backend wiring. */
    private class DistinctKeyCrypto(
        private val delegate: SyncCryptoManager = BootstrapSyncCryptoManager(),
    ) : SyncCryptoManager by delegate {
        private val counter = AtomicInteger(0)

        // ⚠ Must override this one, not just `generateKeyPair`: interface delegation routes the
        // default `generateDeviceSigningKeyPair()` body to the DELEGATE's `generateKeyPair`, so
        // overriding only the latter would silently keep handing back canned bytes.
        override fun generateDeviceSigningKeyPair(): SyncCryptoManager.KeyPair {
            val n = counter.incrementAndGet()
            return SyncCryptoManager.KeyPair(
                publicKey = ByteArray(32) { (n + it).toByte() },
                privateKey = ByteArray(64) { (n + it).toByte() },
            )
        }
    }

    /** Thread-safe, and remembers every distinct value written per key. */
    private class RecordingStorage : SecureStorageDelegate {
        private val values = ConcurrentHashMap<String, String>()
        private val writes = ConcurrentHashMap<String, MutableSet<String>>()

        override fun storeSecret(key: String, value: String) {
            values[key] = value
            writes.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(value)
        }

        override fun getSecret(key: String): String? = values[key]
        override fun storeString(key: String, value: String) = storeSecret(key, value)
        override fun getString(key: String): String? = values[key]
        override fun remove(key: String) {
            values.remove(key)
        }

        fun distinctWrites(key: String): Int = writes[key]?.size ?: 0

        fun currentKeyHex(): String? = values[KEY]
            ?.substringBefore('|')
            ?.let { java.util.Base64.getDecoder().decode(it).toHex() }
    }

    private companion object {
        const val KEY = "sync_device_signing_key"
        const val THREADS = 8
        const val ROUNDS = 200

        fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b) }
    }
}
