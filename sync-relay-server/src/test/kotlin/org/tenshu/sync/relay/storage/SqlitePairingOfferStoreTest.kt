package org.tenshu.sync.relay.storage

import java.io.File
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.sync.relay.PairingResponseInbox
import org.tenshu.sync.relay.mailbox.PairingOfferRegistry
import org.tenshu.sync.relay.mailbox.PairingOfferStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Offer-flow rendezvous store (`sync-pairing-inverted-direction.md` §3.4):
 * SPI parity between the in-memory [PairingOfferStore] and
 * [SqlitePairingOfferStore] for the serving-TTL / single-use / retention
 * semantics (§8's `PairingOfferStoreTtlTest`), plus persistence across a
 * reopen (the consumed marker must survive a relay restart).
 */
class SqlitePairingOfferStoreTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-pairing-offer-test", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun backends(): List<Pair<String, PairingOfferRegistry>> = listOf(
        "in-memory" to PairingOfferStore(),
        "sqlite" to SqlitePairingOfferStore(RelayDatabase(url)),
    )

    private fun response(payload: ByteArray? = byteArrayOf(9, 9, 9)) =
        PairingOfferRegistry.OfferResponse(
            ownerId = "owner-1",
            status = if (payload != null) "approved" else "denied",
            publicKeyA = ByteArray(32) { it.toByte() },
            payload = payload,
        )

    @Test
    fun aQueuedRegistration_roundTripsOnTheTerminalRow_andErasureDropsIt_parityAcrossBackends() {
        // The offer flow registers the offerer when it FETCHES the approval, so the verified
        // registration must survive the store — durably on SQLite, where a restart mid-handshake
        // would otherwise serve the key with no registration and leave the offerer 401ing.
        val t0 = 1_000_000L
        for ((name, store) in backends()) {
            val hash = "reg-$name"
            val admitted = HlcStamp(42L, 7L, "responder-device")
            val chainBytes = ByteArray(300) { (it * 7).toByte() }
            store.post(
                hash,
                PairingOfferRegistry.OfferResponse(
                    ownerId = "owner-1",
                    status = "approved",
                    publicKeyA = ByteArray(32) { it.toByte() },
                    payload = byteArrayOf(9),
                    registration = PairingResponseInbox.PendingRegistration(
                        ownerId = "owner-1",
                        deviceId = "offerer",
                        publicKey = byteArrayOf(5, 6, 7),
                        admittedAt = admitted,
                        admissionCertChain = chainBytes,
                    ),
                ),
                expiresAtMs = t0 + 60_000L,
                nowMs = t0,
            )
            val reg = assertIs<PairingOfferRegistry.Fetch.Found>(store.fetch(hash, nowMs = t0), name)
                .response.registration
            assertNotNull(reg, name)
            assertEquals("owner-1", reg.ownerId, name)
            assertEquals("offerer", reg.deviceId, name)
            assertContentEquals(byteArrayOf(5, 6, 7), reg.publicKey, name)
            assertEquals(admitted, reg.admittedAt, name)
            // ⚠⚠ S1: the chain must survive byte-for-byte — the delivery re-check re-verifies it, and
            // a lost or truncated chain would fail the joiner closed (or, read as A1-direct, open).
            assertContentEquals(chainBytes, reg.admissionCertChain, name)

            // A denial carries none.
            store.post("deny-$name", response(payload = null), expiresAtMs = t0 + 60_000L, nowMs = t0)
            assertNull(
                assertIs<PairingOfferRegistry.Fetch.Found>(store.fetch("deny-$name", nowMs = t0), name)
                    .response.registration,
                name,
            )

            // Account closure takes the queued registration with its row.
            store.eraseOwner("owner-1")
            assertIs<PairingOfferRegistry.Fetch.NotFound>(store.fetch(hash, nowMs = t0), name)
        }
    }

    @Test
    fun ttlSingleUseAndRetention_parityAcrossBackends() {
        for ((name, store) in backends()) {
            val hash = "hash-$name"
            val t0 = 1_000_000L
            val ttl = 7L * 60_000L

            // Nothing yet → NotFound.
            assertIs<PairingOfferRegistry.Fetch.NotFound>(store.fetch(hash, nowMs = t0), name)

            // Post + fetch inside the serving window.
            assertEquals(
                PairingOfferRegistry.PostResult.STORED,
                store.post(hash, response(), expiresAtMs = t0 + ttl, nowMs = t0),
                name,
            )
            val found = assertIs<PairingOfferRegistry.Fetch.Found>(store.fetch(hash, nowMs = t0 + 1_000))
            assertEquals("approved", found.response.status, name)
            assertContentEquals(byteArrayOf(9, 9, 9), found.response.payload, name)

            // Past the serving TTL the poll goes quiet…
            assertIs<PairingOfferRegistry.Fetch.NotFound>(
                store.fetch(hash, nowMs = t0 + ttl),
                "$name: blob must stop being served at expiry",
            )
            // …but the hash stays consumed (single-use survives the lapse, T-P3).
            assertEquals(
                PairingOfferRegistry.PostResult.CONSUMED,
                store.post(hash, response(null), expiresAtMs = t0 + 2 * ttl, nowMs = t0 + ttl + 1),
                "$name: a lapsed hash must still reject re-posts",
            )

            // Retention sweep: nothing dropped inside retention, dropped after.
            assertEquals(0, store.gcSweep(nowMs = t0 + ttl + 1), name)
            assertEquals(
                1,
                store.gcSweep(nowMs = t0 + ttl + PairingOfferRegistry.CONSUMED_RETENTION_MS),
                name,
            )
            // After the sweep the hash is fresh again (far outside any live window).
            assertEquals(
                PairingOfferRegistry.PostResult.STORED,
                store.post(hash, response(), expiresAtMs = t0 + 3 * ttl, nowMs = t0),
                name,
            )
        }
    }

    @Test
    fun sqlite_consumedMarkerSurvivesReopen() {
        val t0 = 5_000_000L
        SqlitePairingOfferStore(RelayDatabase(url)).post(
            "persist-hash", response(), expiresAtMs = t0 + 60_000, nowMs = t0,
        )

        val reopened = SqlitePairingOfferStore(RelayDatabase(url))
        val found = assertIs<PairingOfferRegistry.Fetch.Found>(
            reopened.fetch("persist-hash", nowMs = t0 + 1),
        )
        assertEquals("owner-1", found.response.ownerId)
        assertEquals(
            PairingOfferRegistry.PostResult.CONSUMED,
            reopened.post("persist-hash", response(null), expiresAtMs = t0 + 120_000, nowMs = t0 + 2),
            "single-use must survive a relay restart",
        )
    }
}
