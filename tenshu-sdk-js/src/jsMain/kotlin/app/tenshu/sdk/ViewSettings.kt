@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.fold
import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.IntAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope
import org.tenshu.preference.StringAdapter
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.setForDevice
import org.tenshu.preference.value
import kotlin.js.Promise

/**
 * The web apps' remembered view settings (`docs/proposals/web-apps-layout.md` §3.6, L6).
 *
 * ⚠⚠ **DEVICE scope, like native** (maintainer, 2026-11-14): a listing's display is a per-device
 * choice — a phone and a desktop can differ — so each browser keeps its own, in its own sync device's
 * rows. Not shared across browsers, and not the phone's.
 *
 * ⚠ Preference KEYS, not new composites: every value rides an existing `DeviceSetting` row, so
 * nothing here reshapes a schema (House Rule 9).
 */
internal object WebViewPreferences {
    /**
     * ⚠⚠ The SAME row as native Drive's `DrivePreferences.ListSort` (`drive-shared-logic`): id, scope,
     * adapter and the `"<KEY>:<asc|desc>"` codec. `drive-shared-logic` has no JS target, so it is
     * re-declared here; `WebDriveSettingsMatchNativeTest` (drive-shared-logic desktopTest) reads this
     * file and fails if the two drift.
     */
    val DriveListSort = Preference(id = "drive.listing.sort", scope = PreferenceScope.DEVICE, default = "", adapter = StringAdapter)

    /** ⚠⚠ Native's `DrivePreferences.ListViewMode`: `"LIST"` / `"GRID"`. Same pin as above. */
    val DriveListViewMode = Preference(id = "drive.listing.viewMode", scope = PreferenceScope.DEVICE, default = "LIST", adapter = StringAdapter)

    /** Web only (native has no details pane): whether Drive's pane is open (D2). */
    val DriveDetailsPane = Preference(id = "drive.web.detailsPane", scope = PreferenceScope.DEVICE, default = false, adapter = BooleanAdapter)

    /** Web only (native's grid has no densities): the Library's Days / Months / Years. */
    val PhotosDensity = Preference(id = "photos.web.density", scope = PreferenceScope.DEVICE, default = "months", adapter = StringAdapter)

    /** Web only: the Library draws some photographs over 2×2 cells (native's Mosaic, layout §4.9). */
    val PhotosMosaic = Preference(id = "photos.web.mosaic", scope = PreferenceScope.DEVICE, default = false, adapter = BooleanAdapter)

    /** Web only: tiles show the whole photograph instead of a square crop (layout §4.9). */
    val PhotosFit = Preference(id = "photos.web.fit", scope = PreferenceScope.DEVICE, default = false, adapter = BooleanAdapter)

    /** Web only: the size slider's photos per row, [PHOTO_COLUMNS] (layout §4.10). */
    val PhotosColumns = Preference(id = "photos.web.columns", scope = PreferenceScope.DEVICE, default = 5, adapter = IntAdapter)
}

/** The size slider's steps, as iCloud's: 8 photos per row down to 3. */
internal val PHOTO_COLUMNS = 3..8

/** Native's sort keys, by name — what `FileSortKey.valueOf` accepts on the other side. */
private val SORT_KEYS = setOf("NAME", "MODIFIED", "CREATED", "SIZE")
private val VIEW_MODES = setOf("LIST", "GRID")
private val DENSITIES = setOf("days", "months", "years")

private fun deviceIdOrNull(): String? =
    runCatching { JsRuntime.syncManager.currentDeviceId }.getOrNull()?.takeIf { it.isNotBlank() }

/** `"NAME:asc"` → (`"name"`, false); blank or unknown → native's default, Name ascending. */
internal fun decodeDriveSort(raw: String): Pair<String, Boolean> {
    val parts = raw.split(":")
    val key = parts.getOrNull(0)?.takeIf { it in SORT_KEYS } ?: return "name" to false
    return key.lowercase() to (parts.getOrNull(1) == "desc")
}

internal fun encodeDriveSort(key: String, descending: Boolean): String? =
    key.uppercase().takeIf { it in SORT_KEYS }?.let { "$it:${if (descending) "desc" else "asc"}" }

/**
 * Drive's listing display for THIS browser: `{ ok, key, descending, layout, detailsPane }` with
 * `key` one of `name|modified|created|size` and `layout` `list|grid`. ⚠ `{ ok: false }` with
 * `no_device` while the browser has no sync device id — the app then keeps its local copy.
 */
@JsExport
object DriveSettings {

    fun get(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise failureResult("no_device", "no sync device id yet")
        // ⚠⚠ Whether each row EXISTS, not just its value: a missing row reads as the default, and a
        // browser's first L6 load must not let that default override (and erase) the choice it kept
        // in localStorage before — the app adopts its local value for a row that is not stored.
        val sortRead = deviceStored(WebViewPreferences.DriveListSort, deviceId)
        val modeRead = deviceStored(WebViewPreferences.DriveListViewMode, deviceId)
        val paneRead = deviceStored(WebViewPreferences.DriveDetailsPane, deviceId)
        if (sortRead !is Read.Ok) return@promise failureResult("read_failed", (sortRead as Read.Failed).error.toString())
        if (modeRead !is Read.Ok) return@promise failureResult("read_failed", (modeRead as Read.Failed).error.toString())
        if (paneRead !is Read.Ok) return@promise failureResult("read_failed", (paneRead as Read.Failed).error.toString())
        val sort = sortRead.value
        val mode = modeRead.value
        val pane = paneRead.value
        val (key, descending) = decodeDriveSort(sort ?: "")
        okResult {
            it.key = key
            it.descending = descending
            it.layout = if (mode != null && mode in VIEW_MODES) mode.lowercase() else "list"
            it.detailsPane = pane ?: false
            it.sortStored = sort != null
            it.layoutStored = mode != null
            it.paneStored = pane != null
        }
    }

    // ⚠ Writes are serialised: each is a read-then-write, and two quick clicks interleaved could store
    // the earlier choice last (or one call's sort beside the other's layout).
    private val writes = Mutex()

    /** Stores the sort and layout for this browser. ⚠ An unchanged value writes nothing (a sync block each). */
    fun setListing(key: String, descending: Boolean, layout: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        writes.withLock { setListingLocked(key, descending, layout) }
    }

    private suspend fun setListingLocked(key: String, descending: Boolean, layout: String): dynamic {
        val deviceId = deviceIdOrNull() ?: return failureResult("no_device", "no sync device id yet")
        val sort = encodeDriveSort(key, descending) ?: return failureResult("bad_value", "sort key '$key'")
        val mode = layout.uppercase().takeIf { it in VIEW_MODES } ?: return failureResult("bad_value", "layout '$layout'")
        val sortFailed = writeIfChanged(WebViewPreferences.DriveListSort, sort, deviceId)
        if (sortFailed != null) return sortFailed
        return writeIfChanged(WebViewPreferences.DriveListViewMode, mode, deviceId) ?: okResult()
    }

    fun setDetailsPane(open: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise failureResult("no_device", "no sync device id yet")
        writes.withLock { writeIfChanged(WebViewPreferences.DriveDetailsPane, open, deviceId) ?: okResult() }
    }
}

/**
 * Photos' Library display for THIS browser: `{ ok, density, mosaic, fit, columns }` — `density` one
 * of `days|months|years`, `mosaic` and `fit` booleans, `columns` 3…8. Same rules as [DriveSettings].
 */
@JsExport
object PhotosSettings {

    fun get(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise failureResult("no_device", "no sync device id yet")
        val density = when (val read = devicePref(WebViewPreferences.PhotosDensity, deviceId)) {
            is Read.Failed -> return@promise failureResult("read_failed", read.error.toString())
            is Read.Ok -> read.value.takeIf { v -> v in DENSITIES } ?: "months"
        }
        val mosaic = when (val read = devicePref(WebViewPreferences.PhotosMosaic, deviceId)) {
            is Read.Failed -> return@promise failureResult("read_failed", read.error.toString())
            is Read.Ok -> read.value
        }
        val fit = when (val read = devicePref(WebViewPreferences.PhotosFit, deviceId)) {
            is Read.Failed -> return@promise failureResult("read_failed", read.error.toString())
            is Read.Ok -> read.value
        }
        val columns = when (val read = devicePref(WebViewPreferences.PhotosColumns, deviceId)) {
            is Read.Failed -> return@promise failureResult("read_failed", read.error.toString())
            is Read.Ok -> read.value.takeIf { it in PHOTO_COLUMNS } ?: WebViewPreferences.PhotosColumns.default
        }
        okResult {
            it.density = density
            it.mosaic = mosaic
            it.fit = fit
            it.columns = columns
        }
    }

    // Serialised for the same reason as [DriveSettings]'s writes.
    private val writes = Mutex()

    fun setDensity(density: String): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise failureResult("no_device", "no sync device id yet")
        if (density !in DENSITIES) return@promise failureResult("bad_value", "density '$density'")
        writes.withLock { writeIfChanged(WebViewPreferences.PhotosDensity, density, deviceId) ?: okResult() }
    }

    /** Stores the size slider's photos per row (3…8). ⚠ An unchanged value writes nothing. */
    fun setColumns(columns: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise failureResult("no_device", "no sync device id yet")
        if (columns !in PHOTO_COLUMNS) return@promise failureResult("bad_value", "columns '$columns'")
        writes.withLock { writeIfChanged(WebViewPreferences.PhotosColumns, columns, deviceId) ?: okResult() }
    }

    /** Stores the mosaic and whole-photo switches. ⚠ An unchanged value writes nothing. */
    fun setLayout(mosaic: Boolean, fit: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val deviceId = deviceIdOrNull() ?: return@promise failureResult("no_device", "no sync device id yet")
        writes.withLock {
            writeIfChanged(WebViewPreferences.PhotosMosaic, mosaic, deviceId)
                ?: writeIfChanged(WebViewPreferences.PhotosFit, fit, deviceId)
                ?: okResult()
        }
    }
}

/** Writes [value] unless it is already stored; `null` on success, else the failure to answer with. */
private suspend fun <T> writeIfChanged(pref: Preference<T>, value: T, deviceId: String): dynamic {
    when (val current = devicePref(pref, deviceId)) {
        is Read.Failed -> return failureResult("read_failed", current.error.toString())
        is Read.Ok -> if (current.value == value) return null
    }
    return JsRuntime.tenshuClient.preferenceService.setForDevice(pref, value, deviceId).fold(
        onSuccess = { null },
        onFailure = { failureResult("write_failed", it.toString()) },
    )
}

/** [devicePref], but null when the row does not exist — never the declared default. */
private suspend fun <T> deviceStored(pref: Preference<T>, deviceId: String): Read<T?> =
    JsRuntime.tenshuClient.preferenceService.deviceSettings.getAll()
        .fold(onSuccess = { Read.Ok(pref.storedIn(it.toList(), deviceId)) }, onFailure = { Read.Failed(it) })

/** This device's stored value of [this], or null when the row does not exist (never the default). */
private fun <T> Preference<T>.storedIn(rows: List<DeviceSetting>, deviceId: String): T? {
    val key = DeviceSetting.compositeKey(deviceId, id)
    return rows.firstOrNull { it.compositeKey.get() == key }?.let { adapter.fromData(it.value) }
}

