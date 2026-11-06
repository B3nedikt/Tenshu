package org.tenshu.commerce

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Outbound transactional email seam (`commerce-accounts-and-sessions.md` §9.2). OTP delivery is
 * the only message this sends today; receipts / expiry notices ride the same seam later.
 * These run on the GDPR Art. 6(1)(b) contractual basis — no marketing consent (§5.1).
 *
 * Two impls: [LoggingEmailSender] (dev, prints the code) and [SmtpEmailSender] (real transport,
 * `commerce-end-to-end-dry-run.md` CE-2 / D3). SMTP is deliberately the *first* real impl because
 * it is the one protocol every provider speaks — Resend, Postmark, SES, Mailgun, a self-hosted
 * relay — so choosing a vendor stays a deployment decision rather than a code change.
 */
interface EmailSender {
    fun sendOtp(email: String, code: String)

    /**
     * The code that confirms an **account deletion** (the `/commerce/account/delete` routes). Its own copy,
     * not [sendOtp]'s: the recipient must be able to tell from the mail that typing this code
     * deletes their account, and that ignoring it keeps the account.
     */
    fun sendAccountDeletionCode(email: String, code: String)

    /**
     * Answer to a deletion request for an address **no account uses**. Sent so the request is
     * answered the same way — a mail, after the same SMTP round trip — whether or not the account
     * exists; a route that mailed only known addresses would be an enumeration oracle by timing.
     */
    fun sendNoAccountNotice(email: String)

    /**
     * Answer to a sign-up while the operator's signup cap is reached (`SIGNUP_LIMIT_*`). Sent in
     * place of a code, after the same SMTP round trip, so `/register` answers a new address and an
     * existing one alike — the route never becomes an enumeration oracle, cap or no cap.
     */
    fun sendSignupsPausedNotice(email: String)
}

/**
 * Dev / default [EmailSender] — logs the OTP instead of sending mail. **Never deploy this with a
 * public registration surface**: it prints the verification secret to the server log, which for an
 * OTP-only account model is the whole credential. `CommerceConfig.validate()` refuses to start with
 * this sender when `DEV_MODE=false`, the same treatment the committed dev signing seed gets.
 */
class LoggingEmailSender : EmailSender {
    private val logger = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun sendOtp(email: String, code: String) {
        // Dev only (validate() refuses this sender when DEV_MODE=false). The code is the point of this
        // sender; the address is not, so it prints the domain plus a short hash (emailTag) that tells
        // two sign-ups on one domain apart.
        logger.info("[dev EmailSender] OTP for {} = {} (configure SMTP_* in prod)", emailTag(email), code) // log-pii-ok: dev-only sender (validate() refuses it when DEV_MODE=false); printing the code is its job
    }

    override fun sendAccountDeletionCode(email: String, code: String) {
        logger.info("[dev EmailSender] account-DELETION code for {} = {}", emailTag(email), code) // log-pii-ok: dev-only sender (validate() refuses it when DEV_MODE=false); printing the code is its job
    }

    override fun sendSignupsPausedNotice(email: String) {
        logger.info("Signups paused notice to {} (not sent: dev sender)", emailDomain(email))
    }

    override fun sendNoAccountNotice(email: String) {
        logger.info("[dev EmailSender] deletion requested for {}, which has no account", emailTag(email))
    }
}

/**
 * Real SMTP transport over Jakarta Mail.
 *
 * ⚠ **The code never reaches a log here.** Failures log the recipient's *domain* and the SMTP
 * error only (its message redacted: a `SendFailedException` names the rejected address); the
 * verification secret stays in the message body. That asymmetry with [LoggingEmailSender] is
 * the point of having two classes rather than one with a flag.
 *
 * ⚠ **Sends synchronously on the request thread.** A slow SMTP server therefore shows up as a slow
 * `/register`. Accepted for v1 (volumes are tiny and the call is one round trip to a nearby relay);
 * the fix, if it ever bites, is a small outbound queue behind this same interface — which is why
 * the interface returns Unit and not a delivery receipt.
 *
 * A send failure propagates: the caller's StatusPages turns it into a content-free 500 and the user
 * retries. That is honest — the alternative (swallow it and answer `202 otp_sent`) would tell the
 * user a code is coming when none is.
 */
class SmtpEmailSender(
    private val config: SmtpConfig,
    /** Injectable so a test can assert on the built [MimeMessage] without a live SMTP server. */
    private val transport: (Session, MimeMessage) -> Unit = { _, message -> Transport.send(message) },
) : EmailSender {

    private val logger = LoggerFactory.getLogger(SmtpEmailSender::class.java)

    private val session: Session by lazy {
        val properties = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.starttls.enable", config.startTls.toString())
            // Refuse to fall back to plaintext once STARTTLS is asked for: a downgrade would put
            // the OTP — a live credential — on the wire in clear.
            put("mail.smtp.starttls.required", config.startTls.toString())
            put("mail.smtp.auth", config.hasCredentials.toString())
            put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.timeout", SMTP_TIMEOUT_MS.toString())
            put("mail.smtp.writetimeout", SMTP_TIMEOUT_MS.toString())
        }
        if (config.hasCredentials) {
            Session.getInstance(
                properties,
                object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(config.username, config.password)
                },
            )
        } else {
            Session.getInstance(properties)
        }
    }

    override fun sendOtp(email: String, code: String) = send(email, "OTP", buildMessage(email, code))

    override fun sendAccountDeletionCode(email: String, code: String) = send(
        email,
        "account-deletion code",
        buildMessage(
            email,
            AccountDeletionEmail.CODE_SUBJECT,
            AccountDeletionEmail.codeText(code, config.ttlMinutes),
            AccountDeletionEmail.codeHtml(code, config.ttlMinutes),
        ),
    )

    override fun sendNoAccountNotice(email: String) = send(
        email,
        "no-account notice",
        buildMessage(
            email,
            AccountDeletionEmail.NO_ACCOUNT_SUBJECT,
            AccountDeletionEmail.noAccountText(),
            AccountDeletionEmail.noAccountHtml(),
        ),
    )

    override fun sendSignupsPausedNotice(email: String) = send(
        email,
        "signups-paused notice",
        buildMessage(email, SignupPausedEmail.SUBJECT, SignupPausedEmail.text(), SignupPausedEmail.html()),
    )

    private fun send(email: String, what: String, message: MimeMessage) {
        try {
            transport(session, message)
        } catch (e: Exception) {
            // Recipient domain + cause, never the code and never the address. No stack trace: the
            // frames are Jakarta Mail's, and the messages down the chain echo the recipient.
            logger.error("Failed to send {} email to {}: {}", what, emailDomain(email), describeForLog(e))
            throw e
        }
    }

    /** Build the OTP [MimeMessage]. Internal so a test can inspect it without sending. */
    internal fun buildMessage(email: String, code: String): MimeMessage =
        buildMessage(email, OtpEmail.SUBJECT, OtpEmail.text(code, config.ttlMinutes), OtpEmail.html(code, config.ttlMinutes))

    internal fun buildMessage(email: String, subject: String, text: String, html: String): MimeMessage {
        val message = MimeMessage(session)
        message.setFrom(
            if (config.fromName != null) InternetAddress(config.fromAddress, config.fromName)
            else InternetAddress(config.fromAddress),
        )
        message.setRecipients(Message.RecipientType.TO, arrayOf<jakarta.mail.Address>(InternetAddress(email)))
        message.subject = subject
        // multipart/alternative: text first, HTML second — a client picks the last part it can
        // render, so the order is what makes HTML preferred and text the fallback.
        val multipart = MimeMultipart("alternative")
        multipart.addBodyPart(MimeBodyPart().apply { setText(text, "utf-8") })
        multipart.addBodyPart(
            MimeBodyPart().apply { setContent(html, "text/html; charset=utf-8") },
        )
        message.setContent(multipart)
        // ⚠ Load-bearing, not ceremony: `setContent` only attaches the DataHandler — the actual
        // Content-Type / MIME-Version headers are written by updateHeaders(), which Jakarta Mail
        // otherwise runs deep inside Transport.send. Calling it here means the message handed to
        // [transport] is fully formed whatever that transport does, so what a test inspects is
        // what a server would receive.
        message.saveChanges()
        return message
    }

    private companion object {
        const val SMTP_TIMEOUT_MS = 15_000
    }
}

/**
 * The OTP message body. Pure string building, kept out of [SmtpEmailSender] so the copy is
 * testable and reviewable without touching a transport.
 *
 * ⚠ **The code is deliberately NOT in the subject line.** Plenty of services put it there for
 * convenience, but they are usually sending a *second* factor. Here the OTP is the **only** factor
 * — there is no password — so a code visible in a lock-screen notification preview defeats the
 * possession check outright. Body-only costs one tap and closes that.
 *
 * English only for now: this is server-side copy, outside the client-side `stringResource` /
 * `t(…)` regime of ADR 0016, and there is no locale on the request to select from. When the account
 * record gains a locale, this is the seam that consumes it.
 */
object OtpEmail {

    const val SUBJECT = "Your Tenshu verification code"

    fun text(code: String, ttlMinutes: Long): String = """
        Your Tenshu verification code is:

            $code

        It expires in $ttlMinutes minutes and can be used once.

        If you didn't ask to sign in to Tenshu, you can ignore this email — no account
        has been created or changed, and nobody can use this code without it.

        — Tenshu
    """.trimIndent()

    fun html(code: String, ttlMinutes: Long): String = """
        <!doctype html>
        <html>
          <body style="font-family:system-ui,-apple-system,sans-serif;color:#1a1a1a;line-height:1.5">
            <p>Your Tenshu verification code is:</p>
            <p style="font-size:28px;font-weight:600;letter-spacing:0.15em;margin:24px 0">$code</p>
            <p>It expires in $ttlMinutes minutes and can be used once.</p>
            <p style="color:#666;font-size:13px">
              If you didn't ask to sign in to Tenshu, you can ignore this email — no account has
              been created or changed, and nobody can use this code without it.
            </p>
            <p style="color:#666;font-size:13px">— Tenshu</p>
          </body>
        </html>
    """.trimIndent()
}

/**
 * The two account-deletion messages. Same posture as [OtpEmail]: the code is **not** in the subject
 * (it is the only factor), English only, pure string building so the copy is reviewable.
 *
 * ⚠ The code mail says what the code does *before* giving it. Someone who receives it without
 * having asked must understand that ignoring it is the safe answer.
 */
object AccountDeletionEmail {

    const val CODE_SUBJECT = "Confirm deleting your Tenshu account"
    const val NO_ACCOUNT_SUBJECT = "Your Tenshu account deletion request"

    fun codeText(code: String, ttlMinutes: Long): String = """
        Someone asked to delete the Tenshu account that uses this email address.

        To delete it, enter this code:

            $code

        It expires in $ttlMinutes minutes and can be used once.

        Deleting the account cannot be undone. If you didn't ask for this, ignore this email —
        nothing is deleted without the code.

        — Tenshu
    """.trimIndent()

    fun codeHtml(code: String, ttlMinutes: Long): String = """
        <!doctype html>
        <html>
          <body style="font-family:system-ui,-apple-system,sans-serif;color:#1a1a1a;line-height:1.5">
            <p>Someone asked to delete the Tenshu account that uses this email address.</p>
            <p>To delete it, enter this code:</p>
            <p style="font-size:28px;font-weight:600;letter-spacing:0.15em;margin:24px 0">$code</p>
            <p>It expires in $ttlMinutes minutes and can be used once.</p>
            <p><strong>Deleting the account cannot be undone.</strong> If you didn't ask for this,
              ignore this email — nothing is deleted without the code.</p>
            <p style="color:#666;font-size:13px">— Tenshu</p>
          </body>
        </html>
    """.trimIndent()

    fun noAccountText(): String = """
        Someone asked to delete the Tenshu account that uses this email address, but no Tenshu
        account uses it — so there is nothing to delete.

        If you have a Tenshu account, it may use a different address.

        — Tenshu
    """.trimIndent()

    fun noAccountHtml(): String = """
        <!doctype html>
        <html>
          <body style="font-family:system-ui,-apple-system,sans-serif;color:#1a1a1a;line-height:1.5">
            <p>Someone asked to delete the Tenshu account that uses this email address, but no
              Tenshu account uses it — so there is nothing to delete.</p>
            <p>If you have a Tenshu account, it may use a different address.</p>
            <p style="color:#666;font-size:13px">— Tenshu</p>
          </body>
        </html>
    """.trimIndent()
}

/**
 * The mail a new address gets while sign-ups are paused by the operator's signup cap. No code, so
 * it can never be mistaken for a way in; same posture as [OtpEmail] otherwise.
 */
object SignupPausedEmail {

    const val SUBJECT = "Tenshu sign-ups are paused for now"

    fun text(): String = """
        Thanks for wanting to try Tenshu. We have paused new sign-ups for the moment, so no
        account was created for this email address.

        Please try again in a day or two. If you already have a Tenshu account, it may use a
        different address.

        If you didn't ask to sign up, you can ignore this email.

        — Tenshu
    """.trimIndent()

    fun html(): String = """
        <!doctype html>
        <html>
          <body style="font-family:system-ui,-apple-system,sans-serif;color:#1a1a1a;line-height:1.5">
            <p>Thanks for wanting to try Tenshu. We have paused new sign-ups for the moment, so no
              account was created for this email address.</p>
            <p>Please try again in a day or two. If you already have a Tenshu account, it may use a
              different address.</p>
            <p style="color:#666;font-size:13px">If you didn't ask to sign up, you can ignore this email.</p>
            <p style="color:#666;font-size:13px">— Tenshu</p>
          </body>
        </html>
    """.trimIndent()
}
