package org.tenshu.mia.proxy

import org.tenshu.inference.SemanticSearchUnavailable
import org.tenshu.mia.proxy.impl.semanticUnavailableText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mia's three photo refusals on iOS (`ios-on-device-inference.md` §1.8, §14.18.16 item 4): they
 * name Photos, where the model and the index live, and never "this device" or Control.
 */
class MiaPhotoSearchHomeTest {

    private val three = listOf(
        SemanticSearchUnavailable.Reasons.NO_BACKEND,
        SemanticSearchUnavailable.Reasons.NO_CONSENT,
        SemanticSearchUnavailable.Reasons.NOT_INDEXED,
    )

    @Test
    fun onIosEachOfTheThreeNamesPhotosAndNeitherThisDeviceNorControl() {
        for (reason in three) {
            val line = semanticUnavailableText(reason, MiaPhotoSearchHome.PHOTOS_APP)
            assertTrue("Photos" in line && "Settings → Models" in line, "$reason does not send the user to Photos: $line")
            assertFalse("this device" in line, "$reason still says this device: $line")
            assertFalse("Control" in line, "$reason names Control, which Control-on-iOS does not have: $line")
            assertTrue(line != semanticUnavailableText(reason, MiaPhotoSearchHome.THIS_DEVICE), "$reason has one wording for both")
        }
        // ⚠ The consent line keeps what §14.2 made it say: a separate permission, and its label.
        val consent = semanticUnavailableText(SemanticSearchUnavailable.Reasons.NO_CONSENT, MiaPhotoSearchHome.PHOTOS_APP)
        assertTrue("separate permission" in consent && "Search photos by description" in consent, consent)
        assertFalse(consent.contains("in Settings", ignoreCase = true), consent)
    }

    @Test
    fun theOtherReasonsAndAndroidsWordingAreUnchanged() {
        assertEquals(
            semanticUnavailableText(SemanticSearchUnavailable.Reasons.NO_TEXT_EMBEDDING, MiaPhotoSearchHome.THIS_DEVICE),
            semanticUnavailableText(SemanticSearchUnavailable.Reasons.NO_TEXT_EMBEDDING, MiaPhotoSearchHome.PHOTOS_APP),
        )
        assertEquals(
            "I don't have a model on this device that can look at your photos yet.",
            semanticUnavailableText(SemanticSearchUnavailable.Reasons.NO_BACKEND, MiaPhotoSearchHome.THIS_DEVICE),
        )
    }
}
