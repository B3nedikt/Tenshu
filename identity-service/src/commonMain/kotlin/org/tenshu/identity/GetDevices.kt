package org.tenshu.identity

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Builder for the GetDevices operation.
 */
@Suppress("UNCHECKED_CAST")
fun IdentityService.buildGetDevices(): TList<Device> =
    findOperation<GetDevices>(GetDevices.ID)
        .getDevices() as TList<Device>

/**
 * Extension for triggering the GetDevices operation.
 */
suspend fun IdentityService.getDevices(): TResult<TList<Device>> =
    buildGetDevices().getAll()

/**
 * Operation for retrieving the list of authorized devices.
 */
interface GetDevices : Operation {
    fun getDevices(): RequestObject

    companion object {
        const val ID = "${IdentityService.ID}/getDevices"

        val Schema = operation(
            id = ID,
            receiver = IdentityService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
