package org.tenshu.contact

import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Write a contact's source record — an import, a hand-typed card, or an edit of either.
 *
 * ⚠⚠ **[rawVcard] must be the result of merging into the previous payload, never a
 * regeneration of it** (`contacts-app.md` Q6). This op stores exactly what it is given, so an editor that
 * rebuilds the record from its own fields will destroy every property it does not model, and
 * the service cannot tell the difference. `mergeIntoVCard` in `:contact-service-common` is
 * the function that gets this right; `newVCard` is its counterpart for a record with no
 * history.
 *
 * Upserts on [sourceKey]. [personId] links the record to its aggregate; leave it as it was on
 * an edit.
 */
suspend fun ContactService.saveContactSource(
    sourceKey: String,
    personId: String,
    rawVcard: String,
): TResult<TBoolean> =
    findOperation<SaveContactSource>(SaveContactSource.ID)
        .saveContactSource(sourceKey, personId, rawVcard)
        .asTaskInteractor<TBoolean>()
        .execute()

interface SaveContactSource : Operation {

    override val schema get() = Schema

    fun saveContactSource(sourceKey: String, personId: String, rawVcard: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/saveContactSource"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.SOURCE_KEY)
                string(Params.PERSON_ID)
                string(Params.RAW_VCARD)
            },
            returnType = TBoolean.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val SOURCE_KEY = "$ID/sourceKey"
        const val PERSON_ID = "$ID/personId"
        const val RAW_VCARD = "$ID/rawVcard"
    }
}

/**
 * Import a vCard as a **new** contact: creates the [Person] (named from the record's `FN`,
 * or [fallbackName] when it has none) and its first [ContactSource] in one step. The name is a
 * *suggestion* the user owns from then on; an import is not a stranger's claim, so nothing is
 * quarantined. A `PHOTO` small enough for a [PersonAvatar] also becomes the contact's photo, and
 * stays in the record as well. The person, the record and the avatar are saved together or not
 * at all.
 *
 * Every import is a new record: re-importing the same file makes a second contact rather than
 * overwriting one the user may have edited — de-duplicating is merge (`contacts-app.md` D7),
 * user-assisted by design. A card carrying the reserved `X-TENSHU-PHOTO-REF` property is refused.
 *
 * Returns the new `personId`. Splitting a multi-card `.vcf` is the caller's job — one
 * `ContactSource` is one record.
 */
suspend fun ContactService.importContact(
    rawVcard: String,
    fallbackName: String = "",
): TResult<TString> =
    findOperation<ImportContact>(ImportContact.ID)
        .importContact(rawVcard, fallbackName)
        .asTaskInteractor<TString>()
        .execute()

interface ImportContact : Operation {

    override val schema get() = Schema

    fun importContact(rawVcard: String, fallbackName: String): RequestObject

    companion object {
        const val ID = "${ContactService.ID}/importContact"

        val Schema = operation(
            id = ID,
            receiver = ContactService.Schema.toType(),
            params = {
                string(Params.RAW_VCARD)
                string(Params.FALLBACK_NAME)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val RAW_VCARD = "$ID/rawVcard"
        const val FALLBACK_NAME = "$ID/fallbackName"
    }
}
