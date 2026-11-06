package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelSetup.DownloadSaid
import app.tenshu.control.inference.ModelSetup.Standing
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.ModelsAvailability.Catalog
import app.tenshu.control.inference.ModelsAvailability.Downloads
import app.tenshu.control.inference.OfferedModel
import app.tenshu.control.inference.download
import app.tenshu.control.inference.offersDownload
import app.tenshu.control.inference.offersRemoval
import app.tenshu.control.ui.screens.models.PhotosModelsScreen.Action.Kind
import org.junit.Test
import org.tenshu.inference.InferenceCapability
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Photos' Models screen as values: §14.18.3's table, a case per row. Each asserts the sentence
 * **and which buttons exist**, because a sentence promises only what the next press can do.
 */
class PhotosModelsCopyTest {

    private val copy = PhotosModelsCopy
    private val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
    private val offered = OfferedModel("siglip", "SigLIP", 2, downloadBytes = 709_000_000, onDeviceBytes = 2_600_000_000, licence = "Apache-2.0")
    private val newer = offered.copy(modelVersion = 3, downloadBytes = 720_000_000)
    private val model = HeldModel("siglip", "SigLIP", 2, onDeviceBytes = 2_600_000_000, licence = "Apache-2.0")

    private fun state(
        availability: ModelsAvailability,
        capabilities: ModelsCapabilities = OnDeviceModelsCapabilities,
        consents: List<CapabilityConsent> = listOf(CapabilityConsent(search)),
    ) = ModelsUiState(capabilities = capabilities, availability = availability, consents = consents, consentsRead = true)

    private fun listed(vararg setups: ModelSetup, catalog: Catalog = Catalog.READ, downloads: Downloads = Downloads.KNOWN) =
        ModelsAvailability.Listed(catalog, downloads, setups.map { ModelRow("siglip", "SigLIP", it) })

    private fun body(availability: ModelsAvailability) = photosModelsScreen(state(availability), "iPhone", onWifi = null).body
    private fun row(setup: ModelSetup, onWifi: Boolean? = null, change: (ModelsUiState) -> ModelsUiState = { it }): PhotosModelsScreen.Row =
        assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(change(state(listed(setup))), "iPhone", onWifi).body).rows.single()

    private val PhotosModelsScreen.Row.kinds get() = actions.map { it.kind }
    private val PhotosModelsScreen.Row.labels get() = actions.map { it.label }
    private fun download(held: DownloadSaid? = null, written: Long? = 354_500_000, cellular: Boolean = false) =
        ModelSetup.Download(cellular, written, 709_000_000, held)
    private fun held(standing: Standing, update: ModelSetup.Update = ModelSetup.Update.None) = ModelSetup.Held(model, standing, update)

    @Test
    fun bytesAreSaidAsTheDevicesOwnStorageScreenCountsThem() {
        assertEquals("709 MB", photosModelBytes(709_000_000))
        assertEquals("2.6 GB", photosModelBytes(2_600_000_000))
        assertEquals("1.0 GB", photosModelBytes(1_000_000_000))
        assertEquals("999 MB", photosModelBytes(999_400_000))
        assertEquals("0 MB", photosModelBytes(-5))
    }

    /** Rows 1 to 6: what is said when there is no row to say it under. */
    @Test
    fun theListAsAWholeSaysWhyThereIsNoneAndOffersARetryOnlyWhereOneCanHelp() {
        assertEquals(PhotosModelsScreen.Body.Hidden, body(ModelsAvailability.NotOffered(ModelsAvailability.NotOffered.Why.NOT_IN_THIS_BUILD)))
        assertEquals(
            PhotosModelsScreen.Body.Notice(copy.notOffered("iPhone"), copy.OS_TOO_OLD),
            body(ModelsAvailability.NotOffered(ModelsAvailability.NotOffered.Why.OS_TOO_OLD)),
        )
        assertEquals(
            PhotosModelsScreen.Body.Notice(copy.notOffered("iPhone"), copy.notEnoughMemory("iPhone")),
            body(ModelsAvailability.NotOffered(ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY)),
        )
        assertEquals(PhotosModelsScreen.Body.Notice(copy.NOT_CONNECTED, tryAgain = copy.TRY_AGAIN), body(ModelsAvailability.NotConnected))
        assertEquals(PhotosModelsScreen.Body.Notice(copy.LOADING), body(ModelsAvailability.Loading))
        // ⚠ An unreadable index: the retry and nothing that writes.
        assertEquals(
            PhotosModelsScreen.Body.Notice(copy.installedUnreadable("iPhone"), tryAgain = copy.TRY_AGAIN),
            body(ModelsAvailability.InstalledUnreadable),
        )
        assertEquals(PhotosModelsScreen.Body.Notice(copy.LOADING), body(listed(catalog = Catalog.NOT_READ_YET)))
        assertEquals(PhotosModelsScreen.Body.Notice(copy.CATALOG_UNAVAILABLE, tryAgain = copy.TRY_AGAIN), body(listed(catalog = Catalog.UNAVAILABLE)))
        // ⚠ Nothing published is not *could not be loaded*, and has no retry.
        assertEquals(PhotosModelsScreen.Body.Notice(copy.nothingPublished("iPhone")), body(listed(catalog = Catalog.READ)))
    }

    @Test
    fun aListThatIsOnlyPartlyKnownSaysSoAboveItsRows() {
        fun notice(catalog: Catalog, downloads: Downloads = Downloads.KNOWN) =
            assertIs<PhotosModelsScreen.Body.Rows>(body(listed(held(Standing.Ready), catalog = catalog, downloads = downloads)))
        assertNull(notice(Catalog.READ).notice)
        assertNull(notice(Catalog.READ).tryAgain)
        assertNull(notice(Catalog.NOT_READ_YET).notice)
        assertEquals(copy.CATALOG_STALE, notice(Catalog.STALE).notice)
        assertEquals(copy.CATALOG_UNAVAILABLE, notice(Catalog.UNAVAILABLE).notice)
        assertEquals(copy.TRY_AGAIN, notice(Catalog.UNAVAILABLE).tryAgain)
        // ⚠ The journal first: it is why no row is fetching or absent.
        assertEquals(copy.JOURNAL_UNREADABLE, notice(Catalog.STALE, Downloads.UNREADABLE).notice)
        assertEquals(
            PhotosModelsScreen.Body.Rows(emptyList(), copy.JOURNAL_UNREADABLE, copy.TRY_AGAIN),
            body(listed(downloads = Downloads.UNREADABLE)),
        )
    }

    /** Rows 7 to 7e. */
    @Test
    fun anAbsentModelSaysItsTwoSizesWhatWasLastSaidOfItAndOffersBothWaysToStart() {
        val plain = row(ModelSetup.Absent(offered, null))
        assertEquals("709 MB download · about 2.6 GB on this iPhone · Apache-2.0", plain.facts)
        assertNull(plain.status)
        assertNull(plain.progress)
        assertEquals(listOf(Kind.DOWNLOAD, Kind.DOWNLOAD_WITH_MOBILE_DATA), plain.kinds)
        assertEquals(listOf(copy.DOWNLOAD, copy.USE_MOBILE_DATA), plain.labels)

        val said = mapOf(
            DownloadSaid.NeedsSpace(3_400_000_000, 1_200_000_000) to "Needs 3.4 GB free. This iPhone has 1.2 GB.",
            DownloadSaid.FreeSpaceUnknown to copy.FREE_SPACE_UNKNOWN,
            DownloadSaid.NoTransport to copy.NO_TRANSPORT,
            DownloadSaid.TransfersUnknown to copy.TRANSFERS_UNKNOWN,
            DownloadSaid.JournalUnreadable to copy.JOURNAL_UNREADABLE,
            DownloadSaid.StoreRefused to copy.STORE_REFUSED,
            DownloadSaid.Failed(integrity = false) to copy.DOWNLOAD_FAILED,
            DownloadSaid.Failed(integrity = true) to copy.INTEGRITY_FAILED,
            DownloadSaid.Cancelled to null,
        )
        for ((last, sentence) in said) {
            val row = row(ModelSetup.Absent(offered, last))
            assertEquals(sentence, row.status, "$last")
            assertEquals(listOf(Kind.DOWNLOAD, Kind.DOWNLOAD_WITH_MOBILE_DATA), row.kinds, "$last")
            // ⚠ A retry is promised only where asking again can fetch something else.
            assertEquals(if (last == DownloadSaid.Failed(integrity = false)) copy.TRY_AGAIN else copy.DOWNLOAD, row.labels.first(), "$last")
        }
        // Where the platform has no cellular choice, none is drawn.
        val control = row(ModelSetup.Absent(offered, null)) { it.copy(capabilities = ControlModelsCapabilities) }
        assertEquals(listOf(Kind.DOWNLOAD), control.kinds)
    }

    /** Rows 8 to 8f. */
    @Test
    fun aDownloadSaysHowFarItIsWhyItIsNotMovingAndCanAlwaysBeCancelled() {
        val moving = row(ModelSetup.Fetching(offered, download()))
        assertEquals("355 MB of 709 MB", moving.status)
        assertEquals(copy.CAN_LEAVE, moving.detail)
        assertEquals(PhotosModelsScreen.Progress.Bar(0.5), moving.progress)
        assertEquals(listOf(Kind.CANCEL), moving.kinds)

        // No byte yet: no bar pinned at nothing.
        val starting = row(ModelSetup.Fetching(null, download(written = null)))
        assertEquals(copy.DOWNLOADING, starting.status)
        assertEquals(PhotosModelsScreen.Progress.Busy, starting.progress)
        assertEquals("709 MB download", starting.facts)

        // ⚠ M6: no switch to mobile data once a Wi-Fi-only download has started.
        val waiting = row(ModelSetup.Fetching(offered, download()), onWifi = false)
        assertEquals(copy.WAITING_FOR_WIFI, waiting.status)
        assertEquals(copy.CELLULAR_NEEDS_A_NEW_START, waiting.detail)
        assertEquals(listOf(Kind.CANCEL), waiting.kinds)
        // Not said of a download that may use mobile data, nor while the monitor has not answered.
        assertEquals("355 MB of 709 MB", row(ModelSetup.Fetching(offered, download(cellular = true)), onWifi = false).status)
        assertEquals("355 MB of 709 MB", row(ModelSetup.Fetching(offered, download()), onWifi = null).status)

        val held = mapOf(
            DownloadSaid.NeedsSpace(3_400_000_000, 1_200_000_000) to "Paused. Needs 3.4 GB free; this iPhone has 1.2 GB.",
            DownloadSaid.FreeSpaceUnknown to copy.PAUSED_FREE_SPACE_UNKNOWN,
            DownloadSaid.NoTransport to copy.PAUSED_NO_TRANSPORT,
            DownloadSaid.TransfersUnknown to copy.PAUSED_TRANSFERS_UNKNOWN,
            DownloadSaid.StoreRefused to copy.PAUSED_STORE_REFUSED,
            DownloadSaid.JournalUnreadable to copy.JOURNAL_UNREADABLE,
            DownloadSaid.Failed(integrity = false) to copy.DOWNLOAD_FAILED,
        )
        for ((why, sentence) in held) {
            val row = row(ModelSetup.Fetching(offered, download(why)), onWifi = false)
            assertEquals(sentence, row.status, "$why")
            assertEquals("355 MB of 709 MB", row.detail, "$why")
            // ⚠ Every held download has two ways out.
            assertEquals(listOf(Kind.TRY_AGAIN, Kind.CANCEL), row.kinds, "$why")
        }
    }

    /** Rows 9 to 12. */
    @Test
    fun anInstalledModelSaysWhereItStandsAndOffersOnlyWhatCanChangeThat() {
        class Expected(val status: String, val detail: String?, val kinds: List<Kind>, val busy: Boolean = false)
        val remove = listOf(Kind.REMOVE)
        val retry = listOf(Kind.TRY_AGAIN, Kind.REMOVE)
        val table = mapOf(
            Standing.NotReadYet to Expected(copy.STANDING_NOT_READ, null, retry),
            Standing.Unreadable to Expected(copy.STANDING_UNREADABLE, null, retry),
            Standing.Preparing(Standing.Preparing.How.Now) to Expected(copy.PREPARING_NOW, null, remove, busy = true),
            Standing.Preparing(Standing.Preparing.How.Pending) to Expected(copy.PREPARING, null, remove, busy = true),
            Standing.Preparing(Standing.Preparing.How.NotInForeground) to Expected(copy.PREPARING_NEEDS_FOREGROUND, null, retry),
            Standing.Preparing(Standing.Preparing.How.Failed("vision", null)) to Expected(copy.PREPARE_FAILED, null, retry),
            Standing.Preparing(Standing.Preparing.How.Failed("vision", ModelSetup.Space(1_900_000_000, 400_000_000))) to
                Expected(copy.PREPARE_FAILED, "Needs 1.9 GB free. This iPhone has 400 MB.", retry),
            // ⚠ The one standing a download cures: it fetches what is missing.
            Standing.Preparing(Standing.Preparing.How.Incomplete) to Expected(copy.INCOMPLETE, null, listOf(Kind.DOWNLOAD, Kind.REMOVE)),
            Standing.Preparing(Standing.Preparing.How.Unreadable) to Expected(copy.FILES_UNREADABLE, null, retry),
            Standing.Checking(Standing.Checking.How.Running) to Expected(copy.checking("iPhone"), null, remove, busy = true),
            Standing.Checking(Standing.Checking.How.Pending) to Expected(copy.checking("iPhone"), null, remove, busy = true),
            // ⚠ No *check now* anywhere: nothing can do it.
            Standing.Checking(Standing.Checking.How.Paused(Standing.Checking.Why.HOT)) to Expected(copy.CHECK_PAUSED, "iPhone is warm.", remove),
            Standing.Checking(Standing.Checking.How.Paused(Standing.Checking.Why.MEMORY_PRESSURE)) to Expected(copy.CHECK_PAUSED, "iPhone is low on memory.", remove),
            Standing.Checking(Standing.Checking.How.Paused(Standing.Checking.Why.SLOW)) to Expected(copy.CHECK_PAUSED, copy.CHECK_SLOW, remove),
            Standing.Checking(Standing.Checking.How.Paused(Standing.Checking.Why.BACKING_OFF)) to Expected(copy.CHECK_PAUSED, null, remove),
            Standing.Checking(Standing.Checking.How.Paused(Standing.Checking.Why.ALREADY_TRIED_THIS_TIME)) to Expected(copy.CHECK_PAUSED, null, remove),
            // ⚠ M12: the way out is the removal, and never a download that would fetch nothing.
            Standing.Checking(Standing.Checking.How.InstallFailed) to Expected(copy.installFailed("iPhone"), null, remove),
            Standing.Checking(Standing.Checking.How.NoProbe) to Expected(copy.CHECK_NO_PROBE, null, remove),
            Standing.Checking(Standing.Checking.How.Unreadable) to Expected(copy.CHECK_UNREADABLE, null, retry),
            Standing.Ready to Expected(copy.READY, null, remove),
            Standing.Unsuitable to Expected(copy.unsuitable("iPhone"), null, remove),
            // ⚠ The one way out of a fail: once more, by the user's press (§14.5 item 0).
            Standing.StoppedLoading to Expected(copy.stoppedLoading("iPhone"), null, listOf(Kind.LOAD_AGAIN, Kind.REMOVE)),
        )
        for ((standing, expected) in table) {
            val row = row(held(standing))
            assertEquals(expected.status, row.status, "$standing")
            assertEquals(expected.detail, row.detail, "$standing")
            assertEquals(expected.kinds, row.kinds, "$standing")
            assertEquals(if (expected.busy) PhotosModelsScreen.Progress.Busy else null, row.progress, "$standing")
            assertEquals("about 2.6 GB on this iPhone · Apache-2.0", row.facts)
        }
        assertTrue(copy.installFailed("iPhone").endsWith("Remove it and download it again."))
        assertEquals(copy.DOWNLOAD_AGAIN, row(held(Standing.Preparing(Standing.Preparing.How.Incomplete))).labels.first())
    }

    /**
     * ⚠⚠ No download is offered that the key would answer at once (§14.5 item 0), and a
     * press whose caller stopped waiting says what its call is doing, and what it answered.
     */
    @Test
    fun anAbsentModelThisDeviceAnsweredAlreadyOffersNoDownloadAndAPressThatOutlivedItsWaitSaysSo() {
        val unsuitable = row(ModelSetup.Absent(offered, null, answered = ModelSetup.Answered.UNSUITABLE))
        assertEquals(copy.unsuitable("iPhone"), unsuitable.status)
        assertEquals(emptyList(), unsuitable.kinds)
        val stopped = row(ModelSetup.Absent(offered, null, answered = ModelSetup.Answered.STOPPED_LOADING))
        assertEquals(copy.stoppedLoading("iPhone"), stopped.status)
        assertEquals(listOf(Kind.LOAD_AGAIN), stopped.kinds)

        val starting = row(ModelSetup.Absent(offered, null, pressed = ModelSetup.Pressed.Starting))
        assertEquals(copy.STARTING, starting.status)
        assertEquals(PhotosModelsScreen.Progress.Busy, starting.progress)
        assertEquals(emptyList(), starting.kinds, "a second press beside a call that goes on")
        val refused = row(ModelSetup.Absent(offered, null, pressed = ModelSetup.Pressed.Refused(app.tenshu.control.inference.ModelActionResult.Why.NoCatalog)))
        assertEquals(copy.message(ModelsMessage.CatalogUnavailable, "iPhone"), refused.status)
        assertEquals(listOf(Kind.DOWNLOAD, Kind.DOWNLOAD_WITH_MOBILE_DATA), refused.kinds)

        val updating = row(held(Standing.Ready, ModelSetup.Update.Available(newer, pressed = ModelSetup.Pressed.Starting)))
        assertTrue(updating.detail!!.endsWith(copy.STARTING), updating.detail)
        assertEquals(listOf(Kind.REMOVE), updating.kinds)
    }

    /** Rows 11a to 11c, and M9. */
    @Test
    fun anUpdateIsOfferedBesideTheRemovalAlsoOnAPhoneThatCouldNotRunWhatItHolds() {
        for (standing in listOf(Standing.Ready, Standing.Unsuitable)) {
            val updatable = row(held(standing, ModelSetup.Update.Available(newer, DownloadSaid.Failed(integrity = false))))
            assertEquals(listOf(Kind.DOWNLOAD, Kind.DOWNLOAD_WITH_MOBILE_DATA, Kind.REMOVE), updatable.kinds, "$standing")
            assertEquals(listOf(copy.UPDATE, copy.UPDATE_WITH_MOBILE_DATA, copy.REMOVE), updatable.labels)
            assertEquals("Update available · 720 MB ${copy.DOWNLOAD_FAILED}", updatable.detail)
        }
        // ⚠⚠ Bytes that failed their check are not offered again: the same file would come (I5).
        val corrupt = row(held(Standing.Ready, ModelSetup.Update.Available(newer, DownloadSaid.Failed(integrity = true))))
        assertEquals(listOf(Kind.REMOVE), corrupt.kinds)
        assertEquals("Update available · 720 MB ${copy.INTEGRITY_FAILED}", corrupt.detail)
        // ⚠ With no catalog no update is claimed, and none is ruled out in words either.
        assertNull(row(held(Standing.Ready, ModelSetup.Update.Unknown)).detail)

        val updating = row(held(Standing.Ready, ModelSetup.Update.Fetching(download())))
        assertEquals(copy.READY, updating.status, "the model is installed while its update is fetched")
        assertEquals("355 MB of 709 MB ${copy.CAN_LEAVE}", updating.detail)
        assertEquals(PhotosModelsScreen.Progress.Bar(0.5), updating.progress)
        assertEquals(listOf(Kind.CANCEL, Kind.REMOVE), updating.kinds)
        val stuck = row(held(Standing.Ready, ModelSetup.Update.Fetching(download(DownloadSaid.NoTransport))))
        assertEquals(listOf(Kind.TRY_AGAIN, Kind.CANCEL, Kind.REMOVE), stuck.kinds)
    }

    /** Row 13, and a press that has not been answered: nothing can be done to such a row. */
    @Test
    fun aRowSomethingIsBeingDoneToOffersNothing() {
        for (setup in listOf(held(Standing.Ready), ModelSetup.Absent(offered, null), ModelSetup.Fetching(offered, download()))) {
            val removing = row(setup) { it.copy(removing = setOf("siglip")) }
            assertEquals(copy.REMOVING, removing.status)
            assertEquals(emptyList(), removing.actions)
            assertEquals(PhotosModelsScreen.Progress.Busy, removing.progress)
            val starting = row(setup) { it.copy(starting = setOf("siglip")) }
            assertEquals(copy.STARTING, starting.status)
            assertEquals(emptyList(), starting.actions)
        }
    }

    @Test
    fun theFeatureRowIsTheConsentsAloneAndIsNotDrawnWhereNothingIsOffered() {
        fun feature(availability: ModelsAvailability, on: Boolean) =
            photosModelsScreen(state(availability, consents = listOf(CapabilityConsent(search, realm = on, device = on))), "iPhone", null).feature
        assertEquals(PhotosModelsScreen.Feature(search, copy.FEATURE, "Off for this iPhone", null), feature(listed(held(Standing.Ready)), on = false))
        assertEquals(PhotosModelsScreen.Feature(search, copy.FEATURE, "On for this iPhone", null), feature(listed(held(Standing.Ready)), on = true))
        // On, and no model that can be used yet: it says what it waits for.
        for (notUsable in listOf(listed(ModelSetup.Absent(offered, null)), ModelsAvailability.InstalledUnreadable)) {
            assertEquals(copy.STARTS_WHEN_READY, feature(notUsable, on = true)?.detail, "$notUsable")
            assertNull(feature(notUsable, on = false)?.detail)
        }
        assertEquals("It works once the model is ready and your photos are indexed.", copy.STARTS_WHEN_READY)
        // ⚠ Never over a model this phone cannot run: its own sentence says it never will.
        for (unsuitable in listOf(listed(held(Standing.Unsuitable)), listed(ModelSetup.Absent(offered, null, answered = ModelSetup.Answered.UNSUITABLE)))) {
            assertNull(feature(unsuitable, on = true)?.detail, "$unsuitable")
        }
        // ⚠ Half a consent is off: both switches must be on.
        val half = photosModelsScreen(state(listed(), consents = listOf(CapabilityConsent(search, realm = true, device = false))), "iPhone", null)
        assertEquals("Off for this iPhone", half.feature?.status)
        // No switch on a phone that is offered nothing, nor from a repository that lists no feature.
        val refused = state(ModelsAvailability.NotOffered(ModelsAvailability.NotOffered.Why.OS_TOO_OLD), consents = emptyList())
        assertNull(photosModelsScreen(refused, "iPhone", null).feature)
        assertNull(photosModelsScreen(state(ModelsAvailability.NotConnected, consents = emptyList()), "iPhone", null).feature)
    }

    @Test
    fun everyMessageHasItsSentenceAndTheTwoQuestionsSayWhatTheyChange() {
        val sentences = ModelsMessage.entries.map { copy.message(it, "iPhone") }
        assertTrue(sentences.all { it.isNotBlank() })
        assertEquals(copy.INTEGRITY_FAILED, copy.message(ModelsMessage.IntegrityFailed, "iPhone"))
        assertEquals("Couldn't remove the model. It is still installed.", copy.message(ModelsMessage.DeleteFailed, "iPhone"))

        val base = state(listed(held(Standing.Ready)))
        val asked = photosModelsScreen(
            base.copy(pendingDeletion = ModelRow("siglip", "SigLIP", held(Standing.Ready)), featureOffer = search, message = ModelsMessage.StillRemoving),
            "iPad", null,
        )
        // ⚠⚠ ONE question, and Kotlin's order: the removal the user asked for before the offer.
        val removal = assertNotNull(asked.question)
        assertEquals(PhotosModelsScreen.Asked.Kind.REMOVAL, removal.kind)
        assertEquals("Remove SigLIP?", removal.question.title)
        assertTrue(removal.question.text.startsWith("This frees about 2.6 GB on this iPad."))
        assertEquals(copy.REMOVE to copy.CANCEL, removal.question.yes to removal.question.no)
        assertEquals("Still removing…", asked.message)
        assertEquals(ModelsMessage.StillRemoving, asked.messageShown)
        // The removal answered, the offer is the question.
        val offer = assertNotNull(photosModelsScreen(base.copy(featureOffer = search), "iPad", null).question)
        assertEquals(PhotosModelsScreen.Asked.Kind.FEATURE_OFFER, offer.kind)
        assertEquals("Turn on \"Search photos by description\" for this iPad?", offer.question.title)
        assertEquals(copy.TURN_ON to copy.NOT_NOW, offer.question.yes to offer.question.no)
        val quiet = photosModelsScreen(base, "iPhone", null)
        assertNull(quiet.question)
        assertNull(quiet.message)
        assertNull(quiet.messageShown)
    }

    /** While the list is being read again nothing offers to read it again, and the screen says it is. */
    @Test
    fun whileCheckingAgainNoTryAgainIsOfferedAnywhere() {
        val states = listOf(
            ModelsAvailability.NotConnected, ModelsAvailability.InstalledUnreadable, listed(catalog = Catalog.UNAVAILABLE),
            listed(held(Standing.Unreadable), catalog = Catalog.STALE),
            listed(ModelSetup.Fetching(offered, download(DownloadSaid.NoTransport))),
        )
        for (availability in states) {
            val idle = photosModelsScreen(state(availability), "iPhone", null)
            val checking = photosModelsScreen(state(availability).copy(checkingAgain = true), "iPhone", null)
            assertTrue(idle.offersTryAgain(), "$availability")
            assertEquals(false, idle.checkingAgain)
            assertEquals(false, checking.offersTryAgain(), "$availability")
            assertTrue(checking.checkingAgain)
        }
        // Everything else of the row stays: only the retry is withheld.
        val row = assertIs<PhotosModelsScreen.Body.Rows>(
            photosModelsScreen(state(listed(ModelSetup.Fetching(offered, download(DownloadSaid.NoTransport)))).copy(checkingAgain = true), "iPhone", null).body,
        ).rows.single()
        assertEquals(listOf(Kind.CANCEL), row.kinds)
        assertEquals(copy.PAUSED_NO_TRANSPORT, row.status)
    }

    private fun PhotosModelsScreen.offersTryAgain(): Boolean = when (val body = body) {
        PhotosModelsScreen.Body.Hidden -> false
        is PhotosModelsScreen.Body.Notice -> body.tryAgain != null
        is PhotosModelsScreen.Body.Rows -> body.tryAgain != null || body.rows.any { Kind.TRY_AGAIN in it.kinds }
    }

    @Test
    fun aHeldDownloadWhoseBytesWereNotTheCatalogsPromisesNoRetryAndAnIncompleteInstallWithAnUpdateOffersOneDownload() {
        val wrongBytes = row(ModelSetup.Fetching(offered, download(DownloadSaid.Failed(integrity = true))))
        assertEquals(copy.HELD_INTEGRITY_FAILED, wrongBytes.status)
        assertEquals(listOf(Kind.CANCEL), wrongBytes.kinds)

        val incomplete = row(held(Standing.Preparing(Standing.Preparing.How.Incomplete), ModelSetup.Update.Available(newer)))
        assertEquals(listOf(Kind.DOWNLOAD, Kind.DOWNLOAD_WITH_MOBILE_DATA, Kind.REMOVE), incomplete.kinds)
        assertEquals(copy.UPDATE, incomplete.labels.first())
    }

    /** What the view model would refuse is not drawn: a store that cannot be changed, a second download where one runs at a time. */
    @Test
    fun nothingIsOfferedThatTheViewModelWouldRefuse() {
        val readOnly = row(ModelSetup.Absent(offered, null)) { it.copy(storeReadOnly = StoreReadOnly.OwnedElsewhere) }
        assertEquals(emptyList(), readOnly.kinds)
        assertEquals(emptyList(), row(held(Standing.Ready)) { it.copy(storeReadOnly = StoreReadOnly.OwnedElsewhere) }.kinds)

        val oneAtATime = state(
            ModelsAvailability.Listed(
                Catalog.READ, Downloads.KNOWN,
                listOf(ModelRow("other", "Other", ModelSetup.Fetching(offered, download())), ModelRow("siglip", "SigLIP", ModelSetup.Absent(offered, null))),
            ),
            capabilities = OnDeviceModelsCapabilities.copy(oneDownloadAtATime = true),
        )
        val rows = assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(oneAtATime, "iPhone", null).body).rows
        assertEquals(emptyList(), rows.last().kinds)
        assertEquals(listOf(Kind.CANCEL), rows.first().kinds)
    }

    // ─── Every state there is

    private val everySaid: List<DownloadSaid?> = listOf(
        null, DownloadSaid.NeedsSpace(3_400_000_000, 1_200_000_000), DownloadSaid.FreeSpaceUnknown, DownloadSaid.NoTransport,
        DownloadSaid.TransfersUnknown, DownloadSaid.JournalUnreadable, DownloadSaid.StoreRefused,
        DownloadSaid.Failed(integrity = false), DownloadSaid.Failed(integrity = true), DownloadSaid.Cancelled,
    )
    private val everyDownload: List<ModelSetup.Download> =
        everySaid.flatMap { said -> listOf(null, 354_500_000L).flatMap { written -> listOf(true, false).map { ModelSetup.Download(it, written, 709_000_000, said) } } }
    private val everyStanding: List<Standing> = listOf(
        Standing.NotReadYet, Standing.Unreadable, Standing.Ready, Standing.Unsuitable, Standing.StoppedLoading,
        Standing.Preparing(Standing.Preparing.How.Now), Standing.Preparing(Standing.Preparing.How.Pending),
        Standing.Preparing(Standing.Preparing.How.NotInForeground), Standing.Preparing(Standing.Preparing.How.Failed("vision", null)),
        Standing.Preparing(Standing.Preparing.How.Failed("vision", ModelSetup.Space(2, 1))),
        Standing.Preparing(Standing.Preparing.How.Incomplete), Standing.Preparing(Standing.Preparing.How.Unreadable),
        Standing.Checking(Standing.Checking.How.Running), Standing.Checking(Standing.Checking.How.Pending),
        Standing.Checking(Standing.Checking.How.InstallFailed), Standing.Checking(Standing.Checking.How.NoProbe),
        Standing.Checking(Standing.Checking.How.Unreadable),
    ) + Standing.Checking.Why.entries.map { Standing.Checking(Standing.Checking.How.Paused(it)) }
    private val everyUpdate: List<ModelSetup.Update> =
        listOf(ModelSetup.Update.None, ModelSetup.Update.Unknown) + everySaid.map { ModelSetup.Update.Available(newer, it) } +
            everyDownload.map { ModelSetup.Update.Fetching(it) }
    private val everySetup: List<ModelSetup> =
        everySaid.map { ModelSetup.Absent(offered, it) } +
            everyDownload.flatMap { listOf(ModelSetup.Fetching(offered, it), ModelSetup.Fetching(null, it)) } +
            everyStanding.flatMap { standing -> everyUpdate.map { held(standing, it) } }

    /**
     * The lists above are written by hand, and the walk below is only as whole as they are. So
     * each is held against its sealed type's own members, as the compiler recorded them: a member
     * added to any of them fails here until the walk has a value of it.
     */
    @Test
    fun theWalksListsHoldAMemberOfEveryCaseTheirSealedTypesHave() {
        fun members(type: Class<*>): Set<Class<*>> {
            val permitted = type.permittedSubclasses
            assertTrue(permitted != null && permitted.isNotEmpty(), "$type records no members: this check would pass having checked nothing")
            return permitted!!.toSet()
        }
        fun <T : Any> held(type: Class<T>, values: List<T?>) =
            assertEquals(members(type), values.filterNotNull().mapTo(mutableSetOf<Class<*>>()) { it.javaClass }, type.simpleName)

        held(DownloadSaid::class.java, everySaid)
        assertTrue(null in everySaid && everySaid.filterIsInstance<DownloadSaid.Failed>().map { it.integrity }.toSet() == setOf(true, false))
        held(Standing::class.java, everyStanding)
        held(Standing.Preparing.How::class.java, everyStanding.filterIsInstance<Standing.Preparing>().map { it.how })
        held(Standing.Checking.How::class.java, everyStanding.filterIsInstance<Standing.Checking>().map { it.how })
        assertEquals(
            Standing.Checking.Why.entries.toSet(),
            everyStanding.mapNotNull { ((it as? Standing.Checking)?.how as? Standing.Checking.How.Paused)?.why }.toSet(),
        )
        held(ModelSetup.Update::class.java, everyUpdate)
        held(ModelSetup::class.java, everySetup)
    }

    /**
     * ⚠⚠ Every row a repository can emit, under every mark and both capability sets: each says
     * something, offers no action twice, offers nothing its capabilities or the view model's own
     * guards would refuse, and (I4) has a way out while something is in flight or has gone wrong.
     */
    @Test
    fun everyRowThereIsSaysSomethingOffersNothingTwiceAndNothingThatWouldBeRefusedAndHasAWayOut() {
        var walked = 0
        for (capabilities in listOf(OnDeviceModelsCapabilities, ControlModelsCapabilities.copy(indexing = false)))
            for (setup in everySetup) for (mark in listOf("none", "removing", "starting", "checking", "readOnly")) for (onWifi in listOf(null, true, false)) {
                val uiState = state(listed(setup), capabilities).let {
                    when (mark) {
                        "removing" -> it.copy(removing = setOf("siglip"))
                        "starting" -> it.copy(starting = setOf("siglip"))
                        "checking" -> it.copy(checkingAgain = true)
                        "readOnly" -> it.copy(storeReadOnly = StoreReadOnly.OwnedElsewhere)
                        else -> it
                    }
                }
                val row = assertIs<PhotosModelsScreen.Body.Rows>(photosModelsScreen(uiState, "iPhone", onWifi).body).rows.single()
                val about = "$setup / $mark / wifi=$onWifi / cancel=${capabilities.cancel}"
                walked++
                assertTrue(row.facts.isNotBlank() && row.title.isNotBlank(), about)
                assertTrue(row.status == null || row.status.isNotBlank(), about)
                assertTrue(row.detail == null || row.detail.isNotBlank(), about)
                // Only an absent or a ready model with nothing said of it lets its facts speak alone.
                if (row.status == null) assertTrue(setup is ModelSetup.Absent, about)
                assertEquals(row.kinds.distinct(), row.kinds, "an action twice: $about")
                assertTrue(row.labels.all { it.isNotBlank() }, about)

                // What the capabilities do not have is not drawn.
                if (!capabilities.cancel) assertTrue(Kind.CANCEL !in row.kinds, about)
                if (!capabilities.cellularChoice) assertTrue(Kind.DOWNLOAD_WITH_MOBILE_DATA !in row.kinds, about)
                // What the view model would refuse is not drawn (`download`, `confirmDelete`, `retry`, `cancel`).
                val busy = mark == "removing" || mark == "starting"
                if (Kind.DOWNLOAD in row.kinds || Kind.DOWNLOAD_WITH_MOBILE_DATA in row.kinds) {
                    assertTrue(setup.offersDownload && !busy && mark != "readOnly", about)
                }
                if (Kind.REMOVE in row.kinds) assertTrue(setup.offersRemoval(capabilities.cancel) && !busy && mark != "readOnly", about)
                if (Kind.TRY_AGAIN in row.kinds) assertTrue(mark != "checking" && !busy, about)
                if (Kind.CANCEL in row.kinds) assertTrue(setup.download != null && !busy, about)
                if (busy) assertEquals(emptyList(), row.actions, about)

                // I4: every state that is in flight, or has gone wrong, has a way out the user can see.
                // ⚠ Not while this row's own action, or a re-read, is in flight; not over a read-only
                // store; and Android and desktop cannot end a download (`tech-debt.md`).
                if (mark == "none" && capabilities.cancel) {
                    when (setup) {
                        is ModelSetup.Absent -> assertTrue(Kind.DOWNLOAD in row.kinds, about)
                        is ModelSetup.Fetching -> assertTrue(Kind.CANCEL in row.kinds, about)
                        is ModelSetup.Held -> assertTrue(Kind.REMOVE in row.kinds, about)
                    }
                }
            }
        assertTrue(walked > 5_000, "walked $walked")
    }
}
