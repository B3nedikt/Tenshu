package app.tenshu.fit.data

import app.tenshu.fit.model.CategoryId
import app.tenshu.fit.model.ImportSource
import app.tenshu.fit.model.ImportWindow

/**
 * **Test-only:** the [ReferenceHealthImporter] fixture presented as a *real* read
 * ([ImportOutcome.Success]), for tests that drive `FitProjectionRepository`'s persist path and
 * want realistic rows to land.
 *
 * ⚠⚠ It exists because the repository refuses to persist an [ImportOutcome.Sample]
 * (`fit-app.md` §9.15): the fixture as it ships writes nothing, by design. Living in test sources
 * is the point — a shipping path has no way to dress the fixture up as the user's data.
 */
internal class FixtureAsRealImporter(
    private val fixture: HealthImporter = ReferenceHealthImporter(),
) : HealthImporter by fixture {
    override suspend fun import(source: ImportSource, categories: Set<CategoryId>, window: ImportWindow): ImportOutcome =
        ImportOutcome.Success(fixture.import(source, categories, window).health)
}
