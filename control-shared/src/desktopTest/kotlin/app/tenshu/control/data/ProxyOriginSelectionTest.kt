package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The runtime-override guard behind the iOS commerce origin (S2-pre,
 * `ios-services-status-and-purchase.md` §3.6). A debug binary may be pointed at a local stack
 * through its environment; a release binary always uses the origin baked in at build time — the
 * discipline `DevRelayOverride` already applies to the relay URL.
 */
class ProxyOriginSelectionTest {

    private val baked = "https://commerce.service.tenshu.app"

    @Test
    fun debugBuild_honoursTheOverride() {
        assertEquals(
            "http://localhost:8081",
            ProxyOriginSelection.resolve("http://localhost:8081", isRelease = false, default = baked),
        )
    }

    @Test
    fun releaseBuild_ignoresTheOverride() {
        assertEquals(
            baked,
            ProxyOriginSelection.resolve("http://localhost:8081", isRelease = true, default = baked),
            "a shipped app must not be re-pointable through its environment",
        )
    }

    @Test
    fun noOverride_fallsBackToTheBakedOrigin() {
        assertEquals(baked, ProxyOriginSelection.resolve(null, isRelease = false, default = baked))
        assertEquals(baked, ProxyOriginSelection.resolve(null, isRelease = true, default = baked))
    }

    @Test
    fun blankOverride_isNotAnOrigin() {
        // An Xcode scheme variable that exists but is empty must not produce a blank base URL —
        // `CommercePublisherClient` would then POST to a bare path.
        assertEquals(baked, ProxyOriginSelection.resolve("", isRelease = false, default = baked))
        assertEquals(baked, ProxyOriginSelection.resolve("   ", isRelease = false, default = baked))
    }
}
