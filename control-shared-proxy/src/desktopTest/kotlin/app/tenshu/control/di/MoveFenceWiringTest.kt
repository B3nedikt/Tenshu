package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit over all three `PlatformProxyModule`s: **D3's offline-move refusal
 * (`MoveNeedsLocalBytesError`) is inert unless every platform passes `isOffline`**
 * (`content-plane-cr2-b6-spec.md` §3).
 *
 * ⚠⚠ The seam defaults to **online**, deliberately — an unwired runtime then never refuses a move.
 * That is the right default and the reason this test exists: omitting the argument compiles, runs,
 * passes every other test, and silently lets an offline device move content it cannot repair.
 *
 * ⚠ Same failure shape as [ThumbnailBackfillWiringTest], [MintOnViewWiringTest] and
 * [ReclamationRecorderWiringTest]: an absent argument at a call site, visible only in source text.
 *
 * ⚠⚠ **iOS is included here, unlike the thumbnail backfill's audit.** Route 1 is where a move is
 * most likely to be made offline — a phone on a train is §6d's own motivating example — so an
 * iPhone is the last platform that should be missing the refusal.
 */
class MoveFenceWiringTest {

    @Test
    fun `every platform passes isOffline, or D3's refusal never fires`() {
        val violations = MODULES.flatMap { (platform, path) ->
            val module = File(path)
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val text = module.readText()
            buildList {
                val args = argumentsOf(text, "fileServiceProxyImplementationFactory(")
                if (args == null) {
                    add("$platform: no fileServiceProxyImplementationFactory invocation found — the scan is stale.")
                } else {
                    if ("isOffline =" !in args) {
                        add(
                            "$platform: fileServiceProxyImplementationFactory is invoked without `isOffline =`, " +
                                "so MoveNeedsLocalBytes always sees offline = false and no offline move is ever refused.",
                        )
                    }
                    // ⚠⚠ Without the acknowledged round-trip the refusal trusts a verdict up to
                    // 45 s old, and a move made after the link dropped inside that window goes through
                    // unrefused. Null keeps exactly that behaviour, so omitting it compiles and passes.
                    if ("confirmWritable = { get<SyncRelay>().confirmWritableForFencing(get<SyncHealthHolder>().current()) }" !in withoutComments(args)) {
                        add(
                            "$platform: fileServiceProxyImplementationFactory is invoked without " +
                                "`confirmWritable = { get<SyncRelay>().confirmWritableForFencing(get<SyncHealthHolder>().current()) }`, so the " +
                                "move refusal trusts a cached verdict up to 45 s old.",
                        )
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "D3's offline-move refusal is not wired:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `the fence is not wired to caughtUp`() {
        // ⚠⚠ `caughtUp` is an INBOUND property and its own contract forbids this reading — "read it
        // as 'has everything', never as 'is online'". A device connected but mid-backlog is
        // caughtUp = false while perfectly able to push, so fencing on it would defer moves through
        // every ordinary catch-up. The mistake reads plausibly, which is why it is a test rather
        // than a note.
        val violations = MODULES.mapNotNull { (platform, path) ->
            val args = argumentsOf(File(path).readText(), "fileServiceProxyImplementationFactory(")
                ?.let(::withoutComments)
            if (args != null && "caughtUp" in args) {
                "$platform: the fence is fed `caughtUp`, which is about consuming the log, not about reaching the relay."
            } else null
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `the fence is not wired to the smoothed banner signal`() {
        // ⚠⚠ `SyncHealthFigures.online` is a BANNER verdict: optimistic before the first relay
        // attempt and smoothed across RelayReachability.CONFIRM_MS (20s) so one bad tick never
        // flickers the UI. A fence reading it waves moves through for the whole 20 seconds after the
        // link drops — precisely the window where the write reaches nobody and the bytes are lost.
        // RelayReachability's KDoc already warns that callers needing the real transition must not
        // use the smoothed verdict; this is the machine-checked half of that warning.
        //
        // The guard is on `.online` rather than on the absence of the helper, because the failure
        // it pins is a *plausible-looking* one-liner, not a missing argument.
        val violations = MODULES.mapNotNull { (platform, path) ->
            val args = argumentsOf(File(path).readText(), "fileServiceProxyImplementationFactory(")
                ?.let(::withoutComments)
            val fence = args?.let { fenceLambdaOf(it) }
            when {
                fence == null ->
                    "$platform: no `isOffline =` argument found — the scan is stale."
                "relayUnreachableForFencing()" !in fence ->
                    "$platform: the fence does not use relayUnreachableForFencing(); it reads `$fence`."
                ".online" in fence ->
                    "$platform: the fence reads the smoothed banner signal `online`, which stays true " +
                        "for 20s after the relay becomes unreachable."
                else -> null
            }
        }

        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    private companion object {
        val MODULES = listOf(
            "Android" to "src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
            "desktop" to "src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
            "iOS" to "src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
        )


        /**
         * ⚠⚠ Comments stripped before scanning for a forbidden identifier. The wiring's own note
         * says "deliberately NOT `caughtUp`" — and without this the guard fires on the sentence
         * explaining why it exists, which is the surest way to teach someone to delete it.
         */
        fun withoutComments(text: String): String =
            text.lineSequence().joinToString("\n") { it.substringBefore("//") }

        /** The text of the `isOffline = ...` argument, up to the next top-level comma. */
        fun fenceLambdaOf(args: String): String? {
            val at = args.indexOf("isOffline =").takeIf { it >= 0 } ?: return null
            var depth = 0
            var i = at
            while (i < args.length) {
                when (args[i]) {
                    '{', '(' -> depth++
                    '}', ')' -> depth--
                    ',' -> if (depth <= 0) return args.substring(at, i)
                }
                i++
            }
            return args.substring(at)
        }

        /** Arguments of the first `call(` in [text], by paren balance. */
        fun argumentsOf(text: String, call: String): String? {
            val start = Regex("(^|[^A-Za-z0-9_])" + Regex.escape(call)).find(text)?.range?.last ?: return null
            var depth = 1
            var i = start + 1
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            return text.substring(start + 1, (i - 1).coerceAtLeast(start + 1))
        }
    }
}
