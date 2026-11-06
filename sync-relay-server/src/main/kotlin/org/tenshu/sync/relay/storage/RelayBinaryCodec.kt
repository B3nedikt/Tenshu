package org.tenshu.sync.relay.storage

import org.tenshu.core.Data
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RoProvider
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule

/**
 * Binary (de)serialization for the wire `Data` types the durable backends persist —
 * `SignedHlcTip` (Phase 2b), `SyncBlock`/`DeleteBlock` (next slice). Mirrors the
 * registry/context/serializer/parser construction in `SyncRouting` exactly, so persisted
 * bytes are wire-format-identical and round-trip through the same parser the live endpoints
 * use. Built once at startup and shared by the SQLite stores that hold wire types.
 *
 * The relay never decrypts payloads — this only frames opaque envelopes/ciphertext for
 * storage (House Rule #1 intact).
 */
class RelayBinaryCodec {

    private val context = object : TContext {
        override var components: Map<String, TComponent> = emptyMap()
    }

    private val registry = createDefaultSchemaRegistry(
        tContextProvider = { context },
        extraConfigs = listOf(
            coreCommonModule,
            syncServiceCommonModule,
            identityServiceCommonModule,
        ),
    )

    init {
        context.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry,
            RoProvider.COMPONENT_ID to registry,
            OperationResolver.COMPONENT_ID to registry,
        )
    }

    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    fun serialize(data: Data): ByteArray = serializer.serialize(data)

    // Non-reified overload: the serialized envelope carries its own composite type id, so
    // the parser infers the concrete type from the wire (expectedType = null).
    fun <T : Data> parse(bytes: ByteArray): T = parser.parse(bytes, expectedType = null)
}
