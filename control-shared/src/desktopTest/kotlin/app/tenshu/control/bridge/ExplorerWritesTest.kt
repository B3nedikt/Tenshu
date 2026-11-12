package app.tenshu.control.bridge

import app.tenshu.control.bridge.ExplorerWrites.Decision
import app.tenshu.control.bridge.ExplorerWrites.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorerWritesTest {

    private val now = 1_000_000L
    private val old = now - 60_000

    private fun row(path: String, version: String = "v1", folder: Boolean = false) =
        ExplorerMirror.Remote(path, isFolder = folder, size = 10, modifiedAtMillis = 1, contentVersion = version, contentHash = version)

    private fun ordinary(relative: String, modified: Long = old, folder: Boolean = false, hidden: Boolean = false) =
        ExplorerMirror.Local(relative, isFolder = folder, isPlaceholder = false, size = 10, modifiedAtMillis = modified, ours = false, hidden = hidden)

    private fun edited(relative: String, base: String, online: Boolean = false) =
        ExplorerMirror.Local(
            relative, isFolder = false, isPlaceholder = false, size = 10, modifiedAtMillis = old,
            ours = true, contentVersion = base, identityPath = "/$relative", online = online,
        )

    private fun inSync(relative: String) =
        ExplorerMirror.Local(relative, isFolder = false, isPlaceholder = true, size = 10, modifiedAtMillis = old, ours = true)

    @Test
    fun aNewFileOrFolderIsACandidate_shallowestFirst() {
        val found = ExplorerWrites.candidates(emptyList(), listOf(ordinary("a/b.txt"), ordinary("a", folder = true)), emptyMap(), now)
        assertEquals(listOf("a" to Kind.NEW_FOLDER, "a/b.txt" to Kind.NEW_FILE), found.map { it.relative to it.kind })
    }

    @Test
    fun placeholdersInSyncNeedNothing() {
        assertEquals(emptyList(), ExplorerWrites.candidates(listOf(row("/a.txt")), listOf(inSync("a.txt")), emptyMap(), now))
    }

    @Test
    fun aFileStillBeingWrittenWaits_andANewFolderWaitsForItsName() {
        val fresh = ordinary("new.txt", modified = now - 1_000)
        val namedSoon = ordinary("New folder", modified = now - 5_000, folder = true)
        assertEquals(emptyList(), ExplorerWrites.candidates(emptyList(), listOf(fresh, namedSoon), emptyMap(), now))
    }

    @Test
    fun whatIsStillSettlingIsLookedAtAgainOnceItHas() {
        val fresh = ordinary("new.txt", modified = now - 1_000)
        assertEquals(2_000L, ExplorerWrites.untilSettled(listOf(fresh, inSync("a.txt")), now))
        // Placeholders in sync and settled files leave nothing to wait for.
        assertEquals(null, ExplorerWrites.untilSettled(listOf(inSync("a.txt"), ordinary("old.txt")), now))
    }

    @Test
    fun hiddenSystemAndEditorTempFilesAreNeverUploaded() {
        val noise = listOf(
            ordinary("~\$report.docx"), ordinary("report.tmp"), ordinary(".~lock.sheet.ods#"), ordinary("desktop.ini"),
            ordinary("Thumbs.db"), ordinary("video.mp4.crdownload"), ordinary("secret.txt", hidden = true),
        )
        assertEquals(emptyList(), ExplorerWrites.candidates(emptyList(), noise, emptyMap(), now))
    }

    @Test
    fun anOrdinaryFileOverARowCarriesTheJournalsVersion_anEditedPlaceholderItsIdentitys() {
        val rows = listOf(row("/saved.docx", "v2"), row("/edited.txt", "v3"))
        val found = ExplorerWrites.candidates(
            rows, listOf(ordinary("saved.docx"), edited("edited.txt", base = "v3")), mapOf("saved.docx" to "v2"), now,
        ).associateBy { it.relative }
        assertEquals(Kind.ORDINARY_OVER_ROW, found.getValue("saved.docx").kind)
        assertEquals("v2", found.getValue("saved.docx").base)
        assertEquals(Kind.EDITED_PLACEHOLDER, found.getValue("edited.txt").kind)
        assertEquals("v3", found.getValue("edited.txt").base)
    }

    @Test
    fun aFileWhereTheRealmHasAFolderIsLeftAlone() {
        assertEquals(emptyList(), ExplorerWrites.candidates(listOf(row("/a", folder = true)), listOf(ordinary("a")), emptyMap(), now))
    }

    @Test
    fun theDecisions() {
        val newFile = ExplorerWrites.Candidate("a.txt", Kind.NEW_FILE, null, null)
        assertEquals(Decision.UPLOAD_NEW, ExplorerWrites.decide(newFile, sameBytes = false))

        val edit = ExplorerWrites.Candidate("a.txt", Kind.EDITED_PLACEHOLDER, row("/a.txt", "v1"), base = "v1")
        assertEquals(Decision.ALREADY_THERE, ExplorerWrites.decide(edit, sameBytes = true))
        assertEquals(Decision.REPLACE, ExplorerWrites.decide(edit, sameBytes = false))

        // The realm moved on since this was made: never overwrite it, keep both.
        val stale = edit.copy(row = row("/a.txt", "v2"))
        assertEquals(Decision.CONFLICT_COPY, ExplorerWrites.decide(stale, sameBytes = false))

        // Nothing known about where an ordinary file came from: keep both.
        val unknown = ExplorerWrites.Candidate("a.txt", Kind.ORDINARY_OVER_ROW, row("/a.txt", "v1"), base = null)
        assertEquals(Decision.CONFLICT_COPY, ExplorerWrites.decide(unknown, sameBytes = false))
        assertEquals(Decision.ALREADY_THERE, ExplorerWrites.decide(unknown, sameBytes = true))

        // The realm's row went away meanwhile: the edit comes back as a new file.
        assertEquals(Decision.UPLOAD_NEW, ExplorerWrites.decide(edit.copy(row = null), sameBytes = false))
    }

    @Test
    fun conflictNamesKeepTheExtension_andCountUpWhenTaken() {
        assertEquals("report (conflict, DESK).docx", ExplorerWrites.conflictName("report.docx", "DESK", emptySet()))
        assertEquals(
            "report (conflict, DESK) 2.docx",
            ExplorerWrites.conflictName("report.docx", "DESK", setOf("Report (Conflict, desk).DOCX")),
        )
        assertEquals("Makefile (conflict, DESK)", ExplorerWrites.conflictName("Makefile", "DESK", emptySet()))
        assertTrue(ExplorerWrites.conflictName(".env", "DESK", emptySet()).startsWith(".env"))
    }
}
