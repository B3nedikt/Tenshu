package org.tenshu.sync.relay.storage

import java.io.File
import org.tenshu.sync.relay.mailbox.InviteRegistry
import org.tenshu.sync.relay.mailbox.InviteStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Durable invite mailbox (`contact-invitation-handshake.md` §6): SPI parity
 * with the in-memory [InviteStore] (the same scenario asserted against both
 * backends), the §6 retention sweep, and persistence across a reopen.
 */
class SqliteInviteStoreTest {

    private lateinit var dbFile: File
    private lateinit var url: String

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-invite-test", ".db")
        url = "jdbc:sqlite:${dbFile.absolutePath}"
    }

    @AfterTest
    fun teardown() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun backends(): List<Pair<String, InviteRegistry>> = listOf(
        "in-memory" to InviteStore(),
        "sqlite" to SqliteInviteStore(RelayDatabase(url)),
    )

    @Test
    fun lifecycleParity_acrossBackends() {
        for ((name, store) in backends()) {
            val now = 1_000L
            val expiry = now + 1_000_000L

            // Register + duplicate.
            assertEquals(
                InviteRegistry.RegisterResult.REGISTERED,
                store.register("invite-1", "alice", expiry, now, maxOpenPerOwner = 2),
                name,
            )
            assertEquals(
                InviteRegistry.RegisterResult.DUPLICATE,
                store.register("invite-1", "bob", expiry, now, maxOpenPerOwner = 2),
                name,
            )

            // Per-owner cap (cap = 2; second register is invite-2, third trips).
            assertEquals(
                InviteRegistry.RegisterResult.REGISTERED,
                store.register("invite-2", "alice", expiry, now, maxOpenPerOwner = 2),
                name,
            )
            assertEquals(
                InviteRegistry.RegisterResult.CAP_EXCEEDED,
                store.register("invite-3", "alice", expiry, now, maxOpenPerOwner = 2),
                name,
            )

            // Single response slot.
            assertEquals(
                InviteRegistry.ResponseResult.UNKNOWN,
                store.postResponse("missing", byteArrayOf(1), now),
                name,
            )
            assertEquals(
                InviteRegistry.ResponseResult.POSTED,
                store.postResponse("invite-1", byteArrayOf(1, 2), now + 1),
                name,
            )
            assertEquals(
                InviteRegistry.ResponseResult.ALREADY_RESPONDED,
                store.postResponse("invite-1", byteArrayOf(3), now + 2),
                name,
            )

            // Pending listing is per-owner and carries the blob.
            val pending = store.pendingResponses("alice")
            assertEquals(1, pending.size, name)
            assertEquals("invite-1", pending[0].inviteId, name)
            assertTrue(pending[0].sealedResponse.contentEquals(byteArrayOf(1, 2)), name)
            assertEquals(0, store.pendingResponses("bob").size, name)

            // Ack: conflict before response (invite-2), ownership enforced.
            assertEquals(
                InviteRegistry.AckResult.CONFLICT,
                store.postAck("invite-2", "alice", byteArrayOf(9)),
                name,
            )
            assertEquals(
                InviteRegistry.AckResult.UNKNOWN,
                store.postAck("invite-1", "mallory", byteArrayOf(9)),
                name,
            )
            assertEquals(
                InviteRegistry.AckResult.POSTED,
                store.postAck("invite-1", "alice", byteArrayOf(9, 9)),
                name,
            )
            val fetched = store.getAck("invite-1", now + 3)
            assertIs<InviteRegistry.AckFetch.Found>(fetched, name)
            assertTrue(fetched.sealedAck.contentEquals(byteArrayOf(9, 9)), name)

            // Acked invites leave the pending list.
            assertEquals(0, store.pendingResponses("alice").size, name)

            // Revoke is owner-scoped; revoked reads as Gone.
            assertFalse(store.revoke("invite-2", "mallory"), name)
            assertTrue(store.revoke("invite-2", "alice"), name)
            assertEquals(
                InviteRegistry.ResponseResult.GONE,
                store.postResponse("invite-2", byteArrayOf(1), now + 4),
                name,
            )
            assertIs<InviteRegistry.AckFetch.Gone>(store.getAck("invite-2", now + 4), name)

            // done frees state.
            assertTrue(store.done("invite-1"), name)
            assertIs<InviteRegistry.AckFetch.NotFound>(store.getAck("invite-1", now + 5), name)
        }
    }

    @Test
    fun retentionSweep_honoursPostResponseGrace() {
        for ((name, store) in backends()) {
            val now = 10_000L
            val retention = InviteStore.RESPONSE_RETENTION_MS

            // Expired, never redeemed → collectible at expiry.
            store.register("stale", "alice", now + 100, now, maxOpenPerOwner = 32)
            // Expired but responded → survives until respondedAt + 72 h.
            store.register("graced", "alice", now + 100, now, maxOpenPerOwner = 32)
            store.postResponse("graced", byteArrayOf(1), now + 50)
            // Unexpired → untouched.
            store.register("live", "alice", now + retention * 10, now, maxOpenPerOwner = 32)

            assertEquals(1, store.gcSweep(now + 200), name) // drops "stale" only
            assertEquals(2, store.recordCount(), name)

            // Inside the grace window: "graced" still answers.
            assertEquals(0, store.gcSweep(now + 50 + retention - 1), name)
            // Past it: dropped.
            assertEquals(1, store.gcSweep(now + 50 + retention), name)
            assertEquals(1, store.recordCount(), name)
        }
    }

    @Test
    fun persistsAcrossReopen() {
        val now = 1_000L
        val expiry = now + 1_000_000L
        run {
            val store = SqliteInviteStore(RelayDatabase(url))
            store.register("invite-1", "alice", expiry, now, maxOpenPerOwner = 32)
            store.postResponse("invite-1", byteArrayOf(4, 2), now + 1)
        }
        // A "restarted relay": fresh connection over the same file.
        val reopened = SqliteInviteStore(RelayDatabase(url))
        assertEquals(1, reopened.recordCount())
        val pending = reopened.pendingResponses("alice")
        assertEquals(1, pending.size)
        assertTrue(pending[0].sealedResponse.contentEquals(byteArrayOf(4, 2)))
    }
}
