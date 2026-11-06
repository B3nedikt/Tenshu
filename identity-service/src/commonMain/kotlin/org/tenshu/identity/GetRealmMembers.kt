package org.tenshu.identity

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * The in-realm member roster of shared realm [realmId] — every [Member] row that has
 * synced into the realm (GR-3, `shared-realm-membership-grant.md` §5/§9.2; the Q25
 * roster). Backs Drive's *Manage access* Members section. Dedicated op (the
 * shared-realm read-surface convention, like `getSharedRealmMemberships` /
 * `listSharedFolder`), not the chain DSL: it reads the proxy-derived
 * [IdentityService.Relations.MEMBERS] array and filters by `realmId`.
 */
suspend fun IdentityService.getRealmMembers(realmId: String): TResult<TList<Member>> =
    findOperation<GetRealmMembers>(GetRealmMembers.ID)
        .getRealmMembers(realmId)
        .asTaskInteractor<TList<Member>>()
        .execute()

interface GetRealmMembers : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getRealmMembers(realmId: String): RequestObject

    companion object {
        const val ID = "${IdentityService.ID}/getRealmMembers"

        val Schema = operation(
            id = ID,
            receiver = IdentityService.Schema.toType(),
            params = {
                string(Params.REALM_ID)
            },
            returnType = listType(Member.Schema),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REALM_ID = "$ID/realmId"
    }
}
