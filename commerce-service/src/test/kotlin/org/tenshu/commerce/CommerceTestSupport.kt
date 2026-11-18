package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.CreditNote
import org.tenshu.commerce.store.Invoice
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import org.tenshu.commerce.routing.VerifyResponse
import java.util.concurrent.ConcurrentHashMap

private val testJson = Json { ignoreUnknownKeys = true }

/** Test [EmailSender] that captures the last OTP per email so a test can complete /verify. */
class RecordingEmailSender : EmailSender {
    private val codes = ConcurrentHashMap<String, String>()

    /** Every recipient mailed anything, in order — for "no mail at all" assertions. */
    val recipients: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    override fun sendOtp(email: String, code: String) {
        recipients += email.trim().lowercase()
        codes[email.trim().lowercase()] = code
    }

    fun lastCode(email: String): String? = codes[email.trim().lowercase()]

    private val deletionCodes = ConcurrentHashMap<String, String>()

    override fun sendAccountDeletionCode(email: String, code: String) {
        recipients += email.trim().lowercase()
        deletionCodes[email.trim().lowercase()] = code
    }

    /** Addresses that were sent the "sign-ups are paused" notice, in order. */
    val signupsPausedNotices: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    override fun sendSignupsPausedNotice(email: String) {
        recipients += email.trim().lowercase()
        signupsPausedNotices += email.trim().lowercase()
    }

    /** The last account-DELETION code mailed to [email] — never a sign-in code. */
    fun lastDeletionCode(email: String): String? = deletionCodes[email.trim().lowercase()]

    /**
     * Drop the captured code for [email], so a following assertion that **no** mail was sent is
     * meaningful rather than passing on a leftover from an earlier request in the same test.
     */
    fun forget(email: String) {
        codes.remove(email.trim().lowercase())
        deletionCodes.remove(email.trim().lowercase())
    }
}

/** Register → read the captured OTP → verify, returning the issued session token. */
suspend fun HttpClient.registerAndVerify(
    sender: RecordingEmailSender,
    email: String,
    ownerId: String,
): String {
    post("/commerce/account/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")
    }
    val code = sender.lastCode(email) ?: error("no OTP captured for $email")
    val response = post("/commerce/account/verify") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","code":"$code","ownerId":"$ownerId"}""")
    }
    return testJson.decodeFromString<VerifyResponse>(response.bodyAsText()).sessionToken
}

/**
 * The whole statutory invoice ledger, for assertions. Replaces the old
 * `InMemoryCommerceStore.invoices()` helper (WC-2): that read the in-memory list directly and so
 * existed only on one backend, which is precisely the shape that lets a test pass against a store
 * method production cannot call. This goes through the real [CommerceStore.listInvoices].
 */
fun CommerceStore.allInvoices(): List<Invoice> = listInvoices(0, Long.MAX_VALUE)

/** The whole credit-note ledger, for assertions. See [allInvoices]. */
fun CommerceStore.allCreditNotes(): List<CreditNote> = listCreditNotes(0, Long.MAX_VALUE)
