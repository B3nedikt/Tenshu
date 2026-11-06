package org.tenshu.systemtest

import app.tenshu.control.bridge.FileProviderBridgeState
import app.tenshu.control.bridge.registerFileMethods
import app.tenshu.control.bridge.registerFileWriteMethods
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.search
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.transport.StreamFramer
import org.tenshu.core.entity.DataId
import org.tenshu.core.equalTo
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.path
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.file.client.fileService
import org.tenshu.sync.approvePairingRequest
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getPairingStatus
import org.tenshu.sync.getPendingPairingRequests
import org.tenshu.sync.initiatePairing
import org.tenshu.sync.startPairing
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-device coverage of the macOS Drive File Provider's daemon-side write path:
 * the **real** `NativeBridgeServer` + `registerFileWriteMethods` (control-shared),
 * driven exactly as the `.appex` drives it (JSON over a Unix-domain socket), but
 * backed by the production-shaped two-device proxy stack + real relay
 * (`:sync-relay-server` in-process). Proves a bridge `createFile` on the **host**
 * propagates the file entity to a paired **joiner** through the relay, and that the
 * joiner can then OnDemand-fetch the bytes the bridge wrote.
 *
 * Stronger than the in-process round-trip tests in `:drive-shared`
 * (`FileBridgeRoundTripTest` / `FileWriteBridgeRoundTripTest`), which prove the
 * bridge against a single in-process client but no sync. The pairing + OnDemand
 * shape mirrors [FileBytesOnDemandSystemTest].
 */
class NativeBridgeServerSystemTest {

    @Test
    fun bridgeWriteOnHostPropagatesToJoiner() = runBlocking {
        val system = TestSystem.builder().build()
        // Short /tmp path — macOS Unix-domain sun_path caps at ~104 chars.
        val tempDir = Files.createTempDirectory(Path.of("/tmp"), "br")
        try {
            val host = system.client("host", TestClientRole.RealmHost, pollingIntervalMs = 1_000)
            val joiner = system.client("joiner", TestClientRole.JoiningDevice, pollingIntervalMs = 1_000)

            // --- Pair the two devices (same flow as FileBytesOnDemandSystemTest) ---
            host.bootstrapAsHost(deviceName = "Host")
            host.tenshuClient.syncService.startPairing()
            val parsed = PairingUri.parse(host.syncManager.getPairingString()!!)
            joiner.tenshuClient.syncService.initiatePairing(
                ownerId = parsed.ownerId,
                pairingSecret = parsed.secret,
                deviceName = "Joiner",
                otherDevicePublicKey = parsed.hostPairingPublicKey,
            )
            val request = host.tenshuClient.syncService.getPendingPairingRequests()
                .let { it.valueOrFail("getPendingPairingRequests")!!.toList().single() }
            host.tenshuClient.syncService.approvePairingRequest(request.deviceId.get())
            system.advanceUntil(timeout = 30.seconds) {
                val status = joiner.tenshuClient.syncService.getPairingStatus(
                    pairingSecret = parsed.secret,
                    otherDevicePublicKey = parsed.hostPairingPublicKey,
                )
                status is TResult.Success && status.value?.get() == true
            }

            // --- Start the REAL bridge on the host's koin (as control-desktop does) ---
            val socketPath = tempDir.resolve("bridge.sock").toString()
            NativeBridgeServer(socketPath).apply {
                val state = FileProviderBridgeState()
                registerFileMethods(host.koinApp.koin, state)
                registerFileWriteMethods(host.koinApp.koin, state)
                start()
            }

            // --- Drive a write through the bridge, exactly as the .appex would ---
            val filePath = "/bridged.txt"
            val payload = "hello from the bridge".encodeToByteArray()
            connect(socketPath).use { ch ->
                val reply = call(ch, "fileProvider.v2.createFile", write(filePath, payload))
                assertTrue(reply.contains("\"name\":\"bridged.txt\""), "createFile reply: $reply")
                assertTrue(reply.contains("\"size\":${payload.size}"), "createFile size: $reply")
            }

            // --- The file entity propagates host → relay → joiner ---
            system.advanceUntil(timeout = 30.seconds) {
                joiner.executionContext.database.getData(
                    DataId.StringDataId.create(type = File.ID, keyValue = filePath)
                ) != null
            }

            // --- The joiner can OnDemand-fetch the bytes the bridge wrote ---
            joiner.tenshuClient.fileService.defaultFileSystem.root
                .files
                .select {
                    fields(File.Field.PATH)
                    relations(File.Data)
                }
                .search<File> { this.path equalTo filePath }
                .resolve()

            val localBytes = joiner.fileDatabase.readBytes(filePath)?.getOrNull()
            assertTrue(
                localBytes != null && payload.contentEquals(localBytes),
                "joiner should have the bridge-written bytes via OnDemand for $filePath",
            )
        } finally {
            system.shutdown()
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun write(path: String, bytes: ByteArray): String =
        """{"parentId":"root","name":"${path.removePrefix("/")}","data":"${Base64.getEncoder().encodeToString(bytes)}"}"""

    private fun call(ch: SocketChannel, method: String, params: String = "{}"): String {
        val req = """{"v":1,"id":"s","method":"$method","params":$params}""".encodeToByteArray()
        ch.write(ByteBuffer.wrap(StreamFramer.frame(req)))
        return readFrame(ch)
    }

    private fun connect(socketPath: String): SocketChannel {
        val address = UnixDomainSocketAddress.of(socketPath)
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                ch.connect(address); return ch
            } catch (e: Exception) {
                ch.close(); Thread.sleep(20)
            }
        }
        throw AssertionError("could not connect to bridge socket")
    }

    private fun readFrame(ch: SocketChannel): String {
        val header = ByteBuffer.allocate(4); readFully(ch, header); header.flip()
        val length = header.getInt()
        readFully(ch, ByteBuffer.allocate(1))
        val payload = ByteBuffer.allocate(length); readFully(ch, payload)
        return payload.array().decodeToString()
    }

    private fun readFully(ch: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (ch.read(buf) == -1) error("eof")
    }
}
