package app.tenshu.control.bridge

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

/** `explorer.v1.thumbnail` answers only for files under the Tenshu Drive folder; anything else is refused before any read. */
class ExplorerThumbnailPathTest {

    private val root = Files.createTempDirectory("tenshu-drive")

    @Test
    fun aPathOutsideTheFolderIsNotATenshuFile() {
        val elsewhere = Files.createTempFile("elsewhere", ".jpg")
        assertNull(ExplorerSyncRoot.realmPathOf(elsewhere, root))
    }

    @Test
    fun aPathThatOnlyLooksInsideIsNot() {
        // `..` must not climb out of the folder and back into somewhere else.
        assertNull(ExplorerSyncRoot.realmPathOf(root.resolve("..").resolve("x.jpg"), root))
        assertNull(ExplorerSyncRoot.realmPathOf(root.resolveSibling(root.fileName.toString() + "-other").resolve("x.jpg"), root))
    }

    @Test
    fun theFolderItselfAndFoldersInItHaveNoThumbnail() {
        assertNull(ExplorerSyncRoot.realmPathOf(root, root))
        assertNull(ExplorerSyncRoot.realmPathOf(Files.createDirectory(root.resolve("Photos")), root))
    }

    @Test
    fun anOrdinaryFileInTheFolderIsNotOneOfOurs() {
        // Not a placeholder: no identity to read the realm path from.
        assertNull(ExplorerSyncRoot.realmPathOf(Files.writeString(root.resolve("mine.txt"), "mine"), root))
    }
}
