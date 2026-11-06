package org.tenshu.commerce.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `expireUnverifiedAccounts` — the one path that deletes `acceptance` rows — against both stores:
 * [InMemoryCommerceStore] always, and a **real Postgres** when `COMMERCE_TEST_DATABASE_URL` is set
 * (opt-in exactly like [PostgresCommerceSchemaTest]; ⚠ without it the Postgres tests pass
 * vacuously, and a fresh schema per test keeps the shared database clean).
 *
 * One fixture set, one victim: every other account differs from the victim in exactly one guard,
 * so each guard is pinned by an account that must survive.
 */
class UnverifiedAccountExpiryTest {

    private val url: String? = System.getenv("COMMERCE_TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }

    private fun withFreshSchema(block: (String) -> Unit) {
        val base = url ?: return
        val schema = "t_${System.nanoTime()}"
        fun exec(sql: String) = DriverManager.getConnection(base).use { c -> c.createStatement().use { it.execute(sql) } }
        exec("CREATE SCHEMA $schema")
        try {
            block(base + (if ('?' in base) "&" else "?") + "currentSchema=$schema")
        } finally {
            exec("DROP SCHEMA $schema CASCADE")
        }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_000L * DAY
        const val CUTOFF = NOW - 30 * DAY
        const val OLD = CUTOFF - DAY
        const val YOUNG = CUTOFF + DAY
    }

    /** Accounts by role → id. Only `victim` may be deleted. */
    private fun seed(store: CommerceStore): Map<String, String> {
        val ids = linkedMapOf<String, String>()
        fun old(role: String, createdAt: Long = OLD, lastRequest: Long = OLD): Account {
            val a = store.createAccount("$role@example.com", Rail.Free, createdAt)
            store.recordAcceptance(Acceptance(a.id, "tos", "privacy", createdAt, ip = "203.0.113.1"))
            if (lastRequest != createdAt) {
                store.recordAcceptance(Acceptance(a.id, "tos", "privacy", lastRequest, ip = "203.0.113.2"))
            }
            ids[role] = a.id
            return a
        }

        old("victim").also {
            // Two requests, both old; a pending (long-expired) code, a deletion code, a failed-code log.
            store.recordAcceptance(Acceptance(it.id, "tos", "privacy", OLD + 1, ip = "203.0.113.3"))
            store.putEmailVerification(it.emailNormalised, "h", OLD + 15 * 60_000)
            store.putEmailVerification(ErasedAccounts.deletionOtpKey(it.emailNormalised), "d", OLD)
            store.putEmailVerification(ErasedAccounts.otpFailuresKey(it.emailNormalised), "f", OLD)
        }
        old("young", createdAt = YOUNG, lastRequest = YOUNG)
        // Exactly at the cutoff: kept (strictly older only).
        old("boundary", createdAt = CUTOFF, lastRequest = CUTOFF)
        // Young with no request row at all: pins the created_at guard on its own.
        ids["young-bare"] = store.createAccount("young-bare@example.com", Rail.Free, YOUNG).id
        old("re-requested", lastRequest = YOUNG).also {
            store.putEmailVerification(it.emailNormalised, "h", NOW + 15 * 60_000)
        }
        old("verified").also { store.markEmailVerified(it.id, OLD + 1) }
        old("session").also { store.createSession("tok-${it.id}", it.id, OLD, OLD + DAY) }
        old("entitlement").also {
            store.appendEntitlementHistory(EntitlementHistory(it.id, "free", OLD, OLD + DAY, EntitlementSource.Free, OLD))
        }
        old("owner").also { store.bindOwner(it.id, "owner-${it.id}", OLD) }
        old("marketing").also { store.setMarketingConsent(MarketingConsent(it.id, granted = false, changedAt = OLD)) }
        old("waiver").also {
            store.recordAcceptance(Acceptance(it.id, "tos", null, OLD, kind = AcceptanceKind.PurchaseWithdrawalWaiver))
        }
        old("stripe").also { store.attachStripeCustomer(it.id, "cus_${it.id}") }
        old("invoice").also {
            store.appendInvoice(InvoiceDraft(it.id, "in_${it.id}", 100, "eur", 16, 1900, "DE", "DE", OLD))
        }
        old("closed").also { store.closeAccount(it.id, OLD + 1) }
        old("erased").also { store.eraseAccount(it.id, OLD + 1) }
        old("iap").also { store.setStoreRail(it.id, Rail.IapApple, "txn_${it.id}") }
        ids["stripe-rail"] = store.createAccount("stripe-rail@example.com", Rail.Stripe, OLD).id
        return ids
    }

    private fun assertOnlyVictimGone(store: CommerceStore, ids: Map<String, String>, deleted: Int) {
        assertEquals(1, deleted)
        assertNull(store.findAccountById(ids.getValue("victim")))
        assertNull(store.findAccountByEmail("victim@example.com"))
        assertNull(store.getEmailVerification("victim@example.com"))
        assertNull(store.getEmailVerification(ErasedAccounts.deletionOtpKey("victim@example.com")))
        assertNull(store.getEmailVerification(ErasedAccounts.otpFailuresKey("victim@example.com")))
        for ((role, id) in ids - "victim") assertNotNull(store.findAccountById(id), "$role must survive")
        // The live code of a re-requested account survives, so the user can still sign in.
        assertNotNull(store.getEmailVerification("re-requested@example.com"))
        // Idempotent: a second sweep finds nothing.
        assertEquals(0, store.expireUnverifiedAccounts(CUTOFF))
    }

    @Test
    fun inMemory_deletesOnlyTheBareCodeRequest() {
        val store = InMemoryCommerceStore()
        val ids = seed(store)
        assertOnlyVictimGone(store, ids, store.expireUnverifiedAccounts(CUTOFF))
    }

    @Test
    fun postgres_deletesOnlyTheBareCodeRequest_withItsAcceptanceRows() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).use { db ->
            val store = PostgresCommerceStore(db)
            val ids = seed(store)
            // States the store API cannot reach, each isolating one guard the rail / closure
            // guards would otherwise shadow: a payment reference on the Free rail, and an erased
            // account whose closure stamp is gone.
            db.withConnection { c ->
                c.createStatement().use { st ->
                    st.execute("UPDATE account SET rail = 'Free' WHERE id IN ('${ids.getValue("stripe")}', '${ids.getValue("iap")}')")
                    st.execute("UPDATE account SET closed_at = NULL, status = 'Active' WHERE id = '${ids.getValue("erased")}'")
                }
            }
            val before = db.withConnection { acceptanceCounts(it) }
            assertEquals(2, before[ids.getValue("victim")])

            assertOnlyVictimGone(store, ids, store.expireUnverifiedAccounts(CUTOFF))

            val after = db.withConnection { acceptanceCounts(it) }
            assertEquals(before - ids.getValue("victim"), after, "only the victim's acceptance rows may go")
            // The flag was transaction-local: this pooled connection refuses a DELETE again.
            db.withConnection { c ->
                val e = assertFailsWith<SQLException> {
                    c.createStatement().use { it.execute("DELETE FROM acceptance WHERE account_id = 'nobody'") }
                }
                assertTrue("append-only" in (e.message ?: ""), e.message)
            }
        }
    }

    /**
     * The trigger is the backstop for a bug in the store's guards: even with the flag on, a DELETE
     * may only take Registration rows of an unverified, open, never-erased account.
     */
    @Test
    fun postgres_theRowGuardRefusesEveryOtherAcceptanceRow_evenWithTheFlag() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).use { db ->
            val store = PostgresCommerceStore(db)
            val ids = seed(store)
            // Isolate the trigger's erased_at clause: erasure also stamps closed_at.
            db.withConnection { c ->
                c.createStatement().use { it.execute("UPDATE account SET closed_at = NULL WHERE id = '${ids.getValue("erased")}'") }
            }
            for (role in listOf("verified", "waiver", "closed", "erased")) {
                val e = assertFailsWith<SQLException>(role) {
                    db.transaction { c ->
                        c.createStatement().use {
                            it.execute("SELECT set_config('${CommerceDatabase.EXPIRE_UNVERIFIED_FLAG}', 'on', true)")
                            it.execute("DELETE FROM acceptance WHERE account_id = '${ids.getValue(role)}'")
                        }
                    }
                }
                assertTrue("append-only" in (e.message ?: ""), "$role: ${e.message}")
            }
            // Without the flag, even the victim's rows are refused.
            assertFailsWith<SQLException> {
                db.withConnection { c ->
                    c.createStatement().use { it.execute("DELETE FROM acceptance WHERE account_id = '${ids.getValue("victim")}'") }
                }
            }
            // UPDATE and TRUNCATE stay refused with the flag on.
            for (sql in listOf("UPDATE acceptance SET ip = NULL", "TRUNCATE acceptance")) {
                assertFailsWith<SQLException>(sql) {
                    db.transaction { c ->
                        c.createStatement().use {
                            it.execute("SELECT set_config('${CommerceDatabase.EXPIRE_UNVERIFIED_FLAG}', 'on', true)")
                            it.execute(sql)
                        }
                    }
                }
            }
            assertEquals(2, db.withConnection { acceptanceCounts(it) }[ids.getValue("victim")])
        }
    }

    /** The writes a racing request makes after its lookup must notice the account is gone. */
    private fun assertWritesToAMissingAccountReportIt(store: CommerceStore) {
        assertFalse(store.recordAcceptance(Acceptance("gone", "tos", "privacy", NOW, ip = "203.0.113.4")))
        assertFalse(store.markEmailVerified("gone", NOW))
        assertFalse(store.attachStripeCustomer("gone", "cus_gone"))
        val live = store.createAccount("live-${System.nanoTime()}@example.com", Rail.Free, NOW)
        assertTrue(store.recordAcceptance(Acceptance(live.id, "tos", "privacy", NOW, ip = "203.0.113.4")))
        assertTrue(store.markEmailVerified(live.id, NOW))
        assertTrue(store.attachStripeCustomer(live.id, "cus_live"))
    }

    @Test
    fun inMemory_writesToAMissingAccountReportIt() = assertWritesToAMissingAccountReportIt(InMemoryCommerceStore())

    @Test
    fun postgres_writesToAMissingAccountReportIt() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).use { db -> assertWritesToAMissingAccountReportIt(PostgresCommerceStore(db)) }
    }

    /**
     * The /register race: a request that found the account just before the sweep locked it. Its
     * acceptance insert must wait for the sweep and then insert nothing — never an orphan row the
     * v2 trigger could not delete, and never a row the sweep's own DELETE removes unseen.
     */
    @Test
    fun postgres_anAcceptanceRacingTheSweepWaitsAndThenInsertsNothing() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).use { db ->
            val store = PostgresCommerceStore(db)
            val victim = store.createAccount("race@example.com", Rail.Free, OLD)
            assertTrue(store.recordAcceptance(Acceptance(victim.id, "tos", "privacy", OLD, ip = "203.0.113.5")))
            val locked = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val sweep = Thread {
                db.transaction { c ->
                    c.createStatement().use { st ->
                        st.execute("SELECT set_config('${CommerceDatabase.EXPIRE_UNVERIFIED_FLAG}', 'on', true)")
                        st.execute("SELECT id FROM account WHERE id = '${victim.id}' FOR UPDATE")
                        locked.countDown()
                        release.await()
                        st.execute("DELETE FROM acceptance WHERE account_id = '${victim.id}'")
                        st.execute("DELETE FROM account WHERE id = '${victim.id}'")
                    }
                }
            }.apply { start() }
            locked.await()
            val racer = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val inserted = racer.submit<Boolean> {
                    store.recordAcceptance(Acceptance(victim.id, "tos", "privacy", NOW, ip = "203.0.113.6"))
                }
                Thread.sleep(300)
                assertFalse(inserted.isDone, "the insert must wait for the sweep's lock")
                release.countDown()
                sweep.join()
                assertFalse(inserted.get(), "the account is gone: nothing may be inserted")
            } finally {
                release.countDown()
                racer.shutdownNow()
            }
            assertEquals(null, db.withConnection { acceptanceCounts(it) }[victim.id])
        }
    }

    private fun acceptanceCounts(c: Connection): Map<String, Int> =
        c.createStatement().use { st ->
            st.executeQuery("SELECT account_id, COUNT(*) FROM acceptance GROUP BY account_id").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) }
            }
        }
}
