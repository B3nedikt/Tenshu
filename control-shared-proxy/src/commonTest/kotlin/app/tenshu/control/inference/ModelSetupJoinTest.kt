package app.tenshu.control.inference

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.advanceTimeBy
import app.tenshu.control.inference.ModelSetup.Standing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.tenshu.inference.proxy.AiReadinessState
import org.tenshu.inference.proxy.ReadinessStanding
import org.tenshu.inference.proxy.PreflightAttempts
import org.tenshu.inference.proxy.PreflightFinding
import org.tenshu.inference.proxy.PreflightKey
import org.tenshu.inference.proxy.PreflightMeasurement
import org.tenshu.inference.proxy.PreflightOutcome
import org.tenshu.inference.proxy.PreflightReport
import org.tenshu.inference.proxy.PrepareKey
import org.tenshu.inference.proxy.PrepareOutcome
import org.tenshu.inference.proxy.PrepareRefusal
import org.tenshu.inference.proxy.PrepareReport
import org.tenshu.inference.proxy.RuntimeFingerprint
import org.tenshu.inference.proxy.catalog.InstalledArtifactData
import org.tenshu.inference.proxy.catalog.InstalledModelData
import org.tenshu.inference.proxy.catalog.JournalledModelDownload
import org.tenshu.inference.proxy.catalog.ModelArtifactData
import org.tenshu.inference.proxy.catalog.ModelDownloadJournalView
import org.tenshu.inference.proxy.catalog.ModelDownloadKey
import org.tenshu.inference.proxy.catalog.ModelDownloadOutcome
import org.tenshu.inference.proxy.catalog.ModelDownloadProgressSnapshot
import org.tenshu.inference.proxy.catalog.ModelDownloadRefusal
import org.tenshu.inference.proxy.catalog.ModelEntryData
import org.tenshu.inference.proxy.catalog.ModelStorageInsufficientException
import org.tenshu.inference.proxy.catalog.ModelDownloadChoiceConflictException
import org.tenshu.inference.proxy.catalog.ModelDownloadJournalUnreadableException
import org.tenshu.inference.proxy.catalog.downloadKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ios-on-device-inference.md` §14.18.3, row by row, and §14.18.2's rule: what the process
 * remembers fills in a case the disk chose, and chooses none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupJoinTest {

    private val model = "siglip"

    private fun entry(version: Int = 1, digit: Char = 'a') = ModelEntryData(
        modelId = model, name = "SigLIP", modelVersion = version, capabilities = emptyList(), labelSetVersion = 1,
        runtime = "coreml", artifactVersion = 1,
        artifacts = listOf(
            ModelArtifactData("vision", "v$version.tar", "$digit".repeat(64), 400),
            ModelArtifactData("text", "t$version.tar", "${digit + 1}".repeat(64), 300),
        ),
        licence = "Apache-2.0",
    )

    private fun record(of: ModelEntryData = entry()) = InstalledModelData(
        of.modelId, of.modelVersion, of.artifacts.map { InstalledArtifactData(it.role, "models/${it.path}", it.byteSize, it.sha256) }, 1, of.licence,
    )

    private fun prepareKey(of: InstalledModelData) = PrepareKey(model, of.artifacts.map { it.sha256 })

    private fun preflightKey(of: InstalledModelData) = PreflightKey(
        RuntimeFingerprint(of.artifacts[0].sha256, of.artifacts[1].sha256, "ane", "23A1", 1), "vocab", "100", 1,
    )

    private fun installed(of: InstalledModelData? = record()) =
        InstalledRead.Read(of, of?.let(::prepareKey), of?.let(::preflightKey), onDeviceBytes = 2_400)

    private val nothingInstalled = InstalledRead.Read(null, null, null)
    private val offered = OfferedModel(model, "SigLIP", 1, 700, 2_500, "Apache-2.0")
    private val noJournal = ModelDownloadJournalView.Read(emptyMap())
    private val read = ModelsAvailability.Catalog.READ
    private val known = ModelsAvailability.Downloads.KNOWN

    private fun journalNaming(of: ModelEntryData = entry(), cellular: Boolean = false) =
        ModelDownloadJournalView.Read(mapOf(model to JournalledModelDownload(of.downloadKey(), cellular)))

    private fun disk(
        readiness: AiReadinessState? = AiReadinessState.NoModel,
        installed: InstalledRead = nothingInstalled,
        catalog: CatalogRead = CatalogRead.Read(entry(), 2_500),
        journal: ModelDownloadJournalView = noJournal,
        retired: Boolean = false,
    ) = ModelOnDisk(
        model, retired,
        // ⚠ About the install that is there: the key read beside the record.
        readiness?.let { ReadinessStanding.For((installed as? InstalledRead.Read)?.preflightKey ?: preflightKey(record()), it) }
            ?: ReadinessStanding.NotReadYet,
        installed, catalog, journal,
    )

    private val compilingThis get() = prepareKey(record())
    private val probingThis get() = preflightKey(record())

    private fun refused(why: ModelDownloadRefusal, of: ModelEntryData = entry()) =
        ModelSaid(download = ModelDownloadOutcome.Refused(of.downloadKey(), why))

    private fun listed(disk: ModelOnDisk, said: ModelSaid = ModelSaid()): ModelsAvailability.Listed =
        assertIs<ModelsAvailability.Listed>(modelsOf(disk, said))

    /** The one row's setup. */
    private fun setup(disk: ModelOnDisk, said: ModelSaid = ModelSaid()): ModelSetup = listed(disk, said).models.single().setup

    // ─── The list as a whole.

    @Test
    fun aRetiredGraphIsNotConnectedWhateverItLastHeld() {
        val ready = disk(readiness = AiReadinessState.Ready, installed = installed())
        assertEquals(ModelsAvailability.NotConnected, modelsOf(ready.copy(retired = true), ModelSaid()))
        assertEquals(ModelsAvailability.NotConnected, modelsOf(ready.copy(journal = ModelDownloadJournalView.Retired), ModelSaid()))
        assertEquals(ModelsAvailability.NotConnected, modelsOf(disk(journal = ModelDownloadJournalView.Retired), ModelSaid()))
    }

    @Test
    fun whatHasNotBeenReadYetIsLoadingAndNotNone() {
        assertEquals(ModelsAvailability.Loading, modelsOf(disk(installed = InstalledRead.NotReadYet), ModelSaid()))
        assertEquals(ModelsAvailability.Loading, modelsOf(disk(journal = ModelDownloadJournalView.NotLookedYet), ModelSaid()))
        // The catalog alone: the list is there, and says it has not read what is offered.
        assertEquals(ModelsAvailability.Listed(ModelsAvailability.Catalog.NOT_READ_YET, known, emptyList()), modelsOf(disk(catalog = CatalogRead.NotReadYet), ModelSaid()))
    }

    @Test
    fun aHeldModelWhoseStandingWasNotReadYetSaysSoAndIsNeverPreparing() {
        // ⚠⚠ Readiness's flow starts at `NoModel` before it has read anything: read as a state,
        // a relaunch over a `Ready` model would show "Preparing…" until the first check reached it.
        val notReadYet = disk(installed = installed()).copy(readiness = ReadinessStanding.NotReadYet)
        listOf(ModelSaid(), ModelSaid(compiling = compilingThis), ModelSaid(probing = probingThis)).forEach { said ->
            assertEquals(Standing.NotReadYet, assertIs<ModelSetup.Held>(setup(notReadYet, said)).standing)
        }
        // A model that is NOT held needs nothing of readiness.
        assertIs<ModelSetup.Absent>(setup(disk(readiness = null)))
    }

    @Test
    fun readinessThatCouldNotReadIsItsOwnStandingAndNeverPreparing() {
        val unreadable = disk(installed = installed()).copy(readiness = ReadinessStanding.Unreadable)
        listOf(ModelSaid(), ModelSaid(compiling = compilingThis), ModelSaid(probing = probingThis)).forEach { said ->
            assertEquals(Standing.Unreadable, assertIs<ModelSetup.Held>(setup(unreadable, said)).standing)
        }
    }

    @Test
    fun aCallThatReadNoInstallSaysNothingOfAnInstallThatCameAfterIt() {
        fun how(of: InstalledModelData, refusal: PrepareRefusal?): Standing.Preparing.How =
            assertIs<Standing.Preparing>(standing(AiReadinessState.Preparing, ModelSaid(prepareRefusal = refusal), of)).how
        val old = record()
        val new = record(entry(version = 2, digit = 'c'))
        val refusals = listOf(PrepareOutcome.NotInForeground, PrepareOutcome.Unreadable("the index could not be read"))

        refusals.forEach { outcome ->
            // While nothing changed, it is still what is said of the install it was made beside.
            val expected = if (outcome == PrepareOutcome.NotInForeground) Standing.Preparing.How.NotInForeground else Standing.Preparing.How.Unreadable
            assertEquals(expected, how(old, PrepareRefusal(preflightKey(old), outcome)), "$outcome")
            // ⚠⚠ A new install is committed before any prepare of it has run: the old call
            // says nothing of it, and must not label the new generation "could not be read".
            assertEquals(Standing.Preparing.How.Pending, how(new, PrepareRefusal(preflightKey(old), outcome)), "$outcome")
            // The mirror: refused while NO model was installed (readiness about no install, or
            // having read nothing), and then a first install commits.
            val noInstall = preflightKey(old).copy(runtime = preflightKey(old).runtime.copy(visionArchiveSha256 = null, textArchiveSha256 = null))
            assertEquals(Standing.Preparing.How.Pending, how(old, PrepareRefusal(noInstall, outcome)), "$outcome")
            assertEquals(Standing.Preparing.How.Pending, how(old, PrepareRefusal(null, outcome)), "$outcome")
        }
        assertEquals(Standing.Preparing.How.Pending, how(old, null))
    }

    // ─── Every joined value says what it is about: none depends on which arrived first.

    @Test
    fun aSourceThatIsStaleNeverSpeaksForAnInstallThatHasJustReplacedTheOneItWasAbout() {
        // An update has just been committed: the store holds the NEW record, and every other
        // source still holds what it said of the OLD one.
        val old = record()
        val new = record(entry(version = 2, digit = 'c'))
        val newInstall = installed(new)
        fun standingWith(readiness: ReadinessStanding, said: ModelSaid = ModelSaid()): Standing =
            assertIs<ModelSetup.Held>(setup(disk(installed = newInstall).copy(readiness = readiness), said)).standing

        // ⚠⚠ Readiness: `Ready` (or anything else) of the old install is no state of the new one.
        listOf(AiReadinessState.Ready, AiReadinessState.Unsuitable, AiReadinessState.AwaitingVerdict, AiReadinessState.Preparing).forEach { state ->
            assertEquals(Standing.NotReadYet, standingWith(ReadinessStanding.For(preflightKey(old), state)), "$state")
        }
        // The same digests under another OS build, app build or vocabulary are another key too.
        val otherBuild = preflightKey(new).copy(appBuild = "101")
        assertEquals(Standing.NotReadYet, standingWith(ReadinessStanding.For(otherBuild, AiReadinessState.Ready)))
        assertEquals(Standing.Ready, standingWith(ReadinessStanding.For(preflightKey(new), AiReadinessState.Ready)))

        // A compile, a probe, and their reports, of the old install, with readiness already about the new.
        val preparing = ReadinessStanding.For(preflightKey(new), AiReadinessState.Preparing)
        assertEquals(Standing.Preparing(Standing.Preparing.How.Pending), standingWith(preparing, ModelSaid(compiling = prepareKey(old))))
        assertEquals(Standing.Preparing(Standing.Preparing.How.Now), standingWith(preparing, ModelSaid(compiling = prepareKey(new))))
        assertEquals(
            Standing.Preparing(Standing.Preparing.How.Pending),
            standingWith(preparing, ModelSaid(prepare = PrepareReport(prepareKey(old), PrepareOutcome.Incomplete("x")))),
        )
        val checking = ReadinessStanding.For(preflightKey(new), AiReadinessState.AwaitingVerdict)
        assertEquals(Standing.Checking(Standing.Checking.How.Pending), standingWith(checking, ModelSaid(probing = preflightKey(old))))
        assertEquals(Standing.Checking(Standing.Checking.How.Running), standingWith(checking, ModelSaid(probing = preflightKey(new))))
        assertEquals(
            Standing.Checking(Standing.Checking.How.Pending),
            standingWith(checking, ModelSaid(preflight = PreflightReport(preflightKey(old), PreflightOutcome.InstallFailed))),
        )

        // The journal names a NEW download; the outcome and the bar are still the old download's.
        val newer = entry(version = 3, digit = 'e')
        val fetching = assertIs<ModelSetup.Fetching>(
            setup(
                disk(journal = journalNaming(newer)),
                ModelSaid(
                    download = ModelDownloadOutcome.Refused(entry().downloadKey(), ModelDownloadRefusal.InsufficientStorage(9, 4)),
                    progress = ModelDownloadProgressSnapshot(model, 650, 9_999),
                ),
            ),
        ).download
        assertEquals(ModelSetup.Download(false, null, 700, null), fetching)
    }

    @Test
    fun anIndexThatCouldNotBeReadIsThatAndNothingElseIsConcluded() {
        // ⚠ Whatever the catalog offers and whatever the journal names: no row, no Download.
        listOf(noJournal, journalNaming(), ModelDownloadJournalView.NotLookedYet).forEach { journal ->
            assertEquals(
                ModelsAvailability.InstalledUnreadable,
                modelsOf(disk(installed = InstalledRead.Unreadable, journal = journal), refused(ModelDownloadRefusal.NoTransport)),
            )
        }
    }

    @Test
    fun aCatalogThatCouldNotBeLoadedIsNotAnEmptyOneAndAStaleOneSaysSo() {
        assertEquals(ModelsAvailability.Listed(ModelsAvailability.Catalog.UNAVAILABLE, known, emptyList()), modelsOf(disk(catalog = CatalogRead.Unavailable), ModelSaid()))
        assertEquals(ModelsAvailability.Listed(read, known, emptyList()), modelsOf(disk(catalog = CatalogRead.Read(null, null)), ModelSaid()))
        val stale = listed(disk(catalog = CatalogRead.Read(entry(), 2_500, stale = true)))
        assertEquals(ModelsAvailability.Catalog.STALE, stale.catalog)
        assertIs<ModelSetup.Absent>(stale.models.single().setup)
    }

    @Test
    fun aJournalThatCouldNotBeReadIsSaidForTheListAndNoRowIsMadeFromIt() {
        listOf(emptyMap(), journalNaming().named).forEach { before ->
            // Not installed: neither fetching nor absent. ⚠ No Download beside one that may run.
            assertEquals(
                ModelsAvailability.Listed(read, ModelsAvailability.Downloads.UNREADABLE, emptyList()),
                modelsOf(disk(journal = ModelDownloadJournalView.Unreadable(before)), ModelSaid()),
            )
            // Installed: the row is there, and no update is claimed or offered.
            val held = listed(disk(AiReadinessState.Ready, installed(), journal = ModelDownloadJournalView.Unreadable(before)))
            assertEquals(ModelsAvailability.Downloads.UNREADABLE, held.downloads)
            assertEquals(ModelSetup.Update.Unknown, assertIs<ModelSetup.Held>(held.models.single().setup).update)
        }
    }

    @Test
    fun aRowIsNamedByTheCatalogAndByItsIdWhenTheCatalogCannotSay() {
        assertEquals(ModelRow(model, "SigLIP", ModelSetup.Absent(offered, null)), listed(disk()).models.single())
        // Installed with no catalog, and installed but no longer listed: the id, and what it takes from the record.
        listOf(CatalogRead.Unavailable, CatalogRead.NotReadYet, CatalogRead.Read(null, null)).forEach { catalog ->
            val row = listed(disk(AiReadinessState.Ready, installed(), catalog = catalog)).models.single()
            assertEquals(model, row.name, "$catalog")
            assertEquals(HeldModel(model, model, 1, 2_400, "Apache-2.0"), assertIs<ModelSetup.Held>(row.setup).model, "$catalog")
        }
        // Being fetched with no catalog: a row all the same, by its id, with the record's total.
        val fetching = listed(disk(journal = journalNaming(), catalog = CatalogRead.Unavailable)).models.single()
        assertEquals(model, fetching.name)
        assertEquals(ModelSetup.Fetching(null, ModelSetup.Download(false, null, 700, null)), fetching.setup)
    }

    // ─── Absent.

    @Test
    fun anAbsentModelIsOfferedWithItsFiguresAndTheLastThingSaidOfIt() {
        assertEquals(ModelSetup.Absent(offered, null), setup(disk()))
        val said: Map<ModelSaid, ModelSetup.DownloadSaid> = mapOf(
            refused(ModelDownloadRefusal.InsufficientStorage(9, 4)) to ModelSetup.DownloadSaid.NeedsSpace(9, 4),
            refused(ModelDownloadRefusal.FreeSpaceUnknown) to ModelSetup.DownloadSaid.FreeSpaceUnknown,
            refused(ModelDownloadRefusal.NoTransport) to ModelSetup.DownloadSaid.NoTransport,
            refused(ModelDownloadRefusal.JournalUnreadable) to ModelSetup.DownloadSaid.JournalUnreadable,
            refused(ModelDownloadRefusal.StoreRefused("x")) to ModelSetup.DownloadSaid.StoreRefused,
            refused(ModelDownloadRefusal.TransfersUnknown) to ModelSetup.DownloadSaid.TransfersUnknown,
            ModelSaid(download = ModelDownloadOutcome.Failed(entry().downloadKey(), false, "x")) to ModelSetup.DownloadSaid.Failed(false),
            ModelSaid(download = ModelDownloadOutcome.Failed(entry().downloadKey(), true, "x")) to ModelSetup.DownloadSaid.Failed(true),
            ModelSaid(download = ModelDownloadOutcome.Cancelled(entry().downloadKey())) to ModelSetup.DownloadSaid.Cancelled,
        )
        said.forEach { (remembered, expected) ->
            assertEquals(ModelSetup.Absent(offered, expected), setup(disk(), remembered), "$expected")
        }
    }

    @Test
    fun whatWasSaidOfOtherBytesSaysNothingOfThisEntry() {
        val other = entry(version = 2, digit = 'c')
        assertEquals(ModelSetup.Absent(offered, null), setup(disk(), refused(ModelDownloadRefusal.NoTransport, other)))
        // The same bytes under a newer entry are another key too: nothing is carried across.
        val renumbered = entry().copy(licence = "MIT")
        assertEquals(ModelSetup.Absent(offered, null), setup(disk(), refused(ModelDownloadRefusal.NoTransport, renumbered)))
    }

    // ─── The journal names a download.

    private fun fetching(disk: ModelOnDisk, said: ModelSaid): ModelSetup.Download = assertIs<ModelSetup.Fetching>(setup(disk, said)).download

    @Test
    fun aJournalledDownloadIsRunningWithItsChoiceItsBarAndWhatHoldsIt() {
        val running = ModelSetup.Download(allowsCellular = true, writtenBytes = null, totalBytes = 700, held = null)
        assertEquals(ModelSetup.Fetching(offered, running), setup(disk(journal = journalNaming(cellular = true))))

        val bar = ModelSaid(progress = ModelDownloadProgressSnapshot(model, 120, 700))
        assertEquals(120L, fetching(disk(journal = journalNaming()), bar).writtenBytes)
        // ⚠ A bar against another total is another record's.
        assertEquals(null, fetching(disk(journal = journalNaming()), ModelSaid(progress = ModelDownloadProgressSnapshot(model, 120, 999))).writtenBytes)
        assertEquals(null, fetching(disk(journal = journalNaming()), ModelSaid(progress = ModelDownloadProgressSnapshot("another", 120, 700))).writtenBytes)

        assertEquals(
            ModelSetup.DownloadSaid.NeedsSpace(9, 4),
            fetching(disk(journal = journalNaming()), refused(ModelDownloadRefusal.InsufficientStorage(9, 4))).held,
        )
        assertEquals(
            ModelSetup.DownloadSaid.Failed(false),
            fetching(disk(journal = journalNaming()), ModelSaid(download = ModelDownloadOutcome.Failed(entry().downloadKey(), false, "x"))).held,
        )
        // ⚠ Of another record, or a cancel the journal contradicts: nothing is held.
        assertEquals(null, fetching(disk(journal = journalNaming()), refused(ModelDownloadRefusal.NoTransport, entry(2, 'c'))).held)
        assertEquals(null, fetching(disk(journal = journalNaming()), ModelSaid(download = ModelDownloadOutcome.Cancelled(entry().downloadKey()))).held)
    }

    // ─── Held.

    private fun standing(readiness: AiReadinessState, said: ModelSaid = ModelSaid(), of: InstalledModelData = record()): Standing =
        assertIs<ModelSetup.Held>(setup(disk(readiness, installed(of)), said)).standing

    @Test
    fun aHeldModelStandsWhereReadinessSaysAndNowhereElse() {
        assertEquals(Standing.Ready, standing(AiReadinessState.Ready))
        assertEquals(Standing.Unsuitable, standing(AiReadinessState.Unsuitable))
        assertEquals(Standing.Checking(Standing.Checking.How.Pending), standing(AiReadinessState.AwaitingVerdict))
        assertEquals(Standing.Preparing(Standing.Preparing.How.Pending), standing(AiReadinessState.Preparing))
        // A record readiness READ and does not count as a model yet.
        assertEquals(Standing.Preparing(Standing.Preparing.How.Pending), standing(AiReadinessState.NoModel))
    }

    @Test
    fun aPrepareIsDescribedByWhatIsHappeningNowAndThenByWhatWasSaidOfThisInstall() {
        val key = prepareKey(record())
        fun how(said: ModelSaid): Standing.Preparing.How = assertIs<Standing.Preparing>(standing(AiReadinessState.Preparing, said)).how
        val incomplete = PrepareReport(key, PrepareOutcome.Incomplete("x"))

        // ⚠⚠ "Now" is a tower being COMPILED, and only that.
        assertEquals(Standing.Preparing.How.Now, how(ModelSaid(compiling = compilingThis, prepare = incomplete)))
        assertEquals(Standing.Preparing.How.Incomplete, how(ModelSaid(compiling = null, prepare = incomplete)))
        assertEquals(
            Standing.Preparing.How.Failed("vision", ModelSetup.Space(9, 4)),
            how(ModelSaid(prepare = PrepareReport(key, PrepareOutcome.Failed("vision", "x", PrepareOutcome.SpaceShort(9, 4))))),
        )
        assertEquals(Standing.Preparing.How.Failed("cache", null), how(ModelSaid(prepare = PrepareReport(key, PrepareOutcome.Failed("cache", "x")))))
        assertEquals(Standing.Preparing.How.Unreadable, how(ModelSaid(prepare = PrepareReport(key, PrepareOutcome.Unreadable("x")))))
        assertEquals(Standing.Preparing.How.NotInForeground, how(ModelSaid(prepareRefusal = PrepareRefusal(probingThis, PrepareOutcome.NotInForeground))))
        assertEquals(Standing.Preparing.How.Unreadable, how(ModelSaid(prepareRefusal = PrepareRefusal(probingThis, PrepareOutcome.Unreadable("x")))))
        // ⚠ What was said of the install outlives a call that read none.
        assertEquals(Standing.Preparing.How.Incomplete, how(ModelSaid(prepare = incomplete, prepareRefusal = PrepareRefusal(probingThis, PrepareOutcome.NotInForeground))))
        // ⚠⚠ A report about other towers is another install's.
        val others = PrepareKey(model, listOf("f".repeat(64), "e".repeat(64)))
        assertEquals(Standing.Preparing.How.Pending, how(ModelSaid(prepare = PrepareReport(others, PrepareOutcome.Incomplete("x")))))
        assertEquals(Standing.Preparing.How.Pending, how(ModelSaid(prepare = PrepareReport(null, PrepareOutcome.Incomplete("x")))))
    }

    @Test
    fun aCheckIsDescribedByAProbeThatIsRunningAndThenByWhatWasSaidOfThisInstall() {
        val key = preflightKey(record())
        fun how(outcome: PreflightOutcome, about: PreflightKey? = key, probing: Boolean = false): Standing.Checking.How =
            assertIs<Standing.Checking>(standing(AiReadinessState.AwaitingVerdict, ModelSaid(probing = if (probing) probingThis else null, preflight = PreflightReport(about, outcome)))).how
        fun paused(why: Standing.Checking.Why) = Standing.Checking.How.Paused(why)
        val backingOff = PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.BACKING_OFF, "x")

        // ⚠⚠ A probe that runs NOW: the report is the attempt before it, and says nothing of this one.
        assertEquals(Standing.Checking.How.Running, how(backingOff, probing = true))
        assertEquals(Standing.Checking.How.Running, how(PreflightOutcome.InstallFailed, probing = true))
        assertEquals(paused(Standing.Checking.Why.BACKING_OFF), how(backingOff))
        assertEquals(paused(Standing.Checking.Why.ALREADY_TRIED_THIS_TIME), how(PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.FOREGROUND_SPENT, "x")))
        assertEquals(paused(Standing.Checking.Why.HOT), how(PreflightOutcome.Retry(PreflightFinding.Transient(PreflightFinding.Transient.Cause.HOT, "x"), PreflightAttempts.NONE)))
        assertEquals(
            paused(Standing.Checking.Why.MEMORY_PRESSURE),
            how(PreflightOutcome.Retry(PreflightFinding.Transient(PreflightFinding.Transient.Cause.MEMORY_PRESSURE, "x"), PreflightAttempts.NONE)),
        )
        assertEquals(paused(Standing.Checking.Why.SLOW), how(PreflightOutcome.Retry(PreflightFinding.Slow("x"), PreflightAttempts.NONE)))
        assertEquals(Standing.Checking.How.Pending, how(PreflightOutcome.Retry(PreflightFinding.Failed("x"), PreflightAttempts.NONE)))
        assertEquals(Standing.Checking.How.InstallFailed, how(PreflightOutcome.InstallFailed))
        assertEquals(Standing.Checking.How.NoProbe, how(PreflightOutcome.NoProbe))
        assertEquals(Standing.Checking.How.Unreadable, how(PreflightOutcome.Unreadable))
        assertEquals(Standing.Checking.How.Pending, how(PreflightOutcome.Busy))
        // ⚠ The fail of interrupted loads alone is its own standing: it has a way out.
        val held = installed()
        assertEquals(
            Standing.StoppedLoading,
            assertIs<ModelSetup.Held>(setup(disk(AiReadinessState.Unsuitable, held).copy(readiness = ReadinessStanding.For(preflightKey(record()), AiReadinessState.Unsuitable, stoppedLoading = true)), ModelSaid())).standing,
        )
        // ⚠⚠ About another install, or about NO install: it says nothing of this one. A report
        // with no key must not carry over to whatever is installed next.
        val other = preflightKey(record(entry(2, 'c')))
        assertEquals(Standing.Checking.How.Pending, how(PreflightOutcome.InstallFailed, about = other))
        assertEquals(Standing.Checking.How.Pending, how(PreflightOutcome.Unreadable, about = null))
        assertEquals(Standing.Checking.How.Pending, how(PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.NOT_IN_FOREGROUND, "x"), about = null))
    }

    @Test
    fun anUpdateIsOfferedOnlyAgainstACatalogThatWasReadAndFetchedOnlyWhenTheJournalSays() {
        fun update(disk: ModelOnDisk, said: ModelSaid = ModelSaid()): ModelSetup.Update = assertIs<ModelSetup.Held>(setup(disk, said)).update
        val ready = disk(AiReadinessState.Ready, installed())
        val newer = entry(version = 2, digit = 'c')

        assertEquals(ModelSetup.Update.None, update(ready))
        assertEquals(ModelSetup.Update.None, update(ready.copy(catalog = CatalogRead.Read(null, null))))
        assertEquals(ModelSetup.Update.Unknown, update(ready.copy(catalog = CatalogRead.Unavailable)))
        assertEquals(ModelSetup.Update.Unknown, update(ready.copy(catalog = CatalogRead.NotReadYet)))
        assertEquals(ModelSetup.Update.Available(offered.copy(modelVersion = 2)), update(ready.copy(catalog = CatalogRead.Read(newer, 2_500))))
        // ⚠⚠ Why the last download of THESE bytes ended is said on the row, on iOS too; one of
        // other bytes, or of the installed version, is not.
        val withNewer = ready.copy(catalog = CatalogRead.Read(newer, 2_500))
        assertEquals(
            ModelSetup.Update.Available(offered.copy(modelVersion = 2), ModelSetup.DownloadSaid.Failed(true)),
            update(withNewer, ModelSaid(download = ModelDownloadOutcome.Failed(newer.downloadKey(), true, "x"))),
        )
        assertEquals(
            ModelSetup.Update.Available(offered.copy(modelVersion = 2), ModelSetup.DownloadSaid.NeedsSpace(9, 4)),
            update(withNewer, refused(ModelDownloadRefusal.InsufficientStorage(9, 4), newer)),
        )
        assertEquals(
            ModelSetup.Update.Available(offered.copy(modelVersion = 2)),
            update(withNewer, ModelSaid(download = ModelDownloadOutcome.Failed(entry().downloadKey(), true, "x"))),
        )
        val fetching = assertIs<ModelSetup.Update.Fetching>(
            update(ready.copy(journal = journalNaming(newer)), refused(ModelDownloadRefusal.InsufficientStorage(9, 4), newer)),
        )
        assertEquals(ModelSetup.DownloadSaid.NeedsSpace(9, 4), fetching.download.held)
        // ⚠ And the model is still what readiness says while its update is fetched.
        assertEquals(Standing.Ready, assertIs<ModelSetup.Held>(setup(ready.copy(journal = journalNaming(newer)))).standing)
    }

    // ─── The rule: memory fills in a case the disk chose, and chooses none.

    private fun everythingAProcessCouldRemember(): List<ModelSaid> {
        val keys = listOf(entry().downloadKey(), entry(2, 'c').downloadKey())
        val downloads: List<ModelDownloadOutcome?> = listOf<ModelDownloadOutcome?>(null) + keys.flatMap { key ->
            listOf(
                ModelDownloadOutcome.Installed(key, record()),
                ModelDownloadOutcome.Cancelled(key),
                ModelDownloadOutcome.Failed(key, true, "x"),
                ModelDownloadOutcome.Refused(key, ModelDownloadRefusal.InsufficientStorage(9, 4)),
            )
        }
        val bars = listOf(null, ModelDownloadProgressSnapshot(model, 700, 700))
        val prepares = listOf(
            null,
            PrepareReport(prepareKey(record()), PrepareOutcome.Prepared(AiReadinessState.Ready, null)),
            PrepareReport(prepareKey(record()), PrepareOutcome.Failed("vision", "x")),
        )
        val probes = listOf(
            null,
            PreflightReport(preflightKey(record()), PreflightOutcome.Passed(PreflightMeasurement(thermalAtStart = 0, memoryWarned = false))),
            PreflightReport(preflightKey(record()), PreflightOutcome.Unsuitable(PreflightFinding.Slow("x"))),
            PreflightReport(preflightKey(record()), PreflightOutcome.InstallFailed),
        )
        return downloads.flatMap { download ->
            bars.flatMap { bar ->
                listOf(null, compilingThis).flatMap { compiling ->
                    listOf(null, probingThis).flatMap { probing ->
                        prepares.flatMap { prepare -> probes.map { probe -> ModelSaid(download, bar, compiling, probing, prepare, null, probe) } }
                    }
                }
            }
        }
    }

    @Test
    fun nothingAProcessRemembersMakesAModelInstalledReadyOrDownloadingWhenTheDiskSaysItIsNot() {
        val remembered = everythingAProcessCouldRemember()
        assertEquals(864, remembered.size)
        val readinesses = listOf(
            AiReadinessState.NoModel, AiReadinessState.Preparing, AiReadinessState.AwaitingVerdict, AiReadinessState.Ready, AiReadinessState.Unsuitable,
        )
        remembered.forEach { said ->
            // The store holds no record and the journal names nothing, whatever readiness says.
            readinesses.forEach { readiness -> assertIs<ModelSetup.Absent>(setup(disk(readiness = readiness), said), "$readiness $said") }
            // The store holds a record: its standing is readiness's, whatever is remembered.
            readinesses.forEach { readiness ->
                val standing = assertIs<ModelSetup.Held>(setup(disk(readiness, installed()), said)).standing
                assertEquals(readiness == AiReadinessState.Ready, standing == Standing.Ready, "$readiness $said")
                assertEquals(readiness == AiReadinessState.Unsuitable, standing == Standing.Unsuitable, "$readiness $said")
                assertEquals(readiness == AiReadinessState.AwaitingVerdict, standing is Standing.Checking, "$readiness $said")
            }
            assertEquals(ModelSetup.Update.None, assertIs<ModelSetup.Held>(setup(disk(AiReadinessState.Ready, installed()), said)).update, "$said")
            // And nothing remembered turns a read that failed, or was not made, into an answer.
            assertEquals(ModelsAvailability.InstalledUnreadable, modelsOf(disk(installed = InstalledRead.Unreadable), said))
            assertEquals(ModelsAvailability.Loading, modelsOf(disk(installed = InstalledRead.NotReadYet), said))
            assertEquals(
                Standing.NotReadYet,
                assertIs<ModelSetup.Held>(setup(disk(installed = installed()).copy(readiness = ReadinessStanding.NotReadYet), said)).standing,
            )
            // ⚠ Nor into `Ready`, when readiness's `Ready` is about another install.
            val ofAnother = ReadinessStanding.For(preflightKey(record(entry(2, 'c'))), AiReadinessState.Ready)
            assertEquals(
                Standing.NotReadYet,
                assertIs<ModelSetup.Held>(setup(disk(installed = installed()).copy(readiness = ofAnother), said)).standing,
            )
            assertEquals(ModelsAvailability.NotConnected, modelsOf(disk(retired = true), said))
            assertEquals(emptyList(), listed(disk(journal = ModelDownloadJournalView.Unreadable(emptyMap())), said).models)
        }
    }

    // ─── The source: the flows of one graph, joined.

    private inner class Graph(scope: TestScope, loadCatalog: (suspend () -> CatalogRead)? = null, collect: Boolean = true) {
        val retired = MutableStateFlow(false)
        val readiness = MutableStateFlow<ReadinessStanding>(ReadinessStanding.For(preflightKey(record()), AiReadinessState.NoModel))
        fun stands(state: AiReadinessState) { readiness.value = ReadinessStanding.For(preflightKey(record()), state) }
        val journal = MutableStateFlow<ModelDownloadJournalView>(ModelDownloadJournalView.NotLookedYet)
        val outcome = MutableStateFlow<ModelDownloadOutcome?>(null)
        val progress = MutableStateFlow<Map<String, ModelDownloadProgressSnapshot>>(emptyMap())
        val compiling = MutableStateFlow<PrepareKey?>(null)
        val probing = MutableStateFlow<PreflightKey?>(null)
        val prepare = MutableStateFlow<PrepareReport?>(null)
        val refusal = MutableStateFlow<PrepareRefusal?>(null)
        val preflight = MutableStateFlow<PreflightReport?>(null)
        var store: () -> InstalledRead = { nothingInstalled }
        var storeReads = 0
        var catalogAnswer: suspend () -> CatalogRead = { CatalogRead.Read(entry(), 2_500) }
        var catalogFetches = 0
        var download: suspend (String, Boolean, () -> Unit) -> Unit = { _, _, _ -> }
        var cancel: suspend (String) -> Unit = {}
        var recheck: suspend () -> Unit = {}
        val calls = mutableListOf<String>()
        var offered: () -> org.tenshu.inference.proxy.AiReadiness.KeyAnswer = { org.tenshu.inference.proxy.AiReadiness.KeyAnswer.Open }
        val offeredAsked = mutableListOf<String>()
        val source = JoinedModelSource(
            model, scope.backgroundScope, retired, readiness, journal, outcome, progress,
            compiling, probing, prepare, refusal, preflight,
            readInstalled = { storeReads += 1; store() },
            loadCatalog = { catalogFetches += 1; (loadCatalog ?: catalogAnswer)() },
            startDownload = { id, cellular, admitted -> calls += "download $id $cellular"; download(id, cellular, admitted) },
            cancelDownload = { id -> calls += "cancel $id"; cancel(id) },
            recheck = { calls += "recheck"; recheck() },
            isRetired = { retired.value },
            context = UnconfinedTestDispatcher(scope.testScheduler),
            readOffered = { entry -> offeredAsked += entry.modelId; offered() },
            loadAgainOf = { entry -> calls += "loadAgain ${entry?.modelId ?: "installed"}" },
        )
        val seen = mutableListOf<ModelsAvailability>()
        val collecting = scope.backgroundScope.launch { if (collect) source.models.collect { seen += it } }
        fun last(): ModelSetup = assertIs<ModelsAvailability.Listed>(seen.last()).models.single().setup
    }

    @Test
    fun aNewProcessIsLoadingUntilTheJournalWasLookedAtAndThenSaysWhatTheDiskSays() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        assertEquals(listOf<ModelsAvailability>(ModelsAvailability.Loading), graph.seen)

        graph.journal.value = journalNaming()
        assertIs<ModelSetup.Fetching>(graph.last())
        // The download installs: the store is asked AGAIN, at the emission the journal caused.
        graph.store = { installed() }
        graph.stands(AiReadinessState.Preparing)
        graph.journal.value = noJournal
        assertEquals(Standing.Preparing(Standing.Preparing.How.Pending), assertIs<ModelSetup.Held>(graph.last()).standing)
        graph.compiling.value = compilingThis
        assertEquals(Standing.Preparing(Standing.Preparing.How.Now), assertIs<ModelSetup.Held>(graph.last()).standing)
        graph.compiling.value = null
        graph.stands(AiReadinessState.AwaitingVerdict)
        graph.probing.value = probingThis
        assertEquals(Standing.Checking(Standing.Checking.How.Running), assertIs<ModelSetup.Held>(graph.last()).standing)
        graph.probing.value = null
        graph.stands(AiReadinessState.Ready)
        assertEquals(Standing.Ready, assertIs<ModelSetup.Held>(graph.last()).standing)
    }

    @Test
    fun aRelaunchOverAReadyModelIsNeverShownAsPreparingAndAnUpdateIsNeverShownAsReadyBeforeReadinessSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.readiness.value = ReadinessStanding.NotReadYet
        graph.store = { installed() }
        graph.journal.value = noJournal
        assertEquals(Standing.NotReadYet, assertIs<ModelSetup.Held>(graph.last()).standing)
        graph.stands(AiReadinessState.Ready)
        assertEquals(Standing.Ready, assertIs<ModelSetup.Held>(graph.last()).standing)

        // ⚠⚠ An update is committed: the store answers the new record at the next emission,
        // and readiness's own change has not arrived yet. Whatever moves first.
        val newer = record(entry(version = 2, digit = 'c'))
        graph.store = { installed(newer) }
        graph.journal.value = journalNaming(entry(3, 'e'))
        graph.journal.value = noJournal
        assertEquals(Standing.NotReadYet, assertIs<ModelSetup.Held>(graph.last()).standing)
        graph.readiness.value = ReadinessStanding.For(preflightKey(newer), AiReadinessState.Preparing)
        assertEquals(Standing.Preparing(Standing.Preparing.How.Pending), assertIs<ModelSetup.Held>(graph.last()).standing)

        fun everShown(what: (Standing) -> Boolean): Boolean = graph.seen.any { shown ->
            val held = (shown as? ModelsAvailability.Listed)?.models?.singleOrNull()?.setup as? ModelSetup.Held
            held != null && held.model.modelVersion == 2 && what(held.standing)
        }
        assertFalse(everShown { it == Standing.Ready }, "the new generation was shown as ready on the old one's state: ${graph.seen}")

        graph.readiness.value = ReadinessStanding.Unreadable
        assertEquals(Standing.Unreadable, assertIs<ModelSetup.Held>(graph.last()).standing)
    }

    @Test
    fun aStoreThatThrowsOrHoldsNoIndexIsUnreadableAndNotAnEmptyOne() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.store = { error("the index could not be read") }
        graph.journal.value = noJournal
        assertEquals(ModelsAvailability.InstalledUnreadable, graph.seen.last())
    }

    @Test
    fun aProgressTickDoesNotAskTheStore() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = journalNaming()
        val readsBefore = graph.storeReads
        repeat(50) { tick -> graph.progress.value = mapOf(model to ModelDownloadProgressSnapshot(model, tick.toLong(), 700)) }
        assertEquals(49L, assertIs<ModelSetup.Fetching>(graph.last()).download.writtenBytes)
        assertEquals(readsBefore, graph.storeReads, "the store was read at the rate of the bar")
    }

    @Test
    fun aSlowCatalogDoesNotHoldBackWhatIsAlreadyKnownAndTwoFirstCollectorsMakeOneFetch() = runTest(UnconfinedTestDispatcher()) {
        val answer = CompletableDeferred<CatalogRead>()
        val slow = Graph(this, loadCatalog = { answer.await() }, collect = false)
        slow.store = { installed() }
        slow.stands(AiReadinessState.Ready)
        slow.journal.value = noJournal
        val seen = mutableListOf<ModelsAvailability>()
        backgroundScope.launch { slow.source.models.collect { seen += it } }
        backgroundScope.launch { slow.source.models.collect { } }

        // ⚠ Installed and ready is said at once; only the update is not known yet.
        val held = assertIs<ModelSetup.Held>(assertIs<ModelsAvailability.Listed>(seen.last()).models.single().setup)
        assertEquals(ModelSetup.Update.Unknown, held.update)
        answer.complete(CatalogRead.Read(entry(2, 'c'), 2_500))
        assertIs<ModelSetup.Update.Available>(assertIs<ModelSetup.Held>(assertIs<ModelsAvailability.Listed>(seen.last()).models.single().setup).update)
        assertEquals(1, slow.catalogFetches, "two first collectors each fetched the catalog")
    }

    @Test
    fun aRetiredGraphSaysNotConnectedAndItsFlowEnds() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.store = { installed() }
        graph.stands(AiReadinessState.Ready)
        graph.journal.value = noJournal
        assertIs<ModelSetup.Held>(graph.last())
        val readsBefore = graph.storeReads

        graph.retired.value = true

        assertEquals(ModelsAvailability.NotConnected, graph.seen.last())
        assertTrue(graph.collecting.isCompleted, "a retired graph's flow went on")
        // ⚠ And its store is not asked on the way out: it is the next graph's.
        assertEquals(readsBefore, graph.storeReads)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), graph.source.download(model, false))
        assertEquals(emptyList(), graph.calls)
    }

    @Test
    fun checkingAgainRunsTheCheckBesideTheRefreshAndAsksTheStoreAgain() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        assertIs<ModelSetup.Absent>(graph.last())

        // ⚠⚠ The check does not wait for the network: the refresh is still out when it has run.
        val refresh = CompletableDeferred<CatalogRead>()
        graph.catalogAnswer = { refresh.await() }
        graph.store = { installed() }
        graph.stands(AiReadinessState.Ready)
        val again = backgroundScope.launch { graph.source.checkAgain() }
        assertEquals(listOf("recheck"), graph.calls, "the check waited for the catalog")
        assertFalse(again.isCompleted)

        // ⚠⚠ A refresh that FAILS keeps the catalog that was read, and says it is stale.
        refresh.complete(CatalogRead.Unavailable)
        assertTrue(again.isCompleted)
        val listed = assertIs<ModelsAvailability.Listed>(graph.seen.last())
        assertEquals(ModelsAvailability.Catalog.STALE, listed.catalog)
        assertIs<ModelSetup.Held>(listed.models.single().setup)

        // ⚠ The store changed with NO flow moving: only asking again shows it.
        graph.catalogAnswer = { CatalogRead.Unavailable }
        graph.store = { nothingInstalled }
        graph.source.checkAgain()
        assertIs<ModelSetup.Absent>(graph.last())
    }

    @Test
    fun aCatalogFetchWhoseCollectorLeftRecordsNothingAndTheNextCollectorAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        val firstFetch = CompletableDeferred<Unit>()
        var fetches = 0
        val graph = Graph(
            this,
            loadCatalog = {
                fetches += 1
                if (fetches == 1) {
                    // ⚠ What the shared catalog source does with a cancelled fetch: it swallows
                    // the cancellation and answers *no catalog*.
                    runCatching { firstFetch.await() }
                    CatalogRead.Unavailable
                } else {
                    CatalogRead.Read(entry(), 2_500)
                }
            },
            collect = false,
        )
        graph.journal.value = noJournal
        val first = backgroundScope.launch { graph.source.models.collect { } }
        first.cancel()

        val seen = mutableListOf<ModelsAvailability>()
        backgroundScope.launch { graph.source.models.collect { seen += it } }
        assertEquals(2, fetches, "the answer of a fetch nobody waited for was kept")
        assertIs<ModelSetup.Absent>(assertIs<ModelsAvailability.Listed>(seen.last()).models.single().setup)
    }

    // ─── The actions: an answer at once, and everything after it in the row.
    // ⚠ `Accepted` is what the downloader TOLD this call (it has a record), never a view.

    @Test
    fun aDownloadAnswersWhenTheDownloaderSaysItIsJournalledAndNotWhenItIsInstalled() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        val installs = CompletableDeferred<Unit>()
        graph.download = { _, _, admitted -> graph.journal.value = journalNaming(); admitted(); installs.await() }

        assertEquals(ModelActionResult.Accepted, graph.source.download(model, false))
        assertFalse(installs.isCompleted)
        assertIs<ModelSetup.Fetching>(graph.last())
        // ⚠ What the call says afterwards surfaces nowhere: it is the row's to say.
        installs.completeExceptionally(ModelStorageInsufficientException(9, 4))
        runCurrent()
        // Installed already, with nothing to fetch: the call simply returns.
        graph.download = { _, _, _ -> }
        assertEquals(ModelActionResult.Accepted, graph.source.download(model, false))
    }

    /**
     * ⚠⚠ A bound on the press's wait, never on the call (§14.17.0): the call waits under the step
     * lock and then for a catalog fetch with no bound of its own; unbounded, the row would say
     * *Starting…* for as long, for ever when the fetch never answers.
     */
    @Test
    fun aPressWaitsForItsAdmissionNoLongerThanItsBoundAndTheCallGoesOn() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        val catalogAnswers = CompletableDeferred<Unit>()
        graph.download = { _, _, admitted ->
            catalogAnswers.await()
            graph.journal.value = journalNaming()
            admitted()
            CompletableDeferred<Unit>().await()
        }

        val answer = backgroundScope.async { graph.source.download(model, false) }
        advanceTimeBy(14.seconds)
        assertFalse(answer.isCompleted)
        advanceTimeBy(2.seconds)

        assertEquals(ModelActionResult.StillStarting, answer.await())
        // ⚠⚠ And the row says the call goes on, so it does not look like a press that did nothing.
        assertEquals(ModelSetup.Pressed.Starting, assertIs<ModelSetup.Absent>(graph.last()).pressed)
        // ⚠ Not cancelled: the call is admitted later, and the row says so from the journal.
        catalogAnswers.complete(Unit)
        runCurrent()
        assertIs<ModelSetup.Fetching>(graph.last())
    }

    /** A refusal that came after the caller stopped waiting is said on the row, not dropped. */
    @Test
    fun aRefusalAfterThePressStoppedWaitingIsSaidOnTheRowUntilTheNextPress() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        val catalogAnswers = CompletableDeferred<Unit>()
        graph.download = { _, _, _ ->
            catalogAnswers.await()
            throw org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException("no catalog")
        }
        val answer = backgroundScope.async { graph.source.download(model, false) }
        advanceTimeBy(16.seconds)
        assertEquals(ModelActionResult.StillStarting, answer.await())

        catalogAnswers.complete(Unit)
        runCurrent()

        assertEquals(ModelSetup.Pressed.Refused(ModelActionResult.Why.NoCatalog), assertIs<ModelSetup.Absent>(graph.last()).pressed)
        // The next press is a new call: the old answer is not its.
        graph.download = { _, _, admitted -> admitted(); CompletableDeferred<Unit>().await() }
        assertEquals(ModelActionResult.Accepted, graph.source.download(model, false))
        runCurrent()
        assertNull(assertIs<ModelSetup.Absent>(graph.last()).pressed)
    }

    /**
     * ⚠ A refusal kept for a caller who left goes once the row has moved on: it is not said
     * again on a later absence that nobody pressed for.
     */
    @Test
    fun aKeptRefusalIsDroppedOnceTheJournalNamesTheModel() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        val catalogAnswers = CompletableDeferred<Unit>()
        graph.download = { _, _, _ ->
            catalogAnswers.await()
            throw org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException("no catalog")
        }
        val answer = backgroundScope.async { graph.source.download(model, false) }
        advanceTimeBy(16.seconds)
        assertEquals(ModelActionResult.StillStarting, answer.await())
        catalogAnswers.complete(Unit)
        runCurrent()
        assertIs<ModelSetup.Pressed.Refused>(assertIs<ModelSetup.Absent>(graph.last()).pressed)

        // Another screen's download, then it is cancelled: the row is absent again.
        graph.journal.value = journalNaming()
        runCurrent()
        assertIs<ModelSetup.Fetching>(graph.last())
        graph.journal.value = noJournal
        runCurrent()

        assertNull(assertIs<ModelSetup.Absent>(graph.last()).pressed, "a refusal outlived the row it was said on")
    }

    /**
     * ⚠ §14.18.16 item 14: the same drop **while no screen collects the list**. The journal names
     * the model and stops with nothing open; the next screen must not say the old refusal.
     */
    @Test
    fun aKeptRefusalIsDroppedOnceTheJournalNamesTheModelWhileNoScreenIsOpen() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        val catalogAnswers = CompletableDeferred<Unit>()
        graph.download = { _, _, _ ->
            catalogAnswers.await()
            throw org.tenshu.inference.proxy.catalog.ModelCatalogUnavailableException("no catalog")
        }
        val answer = backgroundScope.async { graph.source.download(model, false) }
        advanceTimeBy(16.seconds)
        assertEquals(ModelActionResult.StillStarting, answer.await())
        catalogAnswers.complete(Unit)
        runCurrent()
        assertIs<ModelSetup.Pressed.Refused>(assertIs<ModelSetup.Absent>(graph.last()).pressed)

        // The screen closes; another's download is journalled and cancelled while none is open.
        graph.collecting.cancel()
        graph.journal.value = journalNaming()
        runCurrent()
        graph.journal.value = noJournal
        runCurrent()

        val next = mutableListOf<ModelsAvailability>()
        backgroundScope.launch { graph.source.models.collect { next += it } }
        runCurrent()
        val row: ModelSetup = assertIs<ModelsAvailability.Listed>(next.last()).models.single().setup
        assertNull(assertIs<ModelSetup.Absent>(row).pressed, "a refusal outlived the row it was said on while no screen was open")
    }

    /**
     * ⚠⚠ The watcher of a kept refusal ends when its graph is retired. The journal then says
     * `Retired` for good and nothing cancels the source's scope, so a watcher waiting for the
     * journal to name the model would live on past its graph. On a REAL dispatcher, with the
     * refusal the teardown itself answered (`NotConnected`).
     */
    @Test
    fun aKeptRefusalsWatcherEndsWhenItsGraphIsRetired() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retired = MutableStateFlow(false)
        val journal = MutableStateFlow<ModelDownloadJournalView>(noJournal)
        val gate = CompletableDeferred<Unit>()
        val source = JoinedModelSource(
            model, scope, retired,
            MutableStateFlow<ReadinessStanding>(ReadinessStanding.For(preflightKey(record()), AiReadinessState.NoModel)),
            journal, MutableStateFlow(null), MutableStateFlow(emptyMap()), MutableStateFlow(null), MutableStateFlow(null),
            MutableStateFlow(null), MutableStateFlow(null), MutableStateFlow(null),
            readInstalled = { nothingInstalled },
            loadCatalog = { CatalogRead.Read(entry(), 2_500) },
            startDownload = { _, _, _ ->
                gate.await()
                throw org.tenshu.inference.proxy.catalog.ModelDownloadCancelledException("torn down")
            },
            cancelDownload = {},
            recheck = {},
            isRetired = { retired.value },
            admitWait = 50.milliseconds,
            readOffered = { org.tenshu.inference.proxy.AiReadiness.KeyAnswer.Open },
            loadAgainOf = {},
        )
        try {
            assertEquals(ModelActionResult.StillStarting, source.download(model, false))
            // The teardown: retired, the journal's last word, and then the call fails.
            retired.value = true
            journal.value = ModelDownloadJournalView.Retired
            gate.complete(Unit)
            val job = scope.coroutineContext[Job]!!
            withTimeoutOrNull(5_000) { while (job.children.any { it.isActive }) delay(10) }
            assertTrue(job.children.none { it.isActive }, "a refusal's watcher outlived its retired graph")
        } finally {
            scope.cancel()
        }
    }

    /**
     * ⚠⚠ No download is offered that the key would answer at once (§14.5 item 0): the
     * catalog entry's key, as it would be installed, is asked of the verdict's file.
     */
    @Test
    fun anAbsentModelWhoseKeyIsAnsweredOffersNoDownloadAndAStopByInterruptedLoadsMayBeTriedAgain() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        graph.offered = { org.tenshu.inference.proxy.AiReadiness.KeyAnswer.Unsuitable(stoppedLoading = true) }
        graph.source.checkAgain()
        runCurrent()

        assertEquals(ModelSetup.Answered.STOPPED_LOADING, assertIs<ModelSetup.Absent>(graph.last()).answered)
        assertTrue(graph.offeredAsked.isNotEmpty())
        graph.source.loadAgain(model)
        assertTrue("loadAgain $model" in graph.calls, "the entry was not tried again: ${graph.calls}")

        graph.offered = { org.tenshu.inference.proxy.AiReadiness.KeyAnswer.Unsuitable(stoppedLoading = false) }
        graph.source.checkAgain()
        runCurrent()
        assertEquals(ModelSetup.Answered.UNSUITABLE, assertIs<ModelSetup.Absent>(graph.last()).answered)
        // A file that could not be read answers nothing either way.
        graph.offered = { error("unreadable") }
        graph.source.checkAgain()
        runCurrent()
        assertNull(assertIs<ModelSetup.Absent>(graph.last()).answered)
    }

    @Test
    fun aDownloadThatIsRefusedBeforeAnyRecordSaysWhyAsValues() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = noJournal
        graph.download = { _, _, _ -> throw ModelStorageInsufficientException(9, 4) }
        assertEquals(
            ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.NeedsSpace(9, 4))),
            graph.source.download(model, false),
        )
        graph.download = { _, _, _ -> error("nobody named this") }
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.Unnamed), graph.source.download(model, false))
        // A journal that cannot be read: the downloader's own refusal, whatever the view says.
        graph.journal.value = ModelDownloadJournalView.Unreadable(emptyMap())
        graph.download = { _, _, _ -> throw ModelDownloadJournalUnreadableException() }
        assertEquals(
            ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.JournalUnreadable)),
            graph.source.download(model, false),
        )
    }

    @Test
    fun aTeardownThatWinsADownloadIsNotConnectedAndNeverAccepted() = runTest(UnconfinedTestDispatcher()) {
        // ⚠⚠ The teardown says `Retired` on the journal view BEFORE it fails the call: a view
        // that "changed", read as journalled, would answer *accepted* for a download that died
        // with its runtime, with no record.
        val graph = Graph(this)
        graph.journal.value = noJournal
        val failed = CompletableDeferred<Unit>()
        graph.download = { _, _, _ ->
            graph.journal.value = ModelDownloadJournalView.Retired
            graph.retired.value = true
            failed.await()
            throw org.tenshu.inference.proxy.catalog.ModelDownloadCancelledException("torn down")
        }
        val answer = backgroundScope.async { graph.source.download(model, false) }
        runCurrent()
        assertFalse(answer.isCompleted, "a view that changed was taken for an answer")
        failed.complete(Unit)
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), answer.await())

        // And a teardown that lands just after the downloader admitted the call.
        val later = Graph(this)
        later.journal.value = noJournal
        later.download = { _, _, admitted -> later.retired.value = true; admitted(); CompletableDeferred<Unit>().await() }
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), later.source.download(model, false))
    }

    @Test
    fun aRecordOfAnotherKeyOrAnotherCallersIsNotThisCallsAcceptance() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        // A download of OTHER bytes is running (the entry before an update), and this call's
        // own download has not been admitted: the view names the model all along.
        graph.journal.value = journalNaming(entry())
        val decided = CompletableDeferred<Unit>()
        graph.download = { _, _, _ -> decided.await(); throw ModelStorageInsufficientException(9, 4) }
        val answer = backgroundScope.async { graph.source.download(model, false) }
        runCurrent()
        assertFalse(answer.isCompleted, "a record that is not this call's was taken for its acceptance")
        // The view moves again, for somebody else: still no answer.
        graph.journal.value = journalNaming(entry(2, 'c'))
        runCurrent()
        assertFalse(answer.isCompleted)

        // The downloader refuses the newer entry (no room): refused, and the running one is untouched.
        decided.complete(Unit)
        assertEquals(
            ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.NeedsSpace(9, 4))),
            answer.await(),
        )
    }

    @Test
    fun aDownloadThatWasJournalledAndCannotGoOnIsAcceptedAndItsRowSaysWhyOnce() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = journalNaming()
        // Joined, and refused for now in the same step: the record is kept, the waiter failed.
        graph.download = { _, _, admitted -> admitted(); throw ModelStorageInsufficientException(9, 4) }
        graph.outcome.value = ModelDownloadOutcome.Refused(entry().downloadKey(), ModelDownloadRefusal.InsufficientStorage(9, 4))

        assertEquals(ModelActionResult.Accepted, graph.source.download(model, false))
        assertEquals(ModelSetup.DownloadSaid.NeedsSpace(9, 4), assertIs<ModelSetup.Fetching>(graph.last()).download.held)
    }

    @Test
    fun theOtherCellularChoiceIsTheRequestsRefusalAndNeverTheRunningDownloads() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = journalNaming(cellular = false)
        graph.download = { _, _, _ -> throw ModelDownloadChoiceConflictException() }
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.OtherCellularChoice), graph.source.download(model, true))
        assertEquals(null, assertIs<ModelSetup.Fetching>(graph.last()).download.held)
    }

    @Test
    fun aCancelAnswersWhatTheDownloaderDidAsValues() = runTest(UnconfinedTestDispatcher()) {
        val graph = Graph(this)
        graph.journal.value = journalNaming()
        assertEquals(ModelActionResult.Accepted, graph.source.cancel(model))
        // Nothing journalled: the downloader finds no record and returns. That is what was asked for.
        graph.journal.value = noJournal
        assertEquals(ModelActionResult.Accepted, graph.source.cancel(model))
        graph.cancel = { throw ModelDownloadJournalUnreadableException() }
        assertEquals(
            ModelActionResult.Refused(ModelActionResult.Why.Said(ModelSetup.DownloadSaid.JournalUnreadable)),
            graph.source.cancel(model),
        )
        // A teardown under the cancel, and a cancel asked of a graph that is gone.
        graph.cancel = { graph.retired.value = true }
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), graph.source.cancel(model))
        val calls = graph.calls.size
        assertEquals(ModelActionResult.Refused(ModelActionResult.Why.NotConnected), graph.source.cancel(model))
        assertEquals(calls, graph.calls.size)
    }
}
