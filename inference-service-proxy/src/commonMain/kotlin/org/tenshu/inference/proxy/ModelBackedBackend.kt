package org.tenshu.inference.proxy

import org.tenshu.inference.ModelProvenance

/**
 * A backend that runs exactly one pinned model and can therefore say which one produced its output.
 *
 * ⚠⚠ What a device-local store's stamp must come from (IN-6 F2). `MediaIndexer`'s fallback — the
 * backend's id at version 1 — is the same string for every checkpoint a backend ever loads, so a
 * store stamped with it cannot notice a model swap and would mix two embedding spaces.
 */
interface ModelBackedBackend {
    val provenance: ModelProvenance
}
