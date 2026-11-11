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
    fun aReplacedFileKeepsItsAlwaysKeepMark() {
        val pinned = placeholder("a.txt").copy(pin = ExplorerMirror.Pin.PINNED)
        val plan = ExplorerMirror.plan(listOf(file("/a.txt", size = 20)), listOf(pinned))
        assertEquals(true, (plan.last() as Create).pinned)
    }

    @Test
    fun pinnedFilesStillOnlineAreDownloaded_freedFilesWithACopyAreDropped() {
        val local = listOf(
            placeholder("keep.txt").copy(pin = ExplorerMirror.Pin.PINNED, online = true),
            placeholder("kept.txt").copy(pin = ExplorerMirror.Pin.PINNED, online = false),
            placeholder("free.txt").copy(pin = ExplorerMirror.Pin.UNPINNED, online = false),
            placeholder("freed.txt").copy(pin = ExplorerMirror.Pin.UNPINNED, online = true),
            placeholder("opened.txt").copy(online = false),
        )
        assertEquals(
            mapOf("keep.txt" to ExplorerMirror.Copy.DOWNLOAD, "free.txt" to ExplorerMirror.Copy.DROP),
            ExplorerMirror.copies(local).copies,
        )
    }

    @Test
    fun aFreeMarkIsClearedOnceCarriedOut_soAReopenedFileKeepsItsCopyAndASecondRequestIsNew() {
        val freed = listOf(
            placeholder("photo.jpg").copy(pin = ExplorerMirror.Pin.UNPINNED, online = false),
            placeholder("already.jpg").copy(pin = ExplorerMirror.Pin.UNPINNED, online = true),
        )
        val plan = ExplorerMirror.copies(freed)
        assertEquals(mapOf("photo.jpg" to ExplorerMirror.Copy.DROP), plan.copies)
        // Each mark goes once its own drop (if any) has succeeded.
        assertEquals(mapOf("photo.jpg" to setOf("photo.jpg"), "already.jpg" to emptySet()), plan.cleared)
        // Reopened after the mark was cleared: no mark, so the copy stays.
        val reopened = listOf(placeholder("photo.jpg").copy(online = false))
        assertEquals(emptyMap(), ExplorerMirror.copies(reopened).copies)
    }

    @Test
    fun aFreedFolderIsClearedOnlyOnceEveryDropBelowItIsDone() {
        val local = listOf(
            placeholderFolder("a").copy(pin = ExplorerMirror.Pin.UNPINNED),
            placeholder("a/x.jpg").copy(online = false),
            placeholder("a/y.jpg").copy(online = true),
        )
        val plan = ExplorerMirror.copies(local)
        assertEquals(mapOf("a/x.jpg" to ExplorerMirror.Copy.DROP), plan.copies)
        assertEquals(mapOf("a" to setOf("a/x.jpg")), plan.cleared)
    }

    @Test
    fun aFileWithoutAMarkFollowsTheNearestMarkedFolder() {
        val local = listOf(
            placeholderFolder("a").copy(pin = ExplorerMirror.Pin.PINNED),
            ExplorerMirror.Local("a/b", isFolder = true, isPlaceholder = true, size = 0, modifiedAtMillis = 0),
            placeholder("a/b/deep.txt").copy(online = true),
            placeholder("a/b/own.txt").copy(pin = ExplorerMirror.Pin.UNPINNED, online = false),
        )
        assertEquals(
            mapOf("a/b/deep.txt" to ExplorerMirror.Copy.DOWNLOAD, "a/b/own.txt" to ExplorerMirror.Copy.DROP),
            ExplorerMirror.copies(local).copies,
        )
    }

    @Test
    fun theUsersOwnFilesAreNeverDownloadedOrDropped_norTheirMarksCleared() {
        val mine = listOf(usersFile("mine.txt").copy(pin = ExplorerMirror.Pin.UNPINNED))
        assertEquals(emptyMap(), ExplorerMirror.copies(mine).copies)
        assertEquals(emptyMap(), ExplorerMirror.copies(mine).cleared)
    }

    @Test
    fun aFileOverwrittenWithTheSameSizeAndDateIsStillReplaced_byItsContentVersion() {
        val stale = placeholder("a.txt").copy(contentVersion = "old")
        val plan = ExplorerMirror.plan(listOf(file("/a.txt", version = "new")), listOf(stale))
        assertEquals(listOf(Remove("a.txt", false), Create("a.txt", file("/a.txt", version = "new"))), plan)
        assertEquals(emptyList(), ExplorerMirror.plan(listOf(file("/a.txt", version = "old")), listOf(stale)))
    }

    @Test
    fun aCaseOnlyRenameReplacesTheFile_soItsIdentityNamesThePathThatExists() {
        val old = placeholder("photo.jpg").copy(contentVersion = "v1", identityPath = "/photo.jpg")
        val plan = ExplorerMirror.plan(listOf(file("/Photo.jpg")), listOf(old))
        assertEquals(listOf(Remove("photo.jpg", false), Create("Photo.jpg", file("/Photo.jpg"))), plan)
        // A file whose identity names its own path needs nothing.
        val same = old.copy(relative = "Photo.jpg", identityPath = "/Photo.jpg")
        assertEquals(emptyList(), ExplorerMirror.plan(listOf(file("/Photo.jpg")), listOf(same)))
    }

    @Test
    fun removingMostOfTheFolderIsAMassRemoval_replacingItIsNot() {
        val local = (1..20).map { placeholder("f$it.txt") }
        assertTrue(ExplorerMirror.isMassRemoval(ExplorerMirror.plan(emptyList(), local), local))
        // Every file changed: twenty removals, each followed by its replacement.
        val changed = (1..20).map { file("/f$it.txt", size = 99) }
        assertFalse(ExplorerMirror.isMassRemoval(ExplorerMirror.plan(changed, local), local))
        // A few files going is ordinary.
        val few = (1..3).map { placeholder("g$it.txt") }
        assertFalse(ExplorerMirror.isMassRemoval(ExplorerMirror.plan(emptyList(), few), few))
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
