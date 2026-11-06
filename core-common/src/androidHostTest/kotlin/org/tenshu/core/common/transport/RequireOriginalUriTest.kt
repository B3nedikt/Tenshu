package org.tenshu.core.common.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which URIs may be asked for their un-redacted original (`timeline-photos.md` T-P4).
 *
 * ⚠⚠ Written after this crashed every file upload on a real phone. `MediaStore.setRequireOriginal`
 * appends a query parameter, so the result is a *different* URI — and a document URI handed over by
 * `ACTION_OPEN_DOCUMENT` carries a grant for the exact URI it issued, not for a mutated one. Asking
 * anyway threw `SecurityException: … requires that you obtain access using ACTION_OPEN_DOCUMENT`
 * on the main thread.
 */
class RequireOriginalUriTest {

    @Test
    fun mediaStoreUrisMayBeAskedForTheOriginal() {
        // What the gallery backup enumerates — the only path whose photos this feature is about.
        assertTrue(shouldRequestOriginal("media", mediaLocationGranted = true))
    }

    @Test
    fun documentUrisMayNotBe() {
        // The picked-file path, and the one that crashed. A grant issued for one URI does not cover
        // the URI you get by appending to it.
        assertFalse(
            shouldRequestOriginal("com.android.providers.media.documents", mediaLocationGranted = true),
        )
        assertFalse(
            shouldRequestOriginal("com.android.externalstorage.documents", mediaLocationGranted = true),
        )
    }

    @Test
    fun aUriWithNoAuthorityIsLeftAlone() {
        assertFalse(shouldRequestOriginal(null, mediaLocationGranted = true))
    }

    @Test
    fun withoutTheGrantThereIsNothingToAskFor() {
        // Asking without the permission throws at open time, so the answer must be no even for the
        // authority that would otherwise qualify.
        assertFalse(shouldRequestOriginal("media", mediaLocationGranted = false))
    }
}
