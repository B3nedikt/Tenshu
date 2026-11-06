package org.tenshu.core.file

import org.tenshu.core.Operation
import org.tenshu.core.TConstant
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.TerminalOperation

/**
 * Which [FileCategory] a file belongs to, derived from its name
 * (`photos-app.md` PH-1) — the resolver behind the [File.Category] dynamic property.
 *
 * **Why an operation at all.** [File.Name] shows that a derived property can be a pure chain
 * template when the derivation is string manipulation the DSL can express. This one cannot be:
 * mapping an extension to a category is a `when` over a table
 * ([fileCategoryForName]), and the chain DSL has no branch. `File.Preview` is the precedent for
 * the shape — a dynamic property whose template calls an operation, so the arbitrary part runs
 * as ordinary Kotlin proxy-side.
 *
 * ⚠ **Pure and synchronous, unlike `GetPreview`.** It reads no bytes and touches no database: the
 * answer is in the path the caller already handed it. That is what lets [File.Category] stay
 * non-deferred and therefore usable *inside a predicate*
 * (`filter<File> { category equalTo FileCategory.Image }`), which is the whole point — a media
 * grid that filters client-side has already shipped every document in the realm across IPC.
 *
 * ⚠ A file whose name carries no recognised extension is [FileCategory.Other], never null: absent
 * is a category, and a caller filtering for images must not have to distinguish "not an image"
 * from "could not tell".
 *
 * ⚠⚠ **Typed `TConstant`, not `FileCategory`, and that is load-bearing.** What a chain hands back
 * at *build* time is a recording placeholder, and `FileCategory` is a **sealed** interface — a
 * placeholder implementing it would be a new subtype, which breaks every exhaustive `when` over
 * the category in the repo (there are three, none with an `else`). The resolved value on the far
 * side is a real `FileCategory`, and [File.Category]'s decoder is what re-types it, so no
 * *consumer* sees `TConstant`.
 */
fun File.categoryOf(): TConstant =
    findOperation<FileCategoryOf>(FileCategoryOf.ID).categoryOf(this)

interface FileCategoryOf : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun categoryOf(file: File): TConstant

    companion object {
        const val ID = "tenshu/file/categoryOf"

        val Schema = operation(
            id = ID,
            receiver = File.Schema.toType(),
            params = {
                composite(id = Params.FILE, type = File.Schema)
            },
            traits = listOf(TerminalOperation.Schema),
        )
    }

    object Params {
        const val FILE = "$ID/file"
    }
}
