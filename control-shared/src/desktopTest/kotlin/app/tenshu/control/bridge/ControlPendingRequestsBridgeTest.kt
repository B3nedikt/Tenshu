package app.tenshu.control.bridge

import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestsRepository
import app.tenshu.control.notifications.RequestActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.tenshu.core.common.proxy.NativeBridgeServer
import org.tenshu.core.common.transport.StreamFramer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlPendingRequestsBridgeTest {

    // Short /tmp path — macOS Unix-domain sun_path caps at ~104 chars.
    private val tempDir = org.tenshu.core.common.shortTempDirectory("td")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ---- pure JSON-shaping ------------------------------------------------

    @Test
    fun pendingRequestsResultEncodesStableIdKindAndTimestamp() {
        val requests = listOf(
            PendingRequest.MembershipGrant(grantorFingerprint = "FACE:CAFE", timestampMillis = 200L),
            PendingRequest.ContactInviteRedeemed(
                inviteId = "inv-1",
                claimedName = "Mallory",
                claimedFingerprint = "BEEF",
                claimedSas = "12 34 56",
                timestampMillis = 100L,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val rows = pendingRequestsResult(requests)["requests"] as List<Map<String, Any?>>

        assertEquals(listOf("membership_grant", "contact_invite"), rows.map { it["kind"] })
        assertEquals(listOf("grant:FACE:CAFE", "invite:inv-1"), rows.map { it["id"] })
        assertEquals(listOf(200L, 100L), rows.map { it["timestampMillis"] })
    }

    @Test
    fun pendingRequestsResultOmitsPiiFields() {
        // §6 proxy isolation: the bridge never ships the quarantined claimed name
        // or the fingerprint as its own field — only the opaque id + generic kind.
        val requests = listOf(
            PendingRequest.ContactInviteRedeemed(
                inviteId = "inv-1",
                claimedName = "Mallory",
                claimedFingerprint = "BEEF",
                claimedSas = "12 34 56",
                timestampMillis = 100L,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val row = (pendingRequestsResult(requests)["requests"] as List<Map<String, Any?>>).single()

        assertEquals(setOf("id", "kind", "timestampMillis"), row.keys)
        assertTrue(row.values.none { it == "Mallory" }, "claimed name leaked into bridge payload: $row")
    }

    @Test
    fun pendingRequestsResultIsEmptyWhenNothingPending() {
        @Suppress("UNCHECKED_CAST")
        val rows = pendingRequestsResult(emptyList())["requests"] as List<Map<String, Any?>>
        assertTrue(rows.isEmpty())
    }

    // ---- real UDS round-trip ---------------------------------------------

    @Test
    fun pendingRequestsRoundTripReturnsRepositoryFeed() {
        val repo = FakePendingRequestsRepository(
            MutableStateFlow(
                listOf(PendingRequest.MembershipGrant(grantorFingerprint = "AB", timestampMillis = 1L)),
            )
        )
        val koin = koinApplication {
            modules(module { single<PendingRequestsRepository> { repo } })
        }.koin
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply { registerPendingRequestMethods(koin); start() }

        connect(socketPath).use { ch ->
            val reply = call(ch, "pendingRequests")
            assertTrue(reply.contains("\"kind\":\"membership_grant\""), "missing kind in: $reply")
            assertTrue(reply.contains("\"id\":\"grant:AB\""), "missing id in: $reply")
        }
    }

    private fun call(ch: SocketChannel, method: String, params: String = "{}"): String {
        val req = """{"v":1,"id":"t","method":"$method","params":$params}""".encodeToByteArray()
        ch.write(ByteBuffer.wrap(StreamFramer.frame(req)))
        return readFrame(ch)
    }

    private fun connect(socketPath: String): SocketChannel {
        val address = UnixDomainSocketAddress.of(socketPath)
        val deadline = System.currentTimeMillis() + 2000
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

/** Minimal repository stub — the bridge reads [unread] (Phase 4); no dismissals here, so it mirrors [requests]. */
private class FakePendingRequestsRepository(
    override val requests: StateFlow<List<PendingRequest>>,
) : PendingRequestsRepository {
    override val seenIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val unread: StateFlow<List<PendingRequest>> = requests
    override fun refresh() {}
    override suspend fun dismiss(request: PendingRequest) = RequestActionResult.Success
    override suspend fun acceptGrant(request: PendingRequest.MembershipGrant) = RequestActionResult.Success
    override suspend fun declineGrant(request: PendingRequest.MembershipGrant) = RequestActionResult.Success
    override suspend fun confirmInvite(
        request: PendingRequest.ContactInviteRedeemed,
        petname: String,
        sasConfirmed: Boolean,
    ) = RequestActionResult.Success
    override suspend fun declineInvite(request: PendingRequest.ContactInviteRedeemed) = RequestActionResult.Success
}
