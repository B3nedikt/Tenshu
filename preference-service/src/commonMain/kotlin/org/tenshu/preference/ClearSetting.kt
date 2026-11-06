package org.tenshu.preference

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Removes the setting row matching `(preferenceId, deviceId?)`. `null`
 * [deviceId] clears the [RealmSetting] row; non-null clears the matching
 * [DeviceSetting] row. Used to implement `clearOverride` (per-device
 * override removal) and explicit "fall back to default" gestures. No-op if
 * the row does not exist.
 */
suspend fun PreferenceService.clearSetting(
    preferenceId: String,
    deviceId: String? = null,
): TResult<Data?> =
    findOperation<ClearSetting>(ClearSetting.ID)
        .clearSetting(preferenceId, deviceId)
        .asTaskInteractor<Data?>()
        .execute()

interface ClearSetting : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun clearSetting(
        preferenceId: String,
        deviceId: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/preference/service/clearSetting"

        val Schema = operation(
            id = ID,
            receiver = PreferenceService.Schema.toType(),
            params = {
                string(id = Params.PREFERENCE_ID)
                string(id = Params.DEVICE_ID, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val PREFERENCE_ID = "$ID/preferenceId"
        const val DEVICE_ID = "$ID/deviceId"
    }
}
