package org.tenshu.core.file

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.count
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.dynamicProperty
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.list
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

interface Folder : File {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/file/folder"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = File.Schema,
            traits = listOf(Syncable(SyncStrategy.Eager, ConflictStrategy.Merge)),
            fields = {
                list(id = Fields.FILES, of = File.Schema)
            }
        )

        fun create(
            context: TContext,
            path: String,
            files: List<File>
        ): Folder = context.create(
            type = ID,
            fields = listOf(path, files)
        )
    }

    object Fields {
        const val FILES = "tenshu/file/folder/files"
    }
}

private val folderFileCount: Property.Dynamic<TInteger> = dynamicProperty<Folder, TInteger>(
    id = "${Folder.ID}/fileCount",
    receiver = Folder.Schema,
) { _ ->
    files.count()
}

/**
 * Number of direct children, resolved proxy-side from the folder's files
 * array **without materialising the children** (`count()` reads the array's
 * member-hash list). This is what listing/details surfaces must use for the
 * "N files" counter — projecting `Folder.Fields.FILES` instead materialises
 * and ships every child entity just to take `.size` (for a 2 000-file folder
 * that was a full-array deserialisation per row plus the whole child list
 * over IPC).
 */
val Folder.Companion.FileCount: Property.Dynamic<TInteger> get() = folderFileCount

val <T : File> Entity<T>.files: TList<File>
    get() {
        val value = getFieldValue<Data>(Folder.Fields.FILES)
        if (value is TList<*>) {
            @Suppress("UNCHECKED_CAST")
            return value as TList<File>
        }
        error("Accessing .files on a non-materialized entity. Use executionContext.resolveList() instead.")
    }

val Folder.files: TList<File>
    get() {
        val value = getField<Data>(Folder.Fields.FILES)
        if (value is TList<*>) {
            @Suppress("UNCHECKED_CAST")
            return value as TList<File>
        }
        error("Accessing .files on a non-materialized snapshot. Use executionContext.resolveList() instead.")
    }
