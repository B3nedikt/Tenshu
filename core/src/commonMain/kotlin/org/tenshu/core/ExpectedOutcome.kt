package org.tenshu.core

/**
 * A [TError] that is part of an operation's ordinary contract rather than a fault — so the
 * proxy-side diagnostic recorder must **not** mint an `ErrorEvent` for it.
 *
 * ⚠⚠ **Why this exists, measured.** On a real device most of the error log was `alreadyExists`
 * from the Synced-Folders background upload — whose own contract documents that collision as the
 * dedup backstop, resolving *silently* to `Skipped`. Those events carry no message, only a device
 * id. They are not diagnostics; they are the feature working.
 *
 * ⚠ The cost of that noise is not disk: a real fault that throws on every call is invisible in a
 * log where most entries are expected outcomes. An error log nobody can read is an error log
 * nobody reads.
 *
 * ⚠ Marking a type here changes **nothing** about how it travels or how callers handle it: it is a
 * code-side marker, never serialised, and the typed failure reaches the caller unchanged. The only
 * effect is on the diagnostic stream.
 *
 * The bar for adding a type: *is a caller contractually expected to handle this, such that seeing
 * one tells a maintainer nothing is wrong?* `alreadyExists` qualifies — a conflicting upload is
 * answered by a conflict dialog or a silent skip, both designed. `internal` never qualifies.
 */
interface ExpectedOutcome : TError
