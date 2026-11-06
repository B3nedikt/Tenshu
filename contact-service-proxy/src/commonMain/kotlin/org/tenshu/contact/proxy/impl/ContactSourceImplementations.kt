package org.tenshu.contact.proxy.impl

import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.ImportContact
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.SaveContactSource
import org.tenshu.contact.GetContactSourcePhotos
import org.tenshu.contact.common.ContactPhotoContent
import org.tenshu.contact.common.photoRefsIn
import org.tenshu.contact.common.readContactCard
import org.tenshu.contact.common.readContactPhoto
import org.tenshu.contact.proxy.ContactProxyComponents
import org.tenshu.contact.proxy.contactPhotosPath
import org.tenshu.contact.proxy.contactSourceDataId
import org.tenshu.contact.proxy.storedContentIdOf
import org.tenshu.contact.proxy.writeContactSource
import org.tenshu.contact.proxy.ContactSourceWrite
import org.tenshu.contact.proxy.storedCardOf
import org.tenshu.contact.proxy.storedPersonIdOf
import org.tenshu.contact.proxy.sweepSupersededContactPhotos
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.Data
import org.tenshu.core.TString
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

// Source records (`contacts-app.md` §6; `contact-service-identity-model.md` §4.3/§5).

/**
 * Upsert one source record.
 *
 * ⚠ The payload is stored **verbatim** but for its big photos (`contacts-app.md` §9.10), which
 * [writeContactSource] moves to the content plane. The merge-don't-regenerate rule (Q6) is the
 * caller's (`SaveContactSource`); a regenerated card is well-formed, so there is nothing to check.
 *
 * Async because the photo move ends in a content-plane emit.
 */
internal class SaveContactSourceImplementation(
    private val components: ContactProxyComponents,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val sourceKey: TString = params.read(SaveContactSource.Params.SOURCE_KEY)
        val personId: TString = params.read(SaveContactSource.Params.PERSON_ID)
        val rawVcard: TString = params.read(SaveContactSource.Params.RAW_VCARD)

        writeContactSource(
            executionContext = executionContext,
            components = components,
            sourceKey = sourceKey.get(),
            personId = personId.get(),
            card = rawVcard.get(),
        )
        return true.toTBoolean(executionContext.context) as T
    }
}

/** Import a record as a new contact: a [Person] plus its first [ContactSource] (see `ImportContact`). */
internal class ImportContactImplementation(
    private val components: ContactProxyComponents,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val rawVcard: TString = params.read(ImportContact.Params.RAW_VCARD)
        val fallbackName = (params?.get(ImportContact.Params.FALLBACK_NAME) as? TString)?.get() ?: ""
        val card = rawVcard.get()

        // ⚠ `X-TENSHU-PHOTO-REF` is reserved: a card that arrives with one names photos no blob
        // here holds. Refused BEFORE the person exists, so a refusal leaves nothing behind.
        if (photoRefsIn(card).isNotEmpty()) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "The card carries ${ContactPhotoContent.REF_PROPERTY}, which is reserved.",
                )
            )
        }

        val personId = generateId()
        val displayName = readContactCard(card).formattedName.ifBlank { fallbackName }
        // Decoded before the commit: the transaction does no base64 work under the store's lock.
        val avatarPhoto = readContactPhoto(card, PersonAvatar.MAX_IMAGE_BYTES)
        writeContactSource(
            executionContext = executionContext,
            components = components,
            // Content-independent, so a re-import is a second record (see `ImportContact`).
            sourceKey = "${ContactSource.Origin.VCARD}:${generateId()}",
            personId = personId,
            card = card,
            // ⚠⚠ The person commits WITH its record, in the record's store transaction: saved first,
            // a cancel or shutdown at the source lock's wait — another photo save can hold it across
            // its upload wait — would leave a name-only contact, and the retry would make a second.
            //
            // ⚠⚠ And its photo: saved after the call, the avatar would wait on the inline upload, so a
            // cancel or shutdown there would leave a contact without its picture (and a failed op
            // whose retry duplicates it), and a `DeletePerson` landing during the upload would be
            // followed by an avatar row for a person that no longer exists.
            alsoInCommit = {
                executionContext.saveData(
                    Person.create(
                        context = executionContext.context,
                        personId = personId,
                        displayName = displayName,
                    )
                )
                // Promote the card's embedded PHOTO to an avatar row, if it has one small
                // enough. Read from the card AS IMPORTED — before its big photos moved, so a 100 KiB
                // portrait that left the card is still promoted. Read-only: the card is never
                // rewritten for this, so the photo stays in the source record as well.
                avatarPhoto?.let { photo ->
                    executionContext.saveData(
                        PersonAvatar.create(
                            context = executionContext.context,
                            personId = personId,
                            imageData = photo,
                        )
                    )
                }
            },
        )
        return personId.toTString(executionContext.context) as T
    }
}

/**
 * Resolve [ContactSource.Photos] from the external [FileDatabase] — the contacts twin of
 * `GetWorkoutStreamsImplementation`. `null` when the row moved nothing, or the bytes are not local
 * yet (the on-demand cycle fetches them on a Maybe/stream terminal).
 *
 * ⚠ The receiver arrives as `caller` (an `Entity<ContactSource>` on a projection read, a bare
 * `ContactSource` called directly — `GetPersonAvatar`'s two shapes). Only the entity carries the
 * content id, so a bare composite resolves through the stored row.
 */
internal class GetContactSourcePhotosImplementation(
    private val fileDatabase: FileDatabase,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val receiver = (caller as? Data) ?: params?.get(GetContactSourcePhotos.Params.SOURCE)
        val contentId = storedContentIdOf(receiver) ?: run {
            val sourceKey = when (receiver) {
                is Entity<*> -> runCatching {
                    receiver.getFieldValue<TString>(ContactSource.Field.SOURCE_KEY).get()
                }.getOrNull()
                is ContactSource -> receiver.sourceKey.get()
                else -> null
            } ?: return null as T
            storedContentIdOf(
                executionContext.database.getData(contactSourceDataId(executionContext, sourceKey), asEntity = true)
            )
        } ?: return null as T
        return fileDatabase.readBytes(contactPhotosPath(contentId)) as T
    }
}

/**
 * `SweepContactPhotos`'s proxy side: the superseded-photo sweep, on the daily schedule. Local blobs
 * no stored row names any more are deleted there, never at the save that superseded them.
 */
internal class SweepContactPhotosImplementation(
    private val components: ContactProxyComponents,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        sweepSupersededContactPhotos(executionContext, components)
        return null as T
    }
}

/**
 * Source keys back-linked to [personId] — used by the delete cascade.
 *
 * ⚠ Filters the empty back-link explicitly, for the same reason `DeletePerson` does with
 * keys: `personId == ""` means *not yet linked*, not *belongs to the empty person*.
 */
internal fun sourceKeysOf(executionContext: ExecutionContext, personId: String): List<String> {
    if (personId.isEmpty()) return emptyList()
    return sourceRows(executionContext)
        .filter { it.personId.get() == personId }
        .map { it.sourceKey.get() }
}

/** Every source row, off the maintained relation. */
internal fun sourceRows(executionContext: ExecutionContext): List<ContactSource> {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = TList.ID,
            relationId = ContactService.Relations.SOURCES,
        ),
        isEntity = false,
        elementSchemaId = ContactSource.ID,
    )
    return executionContext.database.getArrayData(ref)
        ?.mapNotNull { data -> data as? ContactSource }
        ?.toList()
        .orEmpty()
}
