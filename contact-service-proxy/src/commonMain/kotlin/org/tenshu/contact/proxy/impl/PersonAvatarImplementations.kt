package org.tenshu.contact.proxy.impl

import org.tenshu.contact.ClearPersonAvatar
import org.tenshu.contact.ContactService
import org.tenshu.contact.GetPersonAvatar
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.SetPersonAvatar
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.contact.personId
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray

// Contact photos (`contacts-app.md` D1 + Q4).

/** Set or replace a contact's photo — an upsert keyed on `personId`, size-capped (see `SetPersonAvatar`). */
internal class SetPersonAvatarImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val personId: TString = params.read(SetPersonAvatar.Params.PERSON_ID)
        val imageData: TByteArray = params.read(SetPersonAvatar.Params.IMAGE_DATA)

        val bytes = imageData.get()
        if (bytes.size > PersonAvatar.MAX_IMAGE_BYTES) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Contact photo is ${bytes.size} bytes; the limit is " +
                        "${PersonAvatar.MAX_IMAGE_BYTES}.",
                )
            )
        }

        executionContext.saveData(
            PersonAvatar.create(
                context = executionContext.context,
                personId = personId.get(),
                imageData = bytes,
            )
        )
        return true.toTBoolean(executionContext.context) as T
    }
}

/** Drop a contact's photo row. Their source records — and any `PHOTO` in them — are untouched. */
internal class ClearPersonAvatarImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val personId: TString = params.read(ClearPersonAvatar.Params.PERSON_ID)
        deleteAvatarOf(executionContext, personId.get())
        return true.toTBoolean(executionContext.context) as T
    }
}

/** Resolve [Person.Avatar] — the receiver person's photo bytes, ⚠ empty (never `null`) when they have none. */
internal class GetPersonAvatarImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        // ⚠ The receiver arrives as `caller`, not in `params` — and in two shapes: an
        // `Entity<Person>` on a projection read, a bare `Person` composite when the op is
        // called directly. `GetPreview` reads its file the same way, for the same reason.
        // Reading the declared PERSON param instead yields null and the property resolves to
        // nothing.
        val personId = when (val person = caller) {
            is Entity<*> ->
                @Suppress("UNCHECKED_CAST") (person as Entity<out Person>).personId.get()
            is Person -> person.personId.get()
            else -> ""
        }
        val bytes = avatarOf(executionContext, personId) ?: ByteArray(0)
        return bytes.toTByteArray(executionContext.context) as T
    }
}

/** This person's stored image bytes, or `null` when there is no row. */
internal fun avatarOf(executionContext: ExecutionContext, personId: String): ByteArray? {
    if (personId.isEmpty()) return null
    return avatarRows(executionContext)
        .firstOrNull { it.personId.get() == personId }
        ?.imageData
        ?.get()
}

/**
 * Delete this person's photo row if there is one — the delete cascade's avatar half (Q7a).
 */
internal fun deleteAvatarOf(executionContext: ExecutionContext, personId: String) {
    if (personId.isEmpty()) return
    // Checked first because the row is keyed by personId and most contacts have no photo —
    // deleting a DataId that was never written is not something to ask the store to do.
    if (avatarOf(executionContext, personId) == null) return
    executionContext.deleteData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = PersonAvatar.ID,
            keyValue = personId,
        )
    )
}

private fun avatarRows(executionContext: ExecutionContext): List<PersonAvatar> {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = ContactService.Relations.AVATARS,
        ),
        isEntity = false,
        elementSchemaId = PersonAvatar.ID,
    )
    return executionContext.database.getArrayData(ref)
        ?.mapNotNull { data -> data as? PersonAvatar }
        ?.toList()
        .orEmpty()
}
