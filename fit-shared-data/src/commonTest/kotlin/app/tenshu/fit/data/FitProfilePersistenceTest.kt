package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.Goals
import app.tenshu.fit.model.Metric
import app.tenshu.fit.model.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.tenshu.preference.OwnerPreferences
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.readOnce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.health.client.healthServiceClientModule
import org.tenshu.health.common.healthServiceCommonModule
import org.tenshu.health.proxy.healthServiceProxyImplementationFactory
import org.tenshu.health.proxy.initHealthService
import org.tenshu.preference.BiologicalSex
import org.tenshu.preference.MeasurementSystem
import org.tenshu.preference.client.NoopOwnerProfile
import org.tenshu.preference.client.OwnerProfile
import org.tenshu.preference.client.TenshuOwnerProfile
import org.tenshu.preference.client.preferenceServiceClientModule
import org.tenshu.preference.client.set
import org.tenshu.preference.common.preferenceServiceCommonModule
import org.tenshu.preference.proxy.initPreferenceService
import org.tenshu.preference.proxy.preferenceServiceProxyImplementationFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FP-1 (`docs/proposals/fit-app.md` §18): Profile's values come from real REALM preference rows
 * and real `HealthSample` measurements, not from constants baked into the repository.
 *
 * Every case drives the whole pipe — client → op → proxy → store → relation → client read — over
 * one in-process proxy hosting **both** `:health-service` and `:preference-service`, which is the
 * shape production has (Control hosts both; Fit is a client of each).
 */
class FitProfilePersistenceTest {

    @Test
    fun anUntouchedProfileIsUnsetRatherThanAFabricatedPerson() = runTest {
        val proxy = stackProxy(this)
        val repo = repositoryOver(proxy)

        val profile = repo.profile().first()

        // The regression this slice exists to prevent: these used to read
        // "You / 178 cm / 74.2 kg / 1992-05-30 / Male" — the mock's values, in the real
        // repository, presented as the user's own.
        assertNull(profile.name, "name must start unset, not 'You'")
        assertNull(profile.birthDate, "birth date must start unset")
        assertNull(profile.sex, "sex must start unset")
        assertNull(profile.weightKg, "weight must start unset — no HealthSample has been recorded")
        assertNull(profile.heightCm, "height must start unset — no HealthSample has been recorded")
        // Units alone has a real default: metric is a presentation choice, not a claim.
        assertEquals(Units.Metric, profile.units)
    }

    @Test
    fun theOwnersPersonRoundTripsThroughRealPreferenceRows() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val owner = TenshuOwnerProfile(client)
        val repo = repositoryOver(proxy, ownerProfile = owner)

        owner.setName("Benedikt")
        owner.setBirthDate(LocalDate(1990, 3, 14))
        owner.setSex(BiologicalSex.OTHER)
        owner.setUnits(MeasurementSystem.IMPERIAL)

        // ⚠ Not a bare `first()` — the seam seeds defaults on subscribe so a row-less realm still
        // paints, which means the first emission races the store and usually loses. Wait for the
        // written value; see `OwnerProfile.observe`.
        val profile = repo.profile().first { it.name != null }
        assertEquals("Benedikt", profile.name)
        assertEquals(LocalDate(1990, 3, 14), profile.birthDate)
        assertEquals(BiologicalSex.OTHER, profile.sex)
        assertEquals(Units.Imperial, profile.units)
    }

    @Test
    fun clearingAValueReturnsItToUnsetRatherThanToADefault() = runTest {
        val proxy = stackProxy(this)
        val owner = TenshuOwnerProfile(clientOf(proxy))

        owner.setSex(BiologicalSex.FEMALE)
        assertEquals(BiologicalSex.FEMALE, owner.observe().first { it.sex != null }.sex)

        // "Not set" has to be reachable again — a user who mis-tapped must be able to take the
        // answer back, and `null` is what the UI renders as "Not set". Collecting two emissions
        // rather than one proves the *cleared* row read back, not merely the seeded default.
        owner.setSex(null)
        assertNull(owner.observe().take(2).toList().last().sex)
    }

    @Test
    fun aMalformedStoredValueReadsAsUnsetInsteadOfThrowing() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val owner = TenshuOwnerProfile(client)

        // A row written by a future (or buggy) build must not brick every screen that reads the
        // profile — the preference adapters take the same posture on a type mismatch.
        client.preferenceService.set(OwnerPreferences.BirthDate, "not-a-date")
        client.preferenceService.set(OwnerPreferences.Sex, "unrecognised-token")
        client.preferenceService.set(OwnerPreferences.Units, "furlongs")
        // ⚠ The name is the *witness*, and it is load-bearing: an unset profile is all-null too,
        // so waiting on a valid value is the only way to prove the malformed rows were actually
        // read and rejected rather than never having loaded at all.
        client.preferenceService.set(OwnerPreferences.Name, "Benedikt")

        val values = owner.observe().first { it.name != null }
        assertNull(values.birthDate, "an unparseable date must read as unset")
        assertNull(values.sex, "an unknown sex token must read as unset")
        assertEquals(MeasurementSystem.METRIC, values.units, "an unknown units token falls back to metric")
    }

    @Test
    fun goalsSurviveAsPreferenceRowsRatherThanInSessionState() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = repositoryOver(proxy, goalPreferences = TenshuFitGoalPreferences(client))

        assertEquals(FitPreferences.defaultGoals, repo.goals().first())

        repo.updateGoals(Goals(activeMinutes = 45, steps = 12_000))

        // The point of the row: a *separate* repository — standing in for the next process, or a
        // second device reading the same realm — sees the written value, not the default.
        val fresh = repositoryOver(proxy, goalPreferences = TenshuFitGoalPreferences(client))
        assertEquals(
            Goals(activeMinutes = 45, steps = 12_000),
            fresh.goals().first { it != FitPreferences.defaultGoals },
        )
    }

    @Test
    fun unitsPersistThroughTheOwnerRowSoTheToggleSurvivesARestart() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = repositoryOver(proxy, ownerProfile = TenshuOwnerProfile(client))

        repo.updateUnits(Units.Imperial)

        // The shipped bug: `updateUnits` assigned a MutableStateFlow, so metric/imperial reverted
        // on every launch. A fresh repository is the cheapest stand-in for that restart.
        val fresh = repositoryOver(proxy, ownerProfile = TenshuOwnerProfile(client))
        assertEquals(Units.Imperial, fresh.profile().first { it.units == Units.Imperial }.units)
    }

    @Test
    fun bodyMeasurementsAreReadFromTheStoreRatherThanStoredBesideIt() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = repositoryOver(proxy)

        // Both arrive as an ordinary Health Connect import — the same rows Browse → Body renders.
        // Reading them here rather than keeping a second copy is what stops Profile and Body from
        // disagreeing after the next refresh (§18.2).
        val importer = ReferenceHealthImporter()
        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(importer.availableSources().first(), CategoryId.entries.toSet()).health,
        )

        val profile = repo.profile().first { it.weightKg != null }
        assertEquals(74.2, profile.weightKg)
        // FP-2: height is a measurement too, rounded to whole centimetres for the Int model.
        assertEquals(178, profile.heightCm)
    }

    @Test
    fun heightImportsOnlyWithTheBodyCategoryTheUserSelected() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val repo = repositoryOver(proxy)

        // Deselecting Body must leave *both* body measurements out — height rides the same consent
        // as weight, so it can never arrive through a category the user declined.
        val importer = ReferenceHealthImporter()
        HealthImportWriter(client, proxy.tContext, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).write(
            importer.import(importer.availableSources().first(), setOf(CategoryId.Vitals)).health,
        )

        // The vitals witness proves the import actually ran — without it an all-null profile
        // would pass even if nothing had been written at all.
        assertTrue(repo.samplesLatest().first().any { it.metric == Metric.HeartRate })
        val profile = repo.profile().first()
        assertNull(profile.heightCm)
        assertNull(profile.weightKg)
    }

    // ── FP-5: the HealthKit demographics seed (fit-app.md §18.12) ─────────────────────

    @Test
    fun aPlatformThatKnowsTheOwnerSeedsTheFieldsTheUserHasNotGiven() = runTest {
        val proxy = stackProxy(this)
        val owner = TenshuOwnerProfile(clientOf(proxy))

        assertTrue(owner.seedDemographics(LocalDate(1988, 7, 2), BiologicalSex.FEMALE, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE))

        val values = owner.observe().first { it.sex != null }
        assertEquals(LocalDate(1988, 7, 2), values.birthDate)
        assertEquals(BiologicalSex.FEMALE, values.sex)
    }

    @Test
    fun theSeedNeverOverwritesWhatTheUserAlreadyAnswered() = runTest {
        val proxy = stackProxy(this)
        val owner = TenshuOwnerProfile(clientOf(proxy))

        // The user answered during onboarding; the platform grant lands afterwards and disagrees.
        owner.setSex(BiologicalSex.OTHER)
        owner.seedDemographics(LocalDate(1988, 7, 2), BiologicalSex.MALE, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        val values = owner.observe().first { it.birthDate != null }
        assertEquals(BiologicalSex.OTHER, values.sex, "the user's own answer must survive the seed")
        // The field they had NOT answered is still filled — the guard is per field, not all-or-nothing.
        assertEquals(LocalDate(1988, 7, 2), values.birthDate)
    }

    @Test
    fun theSeedIsOneShotSoAClearedValueStaysCleared() = runTest {
        val proxy = stackProxy(this)
        val owner = TenshuOwnerProfile(clientOf(proxy))

        assertTrue(owner.seedDemographics(null, BiologicalSex.MALE, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE))
        assertEquals(BiologicalSex.MALE, owner.observe().first { it.sex != null }.sex)

        // ⚠⚠ The case the one-shot marker exists for: the user deliberately clears the value, and
        // the next import must NOT silently put it back. A plain "fill if unset" rule would.
        owner.setSex(null)
        assertFalse(owner.seedDemographics(null, BiologicalSex.MALE, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE), "the one-shot is spent")
        assertNull(owner.observe().take(2).toList().last().sex)
    }

    @Test
    fun anImportSeedsTheOwnerFromWhateverThePlatformKnows() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val owner = TenshuOwnerProfile(client)
        // The Android shape has nothing to offer (Health Connect has no demographics API), so the
        // fixture importer is wrapped to stand in for HealthKit's.
        val repo = FitProjectionRepository(
            client = client,
            context = proxy.tContext,
            importer = KnowsTheOwnerImporter(LocalDate(1988, 7, 2), BiologicalSex.FEMALE),
            ownerProfile = owner,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )

        repo.startImport(setOf(CategoryId.Body))

        val profile = repo.profile().first { it.sex != null }
        assertEquals(LocalDate(1988, 7, 2), profile.birthDate)
        assertEquals(BiologicalSex.FEMALE, profile.sex)
    }

    @Test
    fun anImportByAPlatformThatKnowsNothingLeavesTheOwnerUnsetAndUnspent() = runTest {
        val proxy = stackProxy(this)
        val client = clientOf(proxy)
        val owner = TenshuOwnerProfile(client)
        // Android: Health Connect cannot answer, so the default `readCharacteristics` applies.
        val repo = FitProjectionRepository(
            client = client,
            context = proxy.tContext,
            importer = FixtureAsRealImporter(),
            ownerProfile = owner,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )

        repo.startImport(setOf(CategoryId.Body))
        assertNull(repo.profile().first().sex)

        // ⚠ The one-shot must NOT have been spent — a grant obtained later still gets its chance.
        assertTrue(owner.seedDemographics(null, BiologicalSex.MALE, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE))
    }

    /**
     * ⚠⚠ Codex P1 on bc7871fd9: the realm gate starts holding WHILE the platform characteristics are
     * being read — after the import's last gated write. DOB, sex and the one-shot marker are synced
     * preference writes, so they go through the boundary: nothing is written, the marker is not
     * burned, and the pass counts as held for the re-run. Paired with the gate staying open, which
     * seeds as before.
     */
    @Test
    fun aGateThatHoldsDuringTheSeedWritesNoDemographics() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            for (turnsHeld in listOf(true, false)) {
                val proxy = stackProxy(this)
                val client = clientOf(proxy)
                val owner = TenshuOwnerProfile(client)
                val probeScope = CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
                )
                var answer: Boolean? = true
                lateinit var repo: FitProjectionRepository
                suspend fun awaitVerdict(v: app.tenshu.reachability.RealmVerdict) =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(10_000) {
                            while (repo.reach.realmVerdict.value != v) kotlinx.coroutines.delay(10)
                        }
                    }
                val importer = object : HealthImporter by FixtureAsRealImporter() {
                    override suspend fun readCharacteristics(): HealthCharacteristics {
                        if (turnsHeld) {
                            // Control drops while the characteristics are read; its re-read answers
                            // "no realm".
                            answer = false
                            repo.reach.markUnreachable()
                            awaitVerdict(app.tenshu.reachability.RealmVerdict.ABSENT)
                        }
                        return HealthCharacteristics(LocalDate(1988, 7, 2), BiologicalSex.FEMALE)
                    }
                }
                try {
                    repo = FitProjectionRepository(
                        client = client,
                        context = proxy.tContext,
                        importer = importer,
                        ownerProfile = owner,
                        probeScope = probeScope,
                        probeIntervalMs = 50,
                        probe = { true },
                        controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { answer },
                    )
                    awaitVerdict(app.tenshu.reachability.RealmVerdict.PRESENT)
                    repo.startImport(setOf(CategoryId.Body))

                    if (turnsHeld) {
                        // `readOnce`, not `observe()` — the latter emits seeded defaults first and
                        // would read "unset" even over a write that landed.
                        val prefs = client.preferenceService
                        assertEquals("", prefs.readOnce(OwnerPreferences.BirthDate), "no date of birth written")
                        assertEquals("", prefs.readOnce(OwnerPreferences.Sex), "no sex written")
                        assertFalse(prefs.readOnce(OwnerPreferences.DemographicsSeeded), "marker not burned")
                        // A manual import (the default here): the refusal surfaces as WaitingForControl
                        // with the exact request, not as a silently held automatic pass.
                        assertEquals(
                            app.tenshu.fit.model.ImportStatus.WaitingForControl(
                                setOf(CategoryId.Body), app.tenshu.fit.model.ImportWindow.Recent,
                            ),
                            repo.importStatus().value,
                        )
                        // The one-shot was not burned: a seed with the gate open still runs.
                        assertTrue(owner.seedDemographics(null, BiologicalSex.MALE, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE), "marker unspent")
                    } else {
                        val profile = repo.profile().first { it.sex != null }
                        assertEquals(LocalDate(1988, 7, 2), profile.birthDate)
                        assertEquals(BiologicalSex.FEMALE, profile.sex)
                    }
                } finally {
                    probeScope.cancel()
                }
            }
        }

    /** Stands in for HealthKit: the fixture import, plus demographics the platform knows. */
    private class KnowsTheOwnerImporter(
        private val birthDate: LocalDate?,
        private val sex: BiologicalSex?,
        private val delegate: HealthImporter = FixtureAsRealImporter(),
    ) : HealthImporter by delegate {
        override suspend fun readCharacteristics() = HealthCharacteristics(birthDate, sex)
    }

    // ── harness ───────────────────────────────────────────────────────────────────────

    /** One proxy hosting both services, mirroring Control (which hosts both in production). */
    private fun stackProxy(scope: CoroutineScope): TestProxy {
        val registry = createCoreTestRegistry().apply {
            registerConfig(healthServiceCommonModule); registerConfig(healthServiceClientModule)
            registerConfig(preferenceServiceCommonModule); registerConfig(preferenceServiceClientModule)
        }
        return TestProxy(
            schemaRegistry = registry,
            coroutineScope = scope,
            implementationFactoryOverride = { id, proxy ->
                healthServiceProxyImplementationFactory(id, proxy.fileDatabase)
                    ?: preferenceServiceProxyImplementationFactory(id)
            },
        ).also {
            it.executionContext.initHealthService()
            it.executionContext.initPreferenceService()
        }
    }

    private fun repositoryOver(
        proxy: TestProxy,
        ownerProfile: OwnerProfile = NoopOwnerProfile,
        goalPreferences: FitGoalPreferences = NoopFitGoalPreferences,
    ): FitProjectionRepository = FitProjectionRepository(
        client = clientOf(proxy),
        context = proxy.tContext,
        ownerProfile = ownerProfile,
        goalPreferences = goalPreferences,
        controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
    )

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}
