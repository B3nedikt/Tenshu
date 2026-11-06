package org.tenshu.contact.proxy.impl

import org.tenshu.contact.proxy.contactSourceDataId
import org.tenshu.contact.proxy.saveContactSourceRow
import org.tenshu.contact.proxy.storedCardOf
import org.tenshu.contact.proxy.storedContentIdOf

import org.tenshu.contact.ContactService
import org.tenshu.contact.ContactSource
import org.tenshu.contact.LinkTenshuIdentity
import org.tenshu.contact.MergePersons
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.TenshuIdentity
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.toTBoolean

// User-assisted merge (`docs/proposals/contacts-app.md` D7).

/**
 * Re-point one key at a different contact.
 *
 * ⚠ A **replace-write**: `TenshuIdentity` rows are rebuilt whole, so every field has to be
 * carried across explicitly. `grantSeed` is *optional*, and the plain `identity.grantSeed`
 * accessor throws on rows written before the field existed. Dropping it here
 * would silently make a re-pointed contact un-grantable.
 */
internal class LinkTenshuIdentityImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val fingerprint: TString = params.read(LinkTenshuIdentity.Params.FINGERPRINT)
        val personId: TString = params.read(LinkTenshuIdentity.Params.PERSON_ID)

        val moved = relinkIdentity(executionContext, fingerprint.get(), personId.get())
        return moved.toTBoolean(executionContext.context) as T
    }
}

/**
 * Fold one contact into another.
 *
 * The order is dependants-then-person, the same reasoning `DeletePerson` uses: a crash
 * partway leaves rows already pointing at the surviving target — untidy but harmless and
 * self-healing on a retry — whereas deleting the source first would strand everything that
 * still referenced it with no handle back.
 */
internal class MergePersonsImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val sourceId: TString = params.read(MergePersons.Params.SOURCE_PERSON_ID)
        val targetId: TString = params.read(MergePersons.Params.TARGET_PERSON_ID)
        val source = sourceId.get()
        val target = targetId.get()

        // ⚠ Guarded rather than tolerated. `""` is the not-yet-linked marker, so a merge
        // involving it would sweep every unlinked row in the realm into a contact; and a
        // self-merge would delete the person it had just moved everything onto.
        if (source.isEmpty() || target.isEmpty() || source == target) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Merge needs two different contacts (source='$source', target='$target').",
                )
            )
        }
        if (readPerson(executionContext, target) == null) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Merge target '$target' does not exist.",
                )
            )
        }

        // Keys: each keeps its own verification, because verification lives on the key.
        identityFingerprintsOf(executionContext, source).forEach { fingerprint ->
            relinkIdentity(executionContext, fingerprint, target)
        }

        // Source records: moved, never merged into one. A person legitimately has several,
        // and the detail screen already renders their union — combining the payloads
        // here would be exactly the regenerate-don't-merge mistake Q6 forbids.
        sourceKeysOf(executionContext, source).forEach { sourceKey ->
            relinkSource(executionContext, sourceKey, target)
        }

        // Photo: the target's wins when it has one. Only a contact with no picture inherits.
        if (avatarOf(executionContext, target) == null) {
            avatarOf(executionContext, source)?.let { bytes ->
                executionContext.saveData(
                    PersonAvatar.create(
                        context = executionContext.context,
                        personId = target,
                        imageData = bytes,
                    )
                )
            }
        }
        deleteAvatarOf(executionContext, source)

        // Everything the source owned now points at the target, so this deletes a bare row.
        executionContext.deleteData(
            DataId.StringDataId.create(
                context = executionContext.context,
                type = Person.ID,
                keyValue = source,
            )
        )
        return true.toTBoolean(executionContext.context) as T
    }
}

// ── shared helpers ───────────────────────────────────────────────────────────

/** `true` when a row existed and was moved. */
private fun relinkIdentity(
    executionContext: ExecutionContext,
    fingerprint: String,
    personId: String,
): Boolean {
    if (fingerprint.isEmpty()) return false
    val identity = executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = TenshuIdentity.ID,
            keyValue = fingerprint,
        )
    ) as? TenshuIdentity ?: return false

    executionContext.saveData(
        TenshuIdentity.create(
            context = executionContext.context,
            fingerprint = identity.fingerprint.get(),
            publicKey = identity.publicKey.get(),
            personId = personId,
            // ⚠ Untouched, all four. The self-asserted name stays a claim by whoever holds
            // the key; the verification, its timestamp and how the key was acquired are
            // facts about the KEY, and re-pointing it at another contact changes none of
            // them. Re-stamping any of these would fabricate history.
            selfAssertedName = identity.selfAssertedName.get(),
            verification = identity.verification.get(),
            verifiedAt = identity.verifiedAt.get(),
            source = identity.source.get(),
            grantSeed = identity
                .getOptionalField<TByteArray>(TenshuIdentity.Field.GRANT_SEED)
                ?.get()
                ?: ByteArray(0),
        )
    )
    return true
}

private fun relinkSource(
    executionContext: ExecutionContext,
    sourceKey: String,
    personId: String,
) {
    if (sourceKey.isEmpty()) return
    // ⚠ The read and the re-save in one store transaction: a photo relocation or
    // an edit landing between them would otherwise be overwritten with the card read here.
    executionContext.database.transaction {
        val stored = executionContext.database.getData(
            contactSourceDataId(executionContext, sourceKey),
            asEntity = true,
        )
        val card = storedCardOf(stored) ?: return@transaction

        saveContactSourceRow(
            executionContext = executionContext,
            sourceKey = sourceKey,
            personId = personId,
            // ⚠ Carried verbatim. This is the one field the whole source-record design exists to keep
            // byte-exact; a merge has no business reading, let alone rewriting, it.
            card = card,
            // ⚠⚠ And its content id (`contacts-app.md` §9.10), stated explicitly: a save that does not state it
            // leaves the edge to whatever the store or the block builder makes of a missing slot.
            contentId = storedContentIdOf(stored),
        )
    }
}

private fun readPerson(executionContext: ExecutionContext, personId: String): Person? {
    if (personId.isEmpty()) return null
    return executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = Person.ID,
            keyValue = personId,
        )
    ) as? Person
}

/** Fingerprints back-linked to [personId]. Empty back-links are excluded (the Q7a rule). */
private fun identityFingerprintsOf(
    executionContext: ExecutionContext,
    personId: String,
): List<String> {
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
