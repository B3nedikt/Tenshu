package org.tenshu.file.client

import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.fileCategoryForName

/**
 * Whether a preview of [requestedMaxEdge] px for [path] should ask for the hero rung **by name**
 * before falling back to `File.Preview` (`drive-synced-thumbnails.md` §16).
 *
 * ## ⚠⚠ Why this is shared rather than copied
 *
 * It began as a private rule in `TenshuPhotosRepository` and was promoted when Timeline needed the
 * same decision (`drive-xxlarge-thumbnail-rung.md` X-6). Two apps answering this question
 * *differently* is not hypothetical: X-5 shipped a web lightbox whose coverage test diverged from
 * the shared ladder by ignoring `COVER_TOLERANCE`, so web fetched whole originals where every
 * native platform served a 1024 px rung. The ladder's numbers already live in four places (§1.2i of
 * `drive-web-parity.md` counts them); its *policy* should live in one.
 *
 * ## The rule
 *
 * Only above `LARGE` — below it the small rungs answer — and then:
 *
 * - **a still, only where the original is not local.** A device holding it gets a sharp answer from
 *   `GetPreview` already (the stored hero class, else a render from the original), and asking it for
 *   a hero class nobody minted would cost a relay round trip per tile. ⚠ [holdsOriginal] is not
 *   consulted at or below `LARGE`, so a caller that only ever asks small pays no stat.
 * - ⚠⚠ **a video, always.** A poster rendered from the original is a platform capability, not a
 *   given: the desktop proxy has no video frame decoder and serves a downloaded video's *stored*
 *   classes only, so the "holds the original, so `GetPreview` is sharp" premise is false exactly
 *   there, and a local video would stay on its 256 px poster while the hero one sat on the relay.
 *   The hero poster is local-first, so a device that minted or fetched it pays no network for this.
 */
suspend fun asksForHeroByName(
    requestedMaxEdge: Int,
    path: String,
    holdsOriginal: suspend (String) -> Boolean,
): Boolean {
    if (requestedMaxEdge <= GetStoredThumbnail.LARGE_PX) return false
    if (fileCategoryForName(path.substringAfterLast('/')) == FileCategory.Video) return true
    return !holdsOriginal(path)
}
