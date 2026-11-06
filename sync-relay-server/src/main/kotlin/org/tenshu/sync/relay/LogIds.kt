package org.tenshu.sync.relay

/**
 * The form an identifier takes in a relay log line: its first [LOG_ID_PREFIX] characters and an
 * ellipsis, never the whole value.
 *
 * ⚠ An owner id is pseudonymous on this box, but commerce stores it beside the account's email
 * (A28), so the full value in a log is a join key to a person. A prefix still correlates the lines
 * of one incident and still finds the row (`WHERE owner_id LIKE '<prefix>%'`), which is all a log
 * line needs. Device ids get the same treatment. Secrets (pairing secrets, tokens) are never
 * logged at all, prefixed or not. Policy: `docs/how-to/server-logging.md`.
 */
internal fun logId(id: String?): String = when {
    id.isNullOrEmpty() -> "-"
    else -> id.take(LOG_ID_PREFIX) + "…"
}

internal const val LOG_ID_PREFIX = 8
