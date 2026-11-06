package org.tenshu.sdk.ios

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The durable set of not-yet-acked upload refs for the O3-B background-upload path
 * (ios-drive-parity.md P3b).
 *
 * Persisted as a newline-delimited file in the App-Group container so a resume after the app
 * was terminated knows exactly which staged chunks still need (re-)enqueuing on the background
 * `URLSession`. It stores ONLY content-addressed `blob-<sha256>` refs — never bearer tokens,
 * device JWTs, or bytes: the request headers are re-minted fresh on every (re-)enqueue and the
 * ciphertext lives in the sibling staging file, so nothing sensitive is at rest here beyond the
 * ref itself (threat-model A5 — the staging area is opaque ciphertext, same posture as the
 * ObjectStore replica already in this container).
 *
 * Thread-safe via a [Mutex]: `record` runs on the (single) emit path while `markComplete` runs
 * off the `URLSession` delegate queue. The in-memory mirror is loaded lazily on first use and
 * flushed on every mutation (the set is tiny — one entry per in-flight chunk).
 */
internal class BackgroundUploadJournal(private val filePath: String) {

    private val mutex = Mutex()
    private val pending = linkedSetOf<String>()
    private var loaded = false

    private fun loadLocked() {
        if (loaded) return
        readBytesFromFile(filePath)
            ?.decodeToString()
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.let { pending.addAll(it) }
        loaded = true
    }

    private fun flushLocked() {
        writeBytesToFile(filePath, pending.joinToString("\n").encodeToByteArray())
    }

    /** Record [ref] as staged-and-enqueued (durable across app termination). */
    suspend fun record(ref: String) = mutex.withLock {
        loadLocked()
        if (pending.add(ref)) flushLocked()
    }

    /** Drop [ref] once the relay has acked it (or it is no longer recoverable). */
    suspend fun markComplete(ref: String) = mutex.withLock {
        loadLocked()
        if (pending.remove(ref)) flushLocked()
    }

    /** The refs still awaiting a successful transfer — the resume work-list. */
    suspend fun pendingRefs(): List<String> = mutex.withLock {
        loadLocked()
        pending.toList()
    }
}
