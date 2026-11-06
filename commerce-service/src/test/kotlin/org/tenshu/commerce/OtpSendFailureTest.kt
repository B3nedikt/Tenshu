package org.tenshu.commerce

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.commerce.store.InMemoryCommerceStore
import org.tenshu.commerce.store.Rail
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the account endpoints answer when the mail transport refuses the message —
 * `commerce-otp-delivery-feedback.md` §2, the **synchronous** half. A send the relay accepts and
 * later bounces is invisible here by construction; that is §3 of the same doc.
 *
 * `/register` may say `send_failed` only because it creates the account when it is absent, so the
 * answer says nothing about whether the address was already known.
 */
class OtpSendFailureTest {

    /** Fails the way a refusing SMTP relay does: synchronously, out of `sendOtp`. */
    private class FailingEmailSender : EmailSender {
        var calls = 0
            private set

        override fun sendOtp(email: String, code: String) {
            calls++
            throw IllegalStateException("smtp relay refused the message")
        }

        override fun sendAccountDeletionCode(email: String, code: String) {
            calls++
            throw IllegalStateException("smtp relay refused the message")
        }

        override fun sendNoAccountNotice(email: String) {
            calls++
            throw IllegalStateException("smtp relay refused the message")
        }

        override fun sendSignupsPausedNotice(email: String) {
            calls++
            throw IllegalStateException("smtp relay refused the message")
        }
    }

    private val store = InMemoryCommerceStore()
    private val sender = FailingEmailSender()
    private val clock = AtomicLong(1_000_000L)

    private fun appTest(block: suspend (HttpClient) -> Unit) = testApplication {
        application {
            module(
                store = store,
                emailSender = sender,
                otpTtlMs = 60_000L,
                otpMaxAttempts = 3,
                sessionTtlMs = 3_600_000L,
                now = { clock.get() },
            )
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String) = post("/commerce/account/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email","tosVersion":"v1","privacyVersion":"v1"}""")
    }

    @Test
    fun register_answersSendFailed_ratherThanAContentFreeInternalError() = appTest { client ->
        val response = client.register("alice@example.com")

        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            response.status,
            "a refused send is a transport failure the caller can act on, not an internal_error",
        )
        assertTrue(
            response.bodyAsText().contains("send_failed"),
            "the client maps this to 'try again in a moment'; got ${response.bodyAsText()}",
        )
    }

    @Test
    fun register_stillRecordsTheAccount_soARetryDoesNotStartOver() = appTest { client ->
        client.register("alice@example.com")

        // The account and its acceptance row are written before the send is attempted. Keeping
        // them is deliberate: the contract was formed, and a retry should find the account rather
        // than create a second one.
        assertTrue(store.findAccountByEmail("alice@example.com") != null)
    }

}
