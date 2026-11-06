package org.tenshu.commerce

/**
 * The forms personal data may take in a commerce log line. Policy: `docs/how-to/server-logging.md`.
 *
 * Commerce is the one plane that holds a person's email beside their owner id (A28), so its log is
 * held to "nothing that names a person": an address appears as its domain only, an account as a
 * short prefix of its id (enough to find the row, `WHERE id LIKE '<prefix>%'`, and to correlate
 * one incident's lines), and an exception as its class and redacted message. The OTP code is never
 * logged outside the dev-only [LoggingEmailSender].
 *
 * ⚠ `LogStatementPiiGuardTest` fails the build when a log statement interpolates an email (or a raw
 * owner/account id) that has not been through one of these.
 */

/** `***@example.com` — the domain is what an SMTP or deliverability problem is diagnosed by. */
internal fun emailDomain(email: String?): String {
    val domain = email?.substringAfterLast('@', missingDelimiterValue = "")?.trim().orEmpty()
    return if (domain.isEmpty()) "***" else "***@$domain"
}

/**
 * `***@example.com#1a2b3c4d` — [emailDomain] plus the first 8 hex of SHA-256 over the trimmed,
 * lower-cased address, so two sign-ups on one domain can be told apart without the address being
 * in the log. Recompute it for an address you have: `printf %s 'jane@example.com' | shasum -a 256 | cut -c1-8`.
 * ⚠ Only for the dev [LoggingEmailSender]: a hash of a low-entropy address is reversible by guessing,
 * so it is a pseudonym, not anonymisation, and production lines use [emailDomain] alone.
 */
internal fun emailTag(email: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(email.trim().lowercase().toByteArray(Charsets.UTF_8))
    return emailDomain(email) + "#" + digest.take(4).joinToString("") { "%02x".format(it) }
}

/** The first [SHORT_ID_LENGTH] characters of an account / owner id, and an ellipsis. */
internal fun shortId(id: String?): String =
    if (id.isNullOrEmpty()) "-" else id.take(SHORT_ID_LENGTH) + "…"

/** [text] with every e-mail address reduced to [emailDomain]'s form. */
internal fun redactEmails(text: String?): String =
    EMAIL_ADDRESS.replace(text.orEmpty()) { "***@" + it.groupValues[1] }

/**
 * A throwable as a log line may carry it: each class in the cause chain with its message, emails
 * redacted. Used in place of the stack trace where the exception's *message* is known to echo an
 * address — an SMTP `SendFailedException` names the rejected recipient.
 */
internal fun describeForLog(failure: Throwable): String =
    generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSES)
        .joinToString(" <- ") { "${it.javaClass.name}: ${redactEmails(it.message)}" }

private const val SHORT_ID_LENGTH = 8
private const val MAX_CAUSES = 5
private val EMAIL_ADDRESS = Regex("""[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")
