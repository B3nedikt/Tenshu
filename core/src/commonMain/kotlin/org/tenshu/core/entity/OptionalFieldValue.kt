package org.tenshu.core.entity

/**
 * Read a field off an [Entity] that may be absent, or `null`.
 *
 * ⚠ **Prefer a nullable accessor.** [Entity.getOptionalFieldValue] is now on the interface, so a
 * field that may legitimately be missing should declare that once, in its accessor
 * (`val Entity<out Place>.city: TString? get() = getOptionalFieldValue(...)`), rather than at every
 * call site. This remains for readers that hold a field id rather than a type — the generic
 * `is Entity<*> -> …` folds in `:file-service-proxy` — and for the belt-and-braces `runCatching`,
 * which covers an [Entity] implementation that resolves a missing field by throwing rather than by
 * returning null.
 *
 * ## ⚠⚠ Why this exists instead of `runCatching { getFieldValue<T>(ref).get() }`
 *
 * That idiom is a **null dereference on Kotlin/Native**, and `runCatching` cannot save it.
 *
 * [Entity.getFieldValue] is declared `fun <T> getFieldValue(reference: String): T` and every
 * implementation ends in an **unchecked cast** of a map lookup (`fields[ref] as T`). An absent
 * optional therefore yields `null` in a slot the caller has declared non-null. On the JVM the cast
 * is erased, the null survives to the `.get()`, and the resulting `NullPointerException` lands in
 * `runCatching` — so the idiom reads as correct and its tests pass. On Kotlin/Native the same
 * `.get()` dereferences address `0x0`: **`EXC_BAD_ACCESS` / `SIGSEGV`, which no `catch` can
 * intercept** and which takes the whole process with it.
 *
 * Measured 2026-08-26: `fit-shared-data`'s `DataSourceReadTest` segfaulted the iOS test binary at
 * `optionalLong` → `TInteger#get` → `Kotlin_Any_getTypeInfo`, `KERN_INVALID_ADDRESS at 0x0`. The
 * same shape appears in `file-service-proxy`, which runs **in the iOS app** under route 1, so this
 * is a live crash on device and not merely a test problem.
 *
 * ## The shape that is safe
 *
 * Ask for a **nullable** `T` so the cast can legally produce null, and let the caller's `?.` do the
 * check. The `runCatching` is kept because it is still load-bearing for a *genuine* throw — not
 * every [Entity] implementation resolves a missing field by returning null.
 *
 * ```kotlin
 * val bytes: Long? = entity.optionalFieldValue<TInteger>(Fields.BYTE_COUNT)?.get()
 * ```
 */
fun <T : Any> Entity<*>.optionalFieldValue(reference: String): T? =
    runCatching { getOptionalFieldValue<T>(reference) }.getOrNull()
