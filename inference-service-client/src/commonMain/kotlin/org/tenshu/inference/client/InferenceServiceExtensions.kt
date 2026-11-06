package org.tenshu.inference.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.inference.InferenceService

/**
 * ⚠ Added when Photos' search became the **first client-side consumer** of this service
 * (`photos-app.md` S2). Everything before it reached the service proxy-side, through Mia's own
 * lowering — so the client Ro shipped with IN-4b but nothing had ever asked a `TenshuClient` for
 * it.
 */
val TenshuClient.inferenceService: InferenceService
    get() = getService(InferenceService.ID)
