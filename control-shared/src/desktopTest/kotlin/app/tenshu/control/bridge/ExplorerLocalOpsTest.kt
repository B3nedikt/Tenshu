package app.tenshu.control.bridge

import app.tenshu.control.bridge.ExplorerLocalOps.Op
import app.tenshu.control.bridge.ExplorerLocalOps.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorerLocalOpsTest {

    private val now = 1_000_000L
    private val settled = now - 10_000

    private fun row(path: String, version: String = "v1", folder: Boolean = false) =
        ExplorerMirror.Remote(path, isFolder = folder, size = 10, modifiedAtMillis = 1, contentVersion = if (folder) "" else version)

    private fun placeholder(relative: String, folder: Boolean = false) =
        ExplorerMirror.Local(relative, isFolder = folder, isPlaceholder = true, size = 10, modifiedAtMillis = 1, identityPath = "/$relative")

    private fun ordinary(relative: String) =
        ExplorerMirror.Local(relative, isFolder = false, isPlaceholder = false, size = 12, modifiedAtMillis = 2)

    private fun delete(relative: String, identity: String = "/$relative", at: Long = settled, folder: Boolean = false, version: String = "v1") =
        Op(relative, null, identity, if (folder) "" else version, folder, at)

    private fun rename(from: String, to: String, identity: String = "/$from", at: Long = settled, folder: Boolean = false) =
        Op(from, to, identity, if (folder) "" else "v1", folder, at)

    @Test
    fun aSettledDeleteGoesToTheTrash() {
        val plan = ExplorerLocalOps.plan(listOf(delete("a.txt")), listOf(row("/a.txt")), emptyList(), now)
        val step = plan.steps.single() as Step.Trash
        assertEquals("/a.txt", step.realmPath)
    }

    @Test
    fun twoNoticesOfOneDelete_trashItOnce() {
        // Measured: the Recycle Bin sends two notices for one file.
        val ops = listOf(rename("a.txt", "ignored", at = settled).copy(target = null), delete("a.txt", at = settled + 5))
        val plan = ExplorerLocalOps.plan(ops, listOf(row("/a.txt")), emptyList(), now)
        assertEquals(1, plan.steps.size)
    }

    @Test
    fun aDeleteWaitsOutItsGrace_andTheMirrorLeavesItsPathAlone() {
        val plan = ExplorerLocalOps.plan(listOf(delete("dir/a.txt", at = now - 1_000)), listOf(row("/dir/a.txt")), emptyList(), now)
        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.waiting.size)
        assertTrue(ExplorerLocalOps.isGuarded("dir/a.txt", plan.guarded))
        assertTrue(!ExplorerLocalOps.isGuarded("dir/b.txt", plan.guarded))
    }

    @Test
    fun anEditorsSave_deleteThenNewFile_isNotADelete() {
        val plan = ExplorerLocalOps.plan(listOf(delete("doc.txt")), listOf(row("/doc.txt")), listOf(ordinary("doc.txt")), now)
        assertTrue(plan.steps.isEmpty())
        assertEquals(1, plan.dropped.size)
    }

    @Test
    fun anEditorsSave_renameAwayThenDeleteTheBackup_isNotADelete() {
        // Word: doc.docx → ~WRL0001.tmp, the new version renamed to doc.docx, then ~WRL0001.tmp deleted.
        val ops = listOf(
            rename("doc.docx", "~WRL0001.tmp", at = settled),
            delete("~WRL0001.tmp", identity = "/doc.docx", at = settled + 1),
        )
        val plan = ExplorerLocalOps.plan(ops, listOf(row("/doc.docx")), listOf(ordinary("doc.docx")), now)
        assertTrue(plan.steps.isEmpty(), "${plan.steps}")
        assertEquals(2, plan.dropped.size)
    }

    @Test
    fun aVersionChangedElsewhere_isNotTrashed() {
        val plan = ExplorerLocalOps.plan(listOf(delete("a.txt", version = "v1")), listOf(row("/a.txt", version = "v2")), emptyList(), now)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun aFolderDeleteTakesItsContentsWhole() {
        val ops = listOf(
            delete("dir/a.txt", at = settled),
            delete("dir/b.txt", at = settled + 1),
            delete("dir", folder = true, at = settled + 2),
        )
        val remote = listOf(row("/dir", folder = true), row("/dir/a.txt"), row("/dir/b.txt"))
        // Explorer deletes the contents first; the folder still goes to the Trash whole, structure kept.
        val plan = ExplorerLocalOps.plan(ops, remote, emptyList(), now)
        assertEquals(listOf("/dir"), plan.steps.map { (it as Step.Trash).realmPath })

        // The folder's own notice still to come (a large folder): nothing goes yet, or each file would on its own.
        val early = ExplorerLocalOps.plan(ops.dropLast(1) + delete("dir", folder = true, at = now - 500), remote, emptyList(), now)
        assertTrue(early.steps.isEmpty())
        assertEquals(3, early.waiting.size)
    }

    @Test
    fun aRenameMovesTheRealmFile_intoTheRealmsSpellingOfItsFolder() {
        val plan = ExplorerLocalOps.plan(
            listOf(rename("a.txt", "photos/b.txt")),
            listOf(row("/a.txt"), row("/Photos", folder = true)),
            listOf(placeholder("photos", folder = true), placeholder("photos/b.txt")),
            now,
        )
        val step = plan.steps.single() as Step.Move
        assertEquals("/a.txt" to "/Photos/b.txt", step.from to step.to)
    }

    @Test
    fun aCaseOnlyRenameIsAMove_andAnUndoneOneIsNot() {
        val remote = listOf(row("/a.txt"))
        val done = ExplorerLocalOps.plan(listOf(rename("a.txt", "A.txt")), remote, listOf(placeholder("A.txt")), now)
        assertEquals("/A.txt", (done.steps.single() as Step.Move).to)
        val undone = ExplorerLocalOps.plan(listOf(rename("a.txt", "A.txt")), remote, listOf(placeholder("a.txt")), now)
        assertTrue(undone.steps.isEmpty())
    }

    @Test
    fun aRenameOntoSomethingTheRealmHas_isNotApplied() {
        val plan = ExplorerLocalOps.plan(
            listOf(rename("a.txt", "b.txt")), listOf(row("/a.txt"), row("/b.txt")), listOf(placeholder("b.txt")), now,
        )
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun aRenameThenADelete_trashesTheOriginal() {
        val ops = listOf(rename("a.txt", "b.txt", at = settled), delete("b.txt", identity = "/a.txt", at = settled + 1))
        val plan = ExplorerLocalOps.plan(ops, listOf(row("/a.txt")), emptyList(), now)
        assertEquals("/a.txt", (plan.steps.single() as Step.Trash).realmPath)
    }

    @Test
    fun aDeleteInTheTrashIsForGood_butTheTrashFolderStays() {
        val remote = listOf(row("/Trash", folder = true), row("/Trash/c1-a.txt"))
        val plan = ExplorerLocalOps.plan(listOf(delete("Trash/c1-a.txt")), remote, emptyList(), now)
        assertTrue((plan.steps.single() as Step.Trash).permanently)
        assertTrue(ExplorerLocalOps.plan(listOf(delete("Trash", folder = true)), remote, emptyList(), now).steps.isEmpty())
        assertTrue(!(ExplorerLocalOps.plan(listOf(delete("a.txt")), listOf(row("/a.txt")), emptyList(), now).steps.single() as Step.Trash).permanently)
        assertTrue(!ExplorerLocalOps.isTrashed("/Trashcan/a.txt"))
    }

    @Test
    fun trashNamesFollowPhotos_andNeverCollide() {
        assertEquals("/Trash/c1-a.txt", ExplorerLocalOps.trashPathFor("/dir/a.txt", "c1", now, emptySet()))
        assertEquals("/Trash/c1-2-a.txt", ExplorerLocalOps.trashPathFor("/dir/a.txt", "c1", now, setOf("/Trash/C1-a.txt")))
        assertEquals("/Trash/$now-dir", ExplorerLocalOps.trashPathFor("/dir", null, now, emptySet()))
    }

    @Test
    fun aNoticeInsideAMovedFolderFollowsIt() {
        val op = rename("old/x.txt", "new/y.txt", identity = "/old/x.txt")
        assertEquals("/new/x.txt", ExplorerLocalOps.rebased(op, "/old", "/new").identityPath)
        assertEquals("/older/x.txt", ExplorerLocalOps.rebased(op.copy(identityPath = "/older/x.txt"), "/old", "/new").identityPath)
    }
}
