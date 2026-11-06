package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit over the Android and desktop `PlatformProxyModule`s: **the three seams W-1/W-2
 * hang off are all optional, all default to "do nothing", and all compile when omitted**
 * (`drive-synced-thumbnails.md` §15).
 *
 * - `OnDemandResolutionImpl(onPull = …)` — without it no device ever declares a class, so
 *   `wanted(realm)` stays empty and the backfill mints nothing, for ever, silently.
 * - `fileServiceProxyImplementationFactory(objectStore = …)` — without it the backfill op is a
 *   no-op (nothing to enumerate).
 * - `registerThumbnailBackfillSchedule()` — without it the op exists and nothing dispatches it.
 *
 * ⚠⚠ Same failure shape as [MotionThumbnailRendererWiringTest] and [ReclamationRecorderWiringTest]:
 * an absent argument at a call site, visible only in source text.
 */
class ThumbnailBackfillWiringTest {

    @Test
    fun `android and desktop wire the pull observer, the store and the schedule`() {
        val violations = MODULES.flatMap { (platform, path) ->
            val module = File(path)
            // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
            assertTrue(module.isFile, "the $platform PlatformProxyModule moved; this test is no longer guarding.")
            val text = module.readText()
            buildList {
                val resolverArgs = argumentsOf(text, "OnDemandResolutionImpl(")
                if (resolverArgs == null) add("$platform: no OnDemandResolutionImpl invocation found — the scan is stale.")
                else if ("onPull =" !in resolverArgs) add("$platform: OnDemandResolutionImpl is built without `onPull =`, so no pull ever declares a class.")

                val factoryArgs = argumentsOf(text, "fileServiceProxyImplementationFactory(")
                if (factoryArgs == null) add("$platform: no fileServiceProxyImplementationFactory invocation found — the scan is stale.")
                else if ("objectStore =" !in factoryArgs) add("$platform: fileServiceProxyImplementationFactory is invoked without `objectStore =`, so the backfill enumerates nothing.")

                if ("registerThumbnailBackfillSchedule()" !in text) add("$platform: registerThumbnailBackfillSchedule() is never called, so the backfill is never dispatched.")
            }
        }

        assertTrue(
            violations.isEmpty(),
            "The thumbnail backfill is wired to do nothing:\n" + violations.joinToString("\n"),
        )
    }

    /**
     * ⚠⚠ **iOS deliberately has none of the three, and that has a consequence now.** The backfill is
     * unregistered there because route 1's shared device id makes the pass's marker a race
     * (`drive-synced-thumbnails.md` §15.9). Since X-2 the keeper's thumbnail sweep *does* run on iOS,
     * and the backfill is the only thing that revalidates or clears an `.evicted` sentinel — so an
     * iPhone evicts and never repairs (`drive-xxlarge-thumbnail-rung.md` §9.3). This pins that
     * absence so the next reader meets it as a decision rather than as a surprise: if iOS ever gains
     * the backfill, this test fails and the doc's "Still open" paragraph is what to update.
     */
    @Test
    fun `ios wires no backfill, so an eviction there is never repaired`() {
        val module = File("src/iosMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
        assertTrue(module.isFile, "the iOS PlatformProxyModule moved; this test is no longer guarding.")
        val text = module.readText()

        assertTrue(
            "registerThumbnailBackfillSchedule()" !in text,
            "iOS now dispatches the thumbnail backfill — §15.9's marker race and " +
                "`drive-xxlarge-thumbnail-rung.md` §9.3's 'iOS evicts but never repairs' both need revisiting.",
        )
        assertTrue(
            "registerOfflineKeeperSchedule()" in text,
            "iOS stopped running the keeper; the sweep's reach changed and §9.3 says otherwise.",
        )
    }

    /**
     * The argument text of the *invocation* of [call] (never its import — the pattern includes the
     * open paren and refuses a preceding identifier character), up to the next `?:` link of a
     * factory chain or the end of the enclosing `single { … }` block, whichever comes first.
     */
    private fun argumentsOf(text: String, call: String): String? {
        val start = Regex("(?<![A-Za-z0-9_])" + Regex.escape(call)).find(text) ?: return null
        val rest = text.substring(start.range.last + 1)
        val end = listOfNotNull(CHAIN_LINK.find(rest)?.range?.first, BLOCK_END.find(rest)?.range?.first).minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        val MODULES = listOf(
            "Android" to "src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
            "desktop" to "src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt",
        )

        /** The next `?: something(` in a factory chain — where one call's arguments end. */
        val CHAIN_LINK = Regex("""\n\s*\?:\s""")

        /** The close of a Koin `single { … }` at four-space indent — where a constructor call's block ends. */
        val BLOCK_END = Regex("""\n    \}""")
    }
}
