package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.HistoryAccess
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow
import app.tenshu.fit.model.SharingReadiness
import kotlinx.datetime.LocalDate
import org.tenshu.preference.BiologicalSex

/**
 * The **acquisition side of the health-import seam** (`docs/proposals/timeline-services.md` §4.2).
 * Reading health data is the one platform-specific step; the mapping onto the `:health-service`
 * schema ([ImportedHealth] → [HealthImportWriter]) and the write path stay shared in `commonMain`.
 *
 * Deliberately a **plain interface bound via DI** (not `expect`/`actual`): each app binds the
 * importer its platform can offer — Android → Health Connect, iOS → HealthKit, desktop/web →
 * FIT/TCX/GPX file import — and a platform with none gets [NoHealthSource] (never the
 * [ReferenceHealthImporter] fixture, which is for tests and previews only). This mirrors
 * the Koin-seam convention used elsewhere and avoids forcing an `actual` stub into every target.
 *
 * ⚠ Health data is **GDPR Article 9 special-category** (threat-model asset **A34**). An importer
 * must acquire it only under explicit, per-source user consent (Health Connect / HealthKit
 * permission grants; an explicit file pick) and never route sensitive fields (routes, HR streams)
 * into relay-visible metadata — they ride the encrypted content plane like file bytes.
 */
interface HealthImporter {

    /** The sources this platform can import from **right now** (empty = none available here). */
    fun availableSources(): List<ImportSource>

    /**
     * Sustained walks and runs found in the platform's **step** data — Tier-1 detection, with no
     * GPS involved (`fit-automatic-activity-detection.md` §9.2).
     *
     * ⚠ Defaults to empty, which is the honest answer for a source with no step history (the
     * fixture, a file import). Android reads Health Connect; iOS's HealthKit twin is unbuilt.
     *
     * ⚠ Read once per launch by the promotion pass, never from a reactive flow: on a real device
     * this read returns 10 000 records over 10 pages, which is not something to repeat per
     * emission.
     */
    suspend fun readStepBouts(): List<StepBoutDetector.Bout> = emptyList()

    /**
     * Whether the **unattended launch refresh** ([HealthImportBootstrap]) should run against this
     * importer. Distinct from [availableSources]: a source can be *offerable* (the user may pick
     * it and grant access) while an unattended import of it could only abort — or worse. On the
     * grant-less path an auto-import ends in `PermissionsRequired` on every launch, parking a
     * permanent "needs permission / try again" banner the user never asked for. **Manual**
     * imports ([FitRepository.startImport] from onboarding / Profile) are deliberately NOT gated
     * on this: the user acted, so an abort-with-banner is an answer, not noise.
     *
     * ⚠⚠ Because manual imports are ungated, this is **not** what keeps fixture data out of the
     * durable, synced store (`fit-app.md` §9.15). That guard is `FitProjectionRepository` refusing
     * every [ImportOutcome.Sample].
     *
     * Default `true` — a real importer that needs no standing consent refreshes freely.
     */
    suspend fun autoRefreshReady(): Boolean = true

    /**
     * Whether this importer can read data from before the platform's default window, and whether
     * it already may — see [HistoryAccess].
     *
     * Default [HistoryAccess.Unsupported]: an importer that has no depth limit (a file import
     * reads whatever the file holds) has nothing to offer here, and the UI must not present an
     * action that would do nothing.
     *
     * Only reports the state; *asking* for the grant stays on [HealthPermissionRequester], which is
     * the established seam for the steps only the app shell can take (the prompt must be launched
     * from an `Activity`).
     */
    suspend fun historyAccess(): HistoryAccess = HistoryAccess.Unsupported

    /**
     * Read health data from [source], restricted to the selected [categories], going back as far
     * as [window] asks (and as far as the platform actually permits — see [ImportWindow]). Returns an
     * [ImportOutcome] — the neutral [ImportedHealth] the [HealthImportWriter] persists, *plus* what
     * the read concluded, so an empty result can say whether it means "nothing recorded", "not
     * authorised", or "this is a fixture". Suspends: a real importer performs IO (Health Connect /
     * HealthKit queries, file parsing). Idempotent downstream — the record ops are
     * content-addressed, so re-importing a day/workout/metric folds to one row.
     *
     * Throws on failure. Do not swallow errors into an empty result: the repository turns a thrown
     * importer into a reportable `ImportStatus.Failed`, and an importer that hides its own breakage
     * puts the app right back into the state where nothing works and nothing says why.
     */
    /**
     * Everything the sharing wizard needs to know, **without running an import** (`fit-app.md`
     * §15.6): whether the store is present, whether we may read it, how deep, and whether the
     * other apps that could feed it are allowed to.
     *
     * Separate from [import] because the wizard asks a different question at a different moment.
     * An import describes the *last read*; this describes the world **now** — the user has just
     * come back from Health Connect's settings and wants to know whether what they did there took
     * effect. Answering that by re-importing would be slow, would write rows as a side effect of a
     * permission check, and on a phone with nothing to import would answer nothing at all.
     *
     * Default: a platform with nothing to configure, so a shell that never implements this simply
     * never offers the wizard.
     */
    suspend fun sharingReadiness(): SharingReadiness = SharingReadiness()

    suspend fun import(
        source: ImportSource,
        categories: Set<CategoryId>,
        window: ImportWindow = ImportWindow.Recent,
    ): ImportOutcome

    /**
     * The user's **demographics**, if this platform records them (`fit-app.md` §18.3).
     *
     * ⚠⚠ Asymmetric by nature, which is the whole reason this exists: HealthKit exposes date of
     * birth and biological sex as `HKCharacteristicType`s, and **Health Connect has no
     * demographics API at all** — no characteristic type, no `dateOfBirth`, no `biologicalSex`
     * anywhere in connect-client. So on iOS these can be seeded from the platform and on Android
     * they can only ever come from the user.
     *
     * Default: nothing known — the honest answer for every importer but HealthKit's.
     *
     * ⚠ Not part of [import]: these are **not health samples**. They are the owner's person, which
     * lives in `OwnerPreferences` rather than the `:health-service` store (§18.2), so they cannot
     * ride [ImportedHealth] through the [HealthImportWriter].
     */
    suspend fun readCharacteristics(): HealthCharacteristics = HealthCharacteristics()
}

/**
 * What a platform can tell us about the user themselves. Both fields `null` = "this platform does
 * not know", which is distinct from the user having declined to say — the caller only ever *seeds*
 * from these, never overwrites (see `FitProjectionRepository.seedOwnerDemographics`).
 */
data class HealthCharacteristics(
    val birthDate: LocalDate? = null,
    val sex: BiologicalSex? = null,
)

/**
 * The importer for a platform or device with **no health source**: it offers nothing, and an
 * import answers [ImportOutcome.SourceUnavailable] so the UI can say so.
 *
 * ⚠⚠ This — not [ReferenceHealthImporter] — is the shipping default wherever no platform importer
 * is bound (desktop) and the answer a platform importer gives when its store is missing (Android
 * without Health Connect, an iPad without HealthKit). In that role the fixture would let a manual
 * import write invented workouts into the user's durable, synced realm (`fit-app.md` §9.15), so it
 * stays a test/preview tool.
 */
object NoHealthSource : HealthImporter {
    override fun availableSources(): List<ImportSource> = emptyList()
    override suspend fun autoRefreshReady(): Boolean = false
    override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome =
        ImportOutcome.SourceUnavailable
}
