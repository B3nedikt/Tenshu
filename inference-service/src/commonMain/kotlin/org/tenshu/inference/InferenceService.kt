package org.tenshu.inference

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * The inference service (`inference-service.md` IN-D12) — the owner of **derived rows**.
 *
 * ⚠ `:file-service` stays free of ML concepts: a file does not know what a model thinks of it, and
 * the labels live here precisely so improving a model never reshapes the platform's hottest
 * composite.
 *
 * ⚠ Only labels are synced state. Embeddings, templates and the models themselves are device-local
 * (IN-D4/IN-D5) and appear nowhere in this schema.
 */
interface InferenceService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Everything any model has said about the user's files. */
    val labels: TList<MediaLabel>

    companion object {
        const val ID = "tenshu/inference/service"

        val Schema: ServiceSchema = service(
            id = ID,
            // v1 = release baseline (`core-schema-evolution.md` Appendix A).
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.LABELS,
                    type = TList.typeOf(MediaLabel.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val LABELS = "$ID/labels"
    }
}
