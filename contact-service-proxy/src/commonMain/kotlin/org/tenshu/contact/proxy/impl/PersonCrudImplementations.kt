package org.tenshu.contact.proxy.impl

import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.CreatePerson
import org.tenshu.contact.DeletePerson
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.RenamePerson
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.UnlinkTenshuIdentity
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.util.generateId
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/*
 * The contact-CRUD implementations (`docs/proposals/contacts-app.md` §6).
 *
 * All four are **local writes only** — no relay, no crypto, no network. The rows are
 * personal-realm `Syncable(Eager)`, so the ordinary sync machinery carries them to the
 * user's other devices with nothing service-specific to do here.
 */

/** D5: a contact with a petname and no key. */
internal class CreatePersonImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val displayName: TString = params.read(CreatePerson.Params.DISPLAY_NAME)
        val personId = generateId()
        executionContext.saveData(
            Person.create(
                context = executionContext.context,
                personId = personId,
                // No display copy is invented here (ADR 0016): an empty name stays empty and
                // the UI substitutes its own localised "unnamed".
                displayName = displayName.get(),
            )
        )
        return personId.toTString(executionContext.context) as T
    }
}

/**
 * ⚠⚠ A **replace-write** (see `RenamePerson`): [Person] is re-created whole, so a field added to
 * `Person` without being carried across here is silently discarded on the next rename.
 */
internal class RenamePersonImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val personId: TString = params.read(RenamePerson.Params.PERSON_ID)
        val displayName: TString = params.read(RenamePerson.Params.DISPLAY_NAME)

        val dataId = personDataId(executionContext, personId.get())
        executionContext.database.getData(dataId) as? Person
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Unknown contact '${personId.get()}'.",
                )
            )

        executionContext.saveData(
            Person.create(
                context = executionContext.context,
                personId = personId.get(),
                displayName = displayName.get(),
            )
        )
        return true.toTBoolean(executionContext.context) as T
    }
}

/**
 * Deletes the aggregate `DeletePerson` names. ⚠ Anything added to the aggregate has to be added
 * here too; the tests assert the sweep by reading the relations back rather than by counting calls.
 */
internal class DeletePersonImplementation(
    private val components: ContactProxyComponents,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val personId: TString = params.read(DeletePerson.Params.PERSON_ID)

        // ⚠⚠ Every row deleted here is tombstoned (`contacts-app.md` §9.10).
        // `SyncObserver.onDataDeleted` is a deliberate no-op — the tombstone is the operation's duty
        // (`DeleteWorkout`'s note) — so without one a deleted contact stays on every other device.
        // With photos on the content plane it matters twice: the source row's tombstone is what
        // retires its content edge on the relay, so without it a deleted contact's photos are
        // never reclaimed.
        val tombstones = components.tombstoneEmitterHolder?.emitter
        fun delete(dataId: DataId, type: String) {
            executionContext.deleteData(dataId)
            tombstones?.emit(targetEntityId = dataId, targetEntityType = type)
        }

        // Dependants first: a crash between the two leaves orphans the next delete cannot
        // find (the Person is the only handle back to them), whereas dependants-then-person
        // leaves at worst a bare contact the user can delete again.
        identitiesOf(executionContext, personId.get()).forEach { fingerprint ->
            delete(identityDataId(executionContext, fingerprint), TenshuIdentity.ID)
        }
        sourceKeysOf(executionContext, personId.get()).forEach { sourceKey ->
            // The relocated photos are not deleted here: the recorder notes the
            // id as the row goes, and the sweep deletes the blob once no row names it — a peer's
            // write that wins over this delete may still name it.
            delete(sourceDataId(executionContext, sourceKey), ContactSource.ID)
        }
        if (avatarOf(executionContext, personId.get()) != null) {
            deleteAvatarOf(executionContext, personId.get())
            tombstones?.emit(
                targetEntityId = DataId.StringDataId.create(
                    context = executionContext.context,
                    type = PersonAvatar.ID,
                    keyValue = personId.get(),
                ),
                targetEntityType = PersonAvatar.ID,
            )
        }
        delete(personDataId(executionContext, personId.get()), Person.ID)
        return true.toTBoolean(executionContext.context) as T
    }
}

/** Drops one key. The [Person] is untouched — a contact with no keys is still a contact. */
internal class UnlinkTenshuIdentityImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val fingerprint: TString = params.read(UnlinkTenshuIdentity.Params.FINGERPRINT)
        executionContext.deleteData(identityDataId(executionContext, fingerprint.get()))
        return true.toTBoolean(executionContext.context) as T
    }
}

// ── shared helpers ───────────────────────────────────────────────────────────

private fun personDataId(executionContext: ExecutionContext, personId: String): DataId =
    DataId.StringDataId.create(
        context = executionContext.context,
        type = Person.ID,
        keyValue = personId,
    )

private fun identityDataId(executionContext: ExecutionContext, fingerprint: String): DataId =
    DataId.StringDataId.create(
        context = executionContext.context,
        type = TenshuIdentity.ID,
        keyValue = fingerprint,
    )

private fun sourceDataId(executionContext: ExecutionContext, sourceKey: String): DataId =
    DataId.StringDataId.create(
        context = executionContext.context,
        type = ContactSource.ID,
        keyValue = sourceKey,
    )

/**
 * Fingerprints of every key back-linked to [personId], read off the maintained relation.
 *
 * ⚠ Filters out the empty back-link explicitly. `personId == ""` marks a *not-yet-linked*
 * candidate, so a `deletePerson("")` — which a caller should never make, but which a bug
 * upstream could — must not sweep every unlinked key in the realm.
 */
private fun identitiesOf(executionContext: ExecutionContext, personId: String): List<String> {
    if (personId.isEmpty()) return emptyList()
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = ContactService.Relations.TENSHU_IDENTITIES,
        ),
        isEntity = false,
        elementSchemaId = TenshuIdentity.ID,
    )
    return executionContext.database.getArrayData(ref)
        ?.mapNotNull { data -> data as? TenshuIdentity }
        ?.filter { it.personId.get() == personId }
        ?.map { it.fingerprint.get() }
        ?.toList()
        .orEmpty()
}
