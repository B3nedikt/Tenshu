@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.error.errorCopyKey
import app.tenshu.sdk.internal.JsRuntime
import app.tenshu.sdk.internal.failureResult
import app.tenshu.sdk.internal.okResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import org.tenshu.core.TError
import org.tenshu.core.TResult
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.mia.MiaAnswer
import org.tenshu.mia.MiaService
import org.tenshu.mia.MiaSource
import org.tenshu.mia.askMia
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.fold
import org.tenshu.core.get
import org.tenshu.preference.Preference
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.set
import kotlin.js.Promise
import kotlin.time.Clock

/**
 * Mia on web (`web-app-suite.md` Wave 4, Mia run A): the `@JsExport` surface behind
 * `@tenshu/sdk/mia` — text chat over the **deterministic planner**, the conversation history, and
 * the grants. It mirrors native `TenshuConversationRepository` (the chat and History) and
 * `TenshuMiaGrantsRepository` (the grants and their switches, `web-app-suite.md` §8, 2026-10-28).
 *
 * ⚠⚠ **The planner is native's, run in the tab** (`MiaPlugin`): `askMia` goes through the in-process
 * client to the same `AskMiaImplementation` every platform runs — grammar, lowering, the MQ-D10
 * validator and the reads. Nothing is re-implemented on this side but the conversation
 * (`MiaBook.kt` rules 1–4). There is no model (§8 item 8): no inference op is registered, so the
 * grammar's plan is always the plan.
 *
 * ⚠⚠ **Read-only is enforced, not inherent** (MQ-D10), and on web it is enforced twice: the tab's
 * proxy withholds every action (`MiaActionOffer.WITHHELD`, so "turn off the light" / "add lunch …"
 * / "run my routine" answer `noMatch` with nothing resolved), and `confirmMiaAction` is not mapped.
 * This surface writes two things: the conversation, in memory (rule 1), and — since run B — the
 * grant switches native Settings writes ([setGranted] / [setWritable]), the same realm rows through
 * the same `PreferenceService.set`. Nothing else.
 *
 * Every call resolves `{ ok: true, … }` or `{ ok: false, error: { kind, message } }`, `kind` a
 * catalogue key ([MiaWebErrorKeys], or the service error's derived key). ⚠ A failed read is a
 * failure, never an empty list or a default grant set.
 *
 * ⚠⚠ **A turn that could not be asked is still a turn**, as natively: it is recorded with verdict
 * `unavailable` and the [COULD_NOT_LOOK] sentence, and [ask] answers `ok: true` with it. `ok: false`
 * is reserved for a call refused before anything was asked (bad input), which records nothing.
 *
 * **Zones:** `zone` `""` is the browser's own; any other must be an IANA id the bundled database
 * knows, else `error_mia_invalid_zone`. ⚠ The zone (with the clock) is what "tomorrow" and "last
 * week" are resolved against — the reader's, never the realm's (§7.4).
 */
@JsExport
object Mia {

    private val asking = Mutex()

    // ── chat ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Ask one question: `{ ok, conversationId, turn }`, `turn.answer.verdict` one of `answered` |
     * `noMatch` | `unavailable` (`MiaBook.kt` rule 4). Questions are answered one at a time, in the
     * order asked. [utterance] is trimmed; blank or longer than [MAX_UTTERANCE_CHARS] is refused.
     */
    fun ask(utterance: String, zone: String): Promise<dynamic> {
        // ⚠ Captured NOW, before anything suspends: a clear after this call must void it.
        val generation = MiaConversations.generation
        return GlobalScope.promise { askAt(utterance, Clock.System.now().toEpochMilliseconds(), zone, generation) }
    }

    /** [ask] against a given clock — the contract tests' seam. Not exported. */
    internal suspend fun askAt(
        utterance: String,
        nowMs: Long,
        zone: String,
        generation: Long = MiaConversations.generation,
    ): dynamic {
        JsRuntime.awaitReady()
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return miaFailure(MiaWebErrorKeys.EMPTY_UTTERANCE.value, null)
        if (trimmed.length > MAX_UTTERANCE_CHARS) {
            return miaFailure(MiaWebErrorKeys.UTTERANCE_TOO_LONG.value, "${trimmed.length} characters")
        }
        val zoneId = zoneIdOrNull(zone) ?: return miaFailure(MiaWebErrorKeys.INVALID_ZONE.value, zone)

        return asking.withLock {
            if (MiaConversations.generation != generation) return@withLock sessionCleared()
            MiaConversations.setReplying(true)
            try {
                val answer = when (val asked = answerOf(trimmed, nowMs, zoneId)) {
                    is Asked.Refused -> return@withLock asked.failure
                    is Asked.Answer -> asked.answer
                }
                // ⚠⚠ Re-checked after the suspension: the clear may have landed while the proxy answered.
                if (MiaConversations.generation != generation) return@withLock sessionCleared()
                val (conversation, turn) = MiaConversations.append(nowMs, trimmed, answer)
                okResult {
                    it.conversationId = conversation.id
                    it.turn = turn.toJs()
                }
            } finally {
                MiaConversations.setReplying(false)
            }
        }
    }

    private fun sessionCleared(): dynamic = miaFailure(MiaWebErrorKeys.SESSION_CLEARED.value, null)

    private sealed interface Asked {
        class Answer(val answer: MiaAnswerJs) : Asked
        class Refused(val failure: dynamic) : Asked
    }

    /** The proxy's answer, or the could-not-look turn for every way of failing to get one. */
    private suspend fun answerOf(utterance: String, nowMs: Long, zoneId: String): Asked {
        val result = runCatchingCancellable {
            JsRuntime.tenshuClient.getService<MiaService>(MiaService.ID).askMia(utterance, nowMs, zoneId)
        }.getOrNull()
        val answer = (result as? TResult.Success<*>)?.value as? MiaAnswer
            ?: return Asked.Answer(MiaAnswerJs(MiaAnswer.VERDICT_UNAVAILABLE, COULD_NOT_LOOK, emptyArray()))
        // ⚠ Fail closed: an action could only be shown, never confirmed, on web (`MiaPlugin`).
        if (answer.action != null || answer.verdict.get() == MiaAnswer.VERDICT_CONFIRM) {
            return Asked.Refused(miaFailure(MiaWebErrorKeys.UNEXPECTED_ACTION.value, answer.explanation.get()))
        }
        return Asked.Answer(answer.toJs())
    }

    // ── history ───────────────────────────────────────────────────────────────────────────────

    /**
     * `{ ok, conversations, replying }`: the History list, newest first — at most one entry, the
     * session's conversation (`MiaBook.kt` rule 2) — and whether an answer is being composed.
     */
    fun history(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        historyResult(MiaConversations.history.value)
    }

    /**
     * [history], live: re-emits on every turn and every change of `replying`. A conversation's
     * `turnCount` moving is the signal to refetch the head of [turnsPage]. `onError` is terminal.
     */
    fun observeHistory(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            MiaConversations.history.collect { callback(historyResult(it)) }
        }

    /**
     * One keyset page of a conversation's turns, **newest first**: `{ ok, turns, nextCursor }`.
     * [cursor] `null` / `""` is the head, else a previous page's `nextCursor` (opaque); `nextCursor`
     * is `null` after the oldest turn. [pageSize] must be `1..MAX_TURN_PAGE_SIZE`.
     */
    fun turnsPage(conversationId: String, cursor: String?, pageSize: Int): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val before = MiaTurnCursor.decode(cursor)
            ?: return@promise miaFailure(MiaWebErrorKeys.BAD_CURSOR.value, "cursor '$cursor'")
        if (pageSize !in 1..MAX_TURN_PAGE_SIZE) {
            return@promise miaFailure(MiaWebErrorKeys.BAD_PAGE_SIZE.value, "pageSize $pageSize")
        }
        val conversation = MiaConversations.get(conversationId)
            ?: return@promise miaFailure(MiaWebErrorKeys.CONVERSATION_NOT_FOUND.value, conversationId)
        val older = conversation.turns.filter { it.seq < before }
        val page = older.takeLast(pageSize).asReversed()
        val next = if (older.size > pageSize) MiaTurnCursor.encode(page.last().seq) else null
        okResult {
            it.turns = page.map { turn -> turn.toJs() }.toTypedArray()
            it.nextCursor = next
        }
    }

    // ── grants ────────────────────────────────────────────────────────────────────────────────

    /**
     * `{ ok, grants }`: every source the planner may be granted, in `MiaSource` order, with whether
     * Mia may read it now and — where a write exists at all — write it. ⚠ Exactly the proxy's own
     * reading (`readMiaGrants` / `canWrite`): an untouched read grant is **on**, an untouched write
     * grant **off**, and write without read counts as off. These are the switches the planner
     * consults per question, so this view cannot drift from what the next answer does.
     *
     * ⚠ Per service, because that is the only granularity there is (MQ-D6). [setGranted] /
     * [setWritable] change one, as native Settings does; a change on any device syncs here.
     */
    fun grants(): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        grantsResult(realmSettingsRows())
    }

    /** [grants], live: a switch flipped on any device re-emits. `onError` is terminal. */
    fun observeGrants(callback: (dynamic) -> Unit, onError: (String) -> Unit): () -> Unit =
        observeJob(onError) {
            // ⚠ Seeded by a read: the relation's stream is silent on a realm that never held a
            // setting, and the default grants are an answer, not a wait (`Contacts.observeOwnProfile`).
            realmSettingsFlow()
                .onStart { emit(realmSettingsRows()) }
                .collect { callback(grantsResult(it)) }
        }

    /**
     * Native Settings' read switch (`MiaGrantsRepository.setGranted`, `web-app-suite.md` §8, decided
     * 2026-10-28): writes [source]'s realm grant row through the same `PreferenceService.set` native
     * calls, then answers `{ ok, grants }` — the whole set read back AFTER the write, so the switch
     * the app draws is the proxy's reading, never its own optimism. ⚠ Realm-scoped like natively:
     * turning a source off here turns it off on every device, and the next question on any of them
     * that needs it is refused by name (`noMatch`).
     *
     * ⚠ A failed write is `{ ok: false }` with the service error's key — never a silent no-op that
     * leaves the user believing a source is revoked when it is not (the native KDoc's warning). An
     * unknown [source] is `error_mia_unknown_source`, and nothing is written. `{ ok: false }` always
     * means nothing changed; a write that landed but could not be read back is `{ ok: true,
     * grants: null }` (re-read; the push follows).
     */
    fun setGranted(source: String, granted: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val miaSource = sourceOrNull(source)
            ?: return@promise miaFailure(MiaWebErrorKeys.UNKNOWN_SOURCE.value, source)
        writeGrant(miaSource.preference, granted)
    }

    /**
     * Native Settings' write switch (`MiaGrantsRepository.setWritable`): the `read + write` tier of a
     * source that has one (only `CALENDAR`). `{ ok, grants }` as [setGranted].
     *
     * ⚠⚠ It changes what NATIVE Mia may propose, not what a tab can do: every action stays withheld
     * here (`MiaPlugin`), and a native write still needs MQ-D7's confirmation tap on that device.
     * ⚠ Native skips a source with no write tier silently; a bridge call cannot be answered with a
     * silent success, so that is `error_mia_no_write_grant` here, and nothing is written. Write
     * without read is stored as asked and read as off (`GrantRows`), exactly as natively — the app
     * disables the line instead, as native Settings does.
     */
    fun setWritable(source: String, granted: Boolean): Promise<dynamic> = GlobalScope.promise {
        JsRuntime.awaitReady()
        val miaSource = sourceOrNull(source)
            ?: return@promise miaFailure(MiaWebErrorKeys.UNKNOWN_SOURCE.value, source)
        val preference = miaSource.writePreference
            ?: return@promise miaFailure(MiaWebErrorKeys.NO_WRITE_GRANT.value, source)
        writeGrant(preference, granted)
    }

    private fun sourceOrNull(name: String): MiaSource? = MiaSource.entries.firstOrNull { it.name == name }

    private suspend fun writeGrant(preference: Preference<Boolean>, granted: Boolean): dynamic {
        val written = JsRuntime.tenshuClient.preferenceService.set(preference, granted)
        if (written is TResult.Failure) return miaFailure(written.error)
        // ⚠ The write landed. A failed read-back must not answer `{ ok: false }`: that would tell the
        // app nothing changed while the grant did. Answer ok with `grants: null` ("written, not
        // confirmed"); the app re-reads, and `observeGrants` pushes the real set anyway.
        return when (val rows = realmSettingsRows()) {
            is Read.Ok -> grantsResult(rows)
            is Read.Failed -> okResult { it.grants = null }
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────────────────────

    private fun historyResult(state: MiaHistoryState): dynamic = okResult {
        it.conversations = state.conversations.toTypedArray()
        it.replying = state.replying
    }

    private fun grantsResult(rows: Read<GrantRows>): dynamic = when (rows) {
        is Read.Failed -> miaFailure(rows.error)
        is Read.Ok -> okResult { it.grants = rows.value.grants().toTypedArray() }
    }

    private fun zoneIdOrNull(zone: String): String? =
        if (zone.isBlank()) TimeZone.currentSystemDefault().id
        else runCatching { TimeZone.of(zone) }.getOrNull()?.id

    /**
     * Runs one push stream. ⚠ [onError] is terminal and fires for every end but the subscriber's
     * own cancel — a throw, or a flow that completed (the `Contacts.observeJob` contract).
     */
    private fun observeJob(onError: (String) -> Unit, body: suspend () -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            val failure = try {
                JsRuntime.awaitReady()
                body()
                MIA_OBSERVE_ENDED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            onError(failure)
        }
        return { job.cancel() }
    }
}

/** The terminal diagnostic for a Mia observe stream that completed rather than failed. */
internal const val MIA_OBSERVE_ENDED = "mia stream ended"

internal fun miaFailure(kind: String, message: String?): dynamic = failureResult(kind, message)

internal fun miaFailure(error: TError): dynamic = failureResult(error.errorCopyKey().value, error.toString())

// ── the grants, as the proxy reads them ─────────────────────────────────────────────────────────

/** The realm's preference rows the grants are read from, once. */
internal class GrantRows(private val read: (Preference<Boolean>) -> Boolean) {
    /**
     * ⚠⚠ Mirrors `readMiaGrants` + `canWrite` (`ProxyMiaGrants.kt`) rule for rule — a view that
     * disagreed with the proxy would tell the user a source is off while Mia reads it.
     */
    fun grants(): List<MiaGrantJs> = MiaSource.entries.map { source ->
        val readGranted = read(source.preference)
        MiaGrantJs(
            source = source.name,
            read = readGranted,
            write = source.writePreference?.let { readGranted && read(it) },
        )
    }
}

private fun grantRowsOf(result: TResult<TList<RealmSetting>>): Read<GrantRows> = result.fold(
    onSuccess = { list ->
        val rows = list.toList()
        Read.Ok(
            GrantRows { pref ->
                rows.firstOrNull { it.preferenceId.get() == pref.id }?.let { pref.adapter.fromData(it.value) }
                    ?: pref.default
            },
        )
    },
    onFailure = { Read.Failed(it) },
)

/** The realm's settings, once. ⚠ Not the client's `readOnce`, which folds a failed read into the default. */
private suspend fun realmSettingsRows(): Read<GrantRows> =
    grantRowsOf(JsRuntime.tenshuClient.preferenceService.realmSettings.getAll())

/** One relation stream for every grant, so each emission is a whole, consistent set. */
private fun realmSettingsFlow() =
    JsRuntime.tenshuClient.preferenceService.realmSettings.flow().map(::grantRowsOf)
