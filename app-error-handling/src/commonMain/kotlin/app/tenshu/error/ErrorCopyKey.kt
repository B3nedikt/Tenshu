package app.tenshu.error

import org.tenshu.core.TError

/**
 * The catalogue key for a typed error's user-facing copy.
 *
 * `core-error-copy-catalogue.md` §5.0 is the rule this type exists to serve: **no proxy code ever
 * creates a localised string.** A user-facing string is created on the app side of the proxy
 * boundary — at the ViewModel layer or above — and below that line only typed values travel. On
 * Android that is a process and package fact, not a layering preference: the `*-service-proxy`
 * modules load into Control's process while (say) `drive-shared-logic` renders in Drive's, so a
 * sentence authored below this line is one app writing another app's copy.
 *
 * An [ErrorCopyKey] therefore carries **no copy**. It is the *name* of a catalogue entry, derived
 * mechanically from the error's schema id, and each renderer owns its own key → copy table in its
 * own catalogue format (§5.5): Compose resources in `:ui-core`, a `when` in `tenshu-sdk-ios-core`, an
 * i18next catalogue on the web. That is what keeps this type legal below the app layer — it is a
 * pure string transform with nothing translatable in it.
 *
 * ⚠ Keys are **derived, not tabulated** (§5.4, ADR 0016 anchor #8: keys chosen now should make the
 * n=2 catalogue merge mechanical rather than a rewrite). The cost of derivation over an explicit
 * table is that an id with no catalogue entry silently resolves to the renderer's fallback where a
 * `when` over known ids would have been exhaustive. `:ui-core`'s `ErrorCopyCatalogueTest` buys that
 * back — see its KDoc for what it does and does not cover.
 *
 * ⚠ **A `data class`, not a `@JvmInline value class`**, though the single-field wrapper is exactly
 * the value-class shape. `:core` is `export()`ed from every iOS app framework, and Kotlin/Native mangles
 * the names of declarations whose signatures mention an inline class — so [errorCopyKey] would
 * reach Swift under a name nobody would guess. `MapPalette`'s KDoc in `:basemap-core` records the
 * same trade-off and rejected the value class for the same reason.
 */
data class ErrorCopyKey(val value: String) {

    /**
     * A last-resort English label derived from the key itself — `error_not_found` → `"Not found"`.
     *
     * ⚠⚠ **Developer surfaces only** (Settings → Errors). It leaks the id vocabulary into the UI,
     * which is precisely what §5.0 exists to stop on a user-facing surface. A user-facing renderer
     * that misses the catalogue must show a generic sentence instead, never this and never the
     * error's own `message` — see `ErrorCopyKey.label()` in `:ui-core`.
     */
    fun humanised(): String = value
        .removePrefix(PREFIX)
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    companion object {
        const val PREFIX: String = "error_"

        /**
         * The generic sentence every renderer falls back to on a catalogue miss. Named here so the
         * three renderers agree on which entry that is without sharing a table.
         */
        val UNEXPECTED: ErrorCopyKey = ErrorCopyKey("${PREFIX}unexpected")

        /**
         * Control is not running, so the action could not be attempted at all.
         *
         * ⚠ Not derived from a `TError` — there is no error; the app knows locally that the proxy
         * is absent. ADR 0025 owns the *destination* for that state (the reachability banner and
         * its four reasons); this key exists only so a carrier that must report a failed action
         * can name honest copy instead of inventing a sentence.
         */
        val CONTROL_NOT_RUNNING: ErrorCopyKey = ErrorCopyKey("${PREFIX}control_not_running")

        /**
         * This device cannot get a file's **contents** — the bytes are not local and the fetch
         * came back with nothing.
         *
         * ⚠ Same shape as [CONTROL_NOT_RUNNING]: not derived from a `TError`, because there is no
         * error to derive from. An absent manifest is what a reclaimed content, an upload still in
         * flight, an unpushed peer and a transient relay failure all look like, and the client
         * cannot tell them apart (`drive-upload-stop-and-reclamation.md` §4.19).
         *
         * ⚠⚠ Which is why the copy must **not** claim the loss is permanent. Three of those four
         * causes resolve themselves; "no longer available" would be wrong for them, and telling a
         * user their file is gone when it is merely late is the failure this key exists to stop.
         * The permanent wording waits on §4.19's D10 signal.
         */
        val CONTENT_UNAVAILABLE: ErrorCopyKey = ErrorCopyKey("${PREFIX}content_unavailable")

        /**
         * A contact invite's reply is not the one its prompt showed — somebody else answered the
         * link as well — so the accept was refused and nothing was saved (threat model T-CI-10).
         *
         * ⚠ Minted, not derived: the proxy raises it as an `InvalidParameter` (no new composite,
         * so no service bump), whose derived key is the generic sentence. The repository that
         * calls `CompleteContactHandshake` recognises it with `CompleteContactHandshake.Refusal`
         * and names this key instead, because the remedy — decline and send a new invite — is
         * the whole point of telling the user.
         */
        val INVITE_RESPONSE_CHANGED: ErrorCopyKey = ErrorCopyKey("${PREFIX}invite_response_changed")

        /**
         * A "codes match" confirmation arrived for an invite no code has been shown for yet. Same
         * carrier as [INVITE_RESPONSE_CHANGED]; "not yet", because the code appears once the
         * invite's reply has been picked up.
         */
        val INVITE_CODE_NOT_SHOWN: ErrorCopyKey = ErrorCopyKey("${PREFIX}invite_code_not_shown")

        /**
         * Keys minted by app code for states that are not `TError`s.
         *
         * ⚠⚠ Declared here rather than written as literals at call sites so the catalogue guard can
         * still tell a deliberate key from a typo: `ErrorCopyParityTest` treats a catalogue entry
         * as legitimate when some error id derives it **or** it appears in this set. A key spelled
         * only in `strings.xml` remains a failure, which is the case that guard exists for.
         */
        val MINTED: Set<ErrorCopyKey> = setOf(
            UNEXPECTED,
            CONTROL_NOT_RUNNING,
            CONTENT_UNAVAILABLE,
            INVITE_RESPONSE_CHANGED,
            INVITE_CODE_NOT_SHOWN,
        )
    }
}

/**
 * The catalogue key for this error, derived from its schema id.
 *
 * The rule, applied to the id: take everything after the last `/error/` segment (an id with no
 * such segment falls back to its final segment), flatten `/` and `-` to `_`, split camelCase, and
 * prefix `error_`. So:
 *
 * ```
 * tenshu/result/error/notFound                  -> error_not_found
 * tenshu/core/error/dispatch/missing-parameter  -> error_dispatch_missing_parameter
 * tenshu/file/system/error/alreadyExists        -> error_already_exists
 * tenshu/storage/error/no-storage               -> error_no_storage
 * ```
 *
 * ⚠ The key mirrors the id, warts included — `tenshu/storage/error/insufficient` derives
 * `error_insufficient`, which reads oddly on its own. That is an argument for naming error ids
 * well, not for hand-writing keys: a hand-written key is a second name to keep in sync, and the
 * n=2 merge (§5.4) needs the two worlds' keys to line up mechanically.
 *
 * ⚠ Two ids whose tails collide would derive the same key. None do today, and
 * `ErrorCopyCatalogueTest` fails the moment two would.
 */
fun errorCopyKeyForId(errorTypeId: String): ErrorCopyKey {
    val tail = errorTypeId.substringAfterLast("/error/", missingDelimiterValue = "")
        .ifEmpty { errorTypeId.substringAfterLast('/') }
    val flattened = tail.replace('/', '_').replace('-', '_')
    return ErrorCopyKey(ErrorCopyKey.PREFIX + flattened.splitCamelCase())
}

/** The catalogue key for this error. See [errorCopyKeyForId] for the derivation rule. */
fun TError.errorCopyKey(): ErrorCopyKey = errorCopyKeyForId(schema.id.get())

/**
 * `alreadyExists` -> `already_exists`. Leaves an already-snake_case tail alone, so the rule is
 * idempotent over ids written in either style (both appear in the taxonomy today).
 */
private fun String.splitCamelCase(): String = buildString {
    for (c in this@splitCamelCase) {
        if (c.isUpperCase()) {
            if (isNotEmpty() && last() != '_') append('_')
            append(c.lowercaseChar())
        } else {
            append(c)
        }
    }
}
