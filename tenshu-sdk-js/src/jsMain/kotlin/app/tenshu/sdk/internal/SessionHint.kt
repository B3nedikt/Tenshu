package app.tenshu.sdk.internal

/**
 * A runtime-free copy of what `Session.detect()` would answer, in `localStorage`.
 *
 * ⚠⚠ Why it exists: apex takes the runtime **lazily** (`docs/web/architecture.md` "Cross-tab") —
 * a public page must not start `JsRuntime`, because starting it takes the one-per-origin
 * `tenshu-runtime` lock. But the landing page still wants to greet a returning visitor ("signed in
 * as …", "Open Tenshu"). It reads this hint (`web/apex/src/lib/sessionHint.ts`) instead of probing
 * the session store.
 *
 * ⚠ A hint, never an authority: it only picks a CTA label. Following the CTA reaches a route that
 * starts the runtime and runs the real `detect()`, which re-states the hint. So a stale hint costs
 * one wrong label, never access. Holds the device name and token expiry only — no key material.
 */
internal object SessionHint {
    const val KEY = "tenshu.session.hint"

    fun write(deviceName: String?, tokenExpiresAtMs: Double?) {
        val storage: dynamic = js("(typeof localStorage !== 'undefined') ? localStorage : null")
        if (storage == null) return
        try {
            if (deviceName == null) {
                storage.removeItem(KEY)
            } else {
                val hint: dynamic = js("({})")
                hint.deviceName = deviceName
                hint.expiresAtMs = tokenExpiresAtMs ?: 0.0
                storage.setItem(KEY, JSON.stringify(hint))
            }
        } catch (_: Throwable) {
            // Blocked or full storage: the landing falls back to the first-time CTA. Never fatal.
        }
    }
}
