package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Lists the region bundles currently present in the proxy's canonical store (D5) — the picker's
 * install-state source. Reads proxy-local state only (no network); returns a list of
 * [InstalledRegion]. Every consuming app sees the same install state because the store lives
 * proxy-side.
 */
suspend fun MapRegionService.installed(): TResult<TList<InstalledRegion>> =
    findOperation<Installed>(Installed.ID)
        .installed()
        .asTaskInteractor<TList<InstalledRegion>>()
        .execute()

interface Installed : Operation {

    override val schema: OperationSchema get() = Schema

    fun installed(): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/installed"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {},
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
