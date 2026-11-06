package org.tenshu.commerce.routing

import org.tenshu.commerce.store.CreditNote
import org.tenshu.commerce.store.Invoice
import java.time.Instant

/**
 * The merchant-of-record export (WC-4, §4.7 / D12) — the line items and per-country totals an EU
 * **OSS** return is filled from.
 *
 * ⚠⚠ **This type is the D12 mitigation, and it is a type on purpose.** D8 refused a bulk export
 * because it "raises the breach blast radius for little ops value"; D12 reverses that because an OSS
 * return is a statutory obligation, and it stays a *narrow* reversal only because a VAT return needs
 * **neither side of the A28 ⨯ A30 join**. So there is no `ownerId` field here, no email, and no
 * `accountId` — not filtered out in a query where a later `SELECT *` could quietly reintroduce them,
 * but absent from the shape, so a widening edit does not compile into a leak. A line can still be
 * traced to an account through its [number] in the console, which is where identity belongs.
 *
 * ⚠⚠ **Amounts are minor units as integers, never decimal strings.** A German Excel reads `"11.90"`
 * as **1190**, and this file's whole purpose is to be opened in a spreadsheet by someone preparing a
 * filing. Integers plus an explicit [currency] are unambiguous in every locale; the console renders
 * decimals for humans reading a page, which is a different job.
 */
internal data class LedgerExportRow(
    val documentType: DocumentType,
    /** The statutory sequence number within its own series (invoices and credit notes number apart). */
    val number: Long,
    val issuedAt: Long,
    /** Place-of-supply country. Null on a credit note we could not link back to its invoice. */
    val country: String?,
    /** The second, independent location evidence (§5); null where Stripe did not carry one. */
    val secondEvidenceCountry: String?,
    val currency: String,
    /**
     * Net / VAT / gross in minor units, **signed**: a credit note is negative, because a return is
     * filed on the period's net movement and a reversal has to subtract.
     *
     * ⚠ Null net and VAT (with gross present) means an unapportionable credit note — see [flags].
     */
    val netMinor: Long?,
    val vatMinor: Long?,
    val grossMinor: Long,
    val vatRateBasisPoints: Int?,
    /** Stripe invoice id, refund id or dispute id — provenance, not identity. */
    val reference: String?,
    /** Machine-readable problems with this row; empty when it is fit to file. */
    val flags: List<RowFlag>,
) {
    enum class DocumentType { INVOICE, CREDIT_NOTE }

    enum class RowFlag {
        /** Only `billingCountry` is known: the VAT rate is not fully defensible (§5 wants two). */
        SINGLE_LOCATION_EVIDENCE,

        /** VAT could not be apportioned — this row cannot enter a country total at all. */
        VAT_UNAPPORTIONED,

        /** A credit note with no linked invoice, so no place of supply either. */
        UNLINKED_CREDIT_NOTE,
    }
}

/** One country's movement for the period — the shape a return is actually filed from. */
internal data class VatSummaryRow(
    /** ISO country, or [UNRECONCILED] for the bucket that could not be attributed. */
    val country: String,
    val currency: String,
    val netMinor: Long,
    val vatMinor: Long,
    val grossMinor: Long,
    val documentCount: Int,
    val note: String,
) {
    companion object {
        /**
         * ⚠⚠ Not a country. Rows that could not be apportioned are emitted under this label rather
         * than dropped: a summary that silently omits them balances perfectly and under-reports, and
         * the operator has no way to notice. A bucket with a non-country code in the country column
         * cannot be summed by accident without someone seeing it.
         */
        const val UNRECONCILED = "(unreconciled)"
    }
}

internal object LedgerExport {

    /** Line items for the window, invoices first then credit notes, each in sequence order. */
    fun rows(invoices: List<Invoice>, creditNotes: List<CreditNote>): List<LedgerExportRow> =
        invoices.map { it.toRow() } + creditNotes.map { it.toRow() }

    private fun Invoice.toRow(): LedgerExportRow = LedgerExportRow(
        documentType = LedgerExportRow.DocumentType.INVOICE,
        number = number,
        issuedAt = issuedAt,
        country = billingCountry,
        secondEvidenceCountry = ipCountry,
        currency = currency,
        netMinor = amountMinor - vatAmountMinor,
        vatMinor = vatAmountMinor,
        grossMinor = amountMinor,
        vatRateBasisPoints = vatRateBasisPoints,
        reference = stripeInvoiceId,
        flags = buildList {
            if (ipCountry.isNullOrBlank()) add(LedgerExportRow.RowFlag.SINGLE_LOCATION_EVIDENCE)
        },
    )

    private fun CreditNote.toRow(): LedgerExportRow = LedgerExportRow(
        documentType = LedgerExportRow.DocumentType.CREDIT_NOTE,
        number = number,
        issuedAt = issuedAt,
        country = billingCountry,
        secondEvidenceCountry = null,
        currency = currency,
        // ⚠ Signed negative: the period's net movement is what gets filed, so a reversal subtracts.
        netMinor = vatAmountMinor?.let { -(amountMinor - it) },
        vatMinor = vatAmountMinor?.let { -it },
        grossMinor = -amountMinor,
        vatRateBasisPoints = vatRateBasisPoints,
        reference = stripeReferenceId,
        flags = buildList {
            if (vatAmountMinor == null) add(LedgerExportRow.RowFlag.VAT_UNAPPORTIONED)
            if (referencesInvoiceNumber == null) add(LedgerExportRow.RowFlag.UNLINKED_CREDIT_NOTE)
        },
    )

    /**
     * Per-(country, currency) totals over [rows], plus one [VatSummaryRow.UNRECONCILED] bucket per
     * currency for anything that could not be attributed.
     *
     * ⚠ Grouped by **currency as well as country**: summing across currencies produces a number that
     * is wrong in every one of them.
     *
     * ⚠⚠ A row is attributable only when it has **both** a country and a VAT figure. Anything else
     * goes to the unreconciled bucket with its **gross only** and blank net/VAT — an unapportioned
     * reversal must not be silently treated as zero-rated, which is what folding it into a country
     * at `vat = 0` would do.
     */
    fun vatSummary(rows: List<LedgerExportRow>): List<VatSummaryRow> {
        val (attributable, unreconciled) = rows.partition {
            !it.country.isNullOrBlank() && it.netMinor != null && it.vatMinor != null
        }
        val byCountry = attributable
            .groupBy { it.country!!.uppercase() to it.currency.lowercase() }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .map { (key, group) ->
                // ⚠ A single-evidence document is still a real sale with a known place of supply,
                // so it belongs in the total — the missing evidence is about *defending* the rate
                // under audit, not about whether to report it. But an operator reading only the
                // summary would never learn that a country's figure rests on such rows, so the
                // count rides along rather than living solely in the line-item file.
                val thinEvidence = group.count {
                    LedgerExportRow.RowFlag.SINGLE_LOCATION_EVIDENCE in it.flags
                }
                VatSummaryRow(
                    country = key.first,
                    currency = key.second,
                    netMinor = group.sumOf { it.netMinor!! },
                    vatMinor = group.sumOf { it.vatMinor!! },
                    grossMinor = group.sumOf { it.grossMinor },
                    documentCount = group.size,
                    note = if (thinEvidence > 0) "$thinEvidence document(s) with a single location evidence" else "",
                )
            }
        val unreconciledRows = unreconciled
            .groupBy { it.currency.lowercase() }
            .toSortedMap()
            .map { (currency, group) ->
                VatSummaryRow(
                    country = VatSummaryRow.UNRECONCILED,
                    currency = currency,
                    netMinor = 0,
                    vatMinor = 0,
                    grossMinor = group.sumOf { it.grossMinor },
                    documentCount = group.size,
                    note = "excluded from country totals — reconcile before filing",
                )
            }
        return byCountry + unreconciledRows
    }

    /** RFC 4180-ish CSV for the line items. */
    fun rowsCsv(rows: List<LedgerExportRow>): String {
        val header = listOf(
            "document_type", "number", "issued_at", "country", "second_evidence_country",
            "currency", "net_minor", "vat_minor", "gross_minor", "vat_rate_bps", "reference", "flags",
        )
        return csv(
            header,
            rows.map { row ->
                listOf(
                    row.documentType.name,
                    row.number.toString(),
                    Instant.ofEpochMilli(row.issuedAt).toString(),
                    row.country.orEmpty(),
                    row.secondEvidenceCountry.orEmpty(),
                    row.currency.uppercase(),
                    row.netMinor?.toString().orEmpty(),
                    row.vatMinor?.toString().orEmpty(),
                    row.grossMinor.toString(),
                    row.vatRateBasisPoints?.toString().orEmpty(),
                    row.reference.orEmpty(),
                    row.flags.joinToString(" ") { it.name },
                )
            },
        )
    }

    /** RFC 4180-ish CSV for the per-country summary. */
    fun summaryCsv(summary: List<VatSummaryRow>): String = csv(
        listOf("country", "currency", "net_minor", "vat_minor", "gross_minor", "documents", "note"),
        summary.map {
            listOf(
                it.country,
                it.currency.uppercase(),
                it.netMinor.toString(),
                it.vatMinor.toString(),
                it.grossMinor.toString(),
                it.documentCount.toString(),
                it.note,
            )
        },
    )

    private fun csv(header: List<String>, rows: List<List<String>>): String =
        (listOf(header) + rows).joinToString("\r\n") { fields -> fields.joinToString(",") { escape(it) } } + "\r\n"

    /**
     * Quote a field when it contains a delimiter, quote or newline, doubling embedded quotes.
     *
     * ⚠ Also neutralises a leading `=`, `+`, `@` or a control character by quoting and prefixing a
     * single quote: spreadsheets treat those as formulas, and this file is opened in a spreadsheet by
     * definition. Nothing we emit today starts that way — the fields are enum names, integers, ISO
     * dates and Stripe ids — but the guard belongs with the writer rather than with an assumption
     * about every future column. ⚠ A leading `-` is deliberately **not** escaped: negative amounts
     * are the point, and Excel reads them as numbers, not formulas.
     */
    private fun escape(field: String): String {
        val dangerous = field.firstOrNull()?.let { it == '=' || it == '+' || it == '@' || it == '\t' || it == '\r' } == true
        val value = if (dangerous) "'$field" else field
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
