package org.tenshu.core.entity

import org.tenshu.core.collection.list.TList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [getDataIdHash] to four values read off a real store, so the derivation is a fact the
 * suite states rather than a paragraph — and so the two out-of-process copies of it
 * (`scripts/store-relation-audit.py --self-test`, `scripts/DataIdHashSearch.java`) cannot drift
 * from the platform without one side going red.
 *
 * The vectors are the desktop Control store's on 2026-09-13
 * (`docs/proposals/drive-folder-files-dangling-member.md`): a folder row, its `Folder.FILES` list
 * row, and the list's two dangling members — each *named* by enumerating WhatsApp's media
 * naming pattern against its hash. ⚠ Type ids are spelled out rather than imported:
 * `:core` cannot see `File.ID` / `Folder.Fields.FILES`, and the point of a vector is that it is
 * the bytes, not a symbol that might be renamed alongside the derivation.
 *
 * ⚠ These move only with EI-1b's successor — a change here is a change to every stored row's
 * primary key, which owes a `TENSHU_STORAGE_FORMAT_VERSION` bump and a wipe (`DataId.kt`).
 */
class DataIdHashVectorsTest {

    private val folder = "/Backup/OnePlus CPH2581/WhatsApp Images"

    private fun fileId(path: String): DataId.StringDataId =
        DataId.StringDataId.create(type = "tenshu/file", keyValue = path)

    @Test
    fun aFolderRowIsKeyedUnderTheFileTypeByItsPath() {
        // Folders key under `tenshu/file`: `Folder`'s parent schema is `File`, so it inherits
        // `FileDataIdProvider`. Hashing a folder path under `tenshu/file/folder` finds nothing.
        assertEquals(6939062656711727838L, fileId(folder).getDataIdHash())
    }

    @Test
    fun aFolderFilesListRowIsTheEntityFieldIdOverTheFolderRow() {
        val listId = DataId.EntityFieldDataId.create(
            type = TList.ID,
            parentEntity = fileId(folder),
            fieldRef = "tenshu/file/folder/files",
        )
        assertEquals(-4299254704025414121L, listId.getDataIdHash())
    }

    @Test
    fun theDanglingMembersWereTheseTwoFiles() {
        // Slots 708 and 710 of the 830-member list; each named by a single match in ~5.9e9
        // WhatsApp-patterned candidates (`scripts/DataIdHashSearch.java`).
        assertEquals(2329932176197719662L, fileId("$folder/IMG-20240410-WA0004.jpg").getDataIdHash())
        assertEquals(-5762757953325457904L, fileId("$folder/IMG-20240830-WA0004.jpg").getDataIdHash())
    }
}
