package org.tenshu.commerce.routing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A reporting window for the admin purchase views (WC-3), resolved from two optional `YYYY-MM-DD`
 * query parameters.
 *
 * ⚠⚠ **The dates an operator types are inclusive on both ends; the instants handed to the store are
 * half-open.** "1 August to 31 August" must mean the whole of the 31st, so [toExclusive] is midnight
 * at the *start of the following day*. Passing the typed `to` date straight through as an exclusive
 * instant — the obvious reading — would silently drop the last day of every period, which on a VAT
 * return is a whole day of revenue missing from a filing that still looks complete.
 *
 * The store side stays half-open for its own reason ([org.tenshu.commerce.store.CommerceStore.listInvoices]):
 * adjacent periods share a boundary instant and must tile without double-counting. This class is
 * where the two conventions meet, deliberately in one place rather than at each call site.
 *
 * ⚠ **UTC**, not the operator's zone. A filing period is a fixed calendar range, and resolving it
 * against whichever machine the console is opened from would make the same URL mean different rows.
 */
internal data class AdminDateWindow(
    val fromInclusive: Long,
    val toExclusive: Long,
    /** The dates as typed/defaulted, for display — both **inclusive**. */
    val fromDate: LocalDate,
    val toDate: LocalDate,
) {
    /** How the window is shown back to the operator, so a misread parameter is visible. */
    fun describe(): String = "$fromDate to $toDate (inclusive, UTC)"
}

/**
 * Parse [from]/[to] (`YYYY-MM-DD`, either may be absent) into an [AdminDateWindow], or return null
 * if either is unparseable or the range runs backwards — the caller answers `400`.
 *
 * Defaults are the **current calendar month to date**: the window an operator reconciling today
 * most likely wants, and bounded rather than "everything". ⚠ Unlike WC-4's export, a *view* may
 * default its range; the export requires one explicitly (§4.7), because an unbounded export is the
 * bulk-extraction shape D8 objected to and a listing on a firewalled page is not.
 */
internal fun parseAdminDateWindow(from: String?, to: String?, nowMs: Long): AdminDateWindow? {
    val today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
    val fromDate = from?.takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return null }
        ?: today.withDayOfMonth(1)
    val toDate = to?.takeIf { it.isNotBlank() }?.let { parseDate(it) ?: return null } ?: today
    if (toDate.isBefore(fromDate)) return null
    return AdminDateWindow(
        fromInclusive = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        // ⚠ +1 day: the typed `to` is an inclusive DATE, the store wants an exclusive INSTANT.
        toExclusive = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        fromDate = fromDate,
        toDate = toDate,
    )
}

private fun parseDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

/**
 * Minor units → a human amount, e.g. `1190` + `"eur"` → `"11.90 EUR"`.
 *
 * ⚠ Assumes a two-decimal currency. Every price we sell is EUR (`ProductCatalog` / the Stripe price
 * ids), so this is correct today — but a zero-decimal currency such as JPY would render 100× too
 * small, so a second currency needs this revisited rather than reused.
 */
internal fun formatMinor(amountMinor: Long, currency: String): String {
    val sign = if (amountMinor < 0) "-" else ""
    val magnitude = kotlin.math.abs(amountMinor)
    return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')} ${currency.uppercase()}"
}
