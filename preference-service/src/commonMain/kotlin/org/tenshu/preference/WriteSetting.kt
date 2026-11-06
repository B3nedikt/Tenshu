package org.tenshu.preference

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Writes a single setting row. [deviceId] discriminates the storage shape:
 * `null` writes a [RealmSetting]; non-null writes a [DeviceSetting]. The
 * proxy-side implementation chooses the concrete composite, builds it via
 * the relevant `Companion.create<V>(...)`, and saves through
 * `ExecutionContext.saveData` — `SyncObserver` emits the outbound block
 * and the relation maintainer adds the entity to the right service relation.
 */
suspend fun PreferenceService.writeSetting(
    preferenceId: String,
    value: Data,
    deviceId: String? = null,
): TResult<Data?> =
    findOperation<WriteSetting>(WriteSetting.ID)
        .writeSetting(preferenceId, value, deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface WriteSetting : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun writeSetting(
        preferenceId: String,
        value: Data,
        deviceId: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/preference/service/writeSetting"

        val Schema = operation(
            id = ID,
            receiver = PreferenceService.Schema.toType(),
            params = {
                string(id = Params.PREFERENCE_ID)
                composite(id = Params.VALUE)
                string(id = Params.DEVICE_ID, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val PREFERENCE_ID = "$ID/preferenceId"
        const val VALUE = "$ID/value"
        const val DEVICE_ID = "$ID/deviceId"
    }
}
