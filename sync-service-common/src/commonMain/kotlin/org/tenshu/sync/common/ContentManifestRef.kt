package org.tenshu.sync.common

import org.tenshu.core.common.util.sha256
import org.tenshu.core.common.util.toHexLower

/**
 * The relay key of a content's **manifest** blob, derived from the `contentId` alone — no key, no fetch.
 *
 * Shared, not copied: the client uploads and fetches the manifest under it, and the relay's operator
 * reclamation derives it to **protect** the manifest of every content it knows about (a manifest is
 * the one blob of a content the relay can name without a description). Two spellings of this function
 * would let them disagree silently, and the relay would then free live manifests.
 *
 * ⚠⚠ Changing the derivation strands every stored manifest: nothing could find them again.
 */
fun manifestRefOf(contentId: String): String =
    "blob-" + sha256("manifest:$contentId".encodeToByteArray()).toHexLower()
