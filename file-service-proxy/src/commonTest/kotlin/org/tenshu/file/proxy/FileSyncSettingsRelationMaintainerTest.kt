package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.FileSyncSetting
import org.tenshu.core.file.sync.PathSyncOverride
import org.tenshu.core.file.sync.PathSyncOverrideSet
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.toValues
import org.tenshu.file.test.createFileTestRegistry
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Preference
import org.tenshu.preference.common.preferenceServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `FileSyncSettingsRelationMaintainer` folds a device's two synced `file.sync.*`
 * `DeviceSetting` rows (default policy + overrides) into a single typed
 * [FileSyncSetting] projection under [FileSyncSetting.RELATION] — the first-class
 * `serviceDataRelation` follow-up (`docs/proposals/drive-offline-online.md`
 * §"Follow-up — a first-class serviceDataRelation").
 *
 * Rows are saved directly via `executionContext.saveData` — the shape the
 * `WriteSetting` op and the sync-merge path both produce — so the test exercises
 * the maintainer in isolation, asserting against the relation array the
 * Slice-2 `preferenceService.fileSyncSettings` accessor reads.
 */
class FileSyncSettingsRelationMaintainerTest {

    // The file-service-common test registry plus the preference module, so the
    // `DeviceSetting` schema/DataId-provider resolve and the rows can be saved.
    private fun proxy(): TestProxy =
        TestProxy(
            // createFileTestRegistry now carries the preference module itself
            // (it mirrors production, where the file proxy reads DeviceSetting rows).
            schemaRegistry = createFileTestRegistry()
        ).apply { executionContext.initFileService() }

    private fun <T> TestProxy.writeSetting(deviceId: String, preference: Preference<T>, value: T) {
        executionContext.saveData(
            Entity.create<DeviceSetting>(
                context = tContext,
                composite = DeviceSetting.create(
                    context = tContext,
                    deviceId = deviceId,
                    preferenceId = preference.id,
                    value = preference.adapter.toData(value, tContext),
                ),
            )
        )
    }

    private fun TestProxy.deleteSetting(deviceId: String, preference: Preference<*>) {
        executionContext.deleteData(
            DataId.StringDataId.create(
                context = tContext,
                type = DeviceSetting.ID,
                keyValue = DeviceSetting.compositeKey(deviceId, preference.id),
            ),
            origin = WriteOrigin.USER,
        )
    }

    private fun TestProxy.fileSyncSettings(): List<Data> {
        val ref = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = tContext,
                type = TList.ID,
                relationId = FileSyncSetting.RELATION,
            ),
            isEntity = false,
            elementSchemaId = FileSyncSetting.ID,
        )
        return executionContext.getArrayData(ref)?.toList() ?: emptyList()
    }

    private fun TestProxy.settingFor(deviceId: String): Data? =
        fileSyncSettings().firstOrNull { it.stringField(FileSyncSetting.Field.DEVICE_ID) == deviceId }

    // Tolerant field reads — the stored projection may surface as a composite DTO
    // or an Entity depending on the read path.
    private fun Data.stringField(field: String): String? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<TString>(field).get() }.getOrNull()
        is Composite -> runCatching { getField<TString>(field).get() }.getOrNull()
        else -> null
    }

    private fun Data.dataField(field: String): Data? = when (this) {
        is Entity<*> -> runCatching { getFieldValue<Data>(field) }.getOrNull()
        // Try getField (mandatory) then getOptionalField (an optional field like
        // OVERRIDES throws from getField).
        is Composite -> runCatching { getField<Data>(field) }.getOrNull()
            ?: runCatching { getOptionalField<Data>(field) }.getOrNull()
        else -> null
    }

    private fun Data.defaultPolicy(): SyncPolicy? =
        dataField(FileSyncSetting.Field.DEFAULT_POLICY) as? SyncPolicy

    // The overrides ride a composite scalar field ([PathSyncOverrideSet]) — read
    // eagerly (its nested list materialises with a null dataId), so no reference
    // resolution needed.
    private fun overridesOf(row: Data?): List<PathSyncOverride> =
        (row?.dataField(FileSyncSetting.Field.OVERRIDES) as? PathSyncOverrideSet)?.toValues()
            ?: emptyList()

    @Test
    fun writingTheDefaultPolicyDerivesATypedProjection() {
        val proxy = proxy()

        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)

        val row = proxy.settingFor("phone")
        assertTrue(row != null, "a projection row is derived for the device")
        assertEquals(SyncPolicy.KEEP_OFFLINE, row.defaultPolicy())
        assertEquals(emptyList(), overridesOf(row), "no overrides written yet -> empty")
    }

    @Test
    fun overridesFoldIntoTheSameRowWithoutLosingTheDefault() {
        val proxy = proxy()
        val overrides = listOf(PathSyncOverride("/Photos", SyncPolicy.ONLINE_ONLY))

        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)
        proxy.writeSetting("phone", FileSyncPreferences.Overrides, overrides)

        // The 2->1 fold: still one row, now carrying *both* source values.
        assertEquals(1, proxy.fileSyncSettings().size, "the two rows fold into one projection")
        val row = proxy.settingFor("phone")!!
        assertEquals(SyncPolicy.KEEP_OFFLINE, row.defaultPolicy(), "the earlier default is retained")
        assertEquals(overrides, overridesOf(row))
    }

    @Test
    fun perDeviceProjectionsAreIsolated() {
        val proxy = proxy()

        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)
        proxy.writeSetting("laptop", FileSyncPreferences.DefaultPolicy, SyncPolicy.ONLINE_ONLY)

        assertEquals(2, proxy.fileSyncSettings().size)
        assertEquals(SyncPolicy.KEEP_OFFLINE, proxy.settingFor("phone")!!.defaultPolicy())
        assertEquals(SyncPolicy.ONLINE_ONLY, proxy.settingFor("laptop")!!.defaultPolicy())
    }

    @Test
    fun reWriteConvergesToASingleRow() {
        val proxy = proxy()

        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)
        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.ONLINE_ONLY)

        assertEquals(1, proxy.fileSyncSettings().size, "idempotent remove-then-add keeps one row")
        assertEquals(
            SyncPolicy.ONLINE_ONLY,
            proxy.settingFor("phone")!!.defaultPolicy(),
            "the projection reflects the latest write",
        )
    }

    @Test
    fun deletingOneOfTwoRowsKeepsTheProjectionFromTheSurvivor() {
        val proxy = proxy()
        val overrides = listOf(PathSyncOverride("/Photos", SyncPolicy.ONLINE_ONLY))

        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)
        proxy.writeSetting("phone", FileSyncPreferences.Overrides, overrides)

        proxy.deleteSetting("phone", FileSyncPreferences.DefaultPolicy)

        val row = proxy.settingFor("phone")
        assertTrue(row != null, "the surviving overrides row keeps the projection alive")
        assertEquals(
            FileSyncPreferences.DefaultPolicy.default,
            row.defaultPolicy(),
            "the deleted default falls back to the preference default",
        )
        assertEquals(overrides, overridesOf(row), "the surviving overrides are still projected")
    }

    @Test
    fun deletingBothRowsRemovesTheProjection() {
        val proxy = proxy()

        proxy.writeSetting("phone", FileSyncPreferences.DefaultPolicy, SyncPolicy.KEEP_OFFLINE)
        proxy.writeSetting("phone", FileSyncPreferences.Overrides, listOf(PathSyncOverride("/a", SyncPolicy.ONLINE_ONLY)))

        proxy.deleteSetting("phone", FileSyncPreferences.DefaultPolicy)
        proxy.deleteSetting("phone", FileSyncPreferences.Overrides)

        assertNull(proxy.settingFor("phone"), "no source rows left -> no synthetic all-defaults projection")
        assertTrue(proxy.fileSyncSettings().isEmpty())
    }
}
