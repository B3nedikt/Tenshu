package org.tenshu.sync.relay.storage

import java.io.File
import java.util.UUID

/**
 * Prove a directory's volume still **accepts a write**, throwing if it does not. Leaves nothing
 * behind.
 *
 * ⚠⚠ A write, specifically — a read would have been useless. On 2026-08-14 the relay's volume
 * filled and it answered 500 to every push while serving reads perfectly and reporting `200` on
 * `GET /`. Nothing that only *reads* could have told the two states apart, which is why the
 * commerce-side `SELECT 1` is the wrong shape here (`drive-upload-stop-and-reclamation.md` §4.6).
 *
 * ⚠ It also takes **no lock**. That is what lets `/health` probe the volume holding `relay.db`
 * without going through [RelayDatabase], which serialises every caller on one `ReentrantLock` over
 * a single connection — a `SELECT 1` there would queue behind whatever sync request holds it and
 * report a merely *busy* relay as down.
 *
 * Extracted from [FileBlobStore] when [S3BlobStore] arrived: once the blobs can live in a bucket,
 * "probe the blob store" and "probe the database's volume" stop being the same act, and both are
 * needed (`hosting-provider-economics.md` D10).
 */
class VolumeWriteProbe(private val directory: File) {

    /**
     * Write a few bytes into [directory] and remove them again — the same `writeBytes` call, on the
     * same volume, that throws when the disk is full.
     *
     * The unique name per probe cannot collide with a concurrent probe or with a real blob; the
     * `finally` is the lesson [FileBlobStore.put] carries, that a probe leaking its own file on the
     * one condition it exists to detect would make a full disk marginally fuller, once per poll.
     */
    fun probe() {
        val probe = File(directory, ".health-probe-${UUID.randomUUID()}")
        try {
            probe.writeBytes(PROBE_BYTES)
        } finally {
            probe.delete()
        }
    }

    private companion object {
        /** Content is irrelevant; it only has to be a real write. */
        val PROBE_BYTES = byteArrayOf(0)
    }
}
