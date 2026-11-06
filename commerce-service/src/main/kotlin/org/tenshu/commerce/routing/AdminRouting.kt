package org.tenshu.commerce.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.log
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe
import org.tenshu.commerce.SubscriptionCanceller
import org.tenshu.commerce.constantTimeEquals
import org.tenshu.commerce.emailDomain
import org.tenshu.commerce.shortId
import org.tenshu.commerce.store.Account
import org.tenshu.commerce.store.AdminAccountRow
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.CreditNote
import org.tenshu.commerce.store.EntitlementHistory
import org.tenshu.commerce.store.Invoice
import kotlinx.html.code
import kotlinx.html.pre
import java.time.Instant

/**
 * The internal admin console (`commerce-accounts-and-sessions.md` §4.5 / D8) — a **read-only**,
 * server-rendered users table at `admin.tenshu.app`. This is the highest-risk surface in the
 * system: the one place that shows **A28 (real PII)** and the **A30 `ownerId↔email` join** in the
 * clear, so it lives on its own host/port behind a Caddy allowlist (deploy config) **and** behind
 * an [AdminAuth] credential separate from any user/commerce session and from the issuer key (A29).
 * Every access is audit-logged.
 *
 * **CE-5 added the two lifecycle mutations** (`commerce-end-to-end-dry-run.md`): close and reopen.
 * Before them nothing anywhere could close an account, so the entire P3 closure path — minter,
 * sweep, relay reclaimer — had no way to be exercised even by an operator. There is still no bulk
 * export **of the users table**; the two `/admin/export/…csv` routes below are the accounting
 * ledger, and D8's anti-bulk-extraction rule survives in them as a mandatory date range.
 *
 * ⚠ The mutations are keyed by **email**, not by an internal id: the id is not on [AdminAccountRow]
 * and an operator identifies an account by its address anyway.
 *
 * ⚠ These are `curl` endpoints, not buttons. Auth is a bearer header, which a plain browser form
 * cannot send, so rendering clickable controls would produce affordances that always 401. The page
 * prints the invocations instead.
 *
 * (The threat-model amendment that records this surface on A28/A30 is batched into Phase 4 per the
 * proposal §9.4; House Rule #2's mandatory trigger — A1–A4/A9/relay-shape/sync-protocol — does not
 * fire for a commerce-plane read UI.)
 */
fun interface AdminAuth {
    /** True iff the raw `Authorization` header value authorises an admin. */
    fun isAuthorized(authorizationHeader: String?): Boolean
}

/**
 * Bearer-token [AdminAuth]. **Locked by default**: a null/blank expected token authorises nobody,
 * so an unconfigured admin server is inert. Constant-time compare.
 */
class TokenAdminAuth(expectedToken: String?) : AdminAuth {
    private val expected: String? = expectedToken?.takeIf { it.isNotBlank() }

    override fun isAuthorized(authorizationHeader: String?): Boolean {
        val token = expected ?: return false
        val provided = authorizationHeader?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        return constantTimeEquals(provided, token)
    }
}

fun Route.configureAdminRouting(
    store: CommerceStore,
    adminAuth: AdminAuth,
    now: () -> Long = System::currentTimeMillis,
    /**
     * Stops a closed account being billed. Null when the Stripe rail is off, in which case there is
     * no subscription to cancel and closing is the whole operation.
     */
    subscriptionCanceller: SubscriptionCanceller? = null,
) {
    get("/admin/users") {
        val query = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
        // Audit every authorised access (who is the admin credential; that a query ran; from where).
        // ⚠ Not the query text: it is usually (part of) somebody's email address. The operator's
        // own IP is kept — this is the admin plane's access audit, not a customer's request.
        call.application.log.info(
            "[admin] users view accessed query={} from {}",
            if (query == null) "*" else "<${query.length} chars>",
            call.request.local.remoteHost,
        )

        val rows = store.listAccountsForAdmin(query, now())
        call.respondHtml { renderUsers(rows, query) }
    }

    // --- Purchase visibility (WC-3) ----------------------------------------------------------
    //
    // What the console could never answer: "what did we sell, and what do we owe each member
    // state". ⚠ These are *listings*, not the filing — the per-country OSS aggregation is WC-4's,
    // and the page says so, because an operator filing a return off a page that merely looks
    // authoritative is the failure this whole phase exists to prevent.

    get("/admin/purchases") {
        val window = parseAdminDateWindow(
            from = call.request.queryParameters["from"],
            to = call.request.queryParameters["to"],
            nowMs = now(),
        )
        if (window == null) {
            call.respondText("from/to must be YYYY-MM-DD, and from must not follow to", status = HttpStatusCode.BadRequest)
            return@get
        }
        // ⚠ Audit the RANGE, not merely the access: for a purchase view the interesting fact is
        // what was looked at, and this is the shape WC-4's export inherits.
        call.application.log.info(
            "[admin] purchases view accessed window={} from {}",
            window.describe(),
            call.request.local.remoteHost,
        )

        val invoices = store.listInvoices(window.fromInclusive, window.toExclusive)
        val creditNotes = store.listCreditNotes(window.fromInclusive, window.toExclusive)
        call.respondHtml { renderPurchases(window, invoices, creditNotes) }
    }

    // --- The MoR export (WC-4, D12) ----------------------------------------------------------
    //
    // ⚠⚠ D12 reverses D8's "no bulk export" because an OSS return is a statutory obligation rather
    // than the "little ops value" D8 weighed. The reversal stays narrow by construction: a VAT
    // return needs neither side of the A28 ⨯ A30 join, so `LedgerExportRow` has no ownerId, no
    // email and no accountId to leak. The remaining mitigations are here — a mandatory range and an
    // audit line naming it.

    get("/admin/export/ledger.csv") {
        call.respondExport(adminAuth, store, now, "ledger") { invoices, creditNotes ->
            LedgerExport.rowsCsv(LedgerExport.rows(invoices, creditNotes))
        }
    }

    get("/admin/export/vat-summary.csv") {
        call.respondExport(adminAuth, store, now, "vat-summary") { invoices, creditNotes ->
            LedgerExport.summaryCsv(LedgerExport.vatSummary(LedgerExport.rows(invoices, creditNotes)))
        }
    }

    get("/admin/account") {
        val email = call.request.queryParameters["email"]?.trim()?.lowercase()
        if (email.isNullOrEmpty()) {
            call.respondText("email is required", status = HttpStatusCode.BadRequest)
            return@get
        }
        val account = store.findAccountByEmail(email)
        // Audited after the lookup so the line can name the ACCOUNT (id prefix) rather than the
        // address that was typed; a miss records the domain only.
        call.application.log.info(
            "[admin] account view accessed for {} from {}",
            account?.let { "account ${shortId(it.id)}" } ?: "no account (${emailDomain(email)})",
            call.request.local.remoteHost,
        )
        if (account == null) {
            // Honest 404 for the same reason the mutations give one: the caller already holds the
            // admin credential, so there is no enumeration to protect against, and an operator
            // typoing an address must find out.
            call.respondText("no such account", status = HttpStatusCode.NotFound)
            return@get
        }
        val nowMs = now()
        call.respondHtml {
            renderAccount(
                account = account,
                history = store.listEntitlementHistory(account.id),
                activeSessions = store.countActiveSessions(account.id, nowMs),
                liveOwnerId = store.liveOwner(account.id),
            )
        }
    }

    // --- Lifecycle mutations (CE-5) ---------------------------------------------------------
    //
    // ⚠⚠ Closing an account is the first step of a path that ends in **permanently deleting that
    // owner's content** (retention-quota-model.md P3). It deletes only while BOTH planes are switched
    // on — commerce needs ACCOUNT_CLOSURE_RELAY_URL and the relay needs ACCOUNT_CLOSURE_RECLAIM —
    // so this route alone cannot delete anything. ⚠ Both are ON in production since 2026-10-24: a
    // close here deletes the owner's content once the 30-day grace has passed.
    //
    // Closing also cancels the account's Stripe subscription (see [SubscriptionCanceller]), so a
    // closed customer stops being charged for a grant the webhook now refuses. Best-effort at this
    // moment and retried by the sweep; the close itself always stands.

    post("/admin/accounts/close") {
        call.adminMutation(store, adminAuth, "close") { account, nowMs ->
            // ⚠⚠ Close FIRST, cancel second — the failure modes are asymmetric. Cancelling first
            // and crashing would leave an ACTIVE account with no subscription: the customer stops
            // paying, keeps the service, and nothing ever notices. Closing first and crashing
            // leaves a closed account still billing — wrong in the visible direction, and the
            // cancellation sweep is built to find exactly that.
            store.closeAccount(account.id, nowMs)
            subscriptionCanceller?.cancelFor(account)
        }
    }

    post("/admin/accounts/reopen") {
        call.adminMutation(store, adminAuth, "reopen") { account, _ ->
            store.reopenAccount(account.id)
        }
    }
}

/**
 * Shared shape for the CSV exports: authorise → require an **explicit** range → audit it → render.
 *
 * ⚠⚠ Unlike the purchase *view*, `from` and `to` are **mandatory**. A defaulted range on an export
 * is one absent parameter away from "everything", which is precisely the bulk-extraction affordance
 * D8 refused; a filing is always for a stated period, so requiring it costs the operator nothing.
 *
 * ⚠ The audit line names the **window**, not just the access. For an export the interesting fact is
 * its scope — "who looked" is much less useful after the event than "how much left".
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondExport(
    adminAuth: AdminAuth,
    store: CommerceStore,
    now: () -> Long,
    label: String,
    render: (List<Invoice>, List<CreditNote>) -> String,
) {
    val from = request.queryParameters["from"]
    val to = request.queryParameters["to"]
    if (from.isNullOrBlank() || to.isNullOrBlank()) {
        respondText("from and to are required (YYYY-MM-DD)", status = HttpStatusCode.BadRequest)
        return
    }
    val window = parseAdminDateWindow(from, to, now())
    if (window == null) {
        respondText("from/to must be YYYY-MM-DD, and from must not follow to", status = HttpStatusCode.BadRequest)
        return
    }
    application.log.warn(
        "[admin] {} EXPORTED window={} from {}",
        label,
        window.describe(),
        request.local.remoteHost,
    )
    val body = render(
        store.listInvoices(window.fromInclusive, window.toExclusive),
        store.listCreditNotes(window.fromInclusive, window.toExclusive),
    )
    respondText(
        text = body,
        contentType = ContentType.Text.CSV.withCharset(Charsets.UTF_8),
        status = HttpStatusCode.OK,
    )
}

/**
 * Shared shape for the two mutations: authorise, resolve the account by email, audit-log, apply.
 *
 * ⚠ Unlike the user-facing routes there is **no enumeration concern** here — the caller already
 * holds the admin credential and can list every account — so an unknown address gets an honest
 * `404` rather than a uniform success. An operator typoing an address must find out.
 */
private suspend fun io.ktor.server.application.ApplicationCall.adminMutation(
    store: CommerceStore,
    adminAuth: AdminAuth,
    action: String,
    apply: suspend (org.tenshu.commerce.store.Account, Long) -> Unit,
) {
    val email = runCatching { receiveParameters()["email"] }.getOrNull()?.trim()?.lowercase()
    if (email.isNullOrEmpty()) {
        respondText("email is required", status = HttpStatusCode.BadRequest)
        return
    }
    val account = store.findAccountByEmail(email)
    if (account == null) {
        respondText("no such account", status = HttpStatusCode.NotFound)
        return
    }
    // Audit BEFORE applying: a mutation that then throws must still leave a trace that it was
    // attempted, which is the whole point of an audit line on a destructive path.
    // The account by id prefix, never the address: this line outlives the account it names.
    application.log.warn("[admin] {} account {} from {}", action, shortId(account.id), request.local.remoteHost)
    apply(account, System.currentTimeMillis())
    respondText("ok", status = HttpStatusCode.OK)
}

private fun kotlinx.html.HTML.renderUsers(rows: List<AdminAccountRow>, query: String?) {
    body {
        style {
            val css = "body{font-family:system-ui,sans-serif;margin:24px} " +
                "table{border-collapse:collapse;width:100%} " +
                "th,td{border:1px solid #ccc;padding:6px 10px;text-align:left;font-size:13px} " +
                "th{background:#f4f4f4}"
            unsafe { +css }
        }
        h1 { +"Tenshu accounts" }
        p {
            // Plain text, not links: every one of these needs the bearer header, so a clickable
            // anchor would 401 in a browser for the same reason the mutations are curl calls.
            +"Other views (same bearer header): /admin/purchases?from=YYYY-MM-DD&to=YYYY-MM-DD "
            +"for the ledger, /admin/account?email=… for one account's entitlement history."
        }
        p {
            +"Lifecycle actions are bearer-authenticated, so they are curl calls rather than "
            +"buttons (a browser form cannot send the header):"
        }
        pre {
            code {
                // ⚠ Both factors shown: outside dev mode the service refuses to start without the
                // TOTP seed, so an invocation missing the code would simply 401 for everyone.
                +"curl -X POST -H 'Authorization: Bearer \$ADMIN_TOKEN' -H \"X-Admin-Totp: \$CODE\" \\\n"
                +"  -d 'email=user@example.com' <host>/admin/accounts/close\n"
                +"curl -X POST -H 'Authorization: Bearer \$ADMIN_TOKEN' -H \"X-Admin-Totp: \$CODE\" \\\n"
                +"  -d 'email=user@example.com' <host>/admin/accounts/reopen"
            }
        }
        p {
            val suffix = query?.let { " matching \"$it\"" } ?: ""
            +"${rows.size} account(s)$suffix"
        }
        table {
            thead {
                tr {
                    th { +"Email" }
                    th { +"Verified" }
                    th { +"Created" }
                    th { +"Status" }
                    th { +"Rail" }
                    th { +"Tier" }
                    th { +"Valid until" }
                    th { +"Source" }
                    th { +"Owner" }
                }
            }
            rows.forEach { row ->
                tr {
                    td { +row.email }
                    td { +epoch(row.emailVerifiedAt) }
                    td { +epoch(row.createdAt) }
                    td { +row.status.name }
                    td { +row.rail.name }
                    td { +(row.currentProductId ?: "—") }
                    td { +epoch(row.currentValidUntil) }
                    td { +(row.currentSource?.name ?: "—") }
                    td { +(row.liveOwnerId ?: "—") }
                }
            }
        }
    }
}

private fun kotlinx.html.HTML.renderPurchases(
    window: AdminDateWindow,
    invoices: List<Invoice>,
    creditNotes: List<CreditNote>,
) {
    body {
        style { unsafe { +ADMIN_CSS } }
        h1 { +"Purchases" }
        p { +"Window: ${window.describe()}. Add ?from=YYYY-MM-DD&to=YYYY-MM-DD to change it." }
        p {
            // ⚠ Said plainly on the page, not only in a doc: this is a listing, and a return is
            // filed from the per-country summary, not from eyeballing these rows.
            +"These are raw ledger rows — do not file from this page. The per-country totals a "
            +"return is filed from are at /admin/export/vat-summary.csv (same window), with the "
            +"line items at /admin/export/ledger.csv."
        }
        p {
            // ⚠⚠ The one instruction that decides whether the summary is trustworthy. Rows flagged
            // below cannot enter a country total, so filing before they are resolved under-reports.
            +"⚠ Resolve any row flagged below before filing: an unapportioned credit note is "
            +"excluded from every country total and appears in the summary under "
            +"\"${VatSummaryRow.UNRECONCILED}\"."
        }

        h2 { +"Invoices (money in)" }
        p {
            +"${invoices.size} row(s)"
            totalsByCurrency(invoices.map { Triple(it.currency, it.amountMinor, it.vatAmountMinor) })
                .forEach { +" · invoiced $it" }
        }
        table {
            thead {
                tr {
                    th { +"No." }
                    th { +"Issued" }
                    th { +"Gross" }
                    th { +"VAT" }
                    th { +"Rate" }
                    th { +"Billing country" }
                    th { +"2nd evidence" }
                    th { +"Stripe id" }
                }
            }
            invoices.forEach { invoice ->
                tr {
                    td { +invoice.number.toString() }
                    td { +epoch(invoice.issuedAt) }
                    td { +formatMinor(invoice.amountMinor, invoice.currency) }
                    td { +formatMinor(invoice.vatAmountMinor, invoice.currency) }
                    td { +"${invoice.vatRateBasisPoints / 100.0}%" }
                    td { +(invoice.billingCountry ?: "—") }
                    // ⚠ An OSS filing wants TWO non-contradictory location evidences (§5). A row
                    // with one is defensible only as far as the billing country goes, and the
                    // operator is the only one who can chase the difference — so it is named here
                    // rather than left to look like a blank cell.
                    td { +(invoice.ipCountry ?: "⚠ missing — 1 evidence only") }
                    td { +(invoice.stripeInvoiceId ?: "—") }
                }
            }
        }

        h2 { +"Credit notes (money out)" }
        p {
            +"${creditNotes.size} row(s)"
            totalsByCurrency(creditNotes.map { Triple(it.currency, it.amountMinor, it.vatAmountMinor) })
                .forEach { +" · credited $it" }
            // ⚠ The VAT total above is a floor, not the figure: rows whose VAT could not be
            // apportioned contribute nothing to it. Saying how many is what stops the total being
            // read as complete.
            val unapportioned = creditNotes.count { it.vatAmountMinor == null }
            if (unapportioned > 0) {
                +" · ⚠ VAT total excludes $unapportioned unapportioned row(s)"
            }
        }
        table {
            thead {
                tr {
                    th { +"No." }
                    th { +"Issued" }
                    th { +"Kind" }
                    th { +"Credited" }
                    th { +"VAT credited" }
                    th { +"Country" }
                    th { +"Reverses invoice" }
                    th { +"Stripe ref" }
                }
            }
            creditNotes.forEach { note ->
                tr {
                    td { +note.number.toString() }
                    td { +epoch(note.issuedAt) }
                    td { +note.kind.name }
                    td { +formatMinor(note.amountMinor, note.currency) }
                    // ⚠⚠ Null VAT is the one row an operator must act on: it could not be
                    // apportioned, so the correction cannot be computed without them. Rendering it
                    // as "0.00" — which a naive formatter would — is exactly the false claim the
                    // nullable column exists to prevent.
                    td { +(note.vatAmountMinor?.let { formatMinor(it, note.currency) } ?: "⚠ unknown — reconcile by hand") }
                    td { +(note.billingCountry ?: "—") }
                    td { +(note.referencesInvoiceNumber?.toString() ?: "⚠ unlinked") }
                    td { +note.stripeReferenceId }
                }
            }
        }
    }
}

private fun kotlinx.html.HTML.renderAccount(
    account: Account,
    history: List<EntitlementHistory>,
    activeSessions: Int,
    liveOwnerId: String?,
) {
    body {
        style { unsafe { +ADMIN_CSS } }
        h1 { +"Account" }
        table {
            tr { th { +"Email" }; td { +account.emailNormalised } }
            tr { th { +"Status" }; td { +account.status.name } }
            tr { th { +"Rail" }; td { +account.rail.name } }
            tr { th { +"Created" }; td { +epoch(account.createdAt) } }
            tr { th { +"Email verified" }; td { +epoch(account.emailVerifiedAt) } }
            tr { th { +"Closed at" }; td { +epoch(account.closedAt) } }
            tr { th { +"Live owner" }; td { +(liveOwnerId ?: "—") } }
            tr { th { +"Stripe customer" }; td { +(account.stripeCustomerId ?: "—") } }
            // ⚠ A count, never the tokens: a session token is a live credential and has no reason
            // to appear on the highest-risk page in the system.
            tr { th { +"Active sessions" }; td { +activeSessions.toString() } }
        }

        h2 { +"Entitlement history" }
        p {
            +"${history.size} row(s), oldest first. Several may be valid at once — the highest "
            +"quota wins, which is why a paid row does not remove the long-lived Free one."
        }
        table {
            thead {
                tr {
                    th { +"Product" }
                    th { +"Source" }
                    th { +"Valid from" }
                    th { +"Valid until" }
                    th { +"Issued" }
                }
            }
            history.forEach { row ->
                tr {
                    td { +row.productId }
                    td { +row.source.name }
                    td { +epoch(row.validFrom) }
                    td { +epoch(row.validUntil) }
                    td { +epoch(row.issuedAt) }
                }
            }
        }
    }
}

/**
 * Per-currency `gross (VAT x)` totals — one string per currency, empty when there are no rows.
 *
 * ⚠ Grouped by currency because summing across them produces a number that is wrong in every
 * currency at once.
 *
 * ⚠⚠ The caller supplies the **direction word** ("invoiced" / "credited"). Both sections previously
 * rendered a bare "Total", so money in and money out looked like the same kind of figure and
 * invited being added together — found by reading the rendered page, which every string assertion
 * had already passed.
 *
 * ⚠⚠ A null VAT is **never summed as zero**. Coalescing it — which is what this function did on its
 * first pass, caught by the very assertion written for the per-row cell — reintroduces at the
 * aggregate exactly the false claim the nullable column exists to prevent: a window whose only
 * credit note is unapportioned would have read "VAT 0.00", which is a statement, and a wrong one.
 * Partially-known totals render as a floor (`≥`); wholly-unknown ones say so.
 */
private fun totalsByCurrency(rows: List<Triple<String, Long, Long?>>): List<String> =
    rows.groupBy { it.first }
        .toSortedMap()
        .map { (currency, entries) ->
            val gross = entries.sumOf { it.second }
            val known = entries.mapNotNull { it.third }
            val vat = when {
                known.isEmpty() -> "VAT unknown"
                known.size < entries.size -> "VAT ≥ ${formatMinor(known.sum(), currency)}"
                else -> "VAT ${formatMinor(known.sum(), currency)}"
            }
            "${formatMinor(gross, currency)} ($vat)"
        }

private const val ADMIN_CSS =
    "body{font-family:system-ui,sans-serif;margin:24px} " +
        "table{border-collapse:collapse;width:100%;margin-bottom:24px} " +
        "th,td{border:1px solid #ccc;padding:6px 10px;text-align:left;font-size:13px} " +
        "th{background:#f4f4f4}"

private fun epoch(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).toString() } ?: "—"
