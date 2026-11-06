package org.tenshu.health.proxy

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.health.DeleteWorkout
import org.tenshu.core.health.GetWorkoutStreams
import org.tenshu.core.health.RecordDailyActivity
import org.tenshu.core.health.RecordDailyNutrition
import org.tenshu.core.health.RecordHealthSample
import org.tenshu.core.health.RecordImportedHealth
import org.tenshu.core.health.RecordMedicalRecord
import org.tenshu.core.health.RecordImportRun
import org.tenshu.core.health.RecordMenstruationDay
import org.tenshu.core.health.RecordSleepSession
import org.tenshu.core.health.RecordWorkout
import org.tenshu.health.proxy.executor.DeleteWorkoutImplementation
import org.tenshu.health.proxy.executor.RecordDailyActivityImplementation
import org.tenshu.health.proxy.executor.RecordDailyNutritionImplementation
import org.tenshu.health.proxy.executor.RecordHealthSampleImplementation
import org.tenshu.health.proxy.executor.RecordImportedHealthImplementation
import org.tenshu.health.proxy.executor.RecordMedicalRecordImplementation
import org.tenshu.core.health.RegisterHealthSource
import org.tenshu.core.health.SetSourceAutoImport
import org.tenshu.health.proxy.executor.RecordImportRunImplementation
import org.tenshu.health.proxy.executor.RecordMenstruationDayImplementation
import org.tenshu.health.proxy.executor.RegisterHealthSourceImplementation
import org.tenshu.health.proxy.executor.SetSourceAutoImportImplementation
import org.tenshu.health.proxy.executor.RecordSleepSessionImplementation
import org.tenshu.health.proxy.executor.GetWorkoutStreamsImplementation
import org.tenshu.health.proxy.executor.RecordWorkoutImplementation
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.common.TombstoneEmitter

/**
 * [fileDatabase] is the external byte store the content-plane `Workout.Streams` blob lives in
 * (shared with files, namespaced under `/.health/…` — read-scaling option B).
 * [syncBlockEmitterHolder] is the outbound emit seam for the entity-then-bytes recipe; `null`
 * in harnesses without sync (the blob then stays device-local, which local reads never notice).
 * [tombstoneEmitter] is the deletion counterpart — ⚠ `null` makes [DeleteWorkout] a
 * **device-local** delete, because `SyncObserver.onDataDeleted` deliberately emits nothing and
 * the tombstone is the operation's own duty. Acceptable in a sync-less harness; never in prod.
 */
fun healthServiceProxyImplementationFactory(
    id: String,
    fileDatabase: FileDatabase,
    syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    tombstoneEmitter: TombstoneEmitter? = null,
): OperationImplementation? {
    return when (id) {
        RecordWorkout.ID -> RecordWorkoutImplementation(fileDatabase, syncBlockEmitterHolder)
        DeleteWorkout.ID -> DeleteWorkoutImplementation(fileDatabase, tombstoneEmitter)
        GetWorkoutStreams.ID -> GetWorkoutStreamsImplementation(fileDatabase)
        RecordDailyActivity.ID -> RecordDailyActivityImplementation()
        RecordDailyNutrition.ID -> RecordDailyNutritionImplementation()
        RecordMenstruationDay.ID -> RecordMenstruationDayImplementation()
        RecordSleepSession.ID -> RecordSleepSessionImplementation()
        RecordHealthSample.ID -> RecordHealthSampleImplementation()
        RecordMedicalRecord.ID -> RecordMedicalRecordImplementation()
        RecordImportedHealth.ID -> RecordImportedHealthImplementation()
        RegisterHealthSource.ID -> RegisterHealthSourceImplementation()
        RecordImportRun.ID -> RecordImportRunImplementation()
        SetSourceAutoImport.ID -> SetSourceAutoImportImplementation()
        else -> null
    }
}
