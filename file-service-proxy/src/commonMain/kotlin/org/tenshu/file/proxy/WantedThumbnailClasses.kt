package org.tenshu.file.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.Setting
import org.tenshu.preference.StringListAdapter

/**
 * This device's **wanted thumbnail classes** (`drive-synced-thumbnails.md` §15.2 D-5), kept as a
 * `DeviceSetting` row every byte holder in the realm can read — and the proxy-side writer of it.
 *
 * ## ⭐ The pull is the declaration
 *
 * The proxy learns that a class is wanted the first time a client on this device names the rung:
 * `cacheThumbnails` pulling `File.ThumbnailXLarge`, the grid's rotation reading
 * `File.ThumbnailMotion`. That is §14.5's own observation — *a rung nothing pulls does not exist*
 * — turned into a record, and it needs no app to declare anything: [notePull] hangs off the
 * resolver's `OnDemandPullObserver`, which sees every projected OnDemand property whether or not
 * the bytes are already here. An app *may* also call [declare] at start; the row is the union.
 *
 * ## ⚠⚠ Class granularity, and only that
 *
 * The row says *"this device renders `XLARGE`"*. It never names a file, a count or a time — the
 * per-file demand signal §10.3.2 rejected as an access pattern is precisely what this must not
 * become, and [ThumbnailPreferences.WantedClasses] carries the rule. This class therefore takes a
 * property id and forgets the entity id it arrived with.
 *
 * ## ⚠ Cheap in steady state
 *
 * Fires on every pull, so after the first sighting of a class it is one uncontended lock and a set
 * lookup; the store is read once per class per process and written only when the row changes.
 * A device with no identity yet (`deviceId()` null) records nothing and retries on the next pull.
 *
 * ⚠ The write is best-effort: a failed save must never fail the fetch it was observed on.
 */
class WantedThumbnailClasses(
    private val deviceId: () -> String?,
) {
    private val mutex = Mutex()

    /** Classes known to be in this device's row — as written by us, or as read back. Guarded by [mutex]. */
    private val declared = mutableSetOf<String>()

    /** The pull-path hook: records the class behind [propertyId], if it is a declarable thumbnail rung. */
    suspend fun notePull(executionContext: ExecutionContext, propertyId: String) {
        val thumbnailClass = FileBytesPropertyWriter.thumbnailClassFor(propertyId) ?: return
        declare(executionContext, thumbnailClass)
    }

    /**
     * Record that this device wants [thumbnailClass]. Idempotent; a class outside
     * [ThumbnailPreferences.DECLARABLE_CLASSES] (`LIST`, `LARGE`, or a typo) is ignored.
     */
    suspend fun declare(executionContext: ExecutionContext, thumbnailClass: String) {
        if (thumbnailClass !in ThumbnailPreferences.DECLARABLE_CLASSES) return
        val device = deviceId() ?: return
        mutex.withLock {
            if (thumbnailClass in declared) return
            try {
                val stored = executionContext.readWantedThumbnailClasses(device)
                if (thumbnailClass !in stored) {
                    executionContext.writeWantedThumbnailClasses(device, stored + thumbnailClass)
                }
                declared += stored
                declared += thumbnailClass
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Best-effort: the next pull of this class tries again.
            }
        }
    }
}

/** This device's own declared classes — the row's value, restricted to the declarable set. */
fun ExecutionContext.readWantedThumbnailClasses(deviceId: String): Set<String> {
    val stored = StringListAdapter.fromData(readDeviceSettingData(deviceId, WANTED_CLASSES_ID))
        ?: return emptySet()
    return ThumbnailPreferences.declarable(stored)
}

/** Replace this device's declared classes. */
suspend fun ExecutionContext.writeWantedThumbnailClasses(deviceId: String, classes: Set<String>) =
    writeDeviceSettingValue(
        deviceId = deviceId,
        preferenceId = WANTED_CLASSES_ID,
        value = StringListAdapter.toData(ThumbnailPreferences.declarable(classes).toList(), context),
    )

/**
 * **`wanted(realm)`** — the union over every device's [ThumbnailPreferences.WantedClasses] row
 * (§15.2), read by a bounded scan of the `DeviceSetting` type. ⚠ No device directory is consulted:
 * a device that left the realm stops being counted when its rows are tombstoned, and a device that
 * never wrote a row wants nothing beyond the implicit `LIST` + `LARGE`.
 *
 * Returns `null` when the store cannot enumerate (the JS `IndexedDbObjectStore`), which is **not**
 * an empty set — the backfill must not latch its marker over a realm it could not read.
 */
fun ExecutionContext.wantedThumbnailClassesInRealm(objectStore: ObjectStore): Set<String>? {
    val pages = objectStore.pagesOfType(DeviceSetting.ID) ?: return null
    // ⚠ A plain set here; `declarable` imposes the stable order on the way out (and `sortedSetOf`
    // is JVM-only, so it would break the JS and K/N lanes from `commonMain`).
    val wanted = mutableSetOf<String>()
    for (page in pages) {
        for (row in page) {
            val data = decodeRow(objectStore, row) ?: continue
            if (settingPreferenceId(data) != WANTED_CLASSES_ID) continue
            val classes = StringListAdapter.fromData(settingValue(data)) ?: continue
            wanted += ThumbnailPreferences.declarable(classes)
        }
    }
    return ThumbnailPreferences.declarable(wanted)
}

private fun settingPreferenceId(data: Data): String? = when (data) {
    is Entity<*> -> runCatching { data.getFieldValue<TString>(Setting.Field.PREFERENCE_ID).get() }.getOrNull()
    is Composite -> runCatching { data.getField<TString>(Setting.Field.PREFERENCE_ID).get() }.getOrNull()
    else -> null
}

private fun settingValue(data: Data): Data? = when (data) {
    is Entity<*> -> runCatching { data.getFieldValue<Data>(Setting.Field.VALUE) }.getOrNull()
    is Composite -> runCatching { data.getField<Data>(Setting.Field.VALUE) }.getOrNull()
    else -> null
}

private val WANTED_CLASSES_ID: String = ThumbnailPreferences.WantedClasses.id
