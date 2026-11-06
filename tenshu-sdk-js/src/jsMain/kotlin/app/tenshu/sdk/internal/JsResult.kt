package app.tenshu.sdk.internal

/**
 * The `{ ok, error: { kind, message? } }` shape the SDK's fallible surfaces answer with.
 *
 * ⚠⚠ Exists because the alternative — `Promise<T?>` — cannot say why. A nullable return forces a
 * failure and a legitimate absence into the same value, so the caller has to guess, and every
 * caller guesses "absent". That is not a hypothetical: `ContentStream.openStream` returned
 * `T?`, its `onFailure` had nowhere to put the error, and W7's streaming download therefore
 * fell back to a whole-file read on **every** file for weeks while looking like it worked
 * (`tech-debt.md`, 2026-09-01).
 *
 * ⚠ `T?` is still right where null has exactly ONE meaning — `SyncHealth.status()` is null until
 * the first tick has produced a row, and cannot fail. The rule is not "never return null", it is
 * "never return null for two different reasons".
 *
 * ⚠ Three private copies of `errorResult` already exist (Devices, Recovery, Pairing). This is the
 * shared one; folding those into it is a follow-up, not a reason to write a fourth.
 */
internal fun okResult(build: (dynamic) -> Unit = {}): dynamic {
    val ok: dynamic = js("({})")
    ok.ok = true
    build(ok)
    return ok
}

/**
 * [kind] is for branching, [message] for humans. Both are kept — a kind alone cannot be
 * diagnosed and a message alone cannot be switched on.
 */
internal fun failureResult(kind: String, message: String?): dynamic {
    val err: dynamic = js("({})")
    err.ok = false
    val errorObj: dynamic = js("({})")
    errorObj.kind = kind
    if (message != null) errorObj.message = message
    err.error = errorObj
    return err
}
