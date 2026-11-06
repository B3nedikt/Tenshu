package org.tenshu.commerce.store

import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two guards the 2026-10 re-cut added to [CommerceDatabase], against a **real Postgres** —
 * neither exists in [InMemoryCommerceStore], so nothing else can pin them:
 *
 * - the **append-only rule** (D10, §5.2): `invoice`, `credit_note` and `acceptance` refuse
 *   `UPDATE`, `DELETE` and `TRUNCATE`;
 * - the **pre-reset refusal**: a database migrated by the old v1–v8 ladder fails to open with a
 *   "wipe it" instruction instead of being silently skipped past.
 *
 * Opt-in exactly like [PostgresAccountErasureTest] (`COMMERCE_TEST_DATABASE_URL`; ⚠ without it every
 * test passes vacuously). Each test runs in its own fresh Postgres schema, so the shared database's
 * own `schema_version` never decides the outcome.
 */
class PostgresCommerceSchemaTest {

    private val url: String? = System.getenv("COMMERCE_TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }

    /**
     * Runs [block] against a fresh, empty schema (its JDBC URL's search_path is that schema alone),
     * and drops the schema afterwards — pass or fail — so the shared test database does not fill up.
     */
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

    private fun refused(jdbc: String, sql: String): SQLException =
        assertFailsWith<SQLException>("expected the append-only trigger to refuse: $sql") {
            DriverManager.getConnection(jdbc).use { c -> c.createStatement().use { it.execute(sql) } }
        }

    @Test
    fun statutoryTablesRefuseUpdateDeleteAndTruncate() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).use { db ->
            val store = PostgresCommerceStore(db)
            val account = store.createAccount("schema-${System.nanoTime()}@example.com", Rail.Free, createdAt = 1L)
            store.recordAcceptance(Acceptance(account.id, "v1", "v1", 2L, ip = "203.0.113.9"))
            store.recordAcceptance(
                Acceptance(account.id, "v1", null, 3L, kind = AcceptanceKind.PurchaseWithdrawalWaiver),
            )
            val invoice = store.appendInvoice(
                InvoiceDraft(account.id, "in_${System.nanoTime()}", 1190, "eur", 190, 1900, "DE", "DE", 4L),
            )
            store.appendCreditNote(
                CreditNoteDraft(
                    accountId = account.id,
                    kind = CreditNoteKind.Refund,
                    stripeReferenceId = "re_${System.nanoTime()}",
                    referencesInvoiceNumber = invoice.number,
                    amountMinor = 1190,
                    currency = "eur",
                    vatAmountMinor = 190,
                    vatRateBasisPoints = 1900,
                    billingCountry = "DE",
                    issuedAt = 5L,
                ),
            )

            for (table in CommerceDatabase.APPEND_ONLY_TABLES) {
                for (sql in listOf(
                    "UPDATE $table SET account_id = account_id",
                    "DELETE FROM $table",
                    // ⚠ Zero matching rows is still refused: the trigger is per statement.
                    "DELETE FROM $table WHERE account_id = 'nobody'",
                    "TRUNCATE $table",
                )) {
                    val e = refused(jdbc, sql)
                    assertTrue("append-only" in (e.message ?: ""), "wrong refusal for `$sql`: ${e.message}")
                }
            }

            // ⚠⚠ An upsert is refused even though nothing conflicts: the statement fires the
            // statement-level UPDATE trigger. DO NOTHING is fine (appendCreditNote above relies on it).
            val upsert = refused(
                jdbc,
                "INSERT INTO credit_note(number, account_id, kind, stripe_reference_id, amount_minor, currency, issued_at) " +
                    "VALUES (nextval('credit_note_number_seq'), 'x', 'Refund', 'fresh-ref', 1, 'eur', 1) " +
                    "ON CONFLICT (stripe_reference_id) DO UPDATE SET amount_minor = EXCLUDED.amount_minor",
            )
            assertTrue("append-only" in (upsert.message ?: ""), upsert.message)

            // Nothing was changed, and erasure — the one path that scrubs a person — still works
            // without touching them.
            assertTrue(store.eraseAccount(account.id, 6L))
            val counts = CommerceDatabase.APPEND_ONLY_TABLES.associateWith { table ->
                db.withConnection { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
                    }
                }
            }
            assertEquals(mapOf("invoice" to 1, "credit_note" to 1, "acceptance" to 2), counts)
        }
    }

    @Test
    fun acceptanceKindIsRequired() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).close()
        refused(
            jdbc,
            "INSERT INTO acceptance(account_id, tos_version, privacy_version, accepted_at) VALUES ('a', 'v1', 'v1', 1)",
        )
    }

    @Test
    fun aDatabaseFromThePreResetLadderIsRefusedNotSkipped() = withFreshSchema { jdbc ->
        // What production looked like before the reset: the old ladder recorded v1..v8 and had no
        // baseline marker.
        DriverManager.getConnection(jdbc).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                st.execute("INSERT INTO schema_version(version) VALUES (1),(2),(3),(4),(5),(6),(7),(8)")
            }
        }
        val e = assertFailsWith<IllegalStateException> { CommerceDatabase(jdbc) }
        assertTrue("PRE-RESET" in (e.message ?: "") && "wipe it" in (e.message ?: ""), e.message)
    }

    @Test
    fun aPreResetDatabaseAtVersionOneIsRefusedToo() = withFreshSchema { jdbc ->
        // ⚠ The version number alone cannot tell the ladders apart at 1 — the marker can.
        DriverManager.getConnection(jdbc).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)")
                st.execute("INSERT INTO schema_version(version) VALUES (1)")
            }
        }
        assertFailsWith<IllegalStateException> { CommerceDatabase(jdbc) }
    }

    @Test
    fun theReCutBaselineReopens() = withFreshSchema { jdbc ->
        CommerceDatabase(jdbc).close()
        CommerceDatabase(jdbc).close() // second boot: marker present, nothing to apply, no refusal
        DriverManager.getConnection(jdbc).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT MAX(version) FROM schema_version").use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1))
                }
            }
        }
    }
}
