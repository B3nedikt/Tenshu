package org.tenshu.file.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a preview names the hero rung before `File.Preview` (`drive-synced-thumbnails.md` §16).
 *
 * ⚠ Moved here with the function in X-6, so the rule and its tests live in one module.
 *
 * ⚠⚠ A correction, because the first version of this note was wrong: I claimed
 * `:photos-shared-logic` has no JVM target and these tests had therefore never executed. It
 * declares `jvm()` (`build.gradle.kts:23`) and `:photos-shared-logic:jvmTest` runs 254 tests, all
 * from `commonTest` — they were running all along. What actually happened when the move broke the
 * old import is `gate-green-while-compile-fails`: the module's tests could not compile, so they did
 * not run, and the gate's summary named an unrelated flake while the iOS lane printed the error.
 */
class HeroRungAskTest {

    private var probes = 0
    private fun held(answer: Boolean): suspend (String) -> Boolean = { probes++; answer }

    @Test
    fun aSmallRequestNeverAsks_andNeverProbes() = runTest {
        assertFalse(asksForHeroByName(256, "/a.jpg", held(false)))
        assertFalse(asksForHeroByName(256, "/v.mp4", held(false)))
        assertEquals(0, probes, "the presence stat is not paid below LARGE")
    }

    @Test
    fun aStillAsksOnlyWhereItsOriginalIsNotLocal() = runTest {
        assertTrue(asksForHeroByName(1024, "/a.jpg", held(false)))
        assertFalse(asksForHeroByName(1024, "/a.jpg", held(true)))
    }

    /**
     * ⚠⚠ The desktop case: a downloaded video's `GetPreview` cannot render a poster (no frame
     * decoder on the JVM), so holding the original does not make the answer sharp.
     */
    @Test
    fun aVideoAlwaysAsks_evenWhenItsOriginalIsLocal() = runTest {
        assertTrue(asksForHeroByName(1024, "/Backup/VID_0001.mp4", held(true)))
        assertEquals(0, probes, "and the stat it could not act on is skipped")
    }
}
