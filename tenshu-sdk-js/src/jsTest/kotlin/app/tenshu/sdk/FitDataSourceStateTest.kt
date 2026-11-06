package app.tenshu.sdk

import app.tenshu.fit.model.DataSourceKind
import app.tenshu.fit.model.DataSourceRow
import app.tenshu.fit.model.DataSourceState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Kotlin half of the `FitDataSource.state` pin: the bridge emits exactly
 * [FitDataSourceStateName.entries], and every `DataSourceState` case reaches one of them.
 * The TS half (`web/apex/src/lib/fitDataSourceStates.test.ts`) diffs this enum against
 * the state list in `web/sdk/src/fit.ts`. ⚠ A TS union that names a subset of these compiles fine.
 */
class FitDataSourceStateTest {

    /** One value of every `DataSourceState` case. ⚠ A new case is a compile error in `stateName`. */
    private val everyCase: List<DataSourceState> = listOf(
        DataSourceState.NeverRun,
        DataSourceState.Running(imported = 3),
        DataSourceState.Imported(itemCount = 4, at = 1),
        DataSourceState.NoData(at = 1),
        DataSourceState.Failed(at = 1),
        DataSourceState.PermissionNeeded,
        DataSourceState.Unavailable,
        DataSourceState.Sample,
        DataSourceState.Recording(autoDetect = true),
    )

    @Test
    fun everyEnumValue_isWhatSomeStateEmits_andNoStateEmitsAnythingElse() {
        val emitted = everyCase.map { it.stateName() }
        assertEquals(FitDataSourceStateName.entries.toSet(), emitted.toSet())
        assertEquals(everyCase.size, emitted.toSet().size, "two cases share a name")
        for (name in FitDataSourceStateName.entries) {
            val case = everyCase.single { it.stateName() == name }
            assertEquals(name.name, row(case).toJs().state, "the row carries the enum's name for $name")
        }
    }

    @Test
    fun theEnumIsTheSetTheTypeScriptUnionNames() {
        // ⚠ A copy of the state list in `fit.ts`, on purpose: apex's test reads both files and fails
        // first if they drift; this one fails if the enum is edited without reading the TS.
        assertEquals(
            listOf("NeverRun", "Imported", "NoData", "Failed", "Running", "Recording", "PermissionNeeded", "Unavailable", "Sample"),
            FitDataSourceStateName.entries.map { it.name },
        )
    }

    private fun row(state: DataSourceState) = DataSourceRow(
        kind = DataSourceKind.HealthConnect,
        deviceId = "d",
        deviceLabel = "Pixel",
        isThisDevice = false,
        state = state,
        autoImport = false,
    )
}
