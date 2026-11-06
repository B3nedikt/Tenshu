package org.tenshu.inference

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Record that these photographs show a person called [name] (IN-6 F3b).
 *
 * ⚠⚠⚠ **This is the operation that turns IN-6 from device-local into realm data**, and the only
 * one that does. Everything else in this feature — templates, vectors, groups — stays on the device
 * that derived it (IN-D5). What crosses the sync boundary is exactly this: a string the user typed,
 * attached to a file path, as an ordinary [MediaLabel]. `docs/sync/threat-model.md` A40 is the
 * entry for it, and it lands with this op rather than after it (House Rule 2).
 *
 * ⚠⚠ **It takes the photographs, never a group id.** A `GroupedFace.groupId` is rebuilt per call
 * and an index pass can renumber it, so a name applied to an id resolved *later* could land on a
 * different person entirely — silently, and in synced data. The user named the photographs they
 * were looking at, so those are what travels. It is also what IN-D5 means literally: *"the synced
 * artifact is a plain label on a photo — this photo shows Anna"*.
 *
 * ⚠⚠ **Additive, and deliberately so.** Two people in one frame is the ordinary case, so naming a
 * second person on a photograph that already names a first must not disturb it. Re-applying the
 * same name folds rather than duplicating, because `MediaLabel.labelKey` is
 * `"$file/$capability/$label"`. ⚠ **Renaming is therefore NOT this op** — writing a corrected
 * spelling adds a row and leaves the old one, which is why a person-wide rename is its own slice
 * and not a flag here.
 *
 * ⚠ Rows are stamped [ModelProvenance.User], which is what keeps a re-index from ever treating a
 * typed name as a stale model opinion.
 *
 * ⚠⚠ **Returns how many photographs it actually named**, because one call can tear: the rows
 * are saved one at a time, so a store failure part way through leaves the earlier ones durable and
 * SYNCED. Without a count the caller has to read that as total failure and tell the user nothing
 * saved — sending them to retype a name that is partly applied, which is the one thing worse than
 * an error.
 *
 * @param filePaths the photographs being named; an empty list is a caller mistake and writes
 *   nothing rather than failing
 * @param name what the user typed. Blank writes nothing — clearing a name is not spelled this way
 * @return the number of photographs named; `0` when nothing was written
 */
suspend fun InferenceService.nameFaceGroup(
    filePaths: List<String>,
    name: String,
): TResult<TInteger> =
    findOperation<NameFaceGroup>(NameFaceGroup.ID)
        .nameFaceGroup(filePaths, name)
        .asTaskInteractor<TInteger>()
        .execute()

interface NameFaceGroup : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun nameFaceGroup(filePaths: List<String>, name: String): RequestObject

    companion object {
        const val ID = "tenshu/inference/nameFaceGroup"

        val Schema = operation(
            id = ID,
            receiver = InferenceService.Schema.toType(),
            params = {
                list(id = Params.FILE_PATHS, of = TString.Schema)
                string(id = Params.NAME)
            },
            returnType = TInteger.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val FILE_PATHS = "$ID/filePaths"
        const val NAME = "$ID/name"
    }
}
