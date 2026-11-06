package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the content-plane **manifest** ref shape against the relay's acceptance pattern.
 *
 * The relay's `ContentRouting.REF_PATTERN` (`^blob-[0-9a-f]{64}$`) is enforced on **both** the
 * content PUT and GET; a ref that fails it is 400'd. The manifest used to be minted as
 * `manifest-<contentId>`, which fails that pattern, so cross-device manifests were silently
 * rejected and OnDemand byte delivery broke — a regression that only the `:system-test-utils`
 * file-bytes tests (real relay) caught, because unit tests use in-process mailboxes that don't
 * enforce the ref shape. This test re-asserts the shape locally so a future drift is caught fast.
 *
 * `REF_PATTERN` lives in `:sync-relay-server` (not a dependency here), so it is mirrored below;
 * keep the two in lockstep.
 */
class ManifestContentRefTest {

    private val relayRefPattern = Regex("^blob-[0-9a-f]{64}$")

    @Test
    fun manifestRef_matchesTheRelayAcceptancePattern() {
        val ref = manifestContentRef("211d84a8-0f31-4f71-8a6c-375b51a0f084")
        assertTrue(
            relayRefPattern.matches(ref),
            "manifest ref must satisfy the relay's ^blob-[0-9a-f]{64}$ pattern; was: $ref",
        )
    }

    @Test
    fun manifestRef_isDeterministicPerContentIdAndDistinctAcrossContentIds() {
        val a = "211d84a8-0f31-4f71-8a6c-375b51a0f084"
        val b = "9f2c1e77-4b3a-4d2e-9c8f-0a1b2c3d4e5f"
        assertEquals(manifestContentRef(a), manifestContentRef(a), "derivation must be deterministic")
        assertNotEquals(manifestContentRef(a), manifestContentRef(b), "distinct contentIds → distinct refs")
    }
}
