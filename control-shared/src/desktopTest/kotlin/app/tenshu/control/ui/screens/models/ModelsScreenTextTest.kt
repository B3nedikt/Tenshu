package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.LastIndexCoverage
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.ui.faceClusteringRunsHere
import app.tenshu.control.ui.screens.offlinemaps.formatBytes
import org.tenshu.inference.InferenceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Models screen **says** for a given state: the sentences and the offered-or-not decisions
 * that live beside the composables.
 *
 * ⚠ The other half is in `:control-shared-logic` (`ModelsViewModelTest`, `ModelsSnapshotTest`),
 * which pins how the view model reaches each state. The two meet on the state's value: a test
 * here starts from the state a test there ends on.
 */
class ModelsScreenTextTest {

    /** The row's second line always carries the size — it is the number the decision turns on. */
    @Test
    fun theSupportingLineAlwaysCarriesTheSizeExceptWhileRemoving() {
        val absent = ManagedModel("m", "M", byteSize = 401_604_608)
        val installed = absent.copy(installed = true)
        val stale = installed.copy(updateAvailable = true)
        fun download(written: Long?) = ModelSetup.Download(allowsCellular = true, writtenBytes = written, totalBytes = 401_604_608, held = null)

        assertEquals("383 MB", supportingTextFor(absent.row().setup))
        assertEquals("Installed · 383 MB", supportingTextFor(installed.row().setup))
        assertEquals("Update available · 383 MB", supportingTextFor(stale.row().setup))
        assertEquals("Downloading… · 383 MB", supportingTextFor(absent.row(download = download(null)).setup))
        // ⚠ Once bytes are flowing it becomes "X of Y" — the only form that stays honest across the
        // checkpoint's three artifacts, where a per-file count would visibly reset twice.
        assertEquals("100 MB of 383 MB", supportingTextFor(absent.row(download = download(104_857_600)).setup))
        // ⚠ An update being fetched says the same, and the model under it is still installed.
        assertEquals("100 MB of 383 MB", supportingTextFor(stale.row(download = download(104_857_600)).setup))
        // ⚠ The one exception: a removal is instant, and the size is about to stop being true.
        assertEquals("Removing…", supportingTextFor(installed.row().setup, removing = true))
        // The moment between a press and its answer.
        assertEquals("Starting… · 383 MB", supportingTextFor(absent.row().setup, starting = true))
        // ⚠ But a download the repository already reports is said as that, pressed here or not.
        assertEquals("100 MB of 383 MB", supportingTextFor(stale.row(download = download(104_857_600)).setup, starting = true))
        // ⚠ And for whatever else a download was pressed on: an update, or an install with a file missing.
        assertEquals("Starting… · 383 MB", supportingTextFor(stale.row().setup, starting = true))
    }

    /**
     * ⚠⚠ How the last download ended is said under its row, and an integrity failure never as
     * "try again": asking again fetches the same file.
     */
    @Test
    fun theRowSaysHowItsLastDownloadEnded() {
        val absent = ManagedModel("m", "M", byteSize = 401_604_608)
        val failed = supportingTextFor(absent.row(last = ModelSetup.DownloadSaid.Failed(integrity = false)).setup)
        val mismatched = supportingTextFor(absent.row(last = ModelSetup.DownloadSaid.Failed(integrity = true)).setup)

        assertEquals("383 MB · The last download didn't finish.", failed)
        assertTrue(mismatched.startsWith("383 MB · "), mismatched)
        assertTrue("didn't match its signature" in mismatched, mismatched)
        assertFalse("again" in mismatched.lowercase(), mismatched)
        // An update that failed leaves the model installed and says so beside the offer.
        val stale = absent.copy(installed = true, updateAvailable = true)
        assertEquals(
            "Update available · 383 MB · The last download didn't finish.",
            supportingTextFor(stale.row(last = ModelSetup.DownloadSaid.Failed(integrity = false)).setup),
        )
        assertNull(downloadSaidText(ModelSetup.DownloadSaid.Cancelled))
    }

    /** ⚠⚠ Not known is not none: over a failed read the indexing line never says "no model installed". */
    @Test
    fun theIndexingLineNeverSaysNoModelOverAReadThatFailed() {
        val line = indexingSupportingText(IndexProgressState(), paused = false, anyModelInstalled = null, anyAllowedHere = true)

        assertEquals(INSTALLED_NOT_KNOWN, line)
        assertFalse("No model installed" in line)
        for (failed in listOf(ModelsAvailability.InstalledUnreadable, ModelsAvailability.NotConnected, ModelsAvailability.Loading)) {
            assertNull(ModelsUiState(ControlModelsCapabilities, availability = failed).anyModelUsable, "$failed")
        }
        assertEquals(false, ModelsUiState(loading = false).anyModelUsable)
    }

    /**
     * The indexing line (IN-D6).
     *
     * ⚠⚠ **A paused device outranks everything**, including a pass that has not yet noticed: pause
     * is read per image, so a pass can still be mid-loop for one more photograph, and "Indexing 300
     * of 9 000" under a button that says Resume is a screen arguing with itself.
     *
     * ⚠⚠ **"No model installed" is not "not indexing".** With no model every pass is a counted
     * no-op, and the screen must never tell a user their photos are waiting for indexing that
     * cannot happen.
     *
     * ⚠ When it runs is said even when nothing is: a multi-hour pass happening silently is what
     * IN-D6 exists to prevent, and a line that only spoke mid-pass would be silent on every device
     * that is behaving normally.
     */
    @Test
    fun theIndexingLineSaysWhichOfPausedRunningAndIdleItIs() {
        val running = IndexProgressState(indexing = true, done = 300, total = 9_000)
        val idle = IndexProgressState()

        assertEquals(
            "300 of 9000 photos",
            indexingSupportingText(running, paused = false, anyModelInstalled = true, anyAllowedHere = true),
        )
        assertEquals(
            "Runs when this device is idle and charging.",
            indexingSupportingText(idle, paused = false, anyModelInstalled = true, anyAllowedHere = true),
        )
        assertEquals(
            "No model installed, so nothing is indexed yet.",
            indexingSupportingText(idle, paused = false, anyModelInstalled = false, anyAllowedHere = true),
        )
        // ⚠⚠ A model and no switch on here: every pass would skip every photo.
        assertEquals(
            "Nothing is switched on for this device, so nothing is indexed. Pick a feature under Features.",
            indexingSupportingText(idle, paused = false, anyModelInstalled = true, anyAllowedHere = false),
        )
        // ⚠⚠ Paused wins over a still-running pass AND over the missing model.
        assertEquals(
            "Paused on this device.",
            indexingSupportingText(running, paused = true, anyModelInstalled = true, anyAllowedHere = true),
        )
        assertEquals(
            "Paused on this device.",
            indexingSupportingText(idle, paused = true, anyModelInstalled = false, anyAllowedHere = true),
        )
    }

    /**
     * After a pass, the indexing line says how much of the library it could reach — photographs
     * stored only on the relay cannot be indexed here, and People looks broken unless something
     * says so.
     */
    @Test
    fun theIndexingLineSaysHowManyPhotosArentOnThisDevice() {
        val idle = IndexProgressState()
        assertEquals(
            "55 of 1345 photos can be indexed on this device. 1290 aren't stored here, so they can't " +
                "be searched or grouped.",
            indexingSupportingText(idle, paused = false, anyModelInstalled = true, anyAllowedHere = true,
                coverage = IndexCoverage(total = 1345, notOnDevice = 1290)),
        )
        // Everything on the device: nothing to explain, the usual line.
        assertEquals(
            "Runs when this device is idle and charging.",
            indexingSupportingText(idle, paused = false, anyModelInstalled = true, anyAllowedHere = true,
                coverage = IndexCoverage(total = 10, notOnDevice = 0)),
        )
    }

    /** ⚠ A deferred or paused pass walked nothing; recording it would replace a real answer with 0 of 0. */
    @Test
    fun aPassThatDidNotRunLeavesTheLastCoverageAlone() {
        val holder = LastIndexCoverage()
        holder.record(total = 1345, notOnDevice = 1290, ran = true)
        holder.record(total = 0, notOnDevice = 0, ran = false)

        assertEquals(IndexCoverage(1345, 1290), holder.coverage.value)
    }

    /**
     * §38.1: a feature's row says where it stands — this device first, then how many others.
     *
     * ⚠⚠ "On for N other devices, not this one" is its own sentence: it is the state a second device
     * opens in, and plain "On" there would describe work that is not happening here. ⚠⚠ And a device
     * answer counts only under a realm yes (§9.2) — but a yes still stored under a no is said.
     */
    @Test
    fun theFeatureRowSaysWhichDevicesItIsOnFor() {
        val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        fun other(id: String, allowed: Boolean) = OtherDeviceConsent(id, id, allowed)

        assertEquals("Off", featureSummary(CapabilityConsent(search), listOf(other("a", false))))
        assertEquals("On for this device", featureSummary(CapabilityConsent(search, realm = true, device = true), emptyList()))
        assertEquals(
            "On for this device and 1 other device",
            featureSummary(CapabilityConsent(search, realm = true, device = true), listOf(other("a", true), other("b", false))),
        )
        assertEquals(
            "On for 2 other devices, not this one",
            featureSummary(CapabilityConsent(search, realm = true), listOf(other("a", true), other("b", true))),
        )
        assertEquals("Off on every device", featureSummary(CapabilityConsent(search, realm = true), listOf(other("a", false))))
        assertEquals(
            "Off. 2 devices would start again if it's turned back on.",
            featureSummary(CapabilityConsent(search, device = true), listOf(other("a", true))),
        )
    }

    /** IN-D3: what a feature does and where its index lives is said before the user agrees. */
    @Test
    fun theSearchDescriptionSaysWhereTheIndexLives() {
        assertTrue(featureDescription(InferenceCapability.SEMANTIC_IMAGE_SEARCH).contains("never leaves that device"))
    }

    /**
     * The face switch is offered, and — biometric data, A40 — its off line says the three
     * things a user must know before agreeing: the face data stays on the device, only typed names
     * sync, and switching off deletes the face data but not the names.
     */
    @Test
    fun theFaceSwitchIsOfferedAndSaysWhatStaysWhatSyncsAndWhatIsDeleted() {
        val faces = InferenceCapability.FACE_CLUSTERING
        assertTrue(ModelsUiState(ControlModelsCapabilities).consents.any { it.capability == faces })
        assertEquals("Group photos by people", consentHeadline(faces))

        val off = featureDescription(faces)
        assertTrue(off.contains("never leaves it"), off)
        assertTrue(off.contains("Only the names you type sync"), off)
        assertTrue(off.contains("deletes the face data and keeps the names"), off)
    }

    /**
     * The face switch appears only on a build that can run the face pipeline — a
     * build fact, never a catalog inference — and a switch that is ON is never hidden.
     */
    @Test
    fun theFaceSwitchIsShownOnlyWhereTheBuildRunsFaces() {
        val search = CapabilityConsent(InferenceCapability.SEMANTIC_IMAGE_SEARCH)
        val faces = CapabilityConsent(InferenceCapability.FACE_CLUSTERING)

        assertEquals(listOf(search, faces), visibleConsents(listOf(search, faces), runsFaces = true))
        assertEquals(listOf(search), visibleConsents(listOf(search, faces), runsFaces = false))
        // ⚠⚠ An ON switch stays visible even where the build cannot run it — never an invisible consent.
        val on = faces.copy(device = true)
        assertEquals(listOf(search, on), visibleConsents(listOf(search, on), runsFaces = false))
        // ⚠ This desktop test build IS the desktop actual.
        assertEquals(false, faceClusteringRunsHere)
    }

    /**
     * ⚠⚠ A yes this device still holds under a realm no says what it will do. It is an ordinary
     * state — a failed clear leaves one — and unsaid, it resumes the work the moment any device turns
     * the feature back on. ⚠ The same for another device's, which *Turn off everywhere* must reach.
     */
    @Test
    fun aLingeringYesUnderARealmNoIsSaidAndCanBeTurnedOff() {
        val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH
        val lingeringHere = CapabilityConsent(search, realm = false, device = true)
        val there = OtherDeviceConsent("a", "Laptop", allowed = true)

        assertTrue(thisDeviceSupportingText(lingeringHere).contains("starts indexing again"))
        assertTrue(otherDeviceSupportingText(realm = false, there).contains("starts again"))
        assertEquals("On", otherDeviceSupportingText(realm = true, there))
        // ⚠⚠ An off device says where to turn it on — its switch here cannot (§38.2).
        assertTrue(otherDeviceSupportingText(realm = true, there.copy(allowed = false)).contains("from that device"))

        assertTrue(canTurnOffEverywhere(lingeringHere, emptyList()))
        assertTrue(canTurnOffEverywhere(CapabilityConsent(search), listOf(there)))
        assertFalse(canTurnOffEverywhere(CapabilityConsent(search), listOf(there.copy(allowed = false))))
    }

    /**
     * §38.2: switching faces off deletes face data, so every such switch asks first and says so —
     * and search, which deletes nothing (§32.8), never claims to.
     */
    @Test
    fun onlyFacesPromiseADeletionWhenSwitchedOff() {
        val faces = InferenceCapability.FACE_CLUSTERING
        val search = InferenceCapability.SEMANTIC_IMAGE_SEARCH

        assertTrue(turnOffDeletes(faces))
        assertFalse(turnOffDeletes(search))
        assertTrue(turnOffText(faces, everywhere = false).contains("face data is deleted"))
        assertTrue(turnOffText(faces, everywhere = true).contains("deleted on every device"))
        assertTrue(turnOffText(faces, everywhere = true).contains("names you typed are kept"))
        listOf(true, false).forEach { everywhere ->
            assertFalse(turnOffText(search, everywhere).contains("delete"), turnOffText(search, everywhere))
        }
        assertEquals("Turn off everywhere and delete face data", turnOffEverywhereLabel(faces))
        assertEquals("Turn off \"Group photos by people\" on Laptop?", turnOffTitle(faces, "Laptop"))
        assertEquals("Turn off \"Search photos by description\" everywhere?", turnOffTitle(search, null))
    }

    /**
     * ⚠⚠ **There is no honest bar for a pass over an empty library.** `total = 0` is a real state,
     * and a fraction over it is either a division by zero or a bar that reads full for work that
     * never happened — so the answer is *no bar*, not a number.
     *
     * ⚠ Nor for a device that is not indexing: a bar pinned at 0 reads as a stalled pass rather
     * than an absent one, the same call §25.4 makes for the download row.
     */
    @Test
    fun theIndexingBarIsDrawnOnlyForAPassWithSomethingInIt() {
        assertEquals(
            0.5f,
            indexBarFraction(IndexProgressState(indexing = true, done = 50, total = 100)),
        )
        assertNull(indexBarFraction(IndexProgressState(indexing = true, done = 0, total = 0)))
        assertNull(indexBarFraction(IndexProgressState(indexing = false, done = 40, total = 100)))
        // ⚠ Clamped: `done` and `total` arrive from separate ticks of a conflating StateFlow and can
        // briefly disagree.
        assertEquals(
            1f,
            indexBarFraction(IndexProgressState(indexing = true, done = 120, total = 100)),
        )
    }

    // ─── a Control that may not change the model store ──────────────────────────────────────────

    /**
     * Off for a row that is busy, and off for good once the store is read-only — either kind.
     * ⚠ A busy row holds no other row's buttons: there is no one slot for the whole list.
     */
    @Test
    fun theButtonsGoWhileARowIsBusyAndForGoodInAReadOnlyStore() {
        val idle = ModelsUiState(loading = false)

        assertTrue(rowActionsEnabled(idle, "siglip"))
        assertFalse(rowActionsEnabled(idle.copy(starting = setOf("siglip")), "siglip"))
        assertFalse(rowActionsEnabled(idle.copy(removing = setOf("siglip")), "siglip"))
        assertTrue(rowActionsEnabled(idle.copy(starting = setOf("siglip"), removing = setOf("faces")), "other"))
        assertFalse(rowActionsEnabled(idle.copy(storeReadOnly = StoreReadOnly.OwnedElsewhere), "siglip"))
        assertFalse(rowActionsEnabled(idle.copy(storeReadOnly = StoreReadOnly.LockUnavailable()), "siglip"))
    }

    /** ⚠ The sentence names the action, because no button on the screen can perform it. */
    @Test
    fun aStoreOwnedElsewhereSaysToQuitTheOtherControl() {
        val notice = storeReadOnlyNotice(StoreReadOnly.OwnedElsewhere)

        assertTrue("Quit the other one" in notice)
        assertFalse("try again" in notice.lowercase())
    }

    /**
     * ⚠⚠ Not the same sentence as "another Control owns it": there may be no other Control. It says
     * what failed and where, and names no remedy it cannot justify.
     */
    @Test
    fun aLockThatCouldNotBeTakenNeverBlamesAnotherControl() {
        val detail = "/data/content/inference/models/store.lock: AccessDeniedException: denied"
        val notice = storeReadOnlyNotice(StoreReadOnly.LockUnavailable(detail))

        assertTrue(detail in notice, "the sentence must say what failed and where: $notice")
        assertFalse("Quit" in notice, "nothing to quit: $notice")
        assertFalse("Another Control is running" in notice)
        assertFalse("try again" in notice.lowercase())
        // ⚠ The typed refusal carries no detail, and still must not send anyone after a process.
        assertFalse("Quit" in storeReadOnlyNotice(StoreReadOnly.LockUnavailable()))
    }

    // ─── removing does not free the disk at once, and nothing may say it does ───────────────────

    /**
     * ⚠⚠ Reclamation waits for Control's next start (F2v), so the dialog never promises freed
     * space — a long-lived Control would make that false for as long as it ran.
     */
    @Test
    fun theRemoveDialogNeverPromisesFreedSpace() {
        val text = removeConfirmationText(ManagedModel("siglip", "siglip", 401_604_608, installed = true))

        assertFalse("frees" in text.lowercase(), text)
        assertTrue("stops being used right away" in text, text)
        assertTrue("released the next time Control starts" in text, text)
        // The number stays: it is what the decision turns on.
        assertTrue(formatBytes(401_604_608) in text, text)
        // ⚠ IN-D10's half of the sentence is untouched.
        assertTrue("stay switched on" in text, text)
    }

    /** ⚠ After the removal and after an update too: "removed" with nothing else said reads as "freed". */
    @Test
    fun theSpacePendingLinesSayWhenTheSpaceIsReleased() {
        assertTrue("next time Control starts" in REMOVED_SPACE_PENDING)
        assertFalse("free" in REMOVED_SPACE_PENDING.lowercase())
        assertTrue("next time Control starts" in UPDATED_SPACE_PENDING)
    }
}
