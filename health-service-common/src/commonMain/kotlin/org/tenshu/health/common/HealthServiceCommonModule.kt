package org.tenshu.health.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.health.ContentId
import org.tenshu.core.common.module.dynamicProperty
import org.tenshu.core.common.module.valueProperty
import org.tenshu.core.common.module.fulfilValueRequirement
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.health.GetWorkoutStreams
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthImportRunDataIdProvider
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.HealthSourceDataIdProvider
import org.tenshu.core.health.RecordImportRun
import org.tenshu.core.health.RegisterHealthSource
import org.tenshu.core.health.SetSourceAutoImport
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.DailyActivityDataIdProvider
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.DailyNutritionDataIdProvider
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.HealthSampleDataIdProvider
import org.tenshu.core.health.LatestSample
import org.tenshu.core.health.WorkoutActivityRollup
import org.tenshu.core.health.WorkoutActivityRollupDataIdProvider
import org.tenshu.core.health.WorkoutRollupAttribution
import org.tenshu.core.health.WorkoutRollupAttributionDataIdProvider
import org.tenshu.core.health.WorkoutRollupState
import org.tenshu.core.health.WorkoutRollupStateDataIdProvider
import org.tenshu.core.health.LatestSampleDataIdProvider
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.MedicalRecordDataIdProvider
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.MenstruationDayDataIdProvider
import org.tenshu.core.health.SleepSession
import org.tenshu.core.health.SleepSessionDataIdProvider
import org.tenshu.core.health.Workout
import org.tenshu.core.trait.ContentReferencing
import org.tenshu.core.health.WorkoutDataIdProvider
import org.tenshu.core.trait.RealmData
import org.tenshu.health.common.impl.DailyActivityDataIdProviderImpl
import org.tenshu.health.common.impl.DailyActivityDto
import org.tenshu.health.common.impl.DailyNutritionDataIdProviderImpl
import org.tenshu.health.common.impl.DailyNutritionDto
import org.tenshu.health.common.impl.HealthImportRunDataIdProviderImpl
import org.tenshu.health.common.impl.HealthImportRunDto
import org.tenshu.health.common.impl.HealthSampleDataIdProviderImpl
import org.tenshu.health.common.impl.HealthSampleDto
import org.tenshu.health.common.impl.HealthSourceDataIdProviderImpl
import org.tenshu.health.common.impl.HealthSourceDto
import org.tenshu.health.common.impl.LatestSampleDataIdProviderImpl
import org.tenshu.health.common.impl.WorkoutActivityRollupDataIdProviderImpl
import org.tenshu.health.common.impl.WorkoutActivityRollupDto
import org.tenshu.health.common.impl.WorkoutRollupAttributionDataIdProviderImpl
import org.tenshu.health.common.impl.WorkoutRollupAttributionDto
import org.tenshu.health.common.impl.WorkoutRollupStateDataIdProviderImpl
import org.tenshu.health.common.impl.WorkoutRollupStateDto
import org.tenshu.health.common.impl.LatestSampleDto
import org.tenshu.health.common.impl.MenstruationDayDataIdProviderImpl
import org.tenshu.health.common.impl.MenstruationDayDto
import org.tenshu.health.common.impl.MedicalRecordDataIdProviderImpl
import org.tenshu.health.common.impl.MedicalRecordDto
import org.tenshu.health.common.impl.SleepSessionDataIdProviderImpl
import org.tenshu.health.common.impl.SleepSessionDto
import org.tenshu.health.common.impl.WorkoutDataIdProviderImpl
import org.tenshu.health.common.impl.WorkoutDto
import org.tenshu.core.health.Start
import org.tenshu.core.health.Streams

val healthServiceCommonModule = tModule {

    service(HealthService.Schema)

    composite(
        interfaceClass = Workout::class,
        dataSchema = Workout.Schema,
        dtoFactory = ::WorkoutDto
    )

    trait(
        dataSchema = Workout.Schema,
        traitSchema = WorkoutDataIdProvider.Schema,
        traitFactory = { context, schema ->
            WorkoutDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // Workout.Start fulfils RealmData.CREATED_AT — the structural creation timestamp the
    // trait contracts for (core-realm-data.md §8). START, not END and not an import wall
    // clock: a workout IS its session, users date one by when it started, and it is the
    // field the whole read layer already orders history by (`sortByDescending { start }`).
    // There is no separate "recorded at" on Workout, and inventing one would be a schema
    // change for a value no producer writes.
    //
    // ⚠⚠ START is a schema FIELD that `Workout.Start` merely aliases (unlike File.CreatedAt,
    // which is a genuine stored value property). Field-aliased handles resolve at the WIRE
    // reference layer (which is why `sortBy` works — see WorkoutSortWireTest), but NOT via
    // `Entity.getProperty`, which is how `DeleteRealmData` reads the cut-off. So a
    // `createdBefore`-narrowed reset of Workouts currently matches nothing and deletes
    // nothing (conservative by construction — the op keeps a row whose createdAt it cannot
    // read). The whole-type reset, which is what Control's Data section offers, is
    // unaffected. See docs/proposals/control-data-reset.md §10 Q1.
    fulfilValueRequirement(
        fromId = Workout.ID,
        requirementId = RealmData.CREATED_AT,
        property = Workout.Start,
    )

    // Content-plane route + HR stream blob (read-scaling option B,
    // docs/proposals/fit-workout-streams-content-plane.md): a dynamicProperty(Syncable(OnDemand))
    // resolved via GetWorkoutStreams from the external FileDatabase — never stored on the
    // Workout entity, so a full-history import keeps the entity cache small and the History
    // list select stays byte-less. Encoded by WorkoutStreamsCodec; File.Data pattern.
    dynamicProperty(fromId = Workout.ID, property = Workout.Streams)

    // The content identity `Workout.Streams` is addressed by (`content-plane-refcount.md` §9.12).
    // ⚠ A valueProperty like `File.ContentId`, not a composite field: a named slot reshapes no
    // stored row, so a content identity costs no service-version bump and no migration.
    valueProperty(fromId = Workout.ID, property = Workout.ContentId) { TStringDto(it as String) }
    // CR-2 B3a (`content-plane-cr2-quorumless.md` §10.11): `Workout.Streams` is this value
    // (`PrimaryContent` on the property), resolved generically by `contentEdgesOf`.
    fulfilValueRequirement(
        fromId = Workout.ID,
        requirementId = ContentReferencing.CONTENT_ID,
        property = Workout.ContentId,
    )
    operation(GetWorkoutStreams.Schema)

    composite(
        interfaceClass = DailyActivity::class,
        dataSchema = DailyActivity.Schema,
        dtoFactory = ::DailyActivityDto
    )

    trait(
        dataSchema = DailyActivity.Schema,
        traitSchema = DailyActivityDataIdProvider.Schema,
        traitFactory = { context, schema ->
            DailyActivityDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = DailyNutrition::class,
        dataSchema = DailyNutrition.Schema,
        dtoFactory = ::DailyNutritionDto
    )

    trait(
        dataSchema = DailyNutrition.Schema,
        traitSchema = DailyNutritionDataIdProvider.Schema,
        traitFactory = { context, schema ->
            DailyNutritionDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = MenstruationDay::class,
        dataSchema = MenstruationDay.Schema,
        dtoFactory = ::MenstruationDayDto
    )

    trait(
        dataSchema = MenstruationDay.Schema,
        traitSchema = MenstruationDayDataIdProvider.Schema,
        traitFactory = { context, schema ->
            MenstruationDayDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = SleepSession::class,
        dataSchema = SleepSession.Schema,
        dtoFactory = ::SleepSessionDto
    )

    trait(
        dataSchema = SleepSession.Schema,
        traitSchema = SleepSessionDataIdProvider.Schema,
        traitFactory = { context, schema ->
            SleepSessionDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = HealthSample::class,
        dataSchema = HealthSample.Schema,
        dtoFactory = ::HealthSampleDto
    )

    trait(
        dataSchema = HealthSample.Schema,
        traitSchema = HealthSampleDataIdProvider.Schema,
        traitFactory = { context, schema ->
            HealthSampleDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = LatestSample::class,
        dataSchema = LatestSample.Schema,
        dtoFactory = ::LatestSampleDto
    )

    trait(
        dataSchema = LatestSample.Schema,
        traitSchema = LatestSampleDataIdProvider.Schema,
        traitFactory = { context, schema ->
            LatestSampleDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = WorkoutActivityRollup::class,
        dataSchema = WorkoutActivityRollup.Schema,
        dtoFactory = ::WorkoutActivityRollupDto
    )

    trait(
        dataSchema = WorkoutActivityRollup.Schema,
        traitSchema = WorkoutActivityRollupDataIdProvider.Schema,
        traitFactory = { context, schema ->
            WorkoutActivityRollupDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // The rollup's bookkeeping row: registered as a composite so it can be stored and read back
    // by id, but given NO service relation — nothing enumerates attributions, they are only ever
    // looked up for one workout at a time (see WorkoutRollupAttribution).
    composite(
        interfaceClass = WorkoutRollupAttribution::class,
        dataSchema = WorkoutRollupAttribution.Schema,
        dtoFactory = ::WorkoutRollupAttributionDto
    )

    trait(
        dataSchema = WorkoutRollupAttribution.Schema,
        traitSchema = WorkoutRollupAttributionDataIdProvider.Schema,
        traitFactory = { context, schema ->
            WorkoutRollupAttributionDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // The rollup's rebuild stamp. Like the attribution row it gets NO service relation — it is a
    // singleton read by its constant key, never enumerated.
    composite(
        interfaceClass = WorkoutRollupState::class,
        dataSchema = WorkoutRollupState.Schema,
        dtoFactory = ::WorkoutRollupStateDto
    )

    trait(
        dataSchema = WorkoutRollupState.Schema,
        traitSchema = WorkoutRollupStateDataIdProvider.Schema,
        traitFactory = { context, schema ->
            WorkoutRollupStateDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = MedicalRecord::class,
        dataSchema = MedicalRecord.Schema,
        dtoFactory = ::MedicalRecordDto
    )

    trait(
        dataSchema = MedicalRecord.Schema,
        traitSchema = MedicalRecordDataIdProvider.Schema,
        traitFactory = { context, schema ->
            MedicalRecordDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    // The Data sources registry (`fit-data-sources-restructure.md` DS-1) — a standing platform
    // connection per (kind, device), and the durable record of every import that did something.
    composite(
        interfaceClass = HealthSource::class,
        dataSchema = HealthSource.Schema,
        dtoFactory = ::HealthSourceDto
    )

    trait(
        dataSchema = HealthSource.Schema,
        traitSchema = HealthSourceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            HealthSourceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = HealthImportRun::class,
        dataSchema = HealthImportRun.Schema,
        dtoFactory = ::HealthImportRunDto
    )

    trait(
        dataSchema = HealthImportRun.Schema,
        traitSchema = HealthImportRunDataIdProvider.Schema,
        traitFactory = { context, schema ->
            HealthImportRunDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    operation(RegisterHealthSource.Schema)
    operation(RecordImportRun.Schema)
    operation(SetSourceAutoImport.Schema)
}
