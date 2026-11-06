package app.tenshu.fit.data

/**
 * The Fit data layer's **diagnostic log seam**. The import chain (acquire → map → write → project)
 * crosses a platform boundary, an IO boundary, and a schema boundary, and every stage used to
 * swallow its failures (`runCatching { … }.getOrDefault(emptyList())`) — so a broken import was
 * indistinguishable from "the source has no data". These logs make each stage's outcome observable.
 *
 * A local `expect`/`actual` rather than a shared facade: the platform-wide `Log` facade
 * (`docs/proposals/core-logging-facade.md`) is designed but not built, and this module must not
 * grow a dependency on `:logging-service` (it is a data layer, not a proxy). When the facade lands,
 * this collapses into it.
 *
 * ⚠ **A34 (GDPR Art. 9).** Health data is special-category. These logs are deliberately
 * **count-level**: record counts, type names, source package names, permission names, failure
 * causes. They must never carry sample *values* (a heart rate, a weight, a route point) — logcat is
 * readable by the user, but it is also the least-controlled sink we have.
 */
internal expect fun fitLog(message: String, error: Throwable? = null)

/** The tag every Fit-import log line carries — `adb logcat -s TenshuFit` to watch an import. */
internal const val FIT_LOG_TAG: String = "TenshuFit"
