package org.tenshu.sync.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash

/**
 * Pre-EI-1 **local** naming, for a harness that has no realm to derive `K_entity` from.
 *
 * ⚠⚠ Passed explicitly rather than defaulted, which is the whole point of `wireIdFor` having no
 * default: a test using this is making a visible choice, and what it is choosing is *"this harness
 * does not exercise wire naming."* Such a test must not be read as covering the relay-visible name.
 *
 * ⚠ A harness that DOES have a realm should pass `{ WireIdResolver(syncManager).of(it) }` instead —
 * otherwise its registry names entities one way while its processor names them another, and the
 * failure is not an assertion but a resurrected row.
 */
val localTestNaming: (DataId) -> String = { it.getDataIdHash().toString() }
