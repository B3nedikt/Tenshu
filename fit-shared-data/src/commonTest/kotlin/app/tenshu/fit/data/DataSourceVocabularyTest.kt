package app.tenshu.fit.data

import app.tenshu.fit.model.DataSourceProjection
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠⚠ `:fit-shared-logic` is deliberately client-free, so [DataSourceProjection] **restates** the
 * schema's string vocabulary rather than importing it. That is the right trade — the module rule
 * is worth more than the duplication — but it buys a silent failure mode: if `HealthSource` or
 * `HealthImportRun` ever renames a constant, the projection keeps compiling, every `when` falls
 * to its else branch, and the Data sources section simply **goes empty**. Nothing fails.
 *
 * This test is the join. It lives in `:fit-shared-data` because that is the lowest module that
 * can see both sides.
 */
class DataSourceVocabularyTest {

    @Test
    fun sourceKindsMatchTheSchema() {
        assertEquals(HealthSource.KIND_HEALTH_CONNECT, DataSourceProjection.KIND_HEALTH_CONNECT)
        assertEquals(HealthSource.KIND_APPLE_HEALTH, DataSourceProjection.KIND_APPLE_HEALTH)
        assertEquals(HealthImportRun.KIND_TAKEOUT, DataSourceProjection.KIND_TAKEOUT)
    }

    @Test
    fun runStatusesMatchTheSchema() {
        assertEquals(HealthImportRun.STATUS_SUCCEEDED, DataSourceProjection.STATUS_SUCCEEDED)
        assertEquals(HealthImportRun.STATUS_FAILED, DataSourceProjection.STATUS_FAILED)
        assertEquals(
            HealthImportRun.STATUS_PERMISSIONS_MISSING,
            DataSourceProjection.STATUS_PERMISSIONS_MISSING,
        )
        assertEquals(HealthImportRun.STATUS_NO_DATA, DataSourceProjection.STATUS_NO_DATA)
    }

    @Test
    fun runWindowsMatchTheSchema() {
        assertEquals(HealthImportRun.WINDOW_RECENT, DataSourceProjection.WINDOW_RECENT)
        assertEquals(HealthImportRun.WINDOW_FULL_HISTORY, DataSourceProjection.WINDOW_FULL_HISTORY)
        assertEquals(HealthImportRun.WINDOW_FILE, DataSourceProjection.WINDOW_FILE)
    }
}
