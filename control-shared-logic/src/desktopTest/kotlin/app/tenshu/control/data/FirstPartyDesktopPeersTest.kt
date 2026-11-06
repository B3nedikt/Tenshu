package app.tenshu.control.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `control-app-catalogue.md` C2. This set is what the desktop peer-credential gate admits, so
 * both directions matter in a sharper way than usual: too narrow locks a real app (or Control's
 * own tray) out of its own proxy, too wide silently restores "any identifier signed by our team".
 */
class FirstPartyDesktopPeersTest {

    @Test
    fun everySignedDesktopAppIsAdmissible() {
        // Derived, not listed: an app gains admissibility by declaring a Mac binding, which is
        // the same edit that makes it resolvable. The two cannot drift.
        val declared = BundledAppCatalogue.apps
            .flatMap { app -> app.clients.filterIsInstance<ClientBinding.MacCodeSignature>() }
            .map { it.identifier }
        assertTrue(declared.isNotEmpty(), "Drive and Fit ship signed desktop bundles")
        declared.forEach {
            assertTrue(it in FirstPartyDesktopPeers.IDENTIFIERS, "$it must be admissible")
        }
    }

    /**
     * ⚠⚠ The trap this phase exists to avoid. Control's tray agent is not an app — no manifest,
     * no Apps-screen presence — but it drives `bridge.sock` on every poll. A policy derived from
     * the app catalogue alone would reject Control's own helper the moment the gate went strict.
     */
    @Test
    fun controlsOwnTrayAgentIsAdmissible() {
        assertTrue("tray-agent" in FirstPartyDesktopPeers.IDENTIFIERS)
        assertFalse(
            "tray-agent" in FirstPartyDesktopPeers.APP_IDENTIFIERS,
            "the tray is a helper, not an app — it must not need a manifest to connect",
        )
    }

    /**
     * The Drive File Provider extension is the other helper that opens `bridge.sock`. The
     * identifier is the one in `drive-desktop/macos/FileProviderExtension/project.yml`; if the
     * two drift, Finder shows an empty Tenshu Drive and nothing says why.
     */
    @Test
    fun drivesFileProviderExtensionIsAdmissible() {
        assertTrue("app.tenshu.drive.ios.fileprovider" in FirstPartyDesktopPeers.IDENTIFIERS)
        assertFalse("app.tenshu.drive.ios.fileprovider" in FirstPartyDesktopPeers.APP_IDENTIFIERS)
        assertFalse(
            "tenshu-domain" in FirstPartyDesktopPeers.IDENTIFIERS,
            "the domain registrar opens no socket and must not widen the gate",
        )
    }

    @Test
    fun anUndeclaredIdentifierIsNotAdmissible() {
        // The whole point of narrowing: an identifier we never shipped gets no team vouching for
        // it, so the strict gate closes the channel instead of admitting it unconstrained and
        // invisible.
        assertFalse("dev.example.notes" in FirstPartyDesktopPeers.IDENTIFIERS)
        assertFalse("app.tenshu.drive.evil" in FirstPartyDesktopPeers.IDENTIFIERS)
        assertFalse("" in FirstPartyDesktopPeers.IDENTIFIERS)
    }

    /**
     * Apps with a desktop shell but no *signed* binding must stay out. They would be rejected at
     * the gate anyway (they attest as nothing), but listing them here would be a standing
     * invitation to widen the set without doing the signing work.
     *
     * ⚠ Driven by a **synthetic** catalogue since 2026-08-10. This used to filter the real one,
     * which worked only while some shipped app happened to lack a Mac binding; once
     * Maps/Timeline/Home gained theirs the filter went empty and the `forEach` became a loop over
     * nothing — which passes for a derivation that admits *everything*. The rule outlived its
     * subject, so the test now brings its own.
     */
    @Test
    fun anAppWithNoMacBindingIsNotAdmissible() {
        val androidOnly = FirstPartyApp(
            manifest = BundledAppManifestSource.MAPS_MANIFEST,
            clients = listOf(ClientBinding.AndroidPackage("app.tenshu.example.android")),
        )
        val signed = FirstPartyApp(
            manifest = BundledAppManifestSource.DRIVE_MANIFEST,
            clients = listOf(ClientBinding.MacCodeSignature("app.tenshu.example")),
        )

        val derived = macIdentifiersOf(listOf(androidOnly, signed))

        assertEquals(setOf("app.tenshu.example"), derived)
        assertFalse(
            "app.tenshu.example.android" in derived,
            "an Android package is not a Mac code-signing identifier — the two namespaces differ " +
                "(app.tenshu.maps vs app.tenshu.maps.android) and conflating them would admit a " +
                "peer that can never present that identifier",
        )
    }

    /**
     * The real catalogue's current state, kept separate from the rule above so that the *rule*
     * never depends on it. This one is expected to change as apps ship desktop bundles.
     */
    @Test
    fun everyCatalogueAppNowShipsASignedDesktopBundle() {
        val withoutMacBinding = BundledAppCatalogue.apps
            .filter { app -> app.clients.none { it is ClientBinding.MacCodeSignature } }
            .map { it.appId }
        assertEquals(
            emptyList(),
            withoutMacBinding,
            "these declare no Mac binding, so they cannot register on desktop",
        )
    }

    @Test
    fun helpersDoNotIncludePipeOnlySubprocesses() {
        // qr-scan-helper and image-decode-helper talk over stdin/stdout, never a socket. Listing
        // them would widen the gate for peers that can never appear at it.
        assertFalse("qr-scan-helper" in FirstPartyDesktopPeers.IDENTIFIERS)
        assertFalse("image-decode-helper" in FirstPartyDesktopPeers.IDENTIFIERS)
    }

    @Test
    fun theAdmissibleSetIsExactlyAppsPlusHelpers() {
        assertEquals(
            FirstPartyDesktopPeers.APP_IDENTIFIERS + FirstPartyDesktopPeers.HELPER_IDENTIFIERS,
            FirstPartyDesktopPeers.IDENTIFIERS,
        )
    }

    /**
     * A second Control hands off to the running one over `bridge.sock`
     * (`desktop-macos-distribution.md` §2.11), so the bridge admits Control's own identifier. ⚠ Only
     * the bridge: on `ipc.sock` Control would be an unregistered "app".
     */
    @Test
    fun controlItselfIsAdmittedOnTheBridgeOnly() {
        assertTrue("app.tenshu.control.ios" in FirstPartyDesktopPeers.BRIDGE_IDENTIFIERS)
        assertFalse("app.tenshu.control.ios" in FirstPartyDesktopPeers.IDENTIFIERS)
        assertEquals(
            FirstPartyDesktopPeers.IDENTIFIERS + FirstPartyDesktopPeers.CONTROL_IDENTIFIERS,
            FirstPartyDesktopPeers.BRIDGE_IDENTIFIERS,
        )
    }
}
