package org.tenshu.sync.relay

import org.tenshu.core.common.security.AccountClosureClaims
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reclaim decision (`retention-quota-model.md` Face A / D4).
 *
 * ⚠ Every test here is about a *refusal*. The happy path is one case; the rest pin the brakes,
 * because this is the only code in the system that permanently destroys user content.
 */
class AccountClosureReclaimerTest {

    private val day = 24L * 60 * 60 * 1000

    private fun closure(ownerId: String = "alice", closedAtMs: Long) = AccountClosureClaims(
        ownerId = ownerId,
        closedAtSec = closedAtMs / 1_000,
        issuedAtSec = closedAtMs / 1_000,
        expiresAtSec = (closedAtMs / 1_000) + 3_600,
    )

    /** RelayConfig's first three params carry no defaults; everything else does. */
    private fun config(reclaim: Boolean = false, graceMs: Long = 30 * day) = RelayConfig(
        devMode = true,
        bindHost = "127.0.0.1",
        port = 8080,
        accountClosureReclaim = reclaim,
        accountClosureGraceMs = graceMs,
    )

    private fun storeWith(vararg owners: String) = ContentBlobStore().apply {
        owners.forEach { put(it, "blob-1", ByteArray(10), nowMs = 0) }
    }

    @Test
    fun `does nothing at all when the flag is off - the shipped default`() {
        val store = storeWith("alice")
        val reclaimer = AccountClosureReclaimer(AccountErasure(listOf(store)), config())

        // ⚠⚠ The deploy gate. A closure a year past grace must still be inert on a relay started
        // without the flag — the operator turns it on deliberately (production did, 2026-10-24).
        val outcome = reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = 365 * day)

        assertIs<AccountClosureReclaimer.Outcome.Disabled>(outcome)
        assertEquals(1, store.blobCount("alice"), "nothing was touched")
    }

    @Test
    fun `holds inside the grace window and reports what remains`() {
        val store = storeWith("alice")
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store)),
            config(reclaim = true, graceMs = 30 * day),
        )

        val outcome = reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = 29 * day)

        val within = assertIs<AccountClosureReclaimer.Outcome.WithinGrace>(outcome)
        assertEquals(day, within.remainingMs)
        assertEquals(1, store.blobCount("alice"), "grace means the bytes are still there")
    }

    @Test
    fun `reclaims once past grace and reports what it freed`() {
        val store = storeWith("alice", "bob")
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store)),
            config(reclaim = true, graceMs = 30 * day),
        )

        val outcome = reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = 31 * day)

        val reclaimed = assertIs<AccountClosureReclaimer.Outcome.Reclaimed>(outcome)
        assertEquals(1, reclaimed.result.blobsDropped)
        assertEquals(10L, reclaimed.result.bytesFreed)
        assertEquals(0, store.blobCount("alice"))
        assertEquals(1, store.blobCount("bob"), "only the closed owner")
    }

    @Test
    fun `past grace the closure erases the owner's metadata too, not only content - F12`() {
        val store = storeWith("alice", "bob")
        val realms = org.tenshu.sync.relay.mailbox.RealmStore()
        val devices = org.tenshu.sync.relay.auth.KnownDevicesStore()
        for (owner in listOf("alice", "bob")) {
            realms.createIfAbsent(org.tenshu.sync.relay.mailbox.RealmStore.RealmRecord(owner, ByteArray(32), 0))
            devices.register(owner, "phone", ByteArray(32))
        }
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store, realms, devices)),
            config(reclaim = true, graceMs = 30 * day),
        )

        val reclaimed = assertIs<AccountClosureReclaimer.Outcome.Reclaimed>(
            reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = 31 * day),
        )

        assertEquals(2, reclaimed.result.rowsErased, "the realm row and the device row")
        assertEquals(null, realms.get("alice"))
        assertEquals(null, devices.getPublicKey("alice", "phone"))
        assertTrue(realms.get("bob") != null && devices.getPublicKey("bob", "phone") != null, "only the closed owner")
    }

    @Test
    fun `within grace no store is touched - metadata included`() {
        val realms = org.tenshu.sync.relay.mailbox.RealmStore()
        realms.createIfAbsent(org.tenshu.sync.relay.mailbox.RealmStore.RealmRecord("alice", ByteArray(32), 0))
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(realms)),
            config(reclaim = true, graceMs = 30 * day),
        )

        assertIs<AccountClosureReclaimer.Outcome.WithinGrace>(
            reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = 29 * day),
        )
        assertTrue(realms.get("alice") != null)
    }

    @Test
    fun `grace runs from the recorded closure time, not from delivery`() {
        val store = storeWith("alice")
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store)),
            config(reclaim = true, graceMs = 30 * day),
        )

        // ⚠ The record is delivered late — issued/expiring "now", but recording a closure 31 days
        // ago. Measuring grace from delivery would restart the clock on every retry and mean a
        // flaky delivery path silently extends retention past what the terms promise.
        val lateRecord = AccountClosureClaims(
            ownerId = "alice",
            closedAtSec = 0,
            issuedAtSec = 31 * day / 1_000,
            expiresAtSec = (31 * day / 1_000) + 3_600,
        )

        assertIs<AccountClosureReclaimer.Outcome.Reclaimed>(
            reclaimer.reclaimIfDue(lateRecord, nowMs = 31 * day),
        )
    }

    @Test
    fun `a zero grace window reclaims immediately rather than never`() {
        val store = storeWith("alice")
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store)),
            config(reclaim = true, graceMs = 0),
        )

        // A deliberate operator choice (e.g. an erasure request). `elapsed >= grace` makes 0 mean
        // "now"; a strict `>` would make it mean "never" for a same-instant record.
        assertIs<AccountClosureReclaimer.Outcome.Reclaimed>(
            reclaimer.reclaimIfDue(closure(closedAtMs = 5_000), nowMs = 5_000),
        )
    }

    @Test
    fun `a repeated delivery is a harmless no-op`() {
        val store = storeWith("alice")
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store)),
            config(reclaim = true, graceMs = 0),
        )

        reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = day)
        val second = reclaimer.reclaimIfDue(closure(closedAtMs = 0), nowMs = day)

        // No dedupe state is kept on purpose — the primitive is safe to repeat, so a retried
        // delivery needs no bookkeeping the relay would then have to keep correct.
        val result = assertIs<AccountClosureReclaimer.Outcome.Reclaimed>(second).result
        assertEquals(0, result.blobsDropped)
        assertEquals(0L, result.bytesFreed)
    }

    @Test
    fun `a closure dated in the future never reclaims`() {
        val store = storeWith("alice")
        val reclaimer = AccountClosureReclaimer(
            AccountErasure(listOf(store)),
            config(reclaim = true, graceMs = 0),
        )

        // ⚠ A clock-skewed or malformed-but-signed record must fail *closed*: negative elapsed
        // time is always inside any grace window, so it holds rather than deleting.
        val outcome = reclaimer.reclaimIfDue(closure(closedAtMs = 10 * day), nowMs = day)

        val within = assertIs<AccountClosureReclaimer.Outcome.WithinGrace>(outcome)
        assertTrue(within.remainingMs > 0)
        assertEquals(1, store.blobCount("alice"))
    }
}
