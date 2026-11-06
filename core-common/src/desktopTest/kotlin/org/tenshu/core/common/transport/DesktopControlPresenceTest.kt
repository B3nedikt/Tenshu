package org.tenshu.core.common.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The desktop presence decision (`ADR 0025` §Decision 4).
 *
 * ⚠⚠ **The negative is the whole subject.** A `true` proves itself — a bundle was found. A `false`
 * is a claim about something that is *not* there, made by an instrument that can be switched off,
 * and acting on a wrong one invites the user to install an app they already have. So every test
 * below is really about which negatives are trusted.
 */
class DesktopControlPresenceTest {

    private fun decide(
        isMacOs: Boolean = true,
        onDisk: Boolean = false,
        spotlight: (String) -> Boolean? = { false },
    ) = controlInstalledFrom(isMacOs, { onDisk }, spotlight)

    /** ⚠ Windows and Linux have no installed-artifact convention yet: ignorance, not absence. */
    @Test
    fun offMacOsTheAnswerIsCannotTell() {
        assertNull(decide(isMacOs = false, onDisk = true, spotlight = { true }))
    }

    @Test
    fun aBundleInApplicationsIsEnoughOnItsOwn() {
        // ⚠ No index consulted: a found bundle proves itself, so a dead Spotlight cannot veto it.
        assertEquals(true, decide(onDisk = true, spotlight = { null }))
    }

    @Test
    fun spotlightFindingControlAnywhereElseAlsoCounts() {
        assertEquals(true, decide(spotlight = { it == "app.tenshu.control.ios" }))
    }

    /**
     * ⚠⚠ The one case allowed to say "not installed": Spotlight answered, and proved it can answer
     * by finding the canary.
     */
    @Test
    fun aNegativeIsTrustedOnlyWhenTheCanaryComesBack() {
        assertEquals(false, decide(spotlight = { it == "com.apple.finder" }))
    }

    /**
     * ⚠⚠ The failure this canary exists for. Spotlight disabled or the volume unindexed answers
     * "no Control" *and* "no Finder" — indistinguishable from a Mac without Control unless you ask.
     */
    @Test
    fun aSilentCanaryMeansTheInstrumentIsBrokenNotThatControlIsMissing() {
        assertNull(decide(spotlight = { false }))
    }

    /** ⚠ `mdfind` failing to run at all is the same ignorance, reached by a different road. */
    @Test
    fun anUnrunnableQueryIsCannotTell() {
        assertNull(decide(spotlight = { null }))
    }
}
