package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.core.TContext
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.RecordImportedHealth
import org.tenshu.core.health.SleepSession
import org.tenshu.core.health.Workout
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordImportedHealthRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordImportedHealth {

    override fun recordImportedHealth(
        workouts: List<Workout>,
        dailyActivities: List<DailyActivity>,
        dailyNutritions: List<DailyNutrition>,
        menstruationDays: List<MenstruationDay>,
        sleepSessions: List<SleepSession>,
        healthSamples: List<HealthSample>,
        medicalRecords: List<MedicalRecord>,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordImportedHealth.Params.WORKOUTS to TListDto(workouts),
                    RecordImportedHealth.Params.DAILY_ACTIVITIES to TListDto(dailyActivities),
                    RecordImportedHealth.Params.DAILY_NUTRITIONS to TListDto(dailyNutritions),
                    RecordImportedHealth.Params.MENSTRUATION_DAYS to TListDto(menstruationDays),
                    RecordImportedHealth.Params.SLEEP_SESSIONS to TListDto(sleepSessions),
                    RecordImportedHealth.Params.HEALTH_SAMPLES to TListDto(healthSamples),
                    RecordImportedHealth.Params.MEDICAL_RECORDS to TListDto(medicalRecords),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
