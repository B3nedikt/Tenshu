package org.tenshu.contact.proxy

import org.tenshu.contact.ContactService
import org.tenshu.contact.Person
import org.tenshu.contact.PersonAvatar
import org.tenshu.contact.TenshuIdentity
import org.tenshu.contact.test.ContactTestHarness
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity

/**
 * Test-side readers over the maintained relation arrays — the proxy-local
 * equivalent of what the chain DSL surfaces to clients.
 */
internal class TenshuIdentityLocalView(private val harness: ContactTestHarness) {

    data class Row(
        val fingerprint: String,
        val personId: String,
        val selfAssertedName: String,
        val verification: String,
        val source: String,
    )

    fun byFingerprint(fingerprint: String): Row? =
        readRelation(harness, ContactService.Relations.TENSHU_IDENTITIES, TenshuIdentity.ID)
            .mapNotNull { data ->
                Row(
                    fingerprint = data.stringField(TenshuIdentity.Field.FINGERPRINT) ?: return@mapNotNull null,
                    personId = data.stringField(TenshuIdentity.Field.PERSON_ID) ?: "",
                    selfAssertedName = data.stringField(TenshuIdentity.Field.SELF_ASSERTED_NAME) ?: "",
                    verification = data.stringField(TenshuIdentity.Field.VERIFICATION) ?: "",
                    source = data.stringField(TenshuIdentity.Field.SOURCE) ?: "",
                )
            }
            .firstOrNull { it.fingerprint == fingerprint }
}

internal class PersonLocalView(private val harness: ContactTestHarness) {

    fun displayNameOf(personId: String): String? =
        readRelation(harness, ContactService.Relations.PERSONS, Person.ID)
            .firstOrNull { it.stringField(Person.Field.PERSON_ID) == personId }
            ?.stringField(Person.Field.DISPLAY_NAME)

    fun count(): Int =
        readRelation(harness, ContactService.Relations.PERSONS, Person.ID).count()
}

/** The avatar rows, as `(personId, bytes)`. */
internal class PersonAvatarLocalView(private val harness: ContactTestHarness) {

    fun rows(): List<Pair<String, ByteArray>> =
        readRelation(harness, ContactService.Relations.AVATARS, PersonAvatar.ID)
            .mapNotNull { data ->
                val personId = data.stringField(PersonAvatar.Field.PERSON_ID) ?: return@mapNotNull null
                personId to (data.byteArrayField(PersonAvatar.Field.IMAGE_DATA) ?: ByteArray(0))
            }

    fun bytesOf(personId: String): ByteArray? = rows().firstOrNull { it.first == personId }?.second
}

private fun readRelation(
    harness: ContactTestHarness,
    relationId: String,
    schemaId: String,
): List<Data> {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = harness.proxy.tContext,
            type = TList.ID,
            relationId = relationId,
        ),
        isEntity = false,
        elementSchemaId = schemaId,
    )
    return harness.proxy.executionContext.database.getArrayData(ref)?.toList() ?: emptyList()
}

private fun Data.byteArrayField(fieldId: String): ByteArray? = when (this) {
    is Entity<*> -> runCatching { getFieldValue<TByteArray>(fieldId).get() }.getOrNull()
    is Composite -> runCatching { getField<TByteArray>(fieldId).get() }.getOrNull()
    else -> null
}

private fun Data.stringField(fieldId: String): String? = when (this) {
    is Entity<*> -> runCatching { getFieldValue<TString>(fieldId).get() }.getOrNull()
    is Composite -> runCatching { getField<TString>(fieldId).get() }.getOrNull()
    else -> null
}
