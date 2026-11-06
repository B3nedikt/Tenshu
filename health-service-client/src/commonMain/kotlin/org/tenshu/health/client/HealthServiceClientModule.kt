package org.tenshu.health.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.DeleteWorkout
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.LatestSample
import org.tenshu.core.health.RecordImportRun
import org.tenshu.core.health.RegisterHealthSource
import org.tenshu.core.health.SetSourceAutoImport
import org.tenshu.core.health.WorkoutActivityRollup
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.RecordDailyActivity
import org.tenshu.core.health.RecordDailyNutrition
import org.tenshu.core.health.RecordHealthSample
import org.tenshu.core.health.RecordImportedHealth
import org.tenshu.core.health.RecordMedicalRecord
import org.tenshu.core.health.RecordMenstruationDay
import org.tenshu.core.health.RecordSleepSession
import org.tenshu.core.health.GetWorkoutStreams
import org.tenshu.core.health.RecordWorkout
import org.tenshu.core.health.SleepSession
import org.tenshu.core.health.Workout
import org.tenshu.health.client.impl.DeleteWorkoutRo
import org.tenshu.health.client.impl.HealthServiceRo
import org.tenshu.health.client.impl.RecordDailyActivityRo
import org.tenshu.health.client.impl.RecordDailyNutritionRo
import org.tenshu.health.client.impl.RecordHealthSampleRo
import org.tenshu.health.client.impl.RecordImportRunRo
import org.tenshu.health.client.impl.RecordImportedHealthRo
import org.tenshu.health.client.impl.RegisterHealthSourceRo
import org.tenshu.health.client.impl.SetSourceAutoImportRo
import org.tenshu.health.client.impl.RecordMedicalRecordRo
import org.tenshu.health.client.impl.RecordMenstruationDayRo
import org.tenshu.health.client.impl.RecordSleepSessionRo
import org.tenshu.health.client.impl.GetWorkoutStreamsRo
import org.tenshu.health.client.impl.RecordWorkoutRo

val healthServiceClientModule = tModule {

    type(
        interfaceClass = Workout::class,
        dataSchema = Workout.Schema,
        // A Workout-TYPED request object (not a bare CompositeRo) so a sort/selector lambda can
        // read a field off the element — `workouts.sortByDescending { start }` calls Workout.start,
        // whose default accessor builds the field-read chain. Mirrors Notification's typed RO
        // (`notifications.sortByDescending { timestamp }`). See WorkoutSortWireTest.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, Workout.Schema), Workout {
                override val schema: CompositeSchema get() = Workout.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordWorkout::class,
        protocolSchema = RecordWorkout.Schema,
        roFactory = ::RecordWorkoutRo
    )

    // On-demand resolver for the content-plane Workout.Streams blob (read-scaling
    // option B) — the health twin of GetData/GetDataRo.
    operation(
        interfaceClass = GetWorkoutStreams::class,
        protocolSchema = GetWorkoutStreams.Schema,
        roFactory = ::GetWorkoutStreamsRo
    )

    type(
        interfaceClass = DailyActivity::class,
        dataSchema = DailyActivity.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, DailyActivity.Schema), DailyActivity {
                override val schema: CompositeSchema get() = DailyActivity.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordDailyActivity::class,
        protocolSchema = RecordDailyActivity.Schema,
        roFactory = ::RecordDailyActivityRo
    )

    type(
        interfaceClass = DailyNutrition::class,
        dataSchema = DailyNutrition.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, DailyNutrition.Schema), DailyNutrition {
                override val schema: CompositeSchema get() = DailyNutrition.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordDailyNutrition::class,
        protocolSchema = RecordDailyNutrition.Schema,
        roFactory = ::RecordDailyNutritionRo
    )

    type(
        interfaceClass = MenstruationDay::class,
        dataSchema = MenstruationDay.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, MenstruationDay.Schema), MenstruationDay {
                override val schema: CompositeSchema get() = MenstruationDay.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordMenstruationDay::class,
        protocolSchema = RecordMenstruationDay.Schema,
        roFactory = ::RecordMenstruationDayRo
    )

    type(
        interfaceClass = SleepSession::class,
        dataSchema = SleepSession.Schema,
        // A SleepSession-TYPED request object so the sort selector lambda can read a field off
        // the element — `sleepSessions.sortByDescending { date }` (the windowed Ph4 read).
        // Mirrors Workout's typed RO above.
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, SleepSession.Schema), SleepSession {
                override val schema: CompositeSchema get() = SleepSession.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordSleepSession::class,
        protocolSchema = RecordSleepSession.Schema,
        roFactory = ::RecordSleepSessionRo
    )

    type(
        interfaceClass = HealthSample::class,
        dataSchema = HealthSample.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, HealthSample.Schema), HealthSample {
                override val schema: CompositeSchema get() = HealthSample.Schema
            }
        }
    )

    type(
        interfaceClass = LatestSample::class,
        dataSchema = LatestSample.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, LatestSample.Schema), LatestSample {
                override val schema: CompositeSchema get() = LatestSample.Schema
            }
        }
    )

    type(
        interfaceClass = WorkoutActivityRollup::class,
        dataSchema = WorkoutActivityRollup.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, WorkoutActivityRollup.Schema), WorkoutActivityRollup {
                override val schema: CompositeSchema get() = WorkoutActivityRollup.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordHealthSample::class,
        protocolSchema = RecordHealthSample.Schema,
        roFactory = ::RecordHealthSampleRo
    )

    type(
        interfaceClass = MedicalRecord::class,
        dataSchema = MedicalRecord.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, MedicalRecord.Schema), MedicalRecord {
                override val schema: CompositeSchema get() = MedicalRecord.Schema
            }
        }
    )

    operation(
        interfaceClass = RecordMedicalRecord::class,
        protocolSchema = RecordMedicalRecord.Schema,
        roFactory = ::RecordMedicalRecordRo
    )

    operation(
        interfaceClass = RecordImportedHealth::class,
        protocolSchema = RecordImportedHealth.Schema,
        roFactory = ::RecordImportedHealthRo
    )

    operation(
        interfaceClass = DeleteWorkout::class,
        protocolSchema = DeleteWorkout.Schema,
        roFactory = ::DeleteWorkoutRo
    )

    // ⚠ Both get a TYPED request object, not a bare CompositeRo: DS-3's projection reads
    // `sources.filter { kind equalTo … }` and `importRuns.sortByDescending { startedAt }`, and
    // a selector lambda can only read a field off the element when the RO carries the type. A
    // client module shadows the common one here, so an untyped RO fails at the predicate, not
    // at compile.
    type(
        interfaceClass = HealthSource::class,
        dataSchema = HealthSource.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, HealthSource.Schema), HealthSource {
                override val schema: CompositeSchema get() = HealthSource.Schema
            }
        }
    )

    type(
        interfaceClass = HealthImportRun::class,
        dataSchema = HealthImportRun.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, HealthImportRun.Schema), HealthImportRun {
                override val schema: CompositeSchema get() = HealthImportRun.Schema
            }
        }
    )

    operation(
        interfaceClass = RegisterHealthSource::class,
        protocolSchema = RegisterHealthSource.Schema,
        roFactory = ::RegisterHealthSourceRo
    )

    operation(
        interfaceClass = RecordImportRun::class,
        protocolSchema = RecordImportRun.Schema,
        roFactory = ::RecordImportRunRo
    )

    operation(
        interfaceClass = SetSourceAutoImport::class,
        protocolSchema = SetSourceAutoImport.Schema,
        roFactory = ::SetSourceAutoImportRo
    )

    type(
        interfaceClass = HealthService::class,
        dataSchema = HealthService.Schema,
        roFactory = ::HealthServiceRo
    )
}
