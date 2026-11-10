package app.tenshu.control.bridge

import app.tenshu.control.bridge.ExplorerMirror.Action.Create
import app.tenshu.control.bridge.ExplorerMirror.Action.Remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplorerMirrorTest {

    private fun file(path: String, size: Long = 10, modified: Long = 1_000, version: String = "v1") =
        ExplorerMirror.Remote(path, isFolder = false, size = size, modifiedAtMillis = modified, contentVersion = version)

    private fun folder(path: String) = ExplorerMirror.Remote(path, isFolder = true, size = 0, modifiedAtMillis = 1_000, contentVersion = "")

    private fun placeholder(relative: String, size: Long = 10, modified: Long = 1_000) =
        ExplorerMirror.Local(relative, isFolder = false, isPlaceholder = true, size = size, modifiedAtMillis = modified)

    private fun placeholderFolder(relative: String) =
        ExplorerMirror.Local(relative, isFolder = true, isPlaceholder = true, size = 0, modifiedAtMillis = 1_000)

    private fun usersFile(relative: String) =
        ExplorerMirror.Local(relative, isFolder = false, isPlaceholder = false, size = 5, modifiedAtMillis = 7)

    private fun usersFolder(relative: String) =
        ExplorerMirror.Local(relative, isFolder = true, isPlaceholder = false, size = 0, modifiedAtMillis = 7)

    @Test
    fun anEmptyFolderGetsTheWholeRealm_parentsBeforeChildren() {
        val plan = ExplorerMirror.plan(listOf(file("/a/b/c.txt"), folder("/a"), folder("/a/b")), emptyList())
        assertEquals(listOf("a", "a/b", "a/b/c.txt"), plan.map { it.relative })
        assertTrue(plan.all { it is Create })
    }

    @Test
    fun aFolderTheListingLeftOutIsCreatedForItsChildren() {
        val plan = ExplorerMirror.plan(listOf(file("/x/y.txt")), emptyList())
        assertEquals(listOf("x", "x/y.txt"), plan.map { it.relative })
        assertTrue((plan[0] as Create).remote.isFolder)
    }

    @Test
    fun aFolderInStepNeedsNothing() {
        val plan = ExplorerMirror.plan(
            listOf(folder("/a"), file("/a/b.txt")),
            listOf(placeholderFolder("a"), placeholder("a/b.txt")),
        )
        assertEquals(emptyList(), plan)
    }

    @Test
    fun aChangedFileIsReplaced_removedBeforeItIsCreated() {
        val plan = ExplorerMirror.plan(listOf(file("/a.txt", size = 20)), listOf(placeholder("a.txt", size = 10)))
        assertEquals(listOf(Remove("a.txt", false), Create("a.txt", file("/a.txt", size = 20))), plan)

        val touched = ExplorerMirror.plan(listOf(file("/a.txt", modified = 2_000)), listOf(placeholder("a.txt")))
        assertEquals(2, touched.size)
    }

    @Test
    fun whatTheRealmNoLongerHasIsRemoved_deepestFirst() {
        val plan = ExplorerMirror.plan(emptyList(), listOf(placeholderFolder("a"), placeholder("a/b.txt")))
        assertEquals(listOf(Remove("a/b.txt", false), Remove("a", true)), plan)
    }

    @Test
    fun theUsersOwnFilesAreNeverRemoved_norTheFoldersHoldingThem() {
        val plan = ExplorerMirror.plan(
            emptyList(),
            listOf(placeholderFolder("a"), placeholder("a/old.txt"), usersFile("a/mine.txt"), usersFile("notes.txt")),
        )
        assertEquals(listOf(Remove("a/old.txt", false)), plan)
    }

    @Test
    fun theUsersFileAtARealmPathWins() {
        val plan = ExplorerMirror.plan(listOf(file("/a.txt")), listOf(usersFile("a.txt")))
        assertEquals(emptyList(), plan)
    }

    @Test
    fun aUsersFileWhereTheRealmHasAFolderHidesThatFolder() {
        val plan = ExplorerMirror.plan(listOf(folder("/a"), file("/a/b.txt")), listOf(usersFile("a")))
        assertEquals(emptyList(), plan)
    }

    @Test
    fun realmChildrenAreShownInsideAFolderTheUserMade() {
        val plan = ExplorerMirror.plan(listOf(folder("/a"), file("/a/b.txt")), listOf(usersFolder("a")))
        assertEquals(listOf(Create("a/b.txt", file("/a/b.txt"))), plan)
    }

    @Test
    fun aFileThatBecameAFolderIsReplaced() {
        val plan = ExplorerMirror.plan(listOf(folder("/a"), file("/a/b.txt")), listOf(placeholder("a")))
        assertEquals(listOf(Remove("a", false), Create("a", folder("/a")), Create("a/b.txt", file("/a/b.txt"))), plan)
    }

    @Test
    fun aFolderThatMustStayIsNotReplacedByAFile() {
        val plan = ExplorerMirror.plan(listOf(file("/a")), listOf(placeholderFolder("a"), usersFile("a/mine.txt")))
        assertEquals(emptyList(), plan)
    }

    @Test
    fun namesWindowsCannotHoldAreSkipped_withEverythingBelowThem() {
        val plan = ExplorerMirror.plan(
            listOf(folder("/what?"), file("/what?/x.txt"), file("/CON.txt"), file("/trailing."), file("/ok.txt")),
            emptyList(),
        )
        assertEquals(listOf("ok.txt"), plan.map { it.relative })
    }

    @Test
    fun ofTwoRowsDifferingOnlyInCase_theFirstIsShown_andTheOthersSubtreeIsNot() {
        val plan = ExplorerMirror.plan(
            listOf(folder("/Photos"), file("/Photos/a.jpg"), folder("/photos"), file("/photos/b.jpg")),
            emptyList(),
        )
        assertEquals(listOf("Photos", "Photos/a.jpg"), plan.map { it.relative })
    }

    @Test
    fun matchingIsCaseInsensitive_likeNtfs() {
        val plan = ExplorerMirror.plan(listOf(file("/Report.PDF")), listOf(placeholder("report.pdf")))
        assertEquals(emptyList(), plan)
    }

    @Test
    fun windowsNames() {
        assertTrue(ExplorerMirror.isWindowsName("résumé (final).docx"))
        assertTrue(ExplorerMirror.isWindowsName("console.log"))
        assertFalse(ExplorerMirror.isWindowsName("aux"))
        assertFalse(ExplorerMirror.isWindowsName("nul.txt"))
        assertFalse(ExplorerMirror.isWindowsName("a|b"))
        assertFalse(ExplorerMirror.isWindowsName("ends with space "))
        assertFalse(ExplorerMirror.isWindowsName("tab\there"))
    }

    @Test
    fun theIdentityRoundTrips_andAForeignBlobIsRefused() {
        val identity = ExplorerIdentity("/a/b c.txt", "sha256:abc\nodd")
        assertEquals(identity, ExplorerIdentity.decode(identity.encode()))
        assertNull(ExplorerIdentity.decode("hello".encodeToByteArray()))
        assertNull(ExplorerIdentity.decode("2\n/a\nv".encodeToByteArray()))
    }
}
