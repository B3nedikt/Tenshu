package org.tenshu.inference

/**
 * The label an index pass writes to record *that it ran*, independent of what it found
 * (`inference-service.md` §12.4).
 *
 * ⚠ A sentinel rather than a second row type, because [MediaLabel]'s key already gives one row per
 * `(file, capability, label)` — and a parallel "index state" type would be a second thing to keep
 * in step with the first.
 *
 * ⚠⚠ **Every reader has to exclude it, and this is why it lives here rather than beside the pass
 * that writes it.** It began in `:inference-service-proxy`, which no client module may depend on —
 * so the readers who most need it (Photos' label search, Photos' label collections) could not see
 * it, and `TenshuPhotosRepository.searchByLabel` shipped without the exclusion as a result: a
 * `contains` search for "ind" would have returned the user's **entire indexed library**, every hit
 * captioned `__indexed`. Invisible while no device has a backend (IN-3b), and wrong the day one
 * does. Machinery indistinguishable from data at the read side is machinery that leaks into the UI.
 */
const val INDEX_MARKER_LABEL: String = "__indexed"
