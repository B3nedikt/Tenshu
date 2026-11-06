package org.tenshu.commerce.store

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory [CommerceStore] — the default backend (used when `DATABASE_URL` is unset) and the
 * behavioural source of truth the route/store tests pin. `ConcurrentHashMap` for the simple
 * key→row maps; owner-binding mutations (read-modify-write across the binding list) are guarded by
 * [ownerLock] so the supersede is atomic.
 */
class InMemoryCommerceStore : CommerceStore {

    private val accountsById = ConcurrentHashMap<String, Account>()
    private val accountIdByEmail = ConcurrentHashMap<String, String>()
    private val owners = CopyOnWriteArrayList<AccountOwner>()
    private val ownerLock = Any()
    private val verifications = ConcurrentHashMap<String, EmailVerification>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val entitlements = CopyOnWriteArrayList<EntitlementHistory>()
    private val redemptionCodes = ConcurrentHashMap<String, RedemptionCode>()
    private val acceptances = CopyOnWriteArrayList<Acceptance>()
    private val marketingConsents = ConcurrentHashMap<String, MarketingConsent>()
    private val invoices = CopyOnWriteArrayList<Invoice>()
    private val invoiceCounter = AtomicLong(0)
    private val creditNotes = CopyOnWriteArrayList<CreditNote>()
    private val creditNoteCounter = AtomicLong(0)
    private val creditNoteLock = Any()
    private val stripeEvents = ConcurrentHashMap.newKeySet<String>()

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
        accountsById[account.id] = account
        accountIdByEmail[emailNormalised] = account.id
        return account
    }

    override fun closeAccount(accountId: String, closedAt: Long) {
        val existing = accountsById[accountId] ?: return
        // ⚠ Idempotent, keeping the ORIGINAL closedAt: re-closing must not restart the grace
        // clock, or a repeated call would quietly extend retention past the published terms.
        if (existing.status == AccountStatus.Closed) return
        accountsById[accountId] = existing.copy(status = AccountStatus.Closed, closedAt = closedAt)
    }

    override fun reopenAccount(accountId: String) {
        val existing = accountsById[accountId] ?: return
        if (existing.status == AccountStatus.Active) return
        // ⚠ An erased account is never reopened: it names nobody, and reopening would stop the
        // content deletion its owner asked for.
        if (existing.erasedAt != null) return
        accountsById[accountId] = existing.copy(status = AccountStatus.Active, closedAt = null)
    }

    private val eraseLock = Any()

    // ⚠ Synchronised: the check-then-set below must not let two concurrent confirmations both
    // see "not erased" (Postgres gets the same from `SELECT … FOR UPDATE`).
    override fun eraseAccount(accountId: String, erasedAt: Long): Boolean = synchronized(eraseLock) {
        val existing = accountsById[accountId] ?: return false
        if (existing.erasedAt != null) return false
        val email = existing.emailNormalised
        accountsById[accountId] = existing.copy(
            status = AccountStatus.Closed,
            // ⚠ An earlier closure keeps its stamp — erasure must not restart the grace clock.
            closedAt = existing.closedAt ?: erasedAt,
            erasedAt = erasedAt,
            emailNormalised = ErasedAccounts.tombstoneFor(accountId),
            emailVerifiedAt = null,
            displayName = null,
        )
        accountIdByEmail.remove(email, accountId)
        verifications.remove(email)
        verifications.remove(ErasedAccounts.deletionOtpKey(email))
        // ⚠ The failed-code log names the address too; erasure must not leave it behind (D15).
        verifications.remove(ErasedAccounts.otpFailuresKey(email))
        sessions.values.removeIf { it.accountId == accountId }
        marketingConsents.remove(accountId)
        return true
    }

    override fun closedAccountsWithOwners(): List<ClosedAccount> =
        accountsById.values
            .filter { it.status == AccountStatus.Closed && it.closedAt != null }
            .mapNotNull { account ->
                // ⚠ No live owner ⇒ no keyspace to reclaim. Skipped, never guessed.
                liveOwner(account.id)?.let { owner ->
                    ClosedAccount(accountId = account.id, ownerId = owner, closedAt = account.closedAt!!)
                }
            }
            // ⚠⚠ A realm still live-bound to an open account is not reclaimable (see the interface).
            .filterNot { closed -> ownerHeldByAnOpenAccount(closed.ownerId) }

    private fun ownerHeldByAnOpenAccount(ownerId: String): Boolean =
        accountIdsForOwner(ownerId).any { accountsById[it]?.status != AccountStatus.Closed }

    override fun closedAccountsWithBilling(closedSince: Long): List<Account> =
        accountsById.values.filter {
            it.status == AccountStatus.Closed &&
                it.closedAt != null &&
                it.closedAt >= closedSince &&
                (!it.stripeCustomerId.isNullOrBlank() || !it.iapOriginalTxnId.isNullOrBlank())
        }

    override fun countVerifiedOpenAccounts(verifiedSinceMs: Long): Int =
        accountsById.values.count { a ->
            val verifiedAt = a.emailVerifiedAt
            verifiedAt != null && verifiedAt >= verifiedSinceMs && a.closedAt == null && a.erasedAt == null
        }

    override fun expireUnverifiedAccounts(olderThanMs: Long): Int = synchronized(eraseLock) {
        // ⚠ Same guards as PostgresCommerceStore.expireUnverifiedAccounts — keep them in step.
        val eligible = accountsById.values.filter { account ->
            val id = account.id
            account.emailVerifiedAt == null &&
                account.createdAt < olderThanMs &&
                account.closedAt == null &&
                account.erasedAt == null &&
                account.rail == Rail.Free &&
                account.stripeCustomerId == null &&
                account.iapOriginalTxnId == null &&
                sessions.values.none { it.accountId == id } &&
                entitlements.none { it.accountId == id } &&
                owners.none { it.accountId == id } &&
                invoices.none { it.accountId == id } &&
                creditNotes.none { it.accountId == id } &&
                !marketingConsents.containsKey(id) &&
                acceptances.none {
                    it.accountId == id && (it.kind != AcceptanceKind.Registration || it.acceptedAt >= olderThanMs)
                }
        }
        for (account in eligible) {
            val email = account.emailNormalised
            accountsById.remove(account.id)
            accountIdByEmail.remove(email, account.id)
            verifications.remove(email)
            verifications.remove(ErasedAccounts.deletionOtpKey(email))
            verifications.remove(ErasedAccounts.otpFailuresKey(email))
        }
        val ids = eligible.map { it.id }.toSet()
        acceptances.removeIf { it.accountId in ids }
        return eligible.size
    }

    override fun findAccountByEmail(emailNormalised: String): Account? =
        accountIdByEmail[emailNormalised]?.let { accountsById[it] }

    override fun findAccountById(id: String): Account? = accountsById[id]

    override fun findAccountByStripeCustomerId(stripeCustomerId: String): Account? =
        accountsById.values.firstOrNull { it.stripeCustomerId == stripeCustomerId }

    override fun markEmailVerified(accountId: String, verifiedAt: Long): Boolean =
        accountsById.computeIfPresent(accountId) { _, a -> a.copy(emailVerifiedAt = a.emailVerifiedAt ?: verifiedAt) } != null

    override fun attachStripeCustomer(accountId: String, stripeCustomerId: String): Boolean =
        accountsById.computeIfPresent(accountId) { _, a ->
            a.copy(stripeCustomerId = stripeCustomerId, rail = Rail.Stripe)
        } != null

    override fun setStoreRail(accountId: String, rail: Rail, storeRef: String) {
        accountsById.computeIfPresent(accountId) { _, a -> a.copy(rail = rail, iapOriginalTxnId = storeRef) }
    }

    override fun bindOwner(accountId: String, ownerId: String, boundAt: Long) {
        synchronized(ownerLock) {
            // Supersede the account's current live binding, then insert the fresh live row.
            owners.replaceAll { row ->
                if (row.accountId == accountId && row.supersededAt == null) row.copy(supersededAt = boundAt) else row
            }
            owners.add(AccountOwner(accountId = accountId, ownerId = ownerId, boundAt = boundAt))
        }
    }

    override fun liveOwner(accountId: String): String? = synchronized(ownerLock) {
        owners.firstOrNull { it.accountId == accountId && it.supersededAt == null }?.ownerId
    }

    override fun accountIdForOwner(ownerId: String): String? = synchronized(ownerLock) {
        owners.firstOrNull { it.ownerId == ownerId && it.supersededAt == null }?.accountId
    }

    override fun accountIdsForOwner(ownerId: String): List<String> = synchronized(ownerLock) {
        owners.filter { it.ownerId == ownerId && it.supersededAt == null }.map { it.accountId }.distinct()
    }

    override fun putEmailVerification(emailNormalised: String, codeHash: String, expiresAt: Long) {
        verifications[emailNormalised] = EmailVerification(emailNormalised, codeHash, expiresAt, attempts = 0)
    }

    override fun getEmailVerification(emailNormalised: String): EmailVerification? =
        verifications[emailNormalised]

    override fun incrementVerificationAttempts(emailNormalised: String) {
        verifications.computeIfPresent(emailNormalised) { _, v -> v.copy(attempts = v.attempts + 1) }
    }

    override fun deleteEmailVerification(emailNormalised: String) {
        verifications.remove(emailNormalised)
    }

    override fun createSession(tokenHash: String, accountId: String, createdAt: Long, expiresAt: Long) {
        sessions[tokenHash] = Session(tokenHash, accountId, createdAt, expiresAt)
    }

    override fun getSession(tokenHash: String): Session? = sessions[tokenHash]

    override fun deleteSessionsForAccount(accountId: String): Int {
        val doomed = sessions.values.filter { it.accountId == accountId }.map { it.tokenHash }
        doomed.forEach { sessions.remove(it) }
        return doomed.size
    }

    override fun deleteSession(tokenHash: String) {
        sessions.remove(tokenHash)
    }

    override fun countActiveSessions(accountId: String, nowMs: Long): Int =
        sessions.values.count { it.accountId == accountId && it.isValid(nowMs) }

    override fun appendEntitlementHistory(entry: EntitlementHistory) {
        entitlements.add(entry)
    }

    override fun currentEntitlement(accountId: String, nowMs: Long): EntitlementHistory? =
        pickCurrentEntitlement(
            entitlements.filter { it.accountId == accountId && nowMs in it.validFrom..it.validUntil },
        )

    override fun endEntitlements(accountId: String, source: EntitlementSource, endedAt: Long): Int {
        var ended = 0
        entitlements.replaceAll { row ->
            if (row.accountId == accountId && row.source == source && row.validUntil >= endedAt) {
                ended++
                // One below, so the grant is already out of `[validFrom, validUntil]` at [endedAt].
                row.copy(validUntil = endedAt - 1)
            } else {
                row
            }
        }
        return ended
    }

    override fun putRedemptionCode(code: RedemptionCode) {
        redemptionCodes[code.codeHash] = code
    }

    override fun getRedemptionCode(codeHash: String): RedemptionCode? = redemptionCodes[codeHash]

    override fun incrementRedemptionUse(codeHash: String) {
        redemptionCodes.computeIfPresent(codeHash) { _, c -> c.copy(usedCount = c.usedCount + 1) }
    }

    /** Test/read helper — the versioned acceptance trail, in insertion order. */
    fun acceptances(): List<Acceptance> = acceptances.toList()

    // Under eraseLock, like the expiry sweep, so "exists, then append" cannot interleave with it.
    override fun recordAcceptance(acceptance: Acceptance): Boolean = synchronized(eraseLock) {
        if (!accountsById.containsKey(acceptance.accountId)) return false
        acceptances.add(acceptance)
    }

    override fun setMarketingConsent(consent: MarketingConsent) {
        marketingConsents[consent.accountId] = consent
    }

    override fun appendInvoice(draft: InvoiceDraft): Invoice {
        val invoice = Invoice(
            number = invoiceCounter.incrementAndGet(),
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
        invoices.add(invoice)
        return invoice
    }

    override fun stripeEventSeen(eventId: String): Boolean = eventId in stripeEvents

    override fun recordStripeEvent(eventId: String, receivedAt: Long) {
        stripeEvents.add(eventId)
    }

    override fun findInvoiceByStripeId(stripeInvoiceId: String): Invoice? =
        invoices.lastOrNull { it.stripeInvoiceId == stripeInvoiceId }

    override fun findInvoiceByPaymentRef(paymentRef: String): Invoice? =
        invoices.lastOrNull { it.paymentRef == paymentRef }

    override fun appendCreditNote(draft: CreditNoteDraft): CreditNote? = synchronized(creditNoteLock) {
        // ⚠ The existence check and the number assignment share a lock: without it two concurrent
        // deliveries of the same refund could both pass the check and mint two notes for one event.
        if (creditNotes.any { it.stripeReferenceId == draft.stripeReferenceId }) return@synchronized null
        val note = CreditNote(
            number = creditNoteCounter.incrementAndGet(),
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
        creditNotes.add(note)
        note
    }

    override fun listInvoices(fromInclusive: Long, toExclusive: Long): List<Invoice> =
        invoices.filter { it.issuedAt >= fromInclusive && it.issuedAt < toExclusive }
            .sortedBy { it.number }

    override fun listCreditNotes(fromInclusive: Long, toExclusive: Long): List<CreditNote> =
        creditNotes.filter { it.issuedAt >= fromInclusive && it.issuedAt < toExclusive }
            .sortedBy { it.number }

    override fun listEntitlementHistory(accountId: String): List<EntitlementHistory> =
        entitlements.filter { it.accountId == accountId }.sortedBy { it.issuedAt }

    override fun listAccountsForAdmin(query: String?, nowMs: Long): List<AdminAccountRow> {
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return accountsById.values
            .map { account ->
                val current = currentEntitlement(account.id, nowMs)
                AdminAccountRow(
                    email = account.emailNormalised,
                    emailVerifiedAt = account.emailVerifiedAt,
                    createdAt = account.createdAt,
                    status = account.status,
                    rail = account.rail,
                    currentProductId = current?.productId,
                    currentValidUntil = current?.validUntil,
                    currentSource = current?.source,
                    liveOwnerId = liveOwner(account.id),
                )
            }
            .filter { row ->
                needle == null ||
                    row.email.lowercase().contains(needle) ||
                    (row.liveOwnerId?.lowercase()?.contains(needle) == true)
            }
            .sortedBy { it.createdAt }
    }

    /** Always reachable — there is no connection to lose. */
    override fun probe() = Unit
}
