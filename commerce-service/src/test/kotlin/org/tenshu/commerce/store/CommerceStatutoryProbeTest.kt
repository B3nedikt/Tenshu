package org.tenshu.commerce.store

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `deploy/reset-test-data.sh` counts commerce's statutory rows (`invoice`, `credit_note`) before it
 * wipes anything, and refuses when it cannot. The runbook's answer to every failed reset is "re-run the
 * full command", so the count must get through every state an interrupted run leaves commerce in —
 * an earlier version ran `compose exec db` against a `db` the failed run had stopped, and refused the
 * re-run even with `--statutory-exported`. This runs the real remote body (`REMOTE_STATUTORY`, cut out
 * of the script) against a fake `docker compose`.
 */
class CommerceStatutoryProbeTest {

    private val script = File("../deploy/reset-test-data.sh")
    private val fakeCompose = File("src/test/resources/statutory-probe/fake-compose.sh")

    private class Box(val root: File) {
        val state = File(root, "state").apply { mkdirs() }
        val pgdata = File(root, "pgdata").apply { mkdirs() }
        val repo = File(root, "repo").apply { mkdirs() }
        val env = File(root, "deploy.env").apply { writeText("COMMERCE_DOMAIN=commerce.example\n") }
        fun populated() = File(pgdata, "PG_VERSION").writeText("16\n")
        fun running() = File(state, "running").createNewFile()
        fun table(name: String, rows: String) = File(state, "table.$name").writeText("$rows\n")
        fun calls() = File(state, "calls").takeIf { it.exists() }?.readLines().orEmpty()
    }

    private class Result(val rc: Int, val stdout: String, val stderr: String)

    private fun body(): String {
        val lines = script.readLines()
        val start = lines.indexOfFirst { it.startsWith("IFS= read -r -d '' REMOTE_STATUTORY <<'REMOTE_EOF'") }
        assertTrue(start >= 0, "REMOTE_STATUTORY not found in ${script.path}")
        val end = (start + 1 until lines.size).first { lines[it] == "REMOTE_EOF" }
        return lines.subList(start + 1, end).joinToString("\n")
    }

    private fun run(box: Box): Result {
        val bodyFile = File(box.root, "body.sh").apply { writeText(body()) }
        val pb = ProcessBuilder("bash", bodyFile.path)
        pb.environment().apply {
            put("COMMERCE_COMPOSE_CMD", "bash ${fakeCompose.absolutePath}")
            put("FAKE_STATE", box.state.path)
            put("ENV_FILE", box.env.path)
            put("REPO_DIR", box.repo.path)
            put("PGDATA_DIR", box.pgdata.path)
        }
        val errFile = File(box.root, "stderr")
        val p = pb.redirectError(errFile).start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        return Result(rc, out, errFile.readText())
    }

    private fun box() = Box(Files.createTempDirectory("statutory-probe").toFile())

    @Test
    fun anAlreadyWipedDatabaseHasNoRowsAndNothingIsStarted() {
        val box = box() // pgdata empty, db down — what a failure after the commerce `rm` leaves
        val r = run(box)
        assertEquals(0, r.rc, r.stderr)
        assertTrue(r.stdout.contains("LOCAL\t0\t0"), r.stdout)
        assertTrue(r.stdout.contains("STRIPE\tnone\t0\t0"), r.stdout)
        assertFalse(box.calls().any { it.startsWith("up") }, "started db on an empty pgdata: ${box.calls()}")
    }

    @Test
    fun aStoppedDbIsStartedAloneAndCounted() {
        val box = box().apply {
            populated() // data present, db stopped — a failed run that stopped commerce + db
            table("invoice", "2")
            table("credit_note", "1")
        }
        val r = run(box)
        assertEquals(0, r.rc, r.stderr)
        assertTrue(r.stdout.contains("LOCAL\t2\t1"), r.stdout)
        assertTrue(box.calls().contains("up -d db"), "db was not started alone: ${box.calls()}")
    }

    @Test
    fun aClusterWithoutTheTablesHasNoRows() {
        val box = box().apply { populated(); running() } // re-initialised cluster, migrations never ran
        val r = run(box)
        assertEquals(0, r.rc, r.stderr)
        assertTrue(r.stdout.contains("LOCAL\t0\t0"), r.stdout)
    }

    @Test
    fun anUnreadableCountStillRefuses() {
        val box = box().apply {
            populated()
            running()
            table("invoice", "ERROR")
            table("credit_note", "0")
        }
        val r = run(box)
        assertEquals(3, r.rc, "an unreadable count must refuse, got: ${r.stdout}")
        assertFalse(r.stdout.contains("LOCAL"), r.stdout)
    }
}
