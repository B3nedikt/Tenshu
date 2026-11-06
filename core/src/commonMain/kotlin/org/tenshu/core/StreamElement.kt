package org.tenshu.core

import org.tenshu.core.entity.DataId
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.field
import org.tenshu.core.schema.int
import org.tenshu.core.schema.type
import org.tenshu.core.schema.typeVar

sealed interface StreamElement<T : Data> : Composite {

    val streamId: TInteger
        get() = getField(Fields.STREAM_ID)

    interface Value<T : Data> : StreamElement<T> {

        override val schema: CompositeSchema
            get() = Schema

        val payload: T
            get() = getField(Fields.PAYLOAD)

        companion object {
            const val ID = "tenshu/core/stream/element/value"

            val Schema = composite(
                id = ID,
                parent = StreamElement.Schema,
                typeParams = { type("E") },
                fields = {
                    field(Fields.PAYLOAD, typeVar("E"))
                }
            )
        }

        object Fields {
            const val PAYLOAD = "$ID/payload"
        }
    }

    interface Removed<T : Data> : StreamElement<T> {

        override val schema: CompositeSchema
            get() = Schema

        val removedId: DataId
            get() = getField(Fields.REMOVED_ID)

        companion object {
            const val ID = "tenshu/core/stream/element/removed"

            val Schema = composite(
                id = ID,
                parent = StreamElement.Schema,
                // Removed<T> carries T to keep the StreamElement<T> umbrella
                // consistent; `removedId` is type-independent.
                typeParams = { type("E") },
                fields = {
                    composite(
                        id = Fields.REMOVED_ID,
                        type = DataId.Schema
                    )
                }
            )
        }

        object Fields {
            const val REMOVED_ID = "$ID/removedId"
        }
    }

    interface End<T : Data> : StreamElement<T> {

        override val schema: CompositeSchema
            get() = Schema

        val error: TError?
            get() = getOptionalField(Fields.ERROR)

        object Fields {
            const val ERROR = "$ID/error"
        }

        companion object {
            const val ID = "tenshu/core/stream/element/end"

            val Schema = composite(
                id = ID,
                parent = StreamElement.Schema,
                // End<T> carries T for umbrella consistency; the optional `error`
                // is type-independent.
                typeParams = { type("E") },
                fields = {
                    composite(
                        id = Fields.ERROR,
                        type = TError.Schema,
                        isOptional = true
                    )
                }
            )
        }
    }

    object Fields {
        const val STREAM_ID = "$ID/streamId"
    }

    companion object {
        const val ID = "tenshu/core/stream/element"

        val Schema = composite(
            id = ID,
            typeParams = { type("E") },
            fields = {
                int(id = Fields.STREAM_ID)
            }
        )
    }
}
