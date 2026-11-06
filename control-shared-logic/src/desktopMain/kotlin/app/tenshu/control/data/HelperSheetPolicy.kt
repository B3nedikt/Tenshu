package app.tenshu.control.data

/**
 * Did the macOS keychain helper actually put an **auth sheet on screen**?
 *
 * ## ⚠⚠ Why this is a whitelist and not `token != "unavailable"`
 *
 * The helper is a bare binary with no bundle identity, so macOS gives it the foreground to present
 * the sheet and does **not** hand activation back when it exits — Control's window drops behind
 * whatever is frontmost, which every reporter so far has described as the app closing
 * (`control-keep-device-unlocked.md` §6.1). The cure is to ask for the foreground afterwards, and
 * the whole question is *when*.
 *
 * The two mistakes are not symmetric:
 *  - **Missing a raise** leaves the known bug in one path.
 *  - **A spurious raise** is a *new* bug — Control shoving itself in front of a user who was never
 *    interrupted — and it would land on the common desktop configuration, not an edge. Under ad-hoc
 *    signing `enroll` fails at `SecItemAdd` **before** anything is presented, so "raise unless the
 *    token says unavailable" would fire on every failed enrolment with no sheet in sight.
 *
 * So this answers only from outcomes that **positively imply** a sheet, read off the helper's own
 * emissions in `control-desktop/macos/KeychainHelper/main.swift`:
 *
 * | verb | sheet | no sheet |
 * |------|-------|----------|
 * | `authenticate` | `ok`, `cancelled`, `failure:*` — all reachable only *past* the `canEvaluatePolicy` guard, so the sheet was up; a fumbled fingerprint is a `failure:` and still needs the raise | `unavailable` (the guard itself) |
 * | `enroll` | `ok`, `cancelled` — `SecItemAdd` prompts via `.userPresence` + the auth context | `unavailable`; every `failure:*` (bad stdin, access-control creation, and the missing-entitlement refusal ad-hoc signing produces) precedes the prompt |
 * | `unwrap` | `ok:*`, `cancelled` — `SecItemCopyMatching` prompts the same way | `not_found` (nothing to authenticate against), `unavailable`, `failure:*`, and `invalidated` — an ACL that no longer matches is refused without prompting |
 *
 * ⚠ **No answer at all ([token] `null`) never raises.** It conflates a helper that failed to start
 * (no sheet) with one that timed out (sheet up, unanswered for two minutes), and neither is worth
 * stealing focus for: the first was never interrupted, and the second walked away long ago.
 *
 * ⚠ Metadata verbs (`is-available`, `has-enrolled`, `clear`) present nothing and are absent here by
 * construction — an unknown verb answers `false`.
 */
internal fun helperSheetWasPresented(command: String, token: String?): Boolean {
    val t = token ?: return false
    return when (command) {
        "authenticate" -> t == "ok" || t == "cancelled" || t.startsWith("failure:")
        "enroll" -> t == "ok" || t == "cancelled"
        "unwrap" -> t.startsWith("ok:") || t == "cancelled"
        else -> false
    }
}
