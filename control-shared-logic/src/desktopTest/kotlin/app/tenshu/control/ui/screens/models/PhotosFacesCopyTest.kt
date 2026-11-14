package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Face grouping on Photos' Models screens (`ios-face-grouping.md` F6): its own switch beside
 * search's, the sentence that says what turning it off deletes, and *Turn off* asking first.
 *
 * ⚠⚠ Android's words wherever Android has them (`:control-shared`'s `consentHeadline`,
 * `featureDescription`, `turnOffText`, `turnOffEverywhereLabel`): pinned here as literals, so a
 * change to one platform's sentence is a red here, not a silent drift.
 */
class PhotosFacesCopyTest {

    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val faces = InferenceCapability.FACE_CLUSTERING
    private val both = OnDeviceModelsCapabilities.copy(features = listOf(search, faces))

    private val siglip = ModelRow("siglip", "SigLIP", ModelSetup.Held(HeldModel("siglip", "SigLIP", 1, 2_579_969_593, "Apache-2.0"), ModelSetup.Standing.Ready, ModelSetup.Update.None))
    private fun face(standing: ModelSetup.Standing = ModelSetup.Standing.Ready) =
        ModelRow("auraface-v1-int8", "People", ModelSetup.Held(HeldModel("auraface-v1-int8", "People", 1, 453_327_426, "Apache-2.0"), standing, ModelSetup.Update.None))
    private val faceAbsent = ModelRow("auraface-v1-int8", "People", ModelSetup.Absent(OfferedModel("auraface-v1-int8", "People", 1, 130_631_680, 453_327_426, "Apache-2.0"), null))
    private val serves = mapOf("siglip" to listOf(search.id), "auraface-v1-int8" to listOf(faces.id))

    private fun listed(vararg rows: ModelRow) = ModelsAvailability.Listed(ModelsAvailability.Catalog.READ, ModelsAvailability.Downloads.KNOWN, rows.toList())

    private fun state(
        facesOn: Boolean = false,
        facesRealm: Boolean = facesOn,
        rows: List<ModelRow> = listOf(siglip, face()),
        others: List<OtherDeviceConsent> = emptyList(),
        capabilities: ModelsCapabilities = both,
    ) = ModelsUiState(
        capabilities = capabilities,
        availability = listed(*rows.toTypedArray()),
        consents = capabilities.features.map { if (it == faces) CapabilityConsent(faces, facesRealm, facesOn) else CapabilityConsent(it, true, true) },
        consentsRead = true,
        otherDevices = capabilities.features.associateWith { others },
        otherDevicesKnown = true,
        otherDevicesRead = true,
        thisDeviceKnown = true,
        serves = serves,
    )

    @Test
    fun theFaceCopyIsAndroidsWhereAndroidHasIt() {
        assertEquals("Group photos by people", PhotosModelsCopy.FACES_FEATURE)
        assertEquals(
            "Find faces in your photos and group them so you can name people. Face data is made on each device you allow and never leaves it. " +
                "Only the names you type sync to your devices. Turning this off deletes the face data and keeps the names.",
            PhotosModelsCopy.FACES_DESCRIPTION,
        )
        assertEquals("Turn off everywhere and delete face data", PhotosModelsCopy.FACES_TURN_OFF_EVERYWHERE)
        assertEquals("Face data is deleted on every device. The names you typed are kept.", PhotosModelsCopy.FACES_TURN_OFF_EVERYWHERE_TEXT)
        assertEquals("Its face data is deleted. The names you typed are kept, and other devices are not changed.", PhotosModelsCopy.FACES_TURN_OFF_OTHER_TEXT)
        assertEquals("Turning this off deletes this iPhone's face data; names stay.", PhotosModelsCopy.facesOffDeletes("iPhone"))
    }

    /** One row per switch, search's first and as it always was; face grouping's with its own title. */
    @Test
    fun theModelsScreenOffersAFaceSwitchOfItsOwnBesideSearchs() {
        val screen = photosModelsScreen(state(), "iPhone", null)

        assertEquals(listOf(search, faces), screen.features.map { it.capability })
        assertEquals(listOf("Search photos by description", "Group photos by people"), screen.features.map { it.title })
        assertEquals(search, screen.feature?.capability, "the first row is search's, as before")
        assertEquals("Off for this iPhone", screen.features[1].status)
        // ⚠⚠ Off, or never written: nothing to delete, so nothing is said of a deletion.
        assertNull(screen.features[1].detail, "a deletion promised over a switch that is off")
    }

    @Test
    fun theFaceRowSaysWhatTurningItOffDeletesWhileItIsOn() {
        val row = photosModelsScreen(state(facesOn = true), "iPhone", null).features[1]
        assertEquals("On for this iPhone", row.status)
        assertEquals("Turning this off deletes this iPhone's face data; names stay.", row.detail)
        // Search's row is unchanged by the face switch.
        assertNull(photosModelsScreen(state(facesOn = true), "iPhone", null).features[0].detail)
    }

    /** ⚠ Usable is asked of the model serving the feature, not of any model: search ready is not faces ready. */
    @Test
    fun whileTheFaceModelIsNotReadyItsRowSaysItWorksOnceReady() {
        val row = photosModelsScreen(state(facesOn = true, rows = listOf(siglip, faceAbsent)), "iPhone", null).features[1]
        assertEquals("${PhotosModelsCopy.STARTS_WHEN_READY} Turning this off deletes this iPhone's face data; names stay.", row.detail)
        assertNull(photosModelsScreen(state(facesOn = true, rows = listOf(siglip, faceAbsent)), "iPhone", null).features[0].detail)
    }

    @Test
    fun theFaceScreenSaysWhatItDoesAndWhatOffDeletes() {
        val screen = assertNotNull(photosFeatureScreen(state(facesOn = true), faces, "iPhone"))
        assertEquals("Group photos by people", screen.title)
        assertEquals(PhotosModelsCopy.FACES_DESCRIPTION, screen.about)
        assertEquals("Turning this off deletes this iPhone's face data; names stay.", screen.thisDevice.status)
        assertEquals(PhotosFeatureScreen.Press.TurnOffHere, screen.thisDevice.press)
    }

    /** ⚠⚠ *Turn off* asks first, and the question names exactly what goes and what stays. */
    @Test
    fun turningFacesOffHereAsksFirstNamingWhatIsDeleted() {
        val question = assertNotNull(assertNotNull(photosFeatureScreen(state(facesOn = true), faces, "iPhone")).thisDevice.question)
        assertEquals("Turn off \"Group photos by people\" on this iPhone?", question.title)
        assertEquals("This iPhone's face data is deleted. The names you typed are kept, and other devices are not changed.", question.text)
        assertEquals("Turn off", question.yes)
        assertEquals("Keep on", question.no)
    }

    @Test
    fun turningFacesOffForAnotherDeviceOrEverywhereAsksFirstToo() {
        val screen = assertNotNull(photosFeatureScreen(state(facesOn = true, others = listOf(OtherDeviceConsent("mac", "Alex's Mac", true), OtherDeviceConsent("pad", "iPad", false))), faces, "iPhone"))
        val mac = screen.others.single { it.key == "mac" }
        assertEquals("Turn off \"Group photos by people\" on Alex's Mac?", mac.question?.title)
        assertEquals(PhotosModelsCopy.FACES_TURN_OFF_OTHER_TEXT, mac.question?.text)
        // A device that is off has nothing to turn off, and asks nothing.
        assertNull(screen.others.single { it.key == "pad" }.question)
        val everywhere = assertNotNull(screen.everywhere)
        assertEquals("Turn off everywhere and delete face data", everywhere.label)
        assertEquals("Turn off \"Group photos by people\" everywhere?", everywhere.question.title)
        assertEquals("Face data is deleted on every device. The names you typed are kept.", everywhere.question.text)
    }

    /** ⚠⚠ Turning ON asks nothing, and an off or absent row has nothing to delete: no question, no sentence. */
    @Test
    fun aSwitchThatIsOffOrNeverWrittenAsksNothingAndPromisesNoDeletion() {
        val screen = assertNotNull(photosFeatureScreen(state(facesOn = false, facesRealm = false), faces, "iPhone"))
        assertEquals(PhotosModelsCopy.DEVICE_OFF, screen.thisDevice.status)
        assertEquals(PhotosFeatureScreen.Press.TurnOnHere, screen.thisDevice.press)
        assertNull(screen.thisDevice.question)
        assertTrue("delete" !in screen.thisDevice.status)
    }

    /** ⚠ Search's screen is as it was: its off deletes nothing, so only *everywhere* asks. */
    @Test
    fun searchsScreenStillAsksOnlyForEverywhere() {
        val screen = assertNotNull(photosFeatureScreen(state(facesOn = true, others = listOf(OtherDeviceConsent("mac", "Mac", true))), search, "iPhone"))
        assertEquals("Search photos by description", screen.title)
        assertEquals(PhotosModelsCopy.FEATURE_DESCRIPTION, screen.about)
        assertNull(screen.thisDevice.question)
        assertNull(screen.others.single().question)
        assertEquals("Turn off everywhere", screen.everywhere?.label)
        assertEquals(PhotosModelsCopy.TURN_OFF_EVERYWHERE_TEXT, screen.everywhere?.question?.text)
    }

    /** Removing the face model deletes no face data, and its question says so; search's says what it always said. */
    @Test
    fun eachModelsRemovalSaysWhatStopsWorking() {
        fun asked(row: ModelRow) = photosModelsScreen(state().copy(pendingDeletion = row), "iPhone", null).question?.question?.text
        assertEquals(PhotosModelsCopy.facesRemoveText(453_327_426, "iPhone"), asked(face()))
        assertTrue(asked(face())!!.contains("face data already made stays until you turn it off"))
        assertEquals(PhotosModelsCopy.removeText(2_579_969_593, "iPhone"), asked(siglip))
    }

    @Test
    fun theFaceModelThisPhoneCannotRunSaysFacesNotSearch() {
        val rows = assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(state(rows = listOf(siglip, face(ModelSetup.Standing.Unsuitable))), "iPhone", null).body).rows
        assertEquals(PhotosModelsCopy.facesUnsuitable("iPhone"), rows[1].status)
        assertEquals("Ready", rows[0].status)
    }

    @Test
    fun theOfferAfterTheFaceModelLandsIsTheFaceSwitchsOwn() {
        val asked = assertNotNull(photosModelsScreen(state().copy(featureOffer = faces), "iPhone", null).question)
        assertEquals(PhotosModelsScreen.Asked.Kind.FEATURE_OFFER, asked.kind)
        assertEquals("Turn on \"Group photos by people\" for this iPhone?", asked.question.title)
        assertTrue(asked.question.text.contains("Turning this off deletes the face data and keeps the names."), asked.question.text)
    }

    /** With face grouping on and search off, the pass has something to do: *Index now* is offered. */
    @Test
    fun theIndexingSectionStartsWithEitherSwitchOn() {
        val searchOff = state(facesOn = true).let { it.copy(consents = it.consents.map { c -> if (c.capability == search) c.copy(realm = false, device = false) else c }) }
        val indexing = assertNotNull(photosModelsScreen(searchOff, "iPhone", null).indexing)
        assertEquals(listOf(PhotosModelsScreen.IndexAction.Kind.INDEX_NOW), indexing.actions.map { it.kind })
        val bothOff = searchOff.copy(consents = searchOff.consents.map { it.copy(realm = false, device = false) })
        // Both switches named: either would give the pass something to do.
        val off = assertNotNull(photosModelsScreen(bothOff, "iPhone", null).indexing)
        assertEquals("Turn on \"Search photos by description\" or \"Group photos by people\" above to index your photos.", off.status)
        assertEquals(emptyList(), off.actions)
    }

    private fun searchOff(state: ModelsUiState) = state.copy(consents = state.consents.map { if (it.capability == search) it.copy(realm = false, device = false) else it })

    /**
     * ⚠⚠ The P2: search's model ready but search off, face grouping on but ITS model absent. The
     * operation refuses every press (`NO_CONSENT`: no feature both on and backed), so no *Index now*,
     * and the line names the model face grouping waits for.
     */
    @Test
    fun faceGroupingOnWithoutItsModelOffersNoIndexNowThoughSearchsModelIsReady() {
        val state = searchOff(state(facesOn = true, rows = listOf(siglip, faceAbsent)))
        assertEquals(true, state.anyModelUsable, "the fixture: search's model is ready")
        val indexing = assertNotNull(photosModelsScreen(state, "iPhone", null).indexing)
        assertEquals(emptyList(), indexing.actions, "Index now offered where every press is refused")
        assertEquals("You can index your photos once the model for \"Group photos by people\" is ready.", indexing.status)
    }

    @Test
    fun faceGroupingOnWithAModelThisPhoneCannotRunSaysSo() {
        val state = searchOff(state(facesOn = true, rows = listOf(siglip, face(ModelSetup.Standing.Unsuitable))))
        val indexing = assertNotNull(photosModelsScreen(state, "iPhone", null).indexing)
        assertEquals(emptyList(), indexing.actions)
        assertEquals("This iPhone can't run the model for \"Group photos by people\", so it can't index for it.", indexing.status)
    }

    /** The mirror: search on with no model of its own, the face model ready and face grouping off. */
    @Test
    fun searchOnWithoutItsModelOffersNoIndexNowThoughTheFaceModelIsReady() {
        val siglipAbsent = ModelRow("siglip", "SigLIP", ModelSetup.Absent(OfferedModel("siglip", "SigLIP", 1, 743_448_870, 2_579_969_593, "Apache-2.0"), null))
        val state = state(facesOn = false, rows = listOf(siglipAbsent, face()))
        assertEquals(true, state.anyModelUsable, "the fixture: the face model is ready")
        val indexing = assertNotNull(photosModelsScreen(state, "iPhone", null).indexing)
        assertEquals(emptyList(), indexing.actions)
        assertEquals("You can index your photos once the model for \"Search photos by description\" is ready.", indexing.status)
    }

    /** ⚠ A feature both on and backed is all a pass needs: either one, alone, offers *Index now*. */
    @Test
    fun eitherFeatureOnWithItsOwnModelOffersIndexNow() {
        val facesOnly = searchOff(state(facesOn = true, rows = listOf(siglip, face())))
        assertEquals(listOf(PhotosModelsScreen.IndexAction.Kind.INDEX_NOW), photosModelsScreen(facesOnly, "iPhone", null).indexing?.actions?.map { it.kind })
        val searchOnly = state(facesOn = false, rows = listOf(siglip, faceAbsent))
        assertEquals(listOf(PhotosModelsScreen.IndexAction.Kind.INDEX_NOW), photosModelsScreen(searchOnly, "iPhone", null).indexing?.actions?.map { it.kind })
    }

    /**
     * ⚠ Before the catalog is read (a cold, offline launch), the graph's own map says which model is
     * whose: the face row's removal and *cannot run* sentences are still face grouping's.
     */
    @Test
    fun beforeTheCatalogIsReadTheGraphSaysWhichModelIsFaceGroupings() {
        val cold = state(rows = listOf(siglip, face(ModelSetup.Standing.Unsuitable))).copy(serves = emptyMap())
            .let { it.copy(capabilities = it.capabilities.copy(modelFeatures = mapOf("siglip" to search, "auraface-v1-int8" to faces))) }
        val rows = assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(cold, "iPhone", null).body).rows
        assertEquals(PhotosModelsCopy.facesUnsuitable("iPhone"), rows[1].status)
        assertEquals(PhotosModelsCopy.facesRemoveText(453_327_426, "iPhone"), photosModelsScreen(cold.copy(pendingDeletion = face()), "iPhone", null).question?.question?.text)
        val facesWait = cold.copy(availability = listed(siglip, faceAbsent))
            .let { it.copy(consents = it.consents.map { c -> if (c.capability == faces) c.copy(realm = true, device = true) else c }) }
        assertEquals(PhotosModelsCopy.STARTS_WHEN_READY + " " + PhotosModelsCopy.facesOffDeletes("iPhone"), photosModelsScreen(facesWait, "iPhone", null).features[1].detail)
        // ⚠ With neither the catalog nor the graph saying, a row is search's, as before.
        val unknown = cold.let { it.copy(capabilities = it.capabilities.copy(modelFeatures = emptyMap())) }
        assertEquals(PhotosModelsCopy.unsuitable("iPhone"), assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(unknown, "iPhone", null).body).rows[1].status)
    }
}
