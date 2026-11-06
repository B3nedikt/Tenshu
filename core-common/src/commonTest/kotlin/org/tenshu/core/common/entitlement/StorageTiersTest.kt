package org.tenshu.core.common.entitlement

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the tier figures now that three planes read them from one place.
 *
 * ⚠⚠ Before this object existed the same numbers were declared three times — in the client's
 * `EntitlementProducts`, commerce's `ProductCatalog` and the relay's `quotaForProduct` — each
 * carrying a comment telling the reader to keep it in lockstep with the other two. Those are the
 * *display*, the *issuer* and the *enforcer*: drift meant a user metered at one figure, billed for
 * a second and shown a third. Agreement is structural now, so what is left to guard is the values
 * themselves, and this is the one test that does it.
 */
class StorageTiersTest {

    @Test
    fun eachCatalogueTierGrantsItsPublishedSize() {
        // `commerce-accounts-and-sessions.md` §2.1 — these appear in the tier picker and the terms.
        assertEquals(5L * 1024 * 1024 * 1024, StorageTiers.quotaBytes(StorageTiers.FREE, 0L))
        assertEquals(50L * 1024 * 1024 * 1024, StorageTiers.quotaBytes(StorageTiers.BASIC, 0L))
        assertEquals(
            2L * 1024 * 1024 * 1024 * 1024,
            StorageTiers.quotaBytes(StorageTiers.PREMIUM, 0L),
        )
    }

    /**
     * ⚠ The fallback is the caller's, not the table's — and that is load-bearing rather than
     * stylistic. The relay passes Free so an unrecognised-but-validly-signed tier is neither
     * unlimited nor walled; commerce passes its *top* tier so an off-catalogue grant (redeem-only,
     * promotional, bespoke) is generous rather than crippled; a display surface passes `0` so it
     * shows nothing instead of inventing a figure. One baked-in default could not serve all three.
     */
    @Test
    fun anUnknownTierYieldsTheCallersFallback_neverAGuess() {
        assertEquals(99L, StorageTiers.quotaBytes("tenshu.drive.enterprise", fallback = 99L))
        assertEquals(0L, StorageTiers.quotaBytes("", fallback = 0L))
    }
}
