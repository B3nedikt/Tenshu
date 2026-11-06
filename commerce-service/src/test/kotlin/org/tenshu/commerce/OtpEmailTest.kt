package org.tenshu.commerce

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMultipart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OTP message: its copy, and the [SmtpEmailSender] envelope built around it. The transport is
 * injected, so this exercises the real Jakarta Mail construction without a live SMTP server —
 * everything up to the socket.
 */
class OtpEmailTest {

    private val smtp = SmtpConfig(
        host = "smtp.example.test",
        port = 587,
        username = "user",
        password = "secret",
        fromAddress = "no-reply@tenshu.app",
        fromName = "Tenshu",
        startTls = true,
        ttlMinutes = 15,
    )

    @Test
    fun theCodeIsInTheBodyAndNeverInTheSubject() {
        // The OTP is the ONLY factor in this account model, so a code rendered in a lock-screen
        // notification preview would defeat the possession check outright. Body-only is the
        // deliberate choice; this test is what stops a later "nicer UX" change undoing it.
        assertFalse(OtpEmail.SUBJECT.contains("123456"))
        assertFalse(OtpEmail.SUBJECT.any { it.isDigit() }, "no digits at all in the subject")
        assertTrue(OtpEmail.text("123456", 15).contains("123456"))
        assertTrue(OtpEmail.html("123456", 15).contains("123456"))
    }

    @Test
    fun bothBodiesStateTheRealExpiryWindow() {
        assertTrue(OtpEmail.text("123456", 7).contains("7 minutes"))
        assertTrue(OtpEmail.html("123456", 7).contains("7 minutes"))
    }

    @Test
    fun bothBodiesTellAnUninvolvedRecipientNothingHappened() {
        // Someone who did not request this must be told that ignoring it is safe — it is the only
        // thing that makes an unsolicited OTP mail non-alarming, and it is required by every
        // reputable-sender guideline we will be judged against.
        assertTrue(OtpEmail.text("123456", 15).contains("ignore this email"))
        assertTrue(OtpEmail.html("123456", 15).contains("ignore this email"))
    }

    @Test
    fun buildsAMultipartMessageAddressedFromTheConfiguredSender() {
        val sender = SmtpEmailSender(smtp, transport = { _, _ -> })
        val message = sender.buildMessage("alice@example.com", "424242")

        assertEquals(OtpEmail.SUBJECT, message.subject)
        assertEquals("alice@example.com", (message.allRecipients.single() as InternetAddress).address)
        val from = message.from.single() as InternetAddress
        assertEquals("no-reply@tenshu.app", from.address)
        assertEquals("Tenshu", from.personal)

        val multipart = message.content as MimeMultipart
        assertEquals(2, multipart.count, "text + html alternatives")
        // Order is load-bearing: a client renders the LAST part it understands, so HTML must come
        // second for it to win and text to remain the fallback.
        assertTrue(multipart.getBodyPart(0).contentType.startsWith("text/plain"))
        assertTrue(multipart.getBodyPart(1).contentType.startsWith("text/html"))
        assertTrue((multipart.getBodyPart(0).content as String).contains("424242"))
    }

    @Test
    fun aSendFailurePropagatesRatherThanSilentlyClaimingSuccess() {
        val sender = SmtpEmailSender(smtp, transport = { _, _ -> error("smtp down") })
        val failure = runCatching { sender.sendOtp("alice@example.com", "424242") }.exceptionOrNull()
        // Swallowing this would answer the caller `202 otp_sent` when no code is coming.
        assertTrue(failure != null, "a transport failure must not be swallowed")
    }
}
