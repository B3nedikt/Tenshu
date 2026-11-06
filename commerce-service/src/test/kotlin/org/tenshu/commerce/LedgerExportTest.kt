package org.tenshu.commerce

import org.tenshu.commerce.routing.LedgerExport
import org.tenshu.commerce.routing.LedgerExportRow
import org.tenshu.commerce.routing.VatSummaryRow
import org.tenshu.commerce.store.CreditNote
import org.tenshu.commerce.store.CreditNoteKind
import org.tenshu.commerce.store.Invoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MoR export (WC-4) — the shape an OSS return is filled from, tested away from HTTP because the
 * arithmetic is the part that has to be right.
 */
class LedgerExportTest {

    private fun invoice(
        number: Long,
        gross: Long,
        vat: Long,
        country: String? = "DE",
        secondEvidence: String? = "DE",
        currency: String = "eur",
    ) = Invoice(
        number = number,
        accountId = "acct-1",
        stripeInvoiceId = "in_$number",
        amountMinor = gross,
        currency = currency,
        vatAmountMinor = vat,
        vatRateBasisPoints = if (gross > vat) ((vat * 10_000) / (gross - vat)).toInt() else 0,
        billingCountry = country,
        ipCountry = secondEvidence,
        issuedAt = 1_000L + number,
    )

    private fun creditNote(
        number: Long,
        gross: Long,
        vat: Long?,
        country: String? = "DE",
        linked: Long? = 1L,
        kind: CreditNoteKind = CreditNoteKind.Refund,
    ) = CreditNote(
        number = number,
        accountId = "acct-1",
        kind = kind,
        stripeReferenceId = "re_$number",
        referencesInvoiceNumber = linked,
        amountMinor = gross,
        currency = "eur",
        vatAmountMinor = vat,
        vatRateBasisPoints = vat?.let { 1900 },
        billingCountry = country,
        issuedAt = 2_000L + number,
    )

    // --- signing + the accounting identity ----------------------------------------------------

    @Test
    fun anInvoiceIsPositive_andItsNetPlusVatIsItsGross() {
        val row = LedgerExport.rows(listOf(invoice(1, gross = 1190, vat = 190)), emptyList()).single()

        assertEquals(LedgerExportRow.DocumentType.INVOICE, row.documentType)
        assertEquals(1000, row.netMinor)
        assertEquals(190, row.vatMinor)
        assertEquals(1190, row.grossMinor)
        assertEquals(row.grossMinor, row.netMinor!! + row.vatMinor!!)
    }

    /** ⚠ A return is filed on the period's **net movement**, so a reversal has to subtract. */
    @Test
    fun aCreditNoteIsNegativeThroughout() {
        val row = LedgerExport.rows(emptyList(), listOf(creditNote(1, gross = 595, vat = 95))).single()

        assertEquals(LedgerExportRow.DocumentType.CREDIT_NOTE, row.documentType)
        assertEquals(-500, row.netMinor)
        assertEquals(-95, row.vatMinor)
        assertEquals(-595, row.grossMinor)
        assertEquals(row.grossMinor, row.netMinor!! + row.vatMinor!!)
    }

    /**
     * ⚠⚠ The property a filing depends on: for every country and currency, `net + vat == gross`,
     * with a reversal in the window pulling all three down together.
     */
    @Test
    fun perCountryTotalsBalance_withACreditNoteInTheWindow() {
        val rows = LedgerExport.rows(
            invoices = listOf(
                invoice(1, gross = 1190, vat = 190, country = "DE"),
                invoice(2, gross = 2380, vat = 380, country = "DE"),
                invoice(3, gross = 1200, vat = 200, country = "AT"),
            ),
            creditNotes = listOf(creditNote(1, gross = 1190, vat = 190, country = "DE")),
        )
        val summary = LedgerExport.vatSummary(rows)

        val germany = summary.single { it.country == "DE" }
        assertEquals(2000, germany.netMinor, "1000 + 2000 − 1000")
        assertEquals(380, germany.vatMinor, "190 + 380 − 190")
        assertEquals(2380, germany.grossMinor)
        assertEquals(3, germany.documentCount)
        assertEquals(germany.grossMinor, germany.netMinor + germany.vatMinor)

        val austria = summary.single { it.country == "AT" }
        assertEquals(austria.grossMinor, austria.netMinor + austria.vatMinor)
        assertEquals(1000, austria.netMinor)
    }

    /**
     * A single-evidence sale still belongs in its country total — the missing evidence is about
     * defending the rate under audit, not about whether to report it — but the summary has to say so,
     * or an operator reading only that file never learns the figure rests on such rows.
     */
    @Test
    fun aCountryTotalNamesHowManyOfItsDocumentsHaveThinEvidence() {
        val summary = LedgerExport.vatSummary(
            LedgerExport.rows(
                invoices = listOf(
                    invoice(1, gross = 1190, vat = 190, country = "AT", secondEvidence = null),
                    invoice(2, gross = 1190, vat = 190, country = "AT", secondEvidence = "AT"),
                    invoice(3, gross = 1190, vat = 190, country = "DE", secondEvidence = "DE"),
                ),
                creditNotes = emptyList(),
            ),
        )
        assertEquals("1 document(s) with a single location evidence", summary.single { it.country == "AT" }.note)
        assertEquals("", summary.single { it.country == "DE" }.note, "a fully-evidenced country says nothing")
    }

    /**
     * ⚠⚠ The defect WC-4's render surfaced, closed by WC-6. A chargeback used to be unlinkable — a
     * dispute names a payment, not an invoice — so a **fully reversed** sale kept its country total
     * intact while an equal figure sat in `(unreconciled)`, and an operator filing without
     * reconciling would remit VAT on a sale that never stood. Linked, it nets to zero.
     */
    @Test
    fun aFullyChargedBackSaleNetsToZeroInItsCountry_leavingNothingUnreconciled() {
        val summary = LedgerExport.vatSummary(
            LedgerExport.rows(
                invoices = listOf(invoice(1, gross = 24_900, vat = 3975, country = "AT")),
                creditNotes = listOf(
                    creditNote(1, gross = 24_900, vat = 3975, country = "AT", kind = CreditNoteKind.Chargeback),
                ),
            ),
        )
        val austria = summary.single { it.country == "AT" }
        assertEquals(0, austria.netMinor)
        assertEquals(0, austria.vatMinor, "no VAT is owed on a sale that was fully reversed")
        assertEquals(0, austria.grossMinor)
        assertEquals(2, austria.documentCount, "…and both documents are still visible")
        assertTrue(summary.none { it.country == VatSummaryRow.UNRECONCILED })
    }

    @Test
    fun countriesAndCurrenciesAreSeparateBuckets() {
        val rows = LedgerExport.rows(
            invoices = listOf(
                invoice(1, gross = 1190, vat = 190, country = "DE", currency = "eur"),
                invoice(2, gross = 1000, vat = 0, country = "DE", currency = "usd"),
            ),
            creditNotes = emptyList(),
        )
        val summary = LedgerExport.vatSummary(rows)

        // ⚠ Summing across currencies produces a number wrong in every one of them.
        assertEquals(2, summary.size)
        assertEquals(setOf("eur", "usd"), summary.map { it.currency }.toSet())
    }

    // --- what must never be silently dropped ---------------------------------------------------

    /**
     * ⚠⚠ An unapportioned credit note cannot enter a country total — it has no VAT figure and no
     * country. Dropping it would leave a summary that balances perfectly and under-reports, with
     * nothing on the page to say so. It gets its own bucket instead.
     */
    @Test
    fun anUnapportionedCreditNoteGoesToItsOwnBucket_neverIntoACountryTotal() {
        val rows = LedgerExport.rows(
            invoices = listOf(invoice(1, gross = 1190, vat = 190, country = "DE")),
            creditNotes = listOf(creditNote(1, gross = 24_900, vat = null, country = null, linked = null)),
        )
        val summary = LedgerExport.vatSummary(rows)

        val germany = summary.single { it.country == "DE" }
        assertEquals(190, germany.vatMinor, "the unapportioned reversal must not touch DE")
        assertEquals(1, germany.documentCount)

        val bucket = summary.single { it.country == VatSummaryRow.UNRECONCILED }
        assertEquals(-24_900, bucket.grossMinor)
        // ⚠ Zero net/VAT here is not a claim about the row — it is the bucket declining to guess,
        // and the note says so out loud.
        assertEquals(0, bucket.vatMinor)
        assertTrue("reconcile before filing" in bucket.note)
    }

    @Test
    fun rowsCarryTheFlagsAnOperatorHasToActOn() {
        val rows = LedgerExport.rows(
            invoices = listOf(invoice(1, gross = 1190, vat = 190, secondEvidence = null)),
            creditNotes = listOf(creditNote(1, gross = 500, vat = null, linked = null)),
        )
        assertEquals(
            listOf(LedgerExportRow.RowFlag.SINGLE_LOCATION_EVIDENCE),
            rows.first { it.documentType == LedgerExportRow.DocumentType.INVOICE }.flags,
        )
        assertEquals(
            listOf(LedgerExportRow.RowFlag.VAT_UNAPPORTIONED, LedgerExportRow.RowFlag.UNLINKED_CREDIT_NOTE),
            rows.first { it.documentType == LedgerExportRow.DocumentType.CREDIT_NOTE }.flags,
        )
    }

    // --- the D12 mitigation --------------------------------------------------------------------

    /**
     * ⚠⚠ D12's whole justification: a VAT return needs **neither side of the A28 ⨯ A30 join**, so
     * the export cannot carry one. Asserted on the rendered bytes, because that is what leaves the
     * building — a field could be reintroduced without any query changing.
     */
    @Test
    fun theExportCarriesNoOwnerIdEmailOrAccountId() {
        val csv = LedgerExport.rowsCsv(
            LedgerExport.rows(
                invoices = listOf(invoice(1, gross = 1190, vat = 190)),
                creditNotes = listOf(creditNote(1, gross = 500, vat = 80)),
            ),
        )
        assertFalse("acct-1" in csv, "the accountId must not reach the export")
        assertFalse("owner" in csv.lowercase(), "no ownerId, not even as a column name")
        assertFalse("@" in csv, "no email can appear")
        // What it does carry: the fiscal facts and provenance.
        assertTrue("in_1" in csv)
        assertTrue("re_1" in csv)
    }

    // --- CSV mechanics --------------------------------------------------------------------------

    /**
     * ⚠⚠ Minor units as integers, never decimal strings: a German Excel reads `"11.90"` as **1190**,
     * and a spreadsheet is exactly where this file is opened.
     */
    @Test
    fun amountsAreEmittedAsIntegerMinorUnits() {
        val csv = LedgerExport.rowsCsv(LedgerExport.rows(listOf(invoice(1, gross = 1190, vat = 190)), emptyList()))
        assertTrue("1000,190,1190" in csv, "net,vat,gross as plain integers")
        assertFalse("11.90" in csv)
    }

    @Test
    fun csvQuotesSeparatorsAndDoublesEmbeddedQuotes() {
        val row = LedgerExport.rows(
            invoices = listOf(invoice(1, gross = 100, vat = 0).copy(stripeInvoiceId = """in_a,b"c""")),
            creditNotes = emptyList(),
        )
        val csv = LedgerExport.rowsCsv(row)
        assertTrue(""""in_a,b""c"""" in csv)
    }

    /** A spreadsheet treats a leading `=`/`+`/`@` as a formula; a leading `-` is just a number. */
    @Test
    fun formulaLeadingFieldsAreNeutralised_butNegativeNumbersAreNot() {
        val csv = LedgerExport.rowsCsv(
            LedgerExport.rows(
                invoices = listOf(invoice(1, gross = 100, vat = 0).copy(stripeInvoiceId = "=cmd()")),
                creditNotes = listOf(creditNote(1, gross = 595, vat = 95)),
            ),
        )
        assertTrue("'=cmd()" in csv, "a formula-leading field is prefixed")
        assertTrue("-500,-95,-595" in csv, "negative amounts stay plain numbers")
    }

    @Test
    fun bothCsvsCarryTheirHeaderAndCrlfLineEndings() {
        val rowsCsv = LedgerExport.rowsCsv(emptyList())
        assertEquals("document_type,number,issued_at,country,second_evidence_country,currency," +
            "net_minor,vat_minor,gross_minor,vat_rate_bps,reference,flags\r\n", rowsCsv)
        val summaryCsv = LedgerExport.summaryCsv(emptyList())
        assertEquals("country,currency,net_minor,vat_minor,gross_minor,documents,note\r\n", summaryCsv)
    }
}
