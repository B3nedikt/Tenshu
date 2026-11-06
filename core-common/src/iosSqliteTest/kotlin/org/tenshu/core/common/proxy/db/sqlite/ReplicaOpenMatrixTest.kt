package org.tenshu.core.common.proxy.db.sqlite

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every combination of what `meta` holds, what marker is there, how this build relates to the
 * store and how it comes to it, against the verdict the design gives **and against the files**:
 * for every verdict that must not write, `objects.db` and its `-wal` are compared byte for byte.
 *
 * The expectation ([expected]) is written from the rules, in their order of precedence, not from
 * the code under test:
 * 1. a store, or a marker, above this build is `SKEW`: whatever else is true, nothing written;
 * 2. a store on an older layout is refused, retryably: nothing validated, nothing written;
 * 3. damaged bookkeeping is `CORRUPT`: the sidecar is written, the database is not touched;
 * 4. a marker this build may resume is "updating", and only a wipe may be met in the background;
 * 5. a store that is behind needs the foreground, which starts its job;
 * 6. otherwise it is opened.
 * A wipe call follows the same rules up to 3, and from there wipes.
 */
class ReplicaOpenMatrixTest {

    private enum class Meta(val sql: String?) {
        HEALTHY(null),

        /** A row that is none of the marker's, of the wrong storage class. */
        ANOTHER_ROW_DAMAGED("INSERT OR REPLACE INTO meta (k, v) VALUES ('wal_rewind', 'seven')"),
    }

    private enum class Marker(val key: String?, val value: () -> String) {
        NONE(null, { "" }),
        WIPE("wipe", { wipeFrame() }),
        WIPE_STAMPING_PAST_EVERY_BUILD("wipe", { wipeFrame(mapOf("tenshu/core" to 99)) }),
        WIPE_OF_ANOTHER_FORMAT("wipe", { "x'0709'" }),
        WIPE_NOT_A_FRAME("wipe", { "x'01'" }),
        WIPE_AS_TEXT("wipe", { "'yes'" }),
        MIGRATION("migration", { migrationFrame() }),
        MIGRATION_TO_PAST_EVERY_BUILD("migration", { migrationFrame(mapOf("tenshu/file" to 99), from = 3) }),
        MIGRATION_OF_ANOTHER_FORMAT("migration", { "x'0709'" }),
        MIGRATION_NOT_A_FRAME("migration", { "x'0100'" }),
        MIGRATION_AS_AN_INTEGER("migration", { "1" }),

        /** A step has failed on a row once: the frame of the counted format (Appendix B.3 (53)). */
        MIGRATION_WITH_A_COUNT("migration", { migrationFrame(failure = MigrationFailure(hash = 3, version = 3, count = 1, limit = 3)) }),

        /** At its limit a job is a wipe; no writer stores that count. */
        MIGRATION_WITH_A_COUNT_AT_ITS_LIMIT("migration", { migrationFrame(failure = MigrationFailure(hash = 3, version = 3, count = 3, limit = 3)) }),
        MIGRATION_OF_THE_COUNTED_FORMAT_WITHOUT_A_COUNT("migration", { migrationFrame(format = MIGRATION_COUNTED_FORMAT) }),

        /** The counted format is the migration marker's alone: on a wipe it is a format this build does not write. */
        WIPE_OF_THE_COUNTED_FORMAT("wipe", { blobLiteral(WipeMarker("test", "test", 0L, emptyMap()).encode().also { it[0] = MIGRATION_COUNTED_FORMAT.toByte() }) }),
        BOTH(null, { "" });

        val above get() = this in setOf(
            WIPE_STAMPING_PAST_EVERY_BUILD, WIPE_OF_ANOTHER_FORMAT, MIGRATION_TO_PAST_EVERY_BUILD, MIGRATION_OF_ANOTHER_FORMAT,
            WIPE_OF_THE_COUNTED_FORMAT,
        )
        val damaged get() = this in setOf(
            WIPE_NOT_A_FRAME, WIPE_AS_TEXT, MIGRATION_NOT_A_FRAME, MIGRATION_AS_AN_INTEGER, BOTH,
            MIGRATION_WITH_A_COUNT_AT_ITS_LIMIT, MIGRATION_OF_THE_COUNTED_FORMAT_WITHOUT_A_COUNT,
        )
    }

    /** The store is always written by `testBuild()`: core v22, layout 1. */
    private enum class Build(val build: () -> ReplicaBuild) {
        OLDER_THAN_THE_STORE({ testBuild(core = 21) }),
        THE_STORES_OWN({ testBuild() }),
        NEWER_THAN_THE_STORE({ testBuild(core = 23) }),
        OF_THE_NEXT_LAYOUT({ testBuild(layout = ReplicaSchema.LAYOUT + 1) }),
    }

    private enum class Kind { BACKGROUND_OPEN, FOREGROUND_OPEN, WIPE }

    private class Expected(val verdict: String, val writes: Boolean)

    private fun expected(meta: Meta, marker: Marker, build: Build, kind: Kind): Expected {
        val untouched = { verdict: String -> Expected(verdict, writes = false) }
        if (build == Build.OLDER_THAN_THE_STORE || marker.above) return untouched("Skew")
        if (build == Build.OF_THE_NEXT_LAYOUT) return untouched("Unavailable")
        if (meta == Meta.ANOTHER_ROW_DAMAGED || marker.damaged) return untouched("Corrupt")
        if (kind == Kind.WIPE) return Expected("Wiped", writes = true)
        val behind = build == Build.NEWER_THAN_THE_STORE
        return when (marker) {
            Marker.WIPE -> untouched("Updating(WIPE)")
            // A count changes nothing an open decides: it is read, and counted, only by a run.
            Marker.MIGRATION, Marker.MIGRATION_WITH_A_COUNT ->
                untouched(if (kind == Kind.BACKGROUND_OPEN) "NeedsForeground" else "Updating(MIGRATION)")
            else -> when {
                !behind -> untouched("Ready")
                kind == Kind.BACKGROUND_OPEN -> untouched("NeedsForeground")
                // The foreground open of a store that is behind starts its migration.
                else -> Expected("Updating(MIGRATION)", writes = true)
            }
        }
    }

    private fun aStore(meta: Meta, marker: Marker): String {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        store.databaseLock.withLock { repeat(5) { store.storeObject(row(it.toLong())) } }
        store.close()
        process.sql { connection ->
            meta.sql?.let(connection::exec)
            if (marker == Marker.BOTH) {
                connection.exec("INSERT INTO meta (k, v) VALUES ('wipe', ${wipeFrame()})")
                connection.exec("INSERT INTO meta (k, v) VALUES ('migration', ${migrationFrame()})")
            } else if (marker.key != null) {
                connection.exec("INSERT INTO meta (k, v) VALUES ('${marker.key}', ${marker.value()})")
            }
        }
        return directory
    }

    private fun verdictOf(directory: String, build: ReplicaBuild, kind: Kind): String {
        val process = ReplicaProcess(directory)
        return try {
            if (kind == Kind.WIPE) {
                if (process.opener(build).wipe("discard")) "Wiped" else "Nothing"
            } else {
                when (val result = process.open(build, foreground = kind == Kind.FOREGROUND_OPEN)) {
                    is ReplicaOpenResult.Ready -> "Ready".also { result.store.close() }
                    is ReplicaOpenResult.Skew -> "Skew"
                    is ReplicaOpenResult.Corrupt -> "Corrupt"
                    is ReplicaOpenResult.Updating -> "Updating(${result.job})"
                }
            }
        } catch (e: WipeRefused) {
            when (e.outcome) {
                is ReplicaRunOutcome.Skew -> "Skew"
                is ReplicaRunOutcome.Corrupt -> "Corrupt"
                else -> "WipeRefused(${e.outcome})"
            }
        } catch (_: StoreNeedsForeground) {
            "NeedsForeground"
        } catch (_: StoreUnavailable) {
            "Unavailable"
        }
    }

    @Test
    fun everyCombinationGetsTheVerdictTheRulesGiveAndAVerdictThatMustNotWriteLeavesTheFilesAsTheyWere() {
        val wrong = ArrayList<String>()
        var cases = 0
        for (meta in Meta.entries) for (marker in Marker.entries) for (build in Build.entries) for (kind in Kind.entries) {
            cases++
            val case = "$meta / $marker / $build / $kind"
            val expected = expected(meta, marker, build, kind)
            val directory = aStore(meta, marker)
            val database = bytesOf("$directory/objects.db")!!
            val wal = bytesOf("$directory/objects.db-wal")!!

            val verdict = verdictOf(directory, build.build(), kind)
            if (verdict != expected.verdict) wrong += "$case: expected ${expected.verdict}, got $verdict"
            if (!expected.writes) {
                if (!database.contentEquals(bytesOf("$directory/objects.db"))) wrong += "$case: objects.db was written"
                if (!wal.contentEquals(bytesOf("$directory/objects.db-wal"))) wrong += "$case: the -wal was written"
            }
            val marked = fileExists("$directory/${ReplicaCorruptMarker.NAME}")
            if (marked != (expected.verdict == "Corrupt")) wrong += "$case: replica/CORRUPT ${if (marked) "was written" else "is missing"}"
        }
        assertEquals(Meta.entries.size * Marker.entries.size * Build.entries.size * Kind.entries.size, cases)
        assertEquals(emptyList<String>(), wrong, "${wrong.size} of $cases combinations")
    }
}
