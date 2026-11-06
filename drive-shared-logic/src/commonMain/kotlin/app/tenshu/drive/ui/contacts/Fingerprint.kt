package app.tenshu.drive.ui.contacts

/**
 * Display form of a full-hex identity fingerprint: the leading 15 hex chars
 * as `XXXXX-YYYYY-ZZZZZ` (uppercased) — the convention pinned in
 * `drive-manage-access-ux.md` §2.3. The stored key stays full-length; this is
 * presentation only.
 */
fun displayFingerprint(fingerprintHex: String): String =
    fingerprintHex
        .take(15)
        .uppercase()
        .chunked(5)
        .joinToString("-")
