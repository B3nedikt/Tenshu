package org.tenshu.commerce.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * Thin JDBC wrapper for the commerce plane's durable Postgres backend
 * (`commerce-accounts-and-sessions.md` §4). Mirrors the relay's `RelayDatabase` shape — a
 * hand-rolled, append-only, versioned migration runner (`schema_version` + per-version
 * transactions, no Flyway) — but over a **HikariCP connection pool** rather than the relay's
 * single locked SQLite connection: Postgres is multi-connection, and concurrent server access is
 * the whole reason the design chose it over SQLite (§4).
 *
 * **A28 isolation (House Rule #1):** this Postgres instance lives on the commerce trust boundary,
 * never the relay's. It holds real PII (email); at-rest encryption + access control are deployment
 * concerns (§5).
 *
 * Timestamps are `BIGINT` epoch-millis (matching the in-memory store + the relay), not
 * `TIMESTAMPTZ`, so the two backends agree byte-for-byte and there's no TZ ambiguity.
 */
class CommerceDatabase(
    private val dataSource: DataSource,
) : AutoCloseable {

    constructor(jdbcUrl: String) : this(
        HikariDataSource(HikariConfig().apply { this.jdbcUrl = jdbcUrl }),
    )

    init {
        migrate()
    }

    /** Borrow a pooled connection for [block]. */
    fun <T> withConnection(block: (Connection) -> T): T = dataSource.connection.use { block(it) }

    /** Run [block] in a transaction (auto-rollback on throw). */
    fun <T> transaction(block: (Connection) -> T): T = withConnection { conn ->
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    /**
     * ⚠ Serialised across processes by a session-level advisory lock: two replicas booting against an
     * empty database would otherwise both read version 0 and race the baseline's `CREATE TABLE`s (the
     * loser fails on the catalog). The second waits, then reads the version the first committed.
     */
    private fun migrate() = withConnection { conn ->
        conn.createStatement().use { it.execute("SELECT pg_advisory_lock($MIGRATION_LOCK_KEY)") }
        try {
            migrateLocked(conn)
        } finally {
            conn.createStatement().use { it.execute("SELECT pg_advisory_unlock($MIGRATION_LOCK_KEY)") }
        }
    }

    private fun migrateLocked(conn: Connection) {
        conn.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)") }
        val current = conn.createStatement().use { st ->
            st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        refusePreResetLadder(conn, current)
        for (migration in MIGRATIONS.filter { it.version > current }) {
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.createStatement().use { st -> migration.statements.forEach { st.execute(it) } }
                conn.prepareStatement("INSERT INTO schema_version(version) VALUES(?)").use { ps ->
                    ps.setInt(1, migration.version)
                    ps.executeUpdate()
                }
                conn.commit()
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    /**
     * ⚠⚠ A database migrated by an **earlier chain** (the pre-squash v1–v8, or the `2026-10-reset`
     * cut's v1–v2) must not be "upgraded" by this one: its recorded version is at or above the
     * baseline's, so the loop above would silently skip
     * every migration and run against a schema this build never created (nullable `acceptance.kind`,
     * no append-only triggers). Such a database is recognised by having a version but no
     * current [BASELINE_MARKER] row — only the latest baseline writes it — and refused, loudly.
     */
    private fun refusePreResetLadder(conn: Connection, current: Int) {
        if (current == 0) return
        // Two queries, not one: Postgres resolves relation names at parse time, so any SELECT
        // naming schema_baseline fails with "relation does not exist" on a pre-squash database —
        // the wrong message, where the one below says what to do.
        val hasMarkerTable = conn.createStatement().use { st ->
            st.executeQuery("SELECT to_regclass('schema_baseline') IS NOT NULL").use { rs -> rs.next() && rs.getBoolean(1) }
        }
        val cut = if (!hasMarkerTable) {
            null
        } else {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT cut FROM schema_baseline").use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        check(cut == BASELINE_MARKER) {
            "Commerce database is at schema_version $current from a PRE-RESET migration ladder or an " +
                "earlier cut of the baseline (marker '${cut ?: "none"}', expected '$BASELINE_MARKER'). " +
                "The 2026-11 re-cut squashed every earlier step into a new " +
                "baseline v1 and does not migrate old databases: wipe it and start empty " +
                "(local dev: drop and recreate the database TENSHU_LOCAL_COMMERCE_DB names — " +
                "scripts/local-commerce-stack.sh --reset wipes the relay's local state, not your Postgres)."
        }
    }

    private data class Migration(val version: Int, val statements: List<String>)

    internal companion object {
        /**
         * Written by the current version-1 baseline; its absence on a versioned database means an
         * older chain. A `2026-10-reset` database at version 2 (the unverified-account expiry
         * triggers as a separate step) is refused rather than read as current. ⚠ Change it whenever
         * version 1 is edited in place.
         */
        const val BASELINE_MARKER = "2026-11-reset"

        /** Arbitrary, fixed `pg_advisory_lock` key for [migrate]; only this runner takes it. */
        private const val MIGRATION_LOCK_KEY = 0x7465_6E73_6875_4D31L

        /**
         * The statutory tables (§5.2 / D10): append-only, ~10-year retention, survive erasure.
         * Enforced by [APPEND_ONLY_FUNCTION] triggers in the baseline.
         */
        val APPEND_ONLY_TABLES = listOf("invoice", "credit_note", "acceptance")

        // ⚠⚠ Append-only, and the D10 rule is ENFORCED HERE, not by a deploy step. A
        // `BEFORE UPDATE OR DELETE OR TRUNCATE … FOR EACH STATEMENT` trigger refuses any attempt —
        // even one matching zero rows — on every APPEND_ONLY_TABLES table. A trigger and not a
        // REVOKE because the app connects as the database OWNER (`POSTGRES_USER=commerce` in both
        // cloud-inits), and an owner or superuser is not bound by privileges; triggers bind both.
        // ⚠ It is a guard against application bugs and stray hand edits, not against the owner: an
        // owner can `ALTER TABLE … DISABLE TRIGGER` or set `session_replication_role = replica`.
        // ⚠⚠ `INSERT … ON CONFLICT DO UPDATE` is refused too, even with no conflict: the statement
        // fires the statement-level UPDATE trigger. An idempotent write uses `ON CONFLICT DO NOTHING`.
        // ⚠ Consequence: erasure (§9.7) cannot touch these rows — `acceptance.ip` survives erasure
        // as evidence (Art. 6(1)(c)/(f)). Changing that, or a future ~10-year retention purge, needs a
        // migration that drops the trigger on purpose, not a quiet UPDATE/DELETE. The baseline already
        // narrows `acceptance` DELETE to never-verified accounts' Registration rows (below).
        private val APPEND_ONLY_FUNCTION = """
            CREATE OR REPLACE FUNCTION refuse_statutory_mutation() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION '% on % refused: statutory table is append-only (D10, commerce-accounts-and-sessions.md §5.2)',
                    TG_OP, TG_TABLE_NAME
                    USING ERRCODE = 'insufficient_privilege';
            END
            ${'$'}${'$'}
        """

        /**
         * The transaction-local flag ([EXPIRE_UNVERIFIED_FUNCTION]) that lets
         * `PostgresCommerceStore.expireUnverifiedAccounts` delete the request-time acceptance rows of
         * a never-verified account — the one sanctioned exception to D10 on `acceptance`.
         */
        const val EXPIRE_UNVERIFIED_FLAG = "tenshu.expire_unverified"

        // ⚠⚠ The baseline narrows D10 on `acceptance` by exactly one case (a counsel decision): the
        // `Registration` rows of an account that was never verified — a code was requested, never
        // entered — may be deleted with the account. No contract was concluded, so there is nothing
        // to prove, and keeping a stranger's IP for ten years is the opposite of minimisation.
        // Two layers, both required:
        //   1. statement level — a DELETE is refused, even matching zero rows, unless the
        //      transaction set EXPIRE_UNVERIFIED_FLAG (`set_config(…, 'on', true)`);
        //   2. row level — every deleted row must be `kind = 'Registration'` and belong to an
        //      account that still exists, is unverified, not closed and not erased. The account row
        //      must therefore be deleted AFTER its acceptance rows.
        // UPDATE and TRUNCATE stay refused unconditionally, and invoice / credit_note are untouched.
        private val EXPIRE_UNVERIFIED_FUNCTION = """
            CREATE OR REPLACE FUNCTION refuse_acceptance_delete_unless_expiring() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF current_setting('$EXPIRE_UNVERIFIED_FLAG', true) IS DISTINCT FROM 'on' THEN
                    RAISE EXCEPTION '% on % refused: statutory table is append-only (D10, commerce-accounts-and-sessions.md §5.2)',
                        TG_OP, TG_TABLE_NAME
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                RETURN NULL;
            END
            ${'$'}${'$'}
        """

        private val EXPIRE_UNVERIFIED_ROW_FUNCTION = """
            CREATE OR REPLACE FUNCTION refuse_acceptance_row_delete_unless_unverified() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                IF OLD.kind IS DISTINCT FROM 'Registration' OR NOT EXISTS (
                    SELECT 1 FROM account a
                    WHERE a.id = OLD.account_id
                      AND a.email_verified_at IS NULL
                      AND a.closed_at IS NULL
                      AND a.erased_at IS NULL
                ) THEN
                    RAISE EXCEPTION 'DELETE on acceptance refused: statutory table is append-only (D10) except the Registration rows of a never-verified account'
                        USING ERRCODE = 'insufficient_privilege';
                END IF;
                RETURN OLD;
            END
            ${'$'}${'$'}
        """

        // ⚠⚠ Append-only list. NEVER edit a shipped migration — add a new version.
        //
        // Version 1 is a squashed baseline that creates the final schema directly
        // (`core-schema-evolution.md` Appendix A.6); the earlier ladder is in git history. Databases
        // from an earlier chain are NOT migrated — they are wiped (`refusePreResetLadder` refuses to
        // start on one). ⚠ `deploy/reset-test-data.sh` derives the expected version as the max
        // `version = N,` in this file — keep that literal shape. Version 1 is shipped; append v2+.
        private val MIGRATIONS = listOf(
            Migration(
                version = 1,
                statements = listOf(
                    "CREATE TABLE IF NOT EXISTS schema_baseline (cut TEXT NOT NULL)",
                    "INSERT INTO schema_baseline(cut) SELECT '$BASELINE_MARKER' " +
                        "WHERE NOT EXISTS (SELECT 1 FROM schema_baseline)",
                    // ⚠ closed_at / erased_at are NULLABLE and the null carries meaning: Active means
                    // closed_at is absent, never-erased means erased_at is absent. A default of 0
                    // would read as "closed at the epoch" — instantly past any grace window.
                    """
                    CREATE TABLE account (
                        id                  TEXT PRIMARY KEY,
                        email_normalised    TEXT NOT NULL UNIQUE,
                        email_verified_at   BIGINT,
                        created_at          BIGINT NOT NULL,
                        status              TEXT NOT NULL,
                        rail                TEXT NOT NULL,
                        stripe_customer_id  TEXT,
                        iap_original_txn_id TEXT,
                        display_name        TEXT,
                        closed_at           BIGINT,
                        erased_at           BIGINT
                    )
                    """.trimIndent(),
                    "CREATE INDEX idx_account_closed ON account(status) WHERE closed_at IS NOT NULL",
                    """
                    CREATE TABLE account_owner (
                        account_id    TEXT NOT NULL,
                        owner_id      TEXT NOT NULL,
                        bound_at      BIGINT NOT NULL,
                        superseded_at BIGINT
                    )
                    """.trimIndent(),
                    // At most one live owner per account (§3.4).
                    "CREATE UNIQUE INDEX idx_account_owner_live ON account_owner(account_id) WHERE superseded_at IS NULL",
                    "CREATE INDEX idx_account_owner_owner ON account_owner(owner_id) WHERE superseded_at IS NULL",
                    """
                    CREATE TABLE entitlement_history (
                        account_id  TEXT NOT NULL,
                        product_id  TEXT NOT NULL,
                        valid_from  BIGINT NOT NULL,
                        valid_until BIGINT NOT NULL,
                        source      TEXT NOT NULL,
                        issued_at   BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX idx_entitlement_history_account ON entitlement_history(account_id)",
                    """
                    CREATE TABLE email_verification (
                        email_normalised TEXT PRIMARY KEY,
                        code_hash        TEXT NOT NULL,
                        expires_at       BIGINT NOT NULL,
                        attempts         INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE redemption_code (
                        code_hash  TEXT PRIMARY KEY,
                        product_id TEXT NOT NULL,
                        max_uses   INTEGER NOT NULL,
                        used_count INTEGER NOT NULL DEFAULT 0,
                        expires_at BIGINT
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE session (
                        token_hash TEXT PRIMARY KEY,
                        account_id TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX idx_session_account ON session(account_id)",
                    // `kind` is an AcceptanceKind name: a registration acceptance vs the
                    // purchase-time withdrawal waiver (policy §5.4). `privacy_version` is nullable
                    // because the waiver accepts no privacy notice.
                    """
                    CREATE TABLE acceptance (
                        account_id      TEXT NOT NULL,
                        tos_version     TEXT NOT NULL,
                        privacy_version TEXT,
                        accepted_at     BIGINT NOT NULL,
                        ip              TEXT,
                        kind            TEXT NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX idx_acceptance_account ON acceptance(account_id)",
                    """
                    CREATE TABLE marketing_consent (
                        account_id TEXT PRIMARY KEY,
                        granted    BOOLEAN NOT NULL,
                        changed_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    // Statutory invoice ledger (§5.2): sequential numbering via a sequence.
                    // `payment_ref` (WC-6) is the charge/PaymentIntent a dispute names — nullable,
                    // because an invoice settled without one has none.
                    "CREATE SEQUENCE invoice_number_seq START 1",
                    """
                    CREATE TABLE invoice (
                        number            BIGINT PRIMARY KEY,
                        account_id        TEXT NOT NULL,
                        stripe_invoice_id TEXT,
                        amount_minor      BIGINT NOT NULL,
                        currency          TEXT NOT NULL,
                        vat_amount_minor  BIGINT NOT NULL,
                        vat_rate_bps      INTEGER NOT NULL,
                        billing_country   TEXT,
                        ip_country        TEXT,
                        issued_at         BIGINT NOT NULL,
                        payment_ref       TEXT
                    )
                    """.trimIndent(),
                    "CREATE INDEX idx_invoice_account ON invoice(account_id)",
                    // The invoice lookup a credit note does to apportion VAT at the original rate.
                    "CREATE INDEX idx_invoice_stripe_id ON invoice(stripe_invoice_id)",
                    "CREATE INDEX idx_invoice_payment_ref ON invoice(payment_ref)",
                    // WC-1 (§4.7) — the money-out half. A refund is a NEW ROW here, never an update
                    // to `invoice`. ⚠ Its own sequence: statutory numbering is per document type.
                    // ⚠⚠ `stripe_reference_id` UNIQUE is the idempotency key (finer than the event
                    // id). ⚠ `vat_*` NULLABLE on purpose: null = "could not be apportioned", which a
                    // 0 would misreport as "no VAT involved".
                    "CREATE SEQUENCE credit_note_number_seq START 1",
                    """
                    CREATE TABLE credit_note (
                        number                    BIGINT PRIMARY KEY,
                        account_id                TEXT NOT NULL,
                        kind                      TEXT NOT NULL,
                        stripe_reference_id       TEXT NOT NULL UNIQUE,
                        references_invoice_number BIGINT,
                        amount_minor              BIGINT NOT NULL,
                        currency                  TEXT NOT NULL,
                        vat_amount_minor          BIGINT,
                        vat_rate_bps              INTEGER,
                        billing_country           TEXT,
                        issued_at                 BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    "CREATE INDEX idx_credit_note_account ON credit_note(account_id)",
                    "CREATE INDEX idx_credit_note_issued ON credit_note(issued_at)",
                    // The webhook idempotency ledger.
                    """
                    CREATE TABLE stripe_event (
                        event_id    TEXT PRIMARY KEY,
                        received_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    APPEND_ONLY_FUNCTION.trimIndent(),
                    EXPIRE_UNVERIFIED_FUNCTION.trimIndent(),
                    EXPIRE_UNVERIFIED_ROW_FUNCTION.trimIndent(),
                ) + APPEND_ONLY_TABLES.map { table ->
                    // `acceptance` refuses DELETE through its own two triggers below, not this one.
                    val ops = if (table == "acceptance") "UPDATE OR TRUNCATE" else "UPDATE OR DELETE OR TRUNCATE"
                    "CREATE TRIGGER ${table}_append_only BEFORE $ops ON $table " +
                        "FOR EACH STATEMENT EXECUTE FUNCTION refuse_statutory_mutation()"
                } + listOf(
                    // Unverified-account expiry: see EXPIRE_UNVERIFIED_FUNCTION.
                    "CREATE TRIGGER acceptance_delete_flag BEFORE DELETE ON acceptance " +
                        "FOR EACH STATEMENT EXECUTE FUNCTION refuse_acceptance_delete_unless_expiring()",
                    "CREATE TRIGGER acceptance_delete_row BEFORE DELETE ON acceptance " +
                        "FOR EACH ROW EXECUTE FUNCTION refuse_acceptance_row_delete_unless_unverified()",
                ),
            ),
        )
    }
}
