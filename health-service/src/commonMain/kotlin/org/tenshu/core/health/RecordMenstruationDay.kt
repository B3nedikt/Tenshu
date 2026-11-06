package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun HealthService.recordMenstruationDay(menstruationDay: MenstruationDay): TResult<Data?> =
    findOperation<RecordMenstruationDay>(RecordMenstruationDay.ID)
        .recordMenstruationDay(menstruationDay)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordMenstruationDay : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordMenstruationDay(menstruationDay: MenstruationDay): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordMenstruationDay"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.MENSTRUATION_DAY, type = MenstruationDay.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val MENSTRUATION_DAY = "$ID/menstruationDay"
    }
}
