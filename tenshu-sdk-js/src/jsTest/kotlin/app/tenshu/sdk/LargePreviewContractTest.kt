package app.tenshu.sdk

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.ThumbnailPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **`getLargePreview` answers across a language boundary, and both sides spell the contract by
 * hand.** The Kotlin surface emits `thumbnailClass` and `coveringRung` as plain strings; `web/sdk`
 * declares them as TypeScript string-literal unions (`ThumbnailClass`, `CoveringRung`) and
 * `web/drive`'s `CLASS_EDGE_PX` keys a lookup table off the first of them. Nothing compiles the two
 * together, so a rename on either side is a silent break: the lightbox would read every class as
 * unknown, score its edge 0, and download the original on every open — with no test failing.
 *
 * This pins the Kotlin half. The TypeScript half is pinned by `largePreview.test.ts`, which uses
 * the same literals; between them a rename has to break one to change the other.
 *
 * ⚠ If this test is edited, `web/sdk/src/files.ts` and `web/drive/src/lib/largePreview.ts` change
 * in the same commit.
 */
class LargePreviewContractTest {

    @Test
    fun theClassNamesCrossingTheBridgeAreTheOnesTypeScriptDeclares() {
        // Exactly the four in `ThumbnailClass`, spelled as `web/drive`'s CLASS_EDGE_PX keys them.
        assertEquals("xxlarge", GetStoredThumbnail.CLASS_XXLARGE)
        assertEquals("xlarge", GetStoredThumbnail.CLASS_XLARGE)
        assertEquals("large", GetStoredThumbnail.CLASS_LARGE)
        assertEquals("list", GetStoredThumbnail.CLASS_LIST)
    }

    @Test
    fun theEdgesTypeScriptHardcodesStillMatchTheLadder() {
        // ⚠ `CLASS_EDGE_PX` in `largePreview.ts` is a fourth copy of these numbers (the three
        // native `GetPreview` implementations hold the others — `drive-web-parity.md` §1.2i). It
        // decides whether what we got covers what was asked for, so drift here silently changes
        // when the lightbox spends a whole original.
        assertEquals(2048, GetStoredThumbnail.XXLARGE_PX)
        assertEquals(1024, GetStoredThumbnail.XLARGE_PX)
        assertEquals(256, GetStoredThumbnail.LARGE_PX)
        assertEquals(100, GetStoredThumbnail.LIST_PX)
    }

    @Test
    fun theCoverToleranceTypeScriptMirrorsIsUnchanged() {
        // ⚠ `largePreview.ts` hardcodes this. The per-rung `stretches` flags it also mirrors live
        // on `ThumbnailClasses`, which is `internal` to `:file-service-proxy` and unreachable here —
        // they are pinned by `ThumbnailClassesTest.theCoverBandWebMirrorsIsStillPerRung` instead.
        assertEquals(2.0, GetStoredThumbnail.COVER_TOLERANCE)
    }

    @Test
    fun theTwoClassesTheLightboxNamesAreDeclarable() {
        // ⚠ X-5 could not ship the launch declaration (web registers no preference service, §9.7),
        // but the classes it *would* declare must stay declarable — otherwise the change that
        // finally wires it would write a row the proxy ignores.
        assertTrue(GetStoredThumbnail.CLASS_XXLARGE in ThumbnailPreferences.DECLARABLE_CLASSES)
        assertTrue(GetStoredThumbnail.CLASS_XLARGE in ThumbnailPreferences.DECLARABLE_CLASSES)
    }

    /**
     * ⚠⚠ **The fail-safe that X-5's first cut did not have.** `absent` is the one answer that
     * licenses the caller to download a whole 6-18 MB original, so it must come only from a
     * *positive* confirmation that the relay cannot serve the class — `ContentResolvability.ABSENT`.
     *
     * The first cut derived it from `OnDemandResolution.resolve`'s `TResult` instead. That call
     * returns `Success` unconditionally once the property has a `contentId` (which a thumbnail class
     * always has), so a 503, a captive portal and "nobody ever minted this" were the same value —
     * and every open during an outage would have spent an original proving it. This is the X-4
     * defect (§9.6) rebuilt one phase later; review caught it.
     *
     * Here there is no session, no relay and no content emitter, so absence can never be confirmed.
     * The answer must be `unknown`. ⚠ A file that does not exist is the strongest form of the case:
     * if anything is going to shortcut to "absent", it is this.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun aRuntimeThatCannotReachTheRelayNeverClaimsTheClassIsAbsent() = GlobalScope.promise {
        val answer = FileSystem.getLargePreview("/x5-no-such-file.jpg", 2048).await()
        assertTrue(answer.ok as Boolean, "the read itself is not a failure")
        assertNotEquals(
            "absent",
            answer.coveringRung as String,
            "absence must be CONFIRMED against the relay, never inferred from a pull that cannot " +
                "report failure — see this test's KDoc",
        )
    }
}
