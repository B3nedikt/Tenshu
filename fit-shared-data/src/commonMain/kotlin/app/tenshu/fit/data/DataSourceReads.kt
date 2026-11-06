package app.tenshu.fit.data

import app.tenshu.fit.model.HealthSourceInput
import app.tenshu.fit.model.ImportRunInput
import org.tenshu.core.TString
import org.tenshu.core.TBoolean
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.number.TInteger
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSource

/**
 * Flattens the Data sources registry's entities into the plain inputs
 * [app.tenshu.fit.model.DataSourceProjection] consumes (DS-3 / D10).
 *
 * ⚠ This file is the *only* place the two worlds meet: `:fit-shared-logic` is client-free and
 * cannot see `HealthSource` / `HealthImportRun` at all, so the projection takes plain classes and
 * this does the extraction — the same seam `FitProjection.WorkoutInput` uses for `Workout`.
 */

internal val SOURCE_FIELDS = arrayOf(
    // ⚠⚠ KEY first, and it is NOT optional to include. A projected read over a relation must
    // keep the field this composite's `DataIdProvider` points at: the stream merge resolves each
    // row's DataId to diff snapshots, and without it every emission throws
    // `Field …/key not found` — which `retryingOnThrow` then converts into a flow that retries
    // forever and never emits. The symptom is a screen that loads for ever, not an error.
    HealthSource.Fields.KEY,
    HealthSource.Fields.KIND,
    HealthSource.Fields.DEVICE_ID,
    HealthSource.Fields.DEVICE_LABEL,
    HealthSource.Fields.AUTO_IMPORT,
    HealthSource.Fields.LAST_RUN_AT,
    HealthSource.Fields.LAST_STATUS,
    HealthSource.Fields.LAST_ITEM_COUNT,
)

internal val RUN_FIELDS = arrayOf(
    // ⚠⚠ See SOURCE_FIELDS — the id-provider's field must be projected.
    HealthImportRun.Fields.KEY,
    HealthImportRun.Fields.DEVICE_ID,
    HealthImportRun.Fields.KIND,
    HealthImportRun.Fields.STARTED_AT,
    HealthImportRun.Fields.FINISHED_AT,
    HealthImportRun.Fields.WINDOW,
    HealthImportRun.Fields.STATUS,
    HealthImportRun.Fields.ITEM_COUNT,
    HealthImportRun.Fields.WORKOUT_COUNT,
    HealthImportRun.Fields.BYTE_COUNT,
    HealthImportRun.Fields.FILE_NAME,
)

internal fun healthSourceInput(e: Entity<out HealthSource>) = HealthSourceInput(
    kind = e.getFieldValue<TString>(HealthSource.Fields.KIND).get(),
    deviceId = e.getFieldValue<TString>(HealthSource.Fields.DEVICE_ID).get(),
    deviceLabel = e.getFieldValue<TString>(HealthSource.Fields.DEVICE_LABEL).get(),
    autoImport = e.getFieldValue<TBoolean>(HealthSource.Fields.AUTO_IMPORT).get(),
    lastRunAt = e.optionalLong(HealthSource.Fields.LAST_RUN_AT),
    lastStatus = e.optionalString(HealthSource.Fields.LAST_STATUS),
    lastItemCount = e.optionalLong(HealthSource.Fields.LAST_ITEM_COUNT),
)

internal fun importRunInput(e: Entity<out HealthImportRun>) = ImportRunInput(
    deviceId = e.getFieldValue<TString>(HealthImportRun.Fields.DEVICE_ID).get(),
    kind = e.getFieldValue<TString>(HealthImportRun.Fields.KIND).get(),
    startedAt = e.getFieldValue<TInteger>(HealthImportRun.Fields.STARTED_AT).get(),
    finishedAt = e.getFieldValue<TInteger>(HealthImportRun.Fields.FINISHED_AT).get(),
    window = e.getFieldValue<TString>(HealthImportRun.Fields.WINDOW).get(),
    status = e.getFieldValue<TString>(HealthImportRun.Fields.STATUS).get(),
    itemCount = e.getFieldValue<TInteger>(HealthImportRun.Fields.ITEM_COUNT).get(),
    workoutCount = e.getFieldValue<TInteger>(HealthImportRun.Fields.WORKOUT_COUNT).get(),
    byteCount = e.optionalLong(HealthImportRun.Fields.BYTE_COUNT),
    fileName = e.optionalString(HealthImportRun.Fields.FILE_NAME),
    // ⚠ `error` is deliberately NOT projected. It is a typed code for diagnosis, and nothing on
    // the Data sources surface renders it — reading it would put it on the wire for no consumer.
)

// ⚠⚠ These used to read `runCatching { getFieldValue<T>(ref).get() }`, on the assumption that an
// absent optional throws and the catch turns it into null. That is true on the JVM and is a
// **SIGSEGV on Kotlin/Native** — the unchecked cast hands back a null in a non-null slot and `.get()`
// dereferences 0x0, which no catch intercepts. It segfaulted the iOS test binary outright.
// `optionalFieldValue` asks for a NULLABLE T so the null is a value, not a crash.
private fun Entity<*>.optionalLong(ref: String): Long? =
    optionalFieldValue<TInteger>(ref)?.get()

private fun Entity<*>.optionalString(ref: String): String? =
    optionalFieldValue<TString>(ref)?.get()?.takeIf { it.isNotBlank() }

/**
 * Replaces each stamped `deviceLabel` with the roster's name where there is one (DS-7).
 *
 * ⚠⚠ **An upgrade, never a gate**, and per-device rather than all-or-nothing. The stamp is
 * always renderable; the roster only improves it. An empty map means "the roster has not
 * loaded" — which is indistinguishable from "that device is gone", because revocation deletes
 * the `Device` row rather than flagging it — so absence here must never remove or annotate a row.
 */
internal fun List<HealthSourceInput>.withRosterNames(names: Map<String, String>): List<HealthSourceInput> =
    map { source -> names[source.deviceId]?.let { source.copy(deviceLabel = it) } ?: source }
