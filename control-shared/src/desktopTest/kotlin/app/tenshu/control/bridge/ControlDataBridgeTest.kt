package app.tenshu.control.bridge

import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.BundledAppManifestSource
import app.tenshu.control.data.ClientBinding
import app.tenshu.control.data.Event
import app.tenshu.control.data.FirstPartyApp
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.macOsBundleIdFor
import app.tenshu.control.data.MockAppRegistry
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
import kotlin.time.Instant

class ControlDataBridgeTest {

    // Short /tmp path — macOS Unix-domain sun_path caps at ~104 chars.
    private val tempDir = org.tenshu.core.common.shortTempDirectory("td")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // ---- pure JSON-shaping ------------------------------------------------

    @Test
    fun appsResultShapesInstalledApps() {
        val apps = listOf(
            InstalledApp("a.b", "Drive", "drive", "1.0.0", "Files", 100L, verified = true, deviceCount = 2),
        )
        @Suppress("UNCHECKED_CAST")
        val rows = appsResult(apps, catalogue = emptyList())["apps"] as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        assertEquals("Drive", rows[0]["name"])
        assertEquals(true, rows[0]["verified"])
        assertEquals(2, rows[0]["deviceCount"])
    }

    /**
     * The tray filters on this field (`control-app-catalogue.md` D5), so it has to survive the
     * JSON hop — and both values have to, since a field pinned to one of them yields either a
     * tray listing nothing or a tray listing apps it cannot launch.
     */
    @Test
    fun appsResultCarriesLocalityForTheTrayLauncher() {
        val apps = listOf(
            InstalledApp(
                "a.local", "Local", "drive", "1.0.0", "Here", 100L,
                verified = true, deviceCount = 1, installedOnThisDevice = true,
            ),
            InstalledApp(
                "a.remote", "Remote", "fit", "1.0.0", "On the phone", 100L,
                verified = true, deviceCount = 1, installedOnThisDevice = false,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val rows = appsResult(apps, catalogue = emptyList())["apps"] as List<Map<String, Any?>>
        assertEquals(true, rows.first { it["appId"] == "a.local" }["installedOnThisDevice"])
        assertEquals(false, rows.first { it["appId"] == "a.remote" }["installedOnThisDevice"])
    }

    /**
     * An app that is installed but was never opened has no registration, so the tray can only
     * offer it if the daemon names it as a candidate. Pins all three edges of that list: a
     * never-connected macOS app is appended, a registered one is not duplicated (whichever
     * device it registered on), and an app with no macOS bundle is not offered at all.
     */
    @Test
    fun appsResultAppendsNeverConnectedMacAppsAsUnregisteredCandidates() {
        val drive = BundledAppManifestSource.DRIVE_MANIFEST
        val fit = BundledAppManifestSource.FIT_MANIFEST
        val maps = BundledAppManifestSource.MAPS_MANIFEST
        val catalogue = listOf(
            FirstPartyApp(drive, listOf(ClientBinding.MacCodeSignature(drive.appId))),
            FirstPartyApp(fit, listOf(ClientBinding.MacCodeSignature(fit.appId))),
            FirstPartyApp(maps, listOf(ClientBinding.AndroidPackage("${maps.appId}.android"))),
        )
        val registered = listOf(
            InstalledApp(
                drive.appId, "Drive", "drive", "1.0.0", "Files", 100L,
                verified = true, deviceCount = 1, installedOnThisDevice = false,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val rows = appsResult(registered, catalogue)["apps"] as List<Map<String, Any?>>

        assertEquals(listOf(drive.appId, fit.appId), rows.map { it["appId"] })
        assertEquals(true, rows[0]["registered"])
        assertEquals(false, rows[1]["registered"])
        assertEquals(fit.name, rows[1]["name"])
        assertEquals(false, rows[1]["installedOnThisDevice"])
        assertEquals(false, rows[1]["verified"])
    }

    @Test
    fun appsResultIncludesNeverConnectedAppWithSharedAppleBundleId() {
        val drive = BundledAppManifestSource.DRIVE_MANIFEST
        val catalogue = listOf(
            FirstPartyApp(drive, listOf(ClientBinding.MacCodeSignature("app.tenshu.drive.ios"))),
        )

        @Suppress("UNCHECKED_CAST")
        val rows = appsResult(emptyList(), catalogue)["apps"] as List<Map<String, Any?>>

        assertEquals(listOf(drive.appId), rows.map { it["appId"] })
        assertEquals(false, rows.single()["registered"])
    }

    @Test
    fun notificationsResultEncodesEachEventKind() {
        val t = Instant.fromEpochMilliseconds(42L)
        val events = listOf(
            Event.FileEvent.Added("1", t, "photo.jpg", isFolder = false),
            Event.FileEvent.Added("2", t, "Trip", isFolder = true),
            Event.FileEvent.MultipleAdded("3", t, "a.txt", otherFilesCount = 4),
            Event.FileEvent.Deleted("4", t, "old.txt"),
            Event.AppEvent.Registered("5", t, "Drive"),
            Event.AppEvent.Removed("6", t, "Notes"),
            Event.FileEvent.Deleted("7", t, "Taxes", isFolder = true),
        )
        @Suppress("UNCHECKED_CAST")
        val rows = notificationsResult(events)["notifications"] as List<Map<String, Any?>>

        assertEquals(listOf("file_added", "folder_added", "files_added", "file_deleted", "app_registered", "app_removed", "folder_deleted"),
            rows.map { it["kind"] })
        assertEquals("photo.jpg", rows[0]["name"])
        assertEquals(4, rows[2]["otherCount"])
        assertEquals(42L, rows[0]["timestamp"])
    }

    // ---- real UDS round-trip ---------------------------------------------

    @Test
    fun listAppsRoundTripReturnsRegistryApps() {
        val koin = koinApplication {
            modules(module { single<AppRegistry> { MockAppRegistry() } })
        }.koin
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply { registerDataMethods(koin); start() }

        connect(socketPath).use { ch ->
            val reply = call(ch, "listApps")
            // MockAppRegistry seeds Drive + a dev build.
            assertTrue(reply.contains("\"name\":\"Drive\""), "missing Drive in: $reply")
            assertTrue(reply.contains("\"verified\":true"), "missing verified flag in: $reply")
            // The production catalogue is wired in: Photos never registered with the mock,
            // so it can only be here as a launch candidate.
            assertTrue(reply.contains("\"name\":\"Photos\""), "missing never-connected Photos in: $reply")
            assertTrue(reply.contains("\"registered\":false"), "missing registered=false in: $reply")
        }
    }

    @Test
    fun macOsBundleIdForMapsPlatformNeutralAppIdsToSharedAppleIds() {
        assertEquals("app.tenshu.drive.ios", macOsBundleIdFor("app.tenshu.drive"))
        assertEquals("app.tenshu.control.ios", macOsBundleIdFor("app.tenshu.control"))
        // A bare name (no dot) isn't a plausible bundle id, nor is blank/garbage.
        assertEquals(null, macOsBundleIdFor("drive"))
        assertEquals(null, macOsBundleIdFor(""))
        assertEquals(null, macOsBundleIdFor("app.tenshu.drive; rm -rf"))
    }

    @Test
    fun launchAppLaunchesResolvedBundle() {
        val launched = mutableListOf<String>()
        val koin = koinApplication { modules(module { }) }.koin
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply {
            registerDataMethods(koin, launchBundle = { launched += it; true })
            start()
        }

        connect(socketPath).use { ch ->
            val reply = call(ch, "launchApp", """{"appId":"app.tenshu.drive"}""")
            assertTrue(reply.contains("\"launched\":true"), "expected launched=true in: $reply")
        }
        assertEquals(listOf("app.tenshu.drive.ios"), launched)
    }

    @Test
    fun launchAppReturnsNotFoundWhenBundleAbsent() {
        val koin = koinApplication { modules(module { }) }.koin
        val socketPath = tempDir.resolve("bridge.sock").toString()
        NativeBridgeServer(socketPath).apply {
            registerDataMethods(koin, launchBundle = { false }) // bundle not installed
            start()
        }

        connect(socketPath).use { ch ->
            val reply = call(ch, "launchApp", """{"appId":"app.tenshu.drive"}""")
            assertTrue(reply.contains("not_found"), "expected not_found error in: $reply")
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
