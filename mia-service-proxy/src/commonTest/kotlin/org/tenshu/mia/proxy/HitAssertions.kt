package org.tenshu.mia.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.core.get
import org.tenshu.mia.MiaHit

/**
 * Reading a citation's address in tests (`control-cross-app-open.md` TT-1c).
 *
 * ⚠ These exist because `MiaHit` traded a `kind` string and a `reference` string for one [DataId],
 * and the assertions that used to read two flat fields now have to unwrap one. Keeping the unwrap
 * in one place is what stops a test from quietly asserting `toString()` and passing forever.
 */

/** The owning schema's type id — `tenshu/file`, `tenshu/place/place`, … */
val MiaHit.targetType: String get() = target.type.get()

/**
 * The single key a simply-addressed row carries.
 *
 * ⚠ Empty for a multi-part id — a calendar occurrence is `(eventId, recurrenceId)` and has no
 * single key. Assert those with [occurrenceParts] instead, so a test cannot pass by comparing two
 * empty strings.
 */
val MiaHit.targetKey: String
    get() = (target as? DataId.StringDataId)?.keyValue?.get().orEmpty()
