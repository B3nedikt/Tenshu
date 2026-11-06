package org.tenshu.core.common.transport

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Is **Tenshu Control installed on this desktop?** — `true`/`false`, or `null` for *cannot tell*
 * (`ADR 0025`, the desktop half of `TaskTransport.controlInstalled`).
 *
 * ## Why this can be asked at all
 *
 * Developer ID and development host builds remain deliberately unsandboxed; MAS host builds
 * opt into App Sandbox in `gradle/desktop-app-signing.gradle.kts`. In both cases the sandboxed
 * peer is Drive's File Provider `.appex`, which is not the process rendering a banner. This query
 * itself needs no TCC prompt or user permission: it is an ordinary local query by the host process.
 *
 * ## ⚠⚠ Silence is not an answer
 *
 * Spotlight can be disabled, a volume can be unindexed, and `mdfind` then reports *nothing* for a
 * Control sitting in `/Applications`. Read naively that is a confident "not installed", and the
 * user is invited to install an app they already have — the exact mirror of the defect ADR 0025
 * exists to close. So the query is **self-testing**: it first asks for a bundle that is present on
 * every macOS install, and only trusts a negative when that canary comes back. A silent canary
 * means the instrument is broken, which is `null` — *cannot tell* — and the shell keeps today's
 * transport wording.
 *
 * ⚠ A path check on `/Applications` is used as the fast, index-free positive; only the *negative*
 * needs the canary, because a found bundle proves itself.
 *
 * ⚠ **macOS only.** Windows and Linux answer `null`: Control has no installed-artifact convention
 * there yet (no registry key, no `.desktop` entry), and inventing one here would be a guess wearing
 * an API. They keep today's behaviour until they have something real to look at.
 */
internal fun desktopControlInstalled(): Boolean? = controlInstalledFrom(
    isMacOs = System.getProperty("os.name").orEmpty().startsWith("Mac"),
    bundleOnDisk = { File(APPLICATIONS_BUNDLE).isDirectory },
    spotlight = ::mdfindBundle,
)

/**
 * The decision itself, split from its three environment reads so it is directly testable — the
 * whole point of this file is the *negative*, and a test that can only run on the machine it is
 * written on cannot check one. Same split, and same reason, as `waitingWillFix` in Fit.
 */
internal fun controlInstalledFrom(
    isMacOs: Boolean,
    bundleOnDisk: () -> Boolean,
    spotlight: (String) -> Boolean?,
): Boolean? {
    if (!isMacOs) return null

    // A bundle that is there is there — no index involved, so no canary needed.
    if (bundleOnDisk()) return true

    return when (spotlight(CONTROL_BUNDLE_ID)) {
        true -> true
        // ⚠ Only now does the canary matter: distinguishing "Spotlight says no" from
        // "Spotlight is not answering", which look identical from here.
        false -> if (spotlight(CANARY_BUNDLE_ID) == true) false else null
        null -> null
    }
}

/**
 * `true`/`false` if `mdfind` ran and answered, `null` if it could not be run, timed out or failed —
 * ⚠ never conflate the last with "no such app", which is the whole point of the tri-state.
 */
private fun mdfindBundle(bundleId: String): Boolean? = runCatching {
    val process = ProcessBuilder("mdfind", "kMDItemCFBundleIdentifier == '$bundleId'")
        .redirectErrorStream(false)
        .start()
    // ⚠ Bounded: this runs on the reachability probe's cadence, and a wedged Spotlight must cost
    // one banner's worth of ignorance, never a hung probe loop.
    if (!process.waitFor(MDFIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null
    process.inputStream.bufferedReader().use { it.readText() }.isNotBlank()
}.getOrNull()

/** Control's packaged `CFBundleIdentifier`, shared with its iOS App Store record. */
private const val CONTROL_BUNDLE_ID = "app.tenshu.control.ios"

/** ⚠ The canary: present on every macOS install, so its absence indicts Spotlight, not the Mac. */
private const val CANARY_BUNDLE_ID = "com.apple.finder"

private const val APPLICATIONS_BUNDLE = "/Applications/TenshuControl.app"

private const val MDFIND_TIMEOUT_SECONDS = 2L
