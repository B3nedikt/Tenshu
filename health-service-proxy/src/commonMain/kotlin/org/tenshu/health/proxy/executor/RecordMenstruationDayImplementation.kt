package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.RecordMenstruationDay

internal class RecordMenstruationDayImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val menstruationDay: MenstruationDay = params.read(RecordMenstruationDay.Params.MENSTRUATION_DAY)

        // saveData (not Database.saveData): fires the SyncObserver + the relation maintainer.
        executionContext.saveData(menstruationDay)

        return null as T
    }
}
