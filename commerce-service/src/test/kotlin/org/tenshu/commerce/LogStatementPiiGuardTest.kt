package org.tenshu.commerce

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ **No personal data in commerce's log** (`docs/how-to/server-logging.md`). Commerce is the
 * plane that holds a person's email beside their owner id (A28), so this is the twin that matters. A source scan, the
 * cheap kind: every log statement in `src/main/kotlin` — a logger's `trace/debug/info/warn/error`,
 * a bare `log(…)` sink, `println` — is parsed, its string-literal text dropped and its
 * *interpolated code* kept, and that code must not name an email, a raw owner/account id or a
 * secret. An address is fine through `emailDomain`/`redactEmails`, an id through `shortId`, an
 * exception whose message may echo an address through `describeForLog`; a secret never.
 *
 * It reads identifiers, not values, so it is a tripwire, not a proof: `log.info("{}", x)` where
 * `x` happens to hold an owner id passes. What it does catch is the shape every PII line found by
 * the 2026-10 audit had — `"OTP email to {}", email`, `"[admin] {} account {}", action, email`.
 *
 * Escape hatch, for a line that must carry one: a trailing `// log-pii-ok: <reason>` on the call's
 * first line. Two today, both the dev-only `LoggingEmailSender` printing the code it exists to print;
 * adding one is a decision a reviewer should see.
 *
 * Twin: `sync-relay-server/.../LogStatementPiiGuardTest.kt` (owner/device ids, pairing secrets).
 */
class LogStatementPiiGuardTest {

    private val scanner = LogPiiScanner(
        sensitive = Regex(
            """\b(?:\w*[Ee]mail(?:s|Normali[sz]ed|Address)?|ownerId|\w*OwnerId|accountId|account\.id|\w*[Ss]ecret|\w*[Tt]oken|clientIp|customerId|stripeCustomerId|\w*[Cc]odeSha256\w*|\w*[Cc]odeHash|\w*[Hh]macKey|candidate|expectedMacs?|\w+\.code|code)\b""",
        ),
        // ⚠ `remoteHost` is deliberately NOT sensitive here: the [admin] audit lines record the
        // OPERATOR's address on the admin plane (off public DNS), which is what an access audit is for.
        redactors = setOf("emailDomain", "emailTag", "redactEmails", "shortId", "describeForLog"),
    )

    @Test
    fun noLogStatementInMainSourcesInterpolatesPersonalDataOrSecrets() {
        val root = moduleDir("commerce-service").resolve("src/main/kotlin")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 20, "found only ${files.size} sources under $root — the walk has rotted")

        val calls = files.flatMap { file -> scanner.logCalls(file.readText()).map { file to it } }
        // The scan must actually be seeing the log statements, or it passes by finding nothing.
        assertTrue(calls.size > 25, "parsed only ${calls.size} log calls — the call regex has rotted")

        val offenders = calls.mapNotNull { (file, call) ->
            scanner.offence(call)?.let { "${file.relativeTo(root)}:${call.line}: names `$it` in: ${call.text.lines().first().trim()}" }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "Log statements naming personal data or a secret (see docs/how-to/server-logging.md; " +
                    "an address through emailDomain(…), an id through shortId(…), a throwable whose " +
                    "message names an address through describeForLog(…); drop a secret entirely):\n" + offenders.joinToString("\n"),
            )
        }
    }

    // The detector itself — a guard that cannot fire is worse than none.

    @Test
    fun theScannerFlagsInterpolatedAndArgumentIdentifiers() {
        val flagged = listOf(
            """logger.error("Failed to send OTP email to {}", email, e)""",
            """application.log.warn("[admin] {} account {} from {}", action, email, request.local.remoteHost)""",
            """println("account ${'$'}{account.emailNormalised} closed")""",
            """appLogger.info("sent to " + customerEmail)""",
            """log.warn("cancel failed for {}", account.id)""",
            """System.err.println("owner ${'$'}ownerId")""",
            // The store-review code's hash (T-CB-12): only ever compared, never printed.
            """println("review hash ${'$'}{config.reviewSignInCodeSha256}")""",
            """log.info("expected {}", verification.codeHash)""",
            // A one-time or review code, however it is reached.
            """log.info("tried {}", candidate)""",
            """log.info("want {}", expectedMacs)""",
            """log.info("got {}", request.code)""",
            """println("code ${'$'}code")""",
        )
        for (source in flagged) {
            val calls = scanner.logCalls(source)
            assertEquals(1, calls.size, "did not parse one call out of: $source")
            assertTrue(scanner.offence(calls.single()) != null, "should have flagged: $source")
        }
    }

    @Test
    fun theScannerPassesRedactedIdsAndPlainWords() {
        val clean = listOf(
            """logger.error("Failed to send OTP email to {}: {}", emailDomain(email), describeForLog(e))""",
            """application.log.warn("[admin] {} account {} from {}", action, shortId(account.id), request.local.remoteHost)""",
            """println("Commerce OTP budgets: ${'$'}{config.otpSendPerEmailPerHour}/email/h")""",
            """log.warn("Stripe event missing email or product_id; skipping grant")""",
            """println("x") // log-pii-ok: fixture""" + "\n" + """println(email) // log-pii-ok: fixture""",
        )
        for (source in clean) {
            val calls = scanner.logCalls(source)
            assertTrue(calls.isNotEmpty(), "did not parse a call out of: $source")
            calls.forEach { assertEquals(null, scanner.offence(it), "should have passed: $source") }
        }
    }

    private fun moduleDir(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.name == name && dir.resolve("src/main").isDirectory) return dir
            dir.resolve(name).takeIf { it.resolve("src/main").isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        fail("could not find module $name walking up from ${System.getProperty("user.dir")}")
    }
}

/**
 * Finds log calls in Kotlin source and reduces each to the code it interpolates. A small hand lexer
 * rather than a regex, because the thing to tell apart — `"email"` the word vs `$email` the value —
 * is exactly the string-template boundary a regex cannot track.
 */
internal class LogPiiScanner(private val sensitive: Regex, private val redactors: Set<String>) {

    class Call(val line: Int, val text: String, val code: String, val exempt: Boolean)

    fun logCalls(source: String): List<Call> = CALL_START.findAll(source).mapNotNull { match ->
        val open = match.range.last
        val end = matchingParen(source, open) ?: return@mapNotNull null
        val text = source.substring(match.range.first, end + 1)
        val line = source.substring(0, match.range.first).count { it == '\n' } + 1
        val firstLine = source.substring(match.range.first).substringBefore('\n')
        Call(line, text, codeView(source.substring(open, end + 1)), firstLine.contains("log-pii-ok:"))
    }.toList()

    /** The first sensitive identifier the call's code names outside a redactor, or null. */
    fun offence(call: Call): String? {
        if (call.exempt) return null
        return sensitive.find(blankRedactorCalls(call.code))?.value
    }

    private fun blankRedactorCalls(code: String): String {
        var result = code
        for (name in redactors) {
            while (true) {
                val at = Regex("""\b${Regex.escape(name)}\(""").find(result) ?: break
                val close = matchingParen(result, at.range.last) ?: break
                result = result.substring(0, at.range.first) + " ".repeat(close + 1 - at.range.first) + result.substring(close + 1)
            }
        }
        return result
    }

    /** Index of the `)` closing the `(` at [open], skipping strings, chars and comments. */
    private fun matchingParen(s: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < s.length) {
            when {
                s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                s.startsWith("\"\"\"", i) -> i = skipString(s, i, raw = true)
                s[i] == '"' -> i = skipString(s, i, raw = false)
                s[i] == '\'' -> i = skipChar(s, i)
                s[i] == '(' -> { depth++; i++ }
                s[i] == ')' -> { depth--; if (depth == 0) return i; i++ }
                else -> i++
            }
        }
        return null
    }

    private fun skipChar(s: String, start: Int): Int {
        var i = start + 1
        while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
        return i + 1
    }

    /** Index just past the string literal opening at [start]; `${…}` bodies are skipped as code. */
    private fun skipString(s: String, start: Int, raw: Boolean): Int {
        var i = start + if (raw) 3 else 1
        while (i < s.length) {
            when {
                raw && s.startsWith("\"\"\"", i) -> return i + 3
                !raw && s[i] == '\\' -> i += 2
                !raw && s[i] == '"' -> return i + 1
                s.startsWith("\${", i) -> i = skipBraces(s, i + 1)
                else -> i++
            }
        }
        return i
    }

    private fun skipBraces(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when {
                s.startsWith("\"\"\"", i) -> i = skipString(s, i, raw = true)
                s[i] == '"' -> i = skipString(s, i, raw = false)
                s[i] == '{' -> { depth++; i++ }
                s[i] == '}' -> { depth--; i++; if (depth == 0) return i }
                else -> i++
            }
        }
        return i
    }

    /** [call] with string-literal text blanked and template expressions (`$x`, `${…}`) kept. */
    private fun codeView(call: String): String {
        val out = StringBuilder()
        fun string(start: Int, raw: Boolean): Int {
            var i = start + if (raw) 3 else 1
            out.append(' ')
            while (i < call.length) {
                when {
                    raw && call.startsWith("\"\"\"", i) -> return i + 3
                    !raw && call[i] == '\\' -> i += 2
                    !raw && call[i] == '"' -> return i + 1
                    call.startsWith("\${", i) -> {
                        val end = skipBraces(call, i + 1)
                        out.append(' ').append(codeView(call.substring(i + 2, end - 1))).append(' ')
                        i = end
                    }
                    call[i] == '$' && i + 1 < call.length && (call[i + 1].isLetter() || call[i + 1] == '_') -> {
                        var j = i + 1
                        while (j < call.length && (call[j].isLetterOrDigit() || call[j] == '_')) j++
                        out.append(' ').append(call, i + 1, j).append(' ')
                        i = j
                    }
                    else -> i++
                }
            }
            return i
        }
        var i = 0
        while (i < call.length) {
            when {
                call.startsWith("//", i) -> i = call.indexOf('\n', i).let { if (it < 0) call.length else it }
                call.startsWith("\"\"\"", i) -> i = string(i, raw = true)
                call[i] == '"' -> i = string(i, raw = false)
                else -> { out.append(call[i]); i++ }
            }
        }
        return out.toString()
    }

    private companion object {
        /**
         * A logger method (`x.info(`), a bare `log(` sink (`ContentSweepJob`'s `(String) -> Unit`) or
         * `println(` — not `.log(`, which is a method on something else (`RendezvousStore`).
         */
        val CALL_START = Regex(
            """\.(?:trace|debug|info|warn|error)\(|System\.(?:err|out)\.print(?:ln)?\(|(?<![\w.])(?:log|println|print)\(""",
        )
    }
}
