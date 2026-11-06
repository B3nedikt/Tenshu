package app.tenshu.control.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-text audit over the Android `PlatformProxyModule`: **the one platform that can mint the
 * `MOTION` class hands its renderer to both file factories** (`photos-video-support.md` §14).
 *
 * ⚠⚠ Same failure shape as [ReclamationRecorderWiringTest], and for the same reason: the renderer
 * is an optional constructor argument defaulting to `null`, so omitting it compiles, starts, and
 * uploads — every video simply gets no motion class, which on a real phone reads as "the feature
 * is not built yet" rather than as a bug. And there are *two* calls to get wrong: Android's own
 * factory serves the descriptor-backed `UploadSourceFile` ops, while the in-memory `UploadFile` /
 * `ReplaceFile` ops (Takeout import, backup ingest) fall through to the common factory in the same
 * module. Wiring one and not the other mints for the picker and silently not for the importer.
 *
 * ⭐ No DI or runtime test can see this: the omission is an absent argument at a call site, and
 * the module graph is only assembled inside a live proxy process. Source text is where it is visible.
 */
class MotionThumbnailRendererWiringTest {

    @Test
    fun `android passes the motion renderer to both file factories`() {
        val module = File("src/androidMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt")
        // ⚠ Anti-vacuity: a scan that finds no file passes for the wrong reason.
        assertTrue(module.isFile, "the Android PlatformProxyModule moved; this test is no longer guarding.")
        val text = module.readText()

        val violations = FACTORY_CALLS.mapNotNull { call ->
            val arguments = argumentsOf(text, call)
                ?: return@mapNotNull "$call: no invocation found in the Android module — the scan is stale."
            if (ARGUMENT in arguments) null
            else "$call: is invoked without `$ARGUMENT`, so the ops it builds mint no motion class."
        }

        assertTrue(
            violations.isEmpty(),
            "The motion renderer is wired to mint nothing:\n" + violations.joinToString("\n"),
        )
    }

    /**
     * The argument text of the *invocation* of [call] (never its import — the pattern includes the
     * open paren and refuses a preceding identifier character, so `fileServiceProxyImplementationFactory(`
     * does not match inside `androidFileServiceProxyImplementationFactory(`), up to the next
     * `?:` link of the factory chain.
     */
    private fun argumentsOf(text: String, call: String): String? {
        val start = Regex("(?<![A-Za-z0-9_])" + Regex.escape(call)).find(text) ?: return null
        val rest = text.substring(start.range.last + 1)
        val end = CHAIN_LINK.find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        val FACTORY_CALLS = listOf(
            "androidFileServiceProxyImplementationFactory(",
            "fileServiceProxyImplementationFactory(",
        )
        const val ARGUMENT = "motionThumbnailRenderer ="

        /** The next `?: something(` in the factory chain — where one call's arguments end. */
        val CHAIN_LINK = Regex("""\n\s*\?:\s""")
    }
}
