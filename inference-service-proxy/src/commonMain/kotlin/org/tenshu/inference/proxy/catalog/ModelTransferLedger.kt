package org.tenshu.inference.proxy.catalog

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One task the session holds that is still running or waiting: its identifier and its ref. */
open class ModelSessionTransfer(val taskId: Long, val ref: String)

/**
 * Who holds which transfer ref in the model download's background session, and in what order
 * starts and cancels were **asked for** (`ios-on-device-inference.md` §14.11).
 *
 * The session answers *what do you hold* later, on another queue (`getAllTasks`). So a start
 * and a cancel are each two moments: when Kotlin asked, and when the session's answer arrived.
 * Everything that goes wrong between the two is order, and order is all this class holds. It
 * touches no `URLSession`, which is what lets a test run it: `ModelDownload.swift` asks it what
 * to do at each moment and does only that. ⚠ It lives beside [BackgroundModelDownloader], and
 * not in the iOS umbrella, so the two are tested **together**, on every target: the races
 * between them are where this code breaks.
 *
 * ⚠⚠ **This is the one authority for who holds a ref**: which start may make a task ([admit]),
 * whose reports are passed on ([speaksFor], [completed]) and still stand when they are acted
 * on ([ModelTransferReport.stands]), and what Kotlin is told is live ([liveRefs]). None of the
 * three is answered from the session's task list, which lags all of them.
 *
 * The rules:
 * - **one task at most holds a ref**, and a task speaks for a ref, and keeps it from being
 *   started again, **only while it is that holder**. There is no other way to count: not "a
 *   task this process does not know", not "a task the session lists";
 * - **every task has an identity here from the first time it is seen**, whoever started it. A
 *   task of an earlier process is seen when the session lists it or when it first reports,
 *   and takes the hold then if the ref is free and no cancel was asked for; otherwise it is
 *   known and holds nothing, for good;
 * - **a start a later cancel overtook is dropped**, at its answer and again when its task
 *   exists: a model the user cancelled does not download because the session was slow to answer;
 * - **a cancel ends the hold at once**, whether or not it has reached the task, so a download
 *   asked for again starts a task of its own, with **its own** request and so its own
 *   cellular choice;
 * - **a cancel stops what was asked for before it and nothing asked for after it**;
 * - **a start asked for before a transfer of its ref ended and was acted on is dropped**: it
 *   was decided on a picture that transfer has since changed.
 *
 * ⚠ Every moment is numbered from one counter, and a start keeps the number of when it was
 * *asked for*, not of when its task was made.
 */
/**
 * @param offered whether this build, on this phone, offers model downloads at all. ⚠⚠ `false`
 *   is **a build met by what an enabled build left behind**: the session and its tasks outlive
 *   the app that started them, and an update can turn the feature off (the build flag, or a
 *   static gate) while a transfer runs. Then nobody holds anything: no start is admitted, no
 *   task speaks, nothing is reported or listed as live, and every task the session lists is
 *   to be cancelled ([notOffered]).
 */
open class ModelTransferLedger(private val offered: Boolean = true) {

    private val lock = SynchronizedObject()
    private var clock: Long = 0

    /** Ref → when its last cancel was asked for. */
    private val cancelledAt = HashMap<String, Long>()

    /** Ref → the start that holds it: asked for at this moment, admitted, its task not ended. */
    private val holding = HashMap<String, Long>()

    /** Everything known of one task: its ref, the moment that numbers it, and whose it is. */
    private class Known(val ref: String, val at: Long, val own: Boolean) {
        /** The last figure of this task's that was passed on. ⚠ The task's, never the ref's. */
        var reported: Long = 0

        /** Ended in the session, its completion handed to Kotlin and not yet acted on there. */
        var unsettled: Boolean = false
    }

    /**
     * Ref → when the last transfer that held it was **acted on** by Kotlin ([settle]) while it
     * still held it. A start asked for before that moment was decided before Kotlin had the
     * transfer's bytes, or its failure: see [admit].
     */
    private val actedOnAt = HashMap<String, Long>()

    /** Task → what is known of it. Every task ever seen and not ended, whoever started it. */
    private val tasks = HashMap<Long, Known>()

    /** Whether the hold on [ref] stands: somebody has it, and no cancel was asked for since. */
    private fun held(ref: String): Boolean = offered && (holding[ref] ?: 0) > (cancelledAt[ref] ?: 0)

    /**
     * The tasks to cancel because the feature is off: **every one the session lists**, whoever
     * started it. Empty when it is on. ⚠ Their completions report nothing ([completed]) and
     * their files are not taken ([speaksFor]): a build that offers no model installs none.
     */
    fun notOffered(live: List<ModelSessionTransfer>): List<Long> = locked {
        if (offered) emptyList() else live.map { it.taskId }
    }

    /**
     * The first sight of a task this process did not start. It takes the hold when the ref is
     * free **and no cancel was ever asked for it here** (a task still around after a cancel is
     * what that cancel was for); otherwise it is known, numbered 0, and never holds anything.
     */
    private fun see(taskId: Long, ref: String) {
        if (taskId in tasks) return
        if (!held(ref) && ref !in cancelledAt) {
            val at: Long = ++clock
            tasks[taskId] = Known(ref, at, own = false)
            holding[ref] = at
        } else {
            tasks[taskId] = Known(ref, 0, own = false)
        }
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /** A start of [ref] is asked for. The answer is what [admit] and [bind] are given back. */
    fun startAsked(ref: String): Long = locked { ++clock }

    /** The session has said what it holds: every task in [live] is known from here on. */
    fun observe(live: List<ModelSessionTransfer>) = locked { live.forEach { see(it.taskId, it.ref) } }

    /**
     * The session has said what it holds ([live]: running or waiting, never cancelling). May the
     * start asked for at [asked] make its task?
     *
     * ⚠ Decided and recorded in one step, so of two starts of one ref the second sees the first
     * even when the session's answer to it was taken before the first task existed.
     */
    fun admit(ref: String, asked: Long, live: List<ModelSessionTransfer>): Boolean = locked {
        live.forEach { see(it.taskId, it.ref) }
        if (!offered) return@locked false
        // A cancel was asked for after this start was: the start is not wanted any more.
        if ((cancelledAt[ref] ?: 0) > asked) return@locked false
        // ⚠⚠ A transfer of this ref ended, and Kotlin acted on it, AFTER this start was asked
        // for: the start was decided by a step that was told the ref was free while that
        // transfer's file, or its failure, was still on its way. Its bytes are staged now, or
        // its download is over; either way this start would fetch what nobody is waiting for.
        // ⚠ Whatever is still missing is asked for again by the next step, with a later number.
        if ((actedOnAt[ref] ?: 0) > asked) return@locked false
        // Somebody holds it: a start of this process, or a task of an earlier one seen above.
        if (held(ref)) return@locked false
        holding[ref] = asked
        true
    }

    /**
     * The admitted start's task exists as [taskId]. `true`: resume it. `false`: a cancel was
     * asked for while it was being made, and it is to be cancelled without ever running.
     */
    fun bind(ref: String, asked: Long, taskId: Long): Boolean = locked {
        tasks[taskId] = Known(ref, asked, own = true)
        val wanted: Boolean = (cancelledAt[ref] ?: 0) < asked
        wanted
    }

    /**
     * A cancel of [ref] is asked for. Answers this process's own tasks to cancel **now**, which
     * need no answer from the session; [cancelFromSession] is for the rest.
     */
    fun cancelAsked(ref: String): CancelAsked = locked {
        val asked: Long = ++clock
        cancelledAt[ref] = asked
        CancelAsked(asked, tasks.filterValues { it.own && it.ref == ref && it.at < asked }.keys.toList())
    }

    class CancelAsked(val asked: Long, val taskIds: List<Long>)

    /**
     * The session has said what it holds. Which of [live] does the cancel asked for at [asked]
     * stop? ⚠ Not a task whose start was asked for after the cancel: that is the download asked
     * for again, not the one that was cancelled.
     */
    fun cancelFromSession(ref: String, asked: Long, live: List<ModelSessionTransfer>): List<Long> = locked {
        live.forEach { see(it.taskId, it.ref) }
        live.filter { it.ref == ref && (tasks[it.taskId]?.at ?: 0) < asked }.map { it.taskId }
    }

    /**
     * Whether [taskId] is the task that **holds [ref] now**: the only task whose reports
     * (delivered, progress, completed) are passed on to Kotlin.
     *
     * ⚠⚠ A ref outlives a task, two ways:
     * - a download cancelled and asked for again has the same ref and a new task, and the old
     *   task's last word can still be on its way;
     * - a task of an earlier process can have ended before this process asked the session what
     *   it holds, its report still queued: the session does not list it, a replacement is
     *   started, and then the old report arrives.
     *
     * Either way the stale report would be read as the **current** download's: its failure
     * removes that download, its file is taken for that download's. So there is one rule, for
     * every task: it speaks while it is the ref's holder, and a task seen for the first time
     * here is given its place by [see] like any other, which is no place when the ref is held.
     */
    fun speaksFor(taskId: Long, ref: String): Boolean = locked {
        see(taskId, ref)
        val known: Known = tasks.getValue(taskId)
        known.ref == ref && holds(known)
    }

    /**
     * [taskId] has a file to deliver for [ref]. `true`: move it into place, and then ask
     * [endDelivery]. `false`: the task does not hold the ref; its file is nobody's.
     *
     * ⚠⚠ **A delivery is two questions around the move, not one before it.** The move is made
     * on the session's queue, with no runtime and outside every lock of the downloader, because
     * the temporary file is gone when the callback returns. Asked once, before the move, a
     * cancel or a removal that came in between would delete the destination *first*, and the
     * move would then put the file back: hundreds of megabytes no record names, and no report to
     * follow, since the task does not hold the ref any more.
     * ⚠ This lock is not held across the move.
     */
    fun beginDelivery(taskId: Long, ref: String): Boolean = speaksFor(taskId, ref)

    /**
     * The move is done. `true`: the file is the ref's delivered file. `false`: the task lost
     * its hold while the file was being moved, and **the file just placed is to be deleted**
     * by whoever moved it.
     *
     * ⚠ Why that is enough, with no waiting on either side: the downloader, ending a download,
     * cancels the ref (the hold ends here, at once) and deletes the destination. Whichever of
     * its delete and the move comes second, the file is gone: its own delete if the move was
     * first, this answer if the move was second. ⚠ And nothing else can be at that path
     * meanwhile: a session delivers one file at a time (its delegate queue is serial), so the
     * task that replaces this one cannot deliver before this answer has been acted on.
     */
    fun endDelivery(taskId: Long, ref: String): Boolean = speaksFor(taskId, ref)

    /**
     * Whether [taskId]'s progress, now at [written] bytes, is passed on to Kotlin: it holds
     * [ref], and it has moved by [step] bytes since the last figure of **its own** that was.
     *
     * ⚠⚠ The throttle is kept here, per task, because a ref outlives a task. Kept by ref, the
     * figure of a task that was cancelled far into its download would stand for the task that
     * replaced it: a 555 MB retry would say nothing until it had passed the point its
     * predecessor reached, and its bar would sit at zero.
     */
    fun reportsProgress(taskId: Long, ref: String, written: Long, step: Long): Boolean = locked {
        see(taskId, ref)
        val known: Known = tasks.getValue(taskId)
        if (!(known.ref == ref && holds(known))) return@locked false
        if (written - known.reported < step) return@locked false
        known.reported = written
        true
    }

    /**
     * [taskId] has completed in the session. A report: pass it to Kotlin, which calls
     * [ModelTransferReport.settled] when it has acted on it. `null`: it is nobody's (the task
     * did not hold its ref, or it was cancelled), nothing is reported and the task is forgotten
     * here and now.
     *
     * ⚠⚠ **A reported task keeps its ref until its report is settled.** Kotlin does not act on
     * a report when it is made: it queues it behind its own lock, which a step may hold for
     * seconds while it waits for this very session to say what is live. Had the ref been freed
     * here, that step would be told the ref is held by nobody, start a replacement, and the
     * queued failure would then end the download the replacement belongs to. So between the
     * report and its settlement the ended task **is still the holder**: [liveRefs] names its
     * ref, [admit] refuses a second start, and the check and the act are one thing although
     * they are two moments.
     *
     * ⚠⚠ **And the hold can end in between**: Kotlin cancels the ref (the user cancelled, or
     * removed the model) while the report waits, and may start the same ref again for a new
     * download. The report is then about a transfer nobody wants, whatever it says, a success
     * as much as a failure: acted on, it would be taken for the new download's. So Kotlin asks
     * [ModelTransferReport.stands] **when it acts**, under the same lock its cancels run under.
     *
     * @param cancelled the completion is `NSURLErrorCancelled`: a cancel reports nothing.
     */
    fun completed(taskId: Long, ref: String, cancelled: Boolean): ModelTransferReport? = locked {
        see(taskId, ref)
        val known: Known = tasks.getValue(taskId)
        val speaks: Boolean = !cancelled && holds(known)
        if (!speaks) {
            forget(taskId)
            return@locked null
        }
        known.unsettled = true
        object : ModelTransferReport {
            override fun stands(): Boolean = locked { tasks[taskId] === known && holds(known) }

            override fun settled() = settle(taskId, known)
        }
    }

    private fun holds(known: Known): Boolean = held(known.ref) && holding[known.ref] == known.at

    private fun settle(taskId: Long, known: Known) = locked {
        if (tasks[taskId] !== known) return@locked
        // ⚠ Only a transfer that still held its ref marks the moment: one whose hold a cancel
        // ended says nothing about the download asked for after that cancel.
        if (holds(known)) actedOnAt[known.ref] = ++clock
        forget(taskId)
    }

    /**
     * What Kotlin is told is live: **every ref somebody holds**. A task the session lists
     * takes its place first ([see]), so one of an earlier process counts from here on.
     *
     * ⚠⚠ From the holds, and not from the session's list, because the list is wrong both ways:
     * - it **leaves out** a transfer that has ended and whose completion has not been called
     *   back yet, or whose report Kotlin has not acted on: told that ref is free, a step starts
     *   it again;
     * - it **names** a task whose cancel was asked for and has not reached it: told that ref
     *   is live, the download asked for after the cancel starts nothing, and then the task goes.
     *
     * So what a step is told and what [admit] will allow are one answer.
     */
    fun liveRefs(live: List<ModelSessionTransfer>): List<String> = locked {
        live.forEach { see(it.taskId, it.ref) }
        holding.keys.filter(::held)
    }

    /** [taskId] is gone, whatever it was: forgotten, and its hold with it. For a task that reported nothing. */
    fun ended(taskId: Long) = locked { forget(taskId) }

    private fun forget(taskId: Long) {
        val known: Known = tasks.remove(taskId) ?: return
        if (holding[known.ref] == known.at) holding.remove(known.ref)
    }
}
