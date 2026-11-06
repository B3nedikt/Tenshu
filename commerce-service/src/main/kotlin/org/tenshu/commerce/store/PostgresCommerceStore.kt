package org.tenshu.commerce.store

import org.tenshu.commerce.ProductCatalog
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * Durable Postgres [CommerceStore] over [CommerceDatabase] — the backend selected when
 * `DATABASE_URL` is set. Mirrors [InMemoryCommerceStore]'s semantics exactly (that impl is the
 * tested source of truth; see `commerce-accounts-and-sessions.md` §9.2 — the raw SQL here is
 * verified manually / on deploy, not in CI). Raw `java.sql` like the relay's `Sqlite*` stores; no
 * ORM.
 *
 * The two read-modify-write invariants — the owner supersede and (implicitly) the live-owner
 * uniqueness — run inside a [CommerceDatabase.transaction], and the schema's partial unique index
 * `idx_account_owner_live` is the backstop.
 */
class PostgresCommerceStore(private val db: CommerceDatabase) : CommerceStore {

    override fun createAccount(
        emailNormalised: String,
        rail: Rail,
        createdAt: Long,
        displayName: String?,
    ): Account {
        val account = Account(
            id = UUID.randomUUID().toString(),
            emailNormalised = emailNormalised,
            emailVerifiedAt = null,
            createdAt = createdAt,
            status = AccountStatus.Active,
            rail = rail,
            displayName = displayName,
        )
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO account(id, email_normalised, email_verified_at, created_at, status, rail, display_name) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, account.id)
                ps.setString(2, account.emailNormalised)
                ps.setObject(3, null)
                ps.setLong(4, account.createdAt)
                ps.setString(5, account.status.name)
                ps.setString(6, account.rail.name)
                ps.setString(7, account.displayName)
                ps.executeUpdate()
            }
        }
        return account
    }

    override fun findAccountByEmail(emailNormalised: String): Account? =
        queryAccount("SELECT * FROM account WHERE email_normalised = ?", emailNormalised)

    override fun findAccountById(id: String): Account? =
        queryAccount("SELECT * FROM account WHERE id = ?", id)

    override fun findAccountByStripeCustomerId(stripeCustomerId: String): Account? =
        queryAccount("SELECT * FROM account WHERE stripe_customer_id = ?", stripeCustomerId)

    private fun queryAccount(sql: String, key: String): Account? = db.withConnection { conn ->
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toAccount() else null }
        }
    }

    override fun markEmailVerified(accountId: String, verifiedAt: Long): Boolean = db.withConnection { conn ->
        conn.prepareStatement("UPDATE account SET email_verified_at = COALESCE(email_verified_at, ?) WHERE id = ?").use { ps ->
            ps.setLong(1, verifiedAt)
            ps.setString(2, accountId)
            ps.executeUpdate() > 0
        }
    }

    override fun closeAccount(accountId: String, closedAt: Long) {
        db.withConnection { conn ->
            // ⚠ `WHERE … AND closed_at IS NULL` makes the idempotency a *database* invariant
            // rather than a read-then-write the caller could race: re-closing keeps the original
            // stamp, so a repeated call cannot restart the grace clock and extend retention past
            // the published terms.
            conn.prepareStatement(
                "UPDATE account SET status = ?, closed_at = ? WHERE id = ? AND closed_at IS NULL",
            ).use { ps ->
                ps.setString(1, AccountStatus.Closed.name)
                ps.setLong(2, closedAt)
                ps.setString(3, accountId)
                ps.executeUpdate()
            }
        }
    }

    override fun eraseAccount(accountId: String, erasedAt: Long): Boolean = db.transaction { conn ->
        // ⚠ FOR UPDATE: two concurrent confirmations must not both read "not erased" and both
        // proceed; the second blocks here, then sees erased_at and returns false.
        val email = conn.prepareStatement(
            "SELECT email_normalised, erased_at FROM account WHERE id = ? FOR UPDATE",
        ).use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@transaction false
                rs.getLong("erased_at")
                if (!rs.wasNull()) return@transaction false
                rs.getString("email_normalised")
            }
        }
        conn.prepareStatement(
            // ⚠ COALESCE keeps an earlier closure's stamp — erasure must not restart the grace clock.
            "UPDATE account SET status = ?, closed_at = COALESCE(closed_at, ?), erased_at = ?, " +
                "email_normalised = ?, email_verified_at = NULL, display_name = NULL WHERE id = ?",
        ).use { ps ->
            ps.setString(1, AccountStatus.Closed.name)
            ps.setLong(2, erasedAt)
            ps.setLong(3, erasedAt)
            ps.setString(4, ErasedAccounts.tombstoneFor(accountId))
            ps.setString(5, accountId)
            ps.executeUpdate()
        }
        // ⚠ The failed-code log (D15) names the address too; erasure must not leave it behind.
        conn.prepareStatement("DELETE FROM email_verification WHERE email_normalised IN (?, ?, ?)").use { ps ->
            ps.setString(1, email)
            ps.setString(2, ErasedAccounts.deletionOtpKey(email))
            ps.setString(3, ErasedAccounts.otpFailuresKey(email))
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM session WHERE account_id = ?").use { ps ->
            ps.setString(1, accountId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM marketing_consent WHERE account_id = ?").use { ps ->
            ps.setString(1, accountId)
            ps.executeUpdate()
        }
        true
    }

    override fun reopenAccount(accountId: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                // ⚠ `erased_at IS NULL`: an erased account is never reopened (see InMemoryCommerceStore).
                "UPDATE account SET status = ?, closed_at = NULL WHERE id = ? AND erased_at IS NULL",
            ).use { ps ->
                ps.setString(1, AccountStatus.Active.name)
                ps.setString(2, accountId)
                ps.executeUpdate()
            }
        }
    }

    override fun closedAccountsWithBilling(closedSince: Long): List<Account> =
        db.withConnection { conn ->
            // ⚠ No join on the owner binding, unlike `closedAccountsWithOwners`: reclamation needs
            // a keyspace, cancellation needs only a Stripe customer. An unbound account can still
            // be billing, and missing it is exactly the "closed but still charged" case.
            conn.prepareStatement(
                """
                SELECT * FROM account
                WHERE closed_at IS NOT NULL
                  AND closed_at >= ?
                  AND (stripe_customer_id IS NOT NULL OR iap_original_txn_id IS NOT NULL)

                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, closedSince)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toAccount()) }
                }
            }
        }

    override fun closedAccountsWithOwners(): List<ClosedAccount> =
        db.withConnection { conn ->
            // ⚠ INNER JOIN on the live binding: an account with no live owner has no keyspace to
            // reclaim, so it is skipped by construction rather than by a nullable branch.
            // ⚠⚠ NOT EXISTS: a realm still live-bound to an OPEN account (closed_at IS NULL — the
            // same predicate closeAccount/reopenAccount maintain) is never minted for, or an erased
            // account would delete the files of the account that replaced it (CommerceStore doc).
            conn.prepareStatement(
                """
                SELECT a.id, a.closed_at, o.owner_id
                FROM account a
                JOIN account_owner o ON o.account_id = a.id AND o.superseded_at IS NULL
                WHERE a.closed_at IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM account_owner o2
                    JOIN account a2 ON a2.id = o2.account_id
                    WHERE o2.owner_id = o.owner_id
                      AND o2.superseded_at IS NULL
                      AND a2.closed_at IS NULL
                  )
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ClosedAccount(
                                    accountId = rs.getString("id"),
                                    ownerId = rs.getString("owner_id"),
                                    closedAt = rs.getLong("closed_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun setStoreRail(accountId: String, rail: Rail, storeRef: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE account SET rail = ?, iap_original_txn_id = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, rail.name)
                ps.setString(2, storeRef)
                ps.setString(3, accountId)
                ps.executeUpdate()
            }
        }
    }

    override fun attachStripeCustomer(accountId: String, stripeCustomerId: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement(
            "UPDATE account SET stripe_customer_id = ?, rail = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, stripeCustomerId)
            ps.setString(2, Rail.Stripe.name)
            ps.setString(3, accountId)
            ps.executeUpdate() > 0
        }
    }

    override fun bindOwner(accountId: String, ownerId: String, boundAt: Long) {
        db.transaction { conn ->
            conn.prepareStatement(
                "UPDATE account_owner SET superseded_at = ? WHERE account_id = ? AND superseded_at IS NULL",
            ).use { ps ->
                ps.setLong(1, boundAt)
                ps.setString(2, accountId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO account_owner(account_id, owner_id, bound_at, superseded_at) VALUES(?, ?, ?, NULL)",
            ).use { ps ->
                ps.setString(1, accountId)
                ps.setString(2, ownerId)
                ps.setLong(3, boundAt)
                ps.executeUpdate()
            }
        }
    }

    override fun liveOwner(accountId: String): String? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT owner_id FROM account_owner WHERE account_id = ? AND superseded_at IS NULL",
        ).use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override fun accountIdForOwner(ownerId: String): String? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT account_id FROM account_owner WHERE owner_id = ? AND superseded_at IS NULL",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override fun accountIdsForOwner(ownerId: String): List<String> = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT DISTINCT account_id FROM account_owner WHERE owner_id = ? AND superseded_at IS NULL",
        ).use { ps ->
            ps.setString(1, ownerId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    override fun putEmailVerification(emailNormalised: String, codeHash: String, expiresAt: Long) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO email_verification(email_normalised, code_hash, expires_at, attempts) VALUES(?, ?, ?, 0) " +
                    "ON CONFLICT (email_normalised) DO UPDATE SET code_hash = EXCLUDED.code_hash, " +
                    "expires_at = EXCLUDED.expires_at, attempts = 0",
            ).use { ps ->
                ps.setString(1, emailNormalised)
                ps.setString(2, codeHash)
                ps.setLong(3, expiresAt)
                ps.executeUpdate()
            }
        }
    }

    override fun getEmailVerification(emailNormalised: String): EmailVerification? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM email_verification WHERE email_normalised = ?").use { ps ->
            ps.setString(1, emailNormalised)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    EmailVerification(
                        emailNormalised = rs.getString("email_normalised"),
                        codeHash = rs.getString("code_hash"),
                        expiresAt = rs.getLong("expires_at"),
                        attempts = rs.getInt("attempts"),
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun incrementVerificationAttempts(emailNormalised: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE email_verification SET attempts = attempts + 1 WHERE email_normalised = ?",
            ).use { ps ->
                ps.setString(1, emailNormalised)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteEmailVerification(emailNormalised: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM email_verification WHERE email_normalised = ?").use { ps ->
                ps.setString(1, emailNormalised)
                ps.executeUpdate()
            }
        }
    }

    override fun createSession(tokenHash: String, accountId: String, createdAt: Long, expiresAt: Long) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO session(token_hash, account_id, created_at, expires_at) VALUES(?, ?, ?, ?) " +
                    "ON CONFLICT (token_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at",
            ).use { ps ->
                ps.setString(1, tokenHash)
                ps.setString(2, accountId)
                ps.setLong(3, createdAt)
                ps.setLong(4, expiresAt)
                ps.executeUpdate()
            }
        }
    }

    override fun getSession(tokenHash: String): Session? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM session WHERE token_hash = ?").use { ps ->
            ps.setString(1, tokenHash)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    Session(
                        tokenHash = rs.getString("token_hash"),
                        accountId = rs.getString("account_id"),
                        createdAt = rs.getLong("created_at"),
                        expiresAt = rs.getLong("expires_at"),
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun deleteSession(tokenHash: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM session WHERE token_hash = ?").use { ps ->
                ps.setString(1, tokenHash)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteSessionsForAccount(accountId: String): Int = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM session WHERE account_id = ?").use { ps ->
            ps.setString(1, accountId)
            ps.executeUpdate()
        }
    }

    override fun countActiveSessions(accountId: String, nowMs: Long): Int = db.withConnection { conn ->
        // `>=` matches Session.isValid's inclusive expiry, so both backends agree on the boundary.
        conn.prepareStatement(
            "SELECT COUNT(*) FROM session WHERE account_id = ? AND expires_at >= ?",
        ).use { ps ->
            ps.setString(1, accountId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override fun appendEntitlementHistory(entry: EntitlementHistory) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO entitlement_history(account_id, product_id, valid_from, valid_until, source, issued_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, entry.accountId)
                ps.setString(2, entry.productId)
                ps.setLong(3, entry.validFrom)
                ps.setLong(4, entry.validUntil)
                ps.setString(5, entry.source.name)
                ps.setLong(6, entry.issuedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun endEntitlements(accountId: String, source: EntitlementSource, endedAt: Long): Int = db.withConnection { conn ->
        conn.prepareStatement(
            "UPDATE entitlement_history SET valid_until = ? WHERE account_id = ? AND source = ? AND valid_until >= ?",
        ).use { ps ->
            ps.setLong(1, endedAt - 1)
            ps.setString(2, accountId)
            ps.setString(3, source.name)
            ps.setLong(4, endedAt)
            ps.executeUpdate()
        }
    }

    override fun currentEntitlement(accountId: String, nowMs: Long): EntitlementHistory? = db.withConnection { conn ->
        // Fetch all currently-valid rows and apply the shared highest-quota selector, so this agrees
        // with the in-memory store's rule (the tested source of truth) without duplicating the
        // quota ranking in SQL.
        conn.prepareStatement(
            "SELECT * FROM entitlement_history WHERE account_id = ? AND ? BETWEEN valid_from AND valid_until",
        ).use { ps ->
            ps.setString(1, accountId)
            ps.setLong(2, nowMs)
            ps.executeQuery().use { rs ->
                val candidates = buildList {
                    while (rs.next()) {
                        add(
                            EntitlementHistory(
                                accountId = rs.getString("account_id"),
                                productId = rs.getString("product_id"),
                                validFrom = rs.getLong("valid_from"),
                                validUntil = rs.getLong("valid_until"),
                                source = EntitlementSource.valueOf(rs.getString("source")),
                                issuedAt = rs.getLong("issued_at"),
                            ),
                        )
                    }
                }
                pickCurrentEntitlement(candidates)
            }
        }
    }

    override fun putRedemptionCode(code: RedemptionCode) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO redemption_code(code_hash, product_id, max_uses, used_count, expires_at) VALUES(?, ?, ?, ?, ?) " +
                    "ON CONFLICT (code_hash) DO UPDATE SET product_id = EXCLUDED.product_id, " +
                    "max_uses = EXCLUDED.max_uses, expires_at = EXCLUDED.expires_at",
            ).use { ps ->
                ps.setString(1, code.codeHash)
                ps.setString(2, code.productId)
                ps.setInt(3, code.maxUses)
                ps.setInt(4, code.usedCount)
                ps.setObject(5, code.expiresAt)
                ps.executeUpdate()
            }
        }
    }

    override fun getRedemptionCode(codeHash: String): RedemptionCode? = db.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM redemption_code WHERE code_hash = ?").use { ps ->
            ps.setString(1, codeHash)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    RedemptionCode(
                        codeHash = rs.getString("code_hash"),
                        productId = rs.getString("product_id"),
                        maxUses = rs.getInt("max_uses"),
                        usedCount = rs.getInt("used_count"),
                        expiresAt = rs.getLongOrNull("expires_at"),
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun incrementRedemptionUse(codeHash: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE redemption_code SET used_count = used_count + 1 WHERE code_hash = ?").use { ps ->
                ps.setString(1, codeHash)
                ps.executeUpdate()
            }
        }
    }

    override fun recordAcceptance(acceptance: Acceptance): Boolean = db.withConnection { conn ->
        // ⚠⚠ FOR SHARE on the account row: blocks while an expiry sweep holds it FOR UPDATE, then
        // re-reads it — gone ⇒ zero rows, never an orphan the expiry trigger could not delete.
        conn.prepareStatement(
            "INSERT INTO acceptance(account_id, tos_version, privacy_version, accepted_at, ip, kind) " +
                "SELECT id, ?, ?, ?, ?, ? FROM account WHERE id = ? FOR SHARE",
        ).use { ps ->
            ps.setString(1, acceptance.tosVersion)
            ps.setString(2, acceptance.privacyVersion)
            ps.setLong(3, acceptance.acceptedAt)
            ps.setString(4, acceptance.ip)
            ps.setString(5, acceptance.kind.name)
            ps.setString(6, acceptance.accountId)
            ps.executeUpdate() > 0
        }
    }

    override fun setMarketingConsent(consent: MarketingConsent) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO marketing_consent(account_id, granted, changed_at) VALUES(?, ?, ?) " +
                    "ON CONFLICT (account_id) DO UPDATE SET granted = EXCLUDED.granted, changed_at = EXCLUDED.changed_at",
            ).use { ps ->
                ps.setString(1, consent.accountId)
                ps.setBoolean(2, consent.granted)
                ps.setLong(3, consent.changedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun appendInvoice(draft: InvoiceDraft): Invoice = db.transaction { conn ->
        // Sequential number from a DB sequence (statutory numbering, §5.2). Append-only: a trigger
        // refuses UPDATE/DELETE on this table (CommerceDatabase.APPEND_ONLY_TABLES).
        val number = conn.prepareStatement("SELECT nextval('invoice_number_seq')").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        conn.prepareStatement(
            "INSERT INTO invoice(number, account_id, stripe_invoice_id, amount_minor, currency, " +
                "vat_amount_minor, vat_rate_bps, billing_country, ip_country, issued_at, payment_ref) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setLong(1, number)
            ps.setString(2, draft.accountId)
            ps.setString(3, draft.stripeInvoiceId)
            ps.setLong(4, draft.amountMinor)
            ps.setString(5, draft.currency)
            ps.setLong(6, draft.vatAmountMinor)
            ps.setInt(7, draft.vatRateBasisPoints)
            ps.setString(8, draft.billingCountry)
            ps.setString(9, draft.ipCountry)
            ps.setLong(10, draft.issuedAt)
            ps.setString(11, draft.paymentRef)
            ps.executeUpdate()
        }
        Invoice(
            number = number,
            accountId = draft.accountId,
            stripeInvoiceId = draft.stripeInvoiceId,
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            vatAmountMinor = draft.vatAmountMinor,
            vatRateBasisPoints = draft.vatRateBasisPoints,
            billingCountry = draft.billingCountry,
            ipCountry = draft.ipCountry,
            issuedAt = draft.issuedAt,
            paymentRef = draft.paymentRef,
        )
    }

    override fun findInvoiceByStripeId(stripeInvoiceId: String): Invoice? = db.withConnection { conn ->
        // Latest wins if a Stripe id somehow repeats — the newest row is the one a reversal refers to.
        conn.prepareStatement(
            "SELECT * FROM invoice WHERE stripe_invoice_id = ? ORDER BY number DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, stripeInvoiceId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toInvoice() else null }
        }
    }

    override fun findInvoiceByPaymentRef(paymentRef: String): Invoice? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT * FROM invoice WHERE payment_ref = ? ORDER BY number DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, paymentRef)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toInvoice() else null }
        }
    }

    override fun appendCreditNote(draft: CreditNoteDraft): CreditNote? = db.transaction { conn ->
        // ⚠ The existence check rides INSIDE the transaction and BEFORE `nextval`, so a duplicate
        // refund does not burn a credit-note number. Statutory numbering wants as few gaps as it
        // can get, and the duplicate case is the common one (Stripe retries generously).
        val alreadyRecorded = conn.prepareStatement(
            "SELECT 1 FROM credit_note WHERE stripe_reference_id = ?",
        ).use { ps ->
            ps.setString(1, draft.stripeReferenceId)
            ps.executeQuery().use { rs -> rs.next() }
        }
        if (alreadyRecorded) return@transaction null

        val number = conn.prepareStatement("SELECT nextval('credit_note_number_seq')").use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        conn.prepareStatement(
            "INSERT INTO credit_note(number, account_id, kind, stripe_reference_id, " +
                "references_invoice_number, amount_minor, currency, vat_amount_minor, vat_rate_bps, " +
                "billing_country, issued_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                // ⚠ The UNIQUE index is the real guard: two concurrent deliveries can both pass the
                // check above. Losing that race must be a no-op, not a 500 that makes Stripe retry.
                "ON CONFLICT (stripe_reference_id) DO NOTHING",
        ).use { ps ->
            ps.setLong(1, number)
            ps.setString(2, draft.accountId)
            ps.setString(3, draft.kind.name)
            ps.setString(4, draft.stripeReferenceId)
            draft.referencesInvoiceNumber?.let { ps.setLong(5, it) } ?: ps.setNull(5, Types.BIGINT)
            ps.setLong(6, draft.amountMinor)
            ps.setString(7, draft.currency)
            draft.vatAmountMinor?.let { ps.setLong(8, it) } ?: ps.setNull(8, Types.BIGINT)
            draft.vatRateBasisPoints?.let { ps.setInt(9, it) } ?: ps.setNull(9, Types.INTEGER)
            ps.setString(10, draft.billingCountry)
            ps.setLong(11, draft.issuedAt)
            if (ps.executeUpdate() == 0) return@transaction null // lost the race; already credited
        }
        CreditNote(
            number = number,
            accountId = draft.accountId,
            kind = draft.kind,
            stripeReferenceId = draft.stripeReferenceId,
            referencesInvoiceNumber = draft.referencesInvoiceNumber,
            amountMinor = draft.amountMinor,
            currency = draft.currency,
            vatAmountMinor = draft.vatAmountMinor,
            vatRateBasisPoints = draft.vatRateBasisPoints,
            billingCountry = draft.billingCountry,
            issuedAt = draft.issuedAt,
        )
    }

    override fun listInvoices(fromInclusive: Long, toExclusive: Long): List<Invoice> =
        db.withConnection { conn ->
            // ⚠ `>= ? AND < ?` — half-open, so consecutive filing periods tile without
            // double-counting a boundary row. Ordered by the statutory sequence, not by time.
            conn.prepareStatement(
                "SELECT * FROM invoice WHERE issued_at >= ? AND issued_at < ? ORDER BY number ASC",
            ).use { ps ->
                ps.setLong(1, fromInclusive)
                ps.setLong(2, toExclusive)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toInvoice()) } }
            }
        }

    override fun listCreditNotes(fromInclusive: Long, toExclusive: Long): List<CreditNote> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM credit_note WHERE issued_at >= ? AND issued_at < ? ORDER BY number ASC",
            ).use { ps ->
                ps.setLong(1, fromInclusive)
                ps.setLong(2, toExclusive)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toCreditNote()) } }
            }
        }

    override fun listEntitlementHistory(accountId: String): List<EntitlementHistory> =
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM entitlement_history WHERE account_id = ? ORDER BY issued_at ASC",
            ).use { ps ->
                ps.setString(1, accountId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                EntitlementHistory(
                                    accountId = rs.getString("account_id"),
                                    productId = rs.getString("product_id"),
                                    validFrom = rs.getLong("valid_from"),
                                    validUntil = rs.getLong("valid_until"),
                                    source = EntitlementSource.valueOf(rs.getString("source")),
                                    issuedAt = rs.getLong("issued_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun ResultSet.toCreditNote(): CreditNote = CreditNote(
        number = getLong("number"),
        accountId = getString("account_id"),
        kind = CreditNoteKind.valueOf(getString("kind")),
        stripeReferenceId = getString("stripe_reference_id"),
        referencesInvoiceNumber = getLongOrNull("references_invoice_number"),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency"),
        // ⚠ Nullable on the way out as well as in: null means "could not be apportioned", and
        // reading it as 0 here would re-introduce exactly the false claim the column exists to avoid.
        vatAmountMinor = getLongOrNull("vat_amount_minor"),
        vatRateBasisPoints = getIntOrNull("vat_rate_bps"),
        billingCountry = getString("billing_country"),
        issuedAt = getLong("issued_at"),
    )

    private fun ResultSet.toInvoice(): Invoice = Invoice(
        number = getLong("number"),
        accountId = getString("account_id"),
        stripeInvoiceId = getString("stripe_invoice_id"),
        amountMinor = getLong("amount_minor"),
        currency = getString("currency"),
        vatAmountMinor = getLong("vat_amount_minor"),
        vatRateBasisPoints = getInt("vat_rate_bps"),
        billingCountry = getString("billing_country"),
        ipCountry = getString("ip_country"),
        issuedAt = getLong("issued_at"),
        paymentRef = getString("payment_ref"),
    )

    override fun stripeEventSeen(eventId: String): Boolean = db.withConnection { conn ->
        conn.prepareStatement("SELECT 1 FROM stripe_event WHERE event_id = ?").use { ps ->
            ps.setString(1, eventId)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    override fun recordStripeEvent(eventId: String, receivedAt: Long) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO stripe_event(event_id, received_at) VALUES(?, ?) ON CONFLICT (event_id) DO NOTHING",
            ).use { ps ->
                ps.setString(1, eventId)
                ps.setLong(2, receivedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun listAccountsForAdmin(query: String?, nowMs: Long): List<AdminAccountRow> = db.withConnection { conn ->
        // account ⨯ live owner ⨯ current entitlement (lateral, latest valid). Read-only (§4.5).
        val base =
            "SELECT a.email_normalised, a.email_verified_at, a.created_at, a.status, a.rail, " +
                "o.owner_id AS live_owner, e.product_id, e.valid_until, e.source " +
                "FROM account a " +
                "LEFT JOIN account_owner o ON o.account_id = a.id AND o.superseded_at IS NULL " +
                "LEFT JOIN LATERAL (" +
                "  SELECT product_id, valid_until, source FROM entitlement_history " +
                "  WHERE account_id = a.id AND ? BETWEEN valid_from AND valid_until " +
                // Highest-quota-then-latest, matching currentEntitlement / pickCurrentEntitlement so
                // the displayed tier agrees with what /mint would issue (§4.3).
                "  ORDER BY (CASE product_id " +
                "    WHEN '${ProductCatalog.PREMIUM}' THEN 3 " +
                "    WHEN '${ProductCatalog.BASIC}' THEN 2 " +
                "    WHEN '${ProductCatalog.FREE}' THEN 1 ELSE 0 END) DESC, valid_until DESC LIMIT 1" +
                ") e ON true "
        val needle = query?.trim()?.takeIf { it.isNotEmpty() }
        val sql = if (needle == null) {
            base + "ORDER BY a.created_at"
        } else {
            base + "WHERE LOWER(a.email_normalised) LIKE ? OR LOWER(o.owner_id) LIKE ? ORDER BY a.created_at"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, nowMs)
            if (needle != null) {
                val like = "%" + needle.lowercase() + "%"
                ps.setString(2, like)
                ps.setString(3, like)
            }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AdminAccountRow(
                                email = rs.getString("email_normalised"),
                                emailVerifiedAt = rs.getLongOrNull("email_verified_at"),
                                createdAt = rs.getLong("created_at"),
                                status = AccountStatus.valueOf(rs.getString("status")),
                                rail = Rail.valueOf(rs.getString("rail")),
                                currentProductId = rs.getString("product_id"),
                                currentValidUntil = rs.getLongOrNull("valid_until"),
                                currentSource = rs.getString("source")?.let { EntitlementSource.valueOf(it) },
                                liveOwnerId = rs.getString("live_owner"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun ResultSet.toAccount(): Account = Account(
        id = getString("id"),
        emailNormalised = getString("email_normalised"),
        emailVerifiedAt = getLongOrNull("email_verified_at"),
        createdAt = getLong("created_at"),
        status = AccountStatus.valueOf(getString("status")),
        closedAt = getLongOrNull("closed_at"),
        erasedAt = getLongOrNull("erased_at"),
        rail = Rail.valueOf(getString("rail")),
        stripeCustomerId = getString("stripe_customer_id"),
        iapOriginalTxnId = getString("iap_original_txn_id"),
        displayName = getString("display_name"),
    )

    private fun ResultSet.getLongOrNull(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.getIntOrNull(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    /**
     * `SELECT 1` over a pooled connection. Borrowing is the load-bearing half: Hikari validates a
     * connection on **borrow**, so this is what discovers — and evicts — a socket left dead by a
     * database restart. Reading the pool's own stats does not.
     */
    override fun probe() {
        db.withConnection { conn ->
            conn.prepareStatement("SELECT 1").use { ps -> ps.executeQuery().use { it.next() } }
        }
    }

    override fun countVerifiedOpenAccounts(verifiedSinceMs: Long): Int = db.transaction { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM account " +
                "WHERE email_verified_at IS NOT NULL AND email_verified_at >= ? " +
                "AND closed_at IS NULL AND erased_at IS NULL",
        ).use { ps ->
            ps.setLong(1, verifiedSinceMs)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override fun expireUnverifiedAccounts(olderThanMs: Long): Int = db.transaction { conn ->
        // ⚠⚠ The one sanctioned DELETE on `acceptance` (D10): the expiry guard refuses it
        // unless this transaction-local flag is on, and then only row by row for a `Registration`
        // row of an account that is still unverified, open and never erased. Transaction-scoped
        // (`is_local = true`), so it never leaks to the next borrower of this pooled connection.
        conn.prepareStatement("SELECT set_config('${CommerceDatabase.EXPIRE_UNVERIFIED_FLAG}', 'on', true)").use { it.execute() }
        // ⚠ FOR UPDATE: a concurrent `markEmailVerified` either committed first — and the row,
        // re-checked against its new version, no longer matches — or blocks until we commit.
        // ⚠ Every guard below is a reason the row is NOT a mere code request. Keep them in step
        // with InMemoryCommerceStore.expireUnverifiedAccounts.
        val accounts = conn.prepareStatement(
            """
            SELECT a.id, a.email_normalised FROM account a
            WHERE a.email_verified_at IS NULL
              AND a.created_at < ?
              AND a.closed_at IS NULL
              AND a.erased_at IS NULL
              AND a.rail = ?
              AND a.stripe_customer_id IS NULL
              AND a.iap_original_txn_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM session s WHERE s.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM entitlement_history e WHERE e.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM account_owner o WHERE o.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM invoice i WHERE i.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM credit_note c WHERE c.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM marketing_consent m WHERE m.account_id = a.id)
              AND NOT EXISTS (
                  SELECT 1 FROM acceptance x
                  WHERE x.account_id = a.id AND (x.kind <> ? OR x.accepted_at >= ?)
              )
            FOR UPDATE OF a
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, olderThanMs)
            ps.setString(2, Rail.Free.name)
            ps.setString(3, AcceptanceKind.Registration.name)
            ps.setLong(4, olderThanMs)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }
        if (accounts.isEmpty()) return@transaction 0

        val ids = conn.createArrayOf("text", accounts.map { it.first }.toTypedArray())
        val emailKeys = conn.createArrayOf(
            "text",
            accounts.flatMap { (_, email) ->
                listOf(email, ErasedAccounts.deletionOtpKey(email), ErasedAccounts.otpFailuresKey(email))
            }.toTypedArray(),
        )
        // ⚠ Acceptance BEFORE account: the row guard checks the account is still there and unverified.
        conn.prepareStatement("DELETE FROM acceptance WHERE account_id = ANY(?)").use { ps ->
            ps.setArray(1, ids)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM email_verification WHERE email_normalised = ANY(?)").use { ps ->
            ps.setArray(1, emailKeys)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM account WHERE id = ANY(?)").use { ps ->
            ps.setArray(1, ids)
            ps.executeUpdate()
        }
    }
}
