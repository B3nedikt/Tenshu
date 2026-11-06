package org.tenshu.inference.proxy.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The pinned key is hand-copied from terminal output, and a bad paste fails SILENTLY.**
 *
 * `hasPinnedKey` is false for a blank, odd-length or non-hex value, and a false pin means
 * `ModelCatalogSource.canFetch` is false — so the device never asks for a catalog and never says
 * why. A truncated paste therefore looks exactly like "no models are published", on every device,
 * with nothing in any log. This is the only thing standing between a slipped character and that.
 *
 * ⚠ It asserts the SHAPE, not the value: the key is the maintainer's and rotating it must not need
 * a test edit. What must never change silently is that whatever is pinned is a usable Ed25519
 * public key.
 */
class PinnedKeyTest {

    @Test
    fun theCompiledInCatalogKeyIsAUsableEd25519PublicKey() {
        val hex = MODEL_CATALOG_PUBLIC_KEY_HEX

        assertTrue(hex.isNotBlank(), "the release key is pinned — a blank value refuses every catalog")
        assertEquals(
            64,
            hex.length,
            "an Ed25519 public key is 32 bytes / 64 hex chars; a different length is a bad paste",
        )
        assertTrue(
            hex.all { it in "0123456789abcdefABCDEF" },
            "the pinned key must be hex; a stray character makes `hasPinnedKey` false and the " +
                "device silently stops asking for a catalog",
        )
    }

    /**
     * ⚠ The property the constant exists for, asserted through the class that reads it rather than
     * by re-deriving it here — `hasPinnedKey` is what `canFetch` actually consults.
     */
    @Test
    fun theVerifierAcceptsThePinAsUsable() {
        val verifier = ModelCatalogVerifier(verifier = { _, _, _ -> true })
        assertTrue(
            verifier.hasPinnedKey,
            "with a key pinned, the verifier must report one — otherwise no device ever fetches",
        )
    }
}
