package app.tenshu.windows.spike

import app.tenshu.windows.CloudFilesHResult
import app.tenshu.windows.WindowsCloudFiles
import app.tenshu.windows.WindowsCloudFiles.ConnectResult
import app.tenshu.windows.WindowsSyncRootManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/*
 * ⚠⚠ SPIKE — X0 of docs/proposals/desktop-windows-port.md §7. Not wired into Drive or Control, not
 * shipped, not a test. It registers a REAL sync root (visible in File Explorer) under a throwaway folder
 * in %TEMP%\tenshu-cfapi-spike\, so every run starts by removing what an earlier, killed run left behind
 * and ends — finally block and shutdown hook — by unregistering and verifying the root is gone.
 *
 *   ./gradlew :windows-helpers:cfapiSpike                         # measure, clean up, exit
 *   ./gradlew :windows-helpers:cfapiSpike -PspikeArgs=--hold      # also wait for Enter, for Explorer eyes
 *   ./gradlew :windows-helpers:cfapiSpike -PspikeArgs=--cleanup   # only remove leftovers
 */

private const val PROVIDER = "TenshuSpike"
private val PROVIDER_ID: UUID = UUID.fromString("5b0a3c1e-7d2f-4e8a-9c61-0e5d7a2b4f13")
/** `--parent=<dir>` overrides it (a packaged run keeps its root out of AppData, which MSIX may virtualise). */
private var SPIKE_PARENT: Path = Paths.get(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"), "tenshu-cfapi-spike")
private const val SYNC_ROOT_REGISTRY = "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\SyncRootManager"
private const val NAV_PANE_REGISTRY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Desktop\\NameSpace"

/** ~10 KB, so a hydration needs more than one aligned `TRANSFER_DATA` chunk. */
private val PAYLOAD: ByteArray = buildString {
    for (n in 1..320) append("Tenshu X0 cfapi spike - payload line %03d\r\n".format(n))
}.encodeToByteArray()

private var failures = 0
private fun log(s: String) = println("[spike] $s")
private fun check(ok: Boolean, what: String) {
    if (!ok) failures++
    println("[spike] ${if (ok) "PASS" else "FAIL"} $what")
}

private class Callback(val identity: String, val thread: String, val threadId: Long, val daemon: Boolean, val pid: Long?, val image: String?,
                       val offset: Long, val length: Long, val fileSize: Long, val infoSize: Int, val paramSize: Int, val path: String?)

fun main(args: Array<String>) {
    if (!WindowsCloudFiles.isAvailable() || !WindowsSyncRootManager.isAvailable()) {
        log("Cloud Files API not callable here (needs Windows x64 + JDK >= 22 with --enable-native-access)")
        exitProcess(2)
    }
    if (args.firstOrNull() == "--child-provider") return childProvider(Paths.get(args[1]))
    args.firstOrNull { it.startsWith("--parent=") }?.let { SPIKE_PARENT = Paths.get(it.removePrefix("--parent=")).resolve("tenshu-cfapi-spike") }
    log("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.version")}; pid ${ProcessHandle.current().pid()}")
    log("package identity: ${packageFamilyName() ?: "none (unpackaged)"}")
    removeLeftovers()
    if ("--cleanup" in args) exitProcess(if (leftoversRemain()) 1 else 0)

    val sid = currentUserSid()
    val id = "$PROVIDER!$sid!x0"
    val run = SPIKE_PARENT.resolve("run-${System.currentTimeMillis()}")
    val root = Files.createDirectories(run.resolve("Tenshu Drive (spike)"))
    log("sync root folder: $root")

    val cleanup = Cleanup(id, root, run)
    Runtime.getRuntime().addShutdownHook(Thread { cleanup.run("shutdown hook") })
    try {
        experimentCfRegisterOnly(run)
        experimentFullStack(id, root, cleanup, hold = "--hold" in args)
    } catch (t: Throwable) {
        failures++
        log("FAIL unexpected: $t")
        t.printStackTrace()
    } finally {
        cleanup.run("finally")
    }
    check(!leftoversRemain(), "no Tenshu spike sync root is left registered")
    runCatching { Files.deleteIfExists(SPIKE_PARENT) }
    log(if (failures == 0) "ALL PASS" else "$failures FAILURE(S)")
    exitProcess(if (failures == 0) 0 else 1)
}

/** A: `CfRegisterSyncRoot` alone — what does it give Explorer, and is it enough to connect? */
private fun experimentCfRegisterOnly(run: Path) {
    log("--- A: CfRegisterSyncRoot only")
    val root = Files.createDirectories(run.resolve("cf-only"))
    val navBefore = regKeys(NAV_PANE_REGISTRY)
    val rootsBefore = regKeys(SYNC_ROOT_REGISTRY)
    val hr = WindowsCloudFiles.registerSyncRoot(root, WindowsCloudFiles.Registration("Tenshu spike (cf only)", "0.0.1", PROVIDER_ID))!!
    log("CfRegisterSyncRoot -> ${CloudFilesHResult.describe(hr)}")
    check(CloudFilesHResult.succeeded(hr), "CfRegisterSyncRoot succeeds unpackaged")
    try {
        log("CfGetSyncRootInfoByPath -> ${WindowsCloudFiles.syncRootFileId(root)}")
        val newRoots = regKeys(SYNC_ROOT_REGISTRY) - rootsBefore
        val newNav = regKeys(NAV_PANE_REGISTRY) - navBefore
        log("new SyncRootManager keys: $newRoots; new nav-pane NameSpace keys: $newNav")
        log("GetCurrentSyncRoots lists it: ${WindowsSyncRootManager.currentSyncRootIds()?.getOrNull()?.filter { "cf only" in it || PROVIDER in it }}")
        val connected = WindowsCloudFiles.connect(root, object : WindowsCloudFiles.Handler {
            override fun fetchData(request: WindowsCloudFiles.FetchData) { request.fail() }
        })
        log("CfConnectSyncRoot on a cf-only root -> $connected")
        (connected as? ConnectResult.Connected)?.connection?.disconnect()
    } finally {
        val un = WindowsCloudFiles.unregisterSyncRoot(root)!!
        log("CfUnregisterSyncRoot -> ${CloudFilesHResult.describe(un)}")
        check(CloudFilesHResult.succeeded(un), "cf-only root unregisters")
        check(regKeys(SYNC_ROOT_REGISTRY) == rootsBefore, "cf-only root leaves no SyncRootManager key")
    }
}

/** B: `StorageProviderSyncRootManager.Register` → connect → placeholder → hydrate → failures → disconnect. */
private fun experimentFullStack(id: String, root: Path, cleanup: Cleanup, hold: Boolean) {
    log("--- B: StorageProviderSyncRootManager.Register + CfConnectSyncRoot")
    val navBefore = regKeys(NAV_PANE_REGISTRY)
    val rootsBefore = regKeys(SYNC_ROOT_REGISTRY)
    log("supported: ${WindowsSyncRootManager.isSupported()}")
    log("GetCurrentSyncRoots before Register: ${WindowsSyncRootManager.currentSyncRootIds()}")
    val outcome = WindowsSyncRootManager.register(
        WindowsSyncRootManager.Info(
            id = id,
            path = root.toString(),
            displayName = "Tenshu Drive (spike)",
            iconResource = "${System.getenv("SystemRoot") ?: "C:\\Windows"}\\System32\\imageres.dll,-1043",
            version = "0.0.1",
            providerId = PROVIDER_ID,
        ),
    )!!
    cleanup.registered = true
    log("Register -> $outcome")
    check(outcome.ok, "StorageProviderSyncRootManager.Register succeeds WITHOUT package identity")
    if (!outcome.ok) return

    val newRoots = regKeys(SYNC_ROOT_REGISTRY) - rootsBefore
    val newNav = regKeys(NAV_PANE_REGISTRY) - navBefore
    log("new SyncRootManager keys: $newRoots")
    log("new nav-pane NameSpace keys: $newNav")
    newNav.forEach { log("  nav-pane entry ${it.substringAfterLast('\\')} = ${regDefault(it)}") }
    check(newRoots.any { id in it }, "Register writes HKLM SyncRootManager\\$id")
    check(newNav.isNotEmpty(), "Register adds an Explorer navigation-pane entry")
    // Measured: it does NOT list a root an unpackaged process registered (see the X0 findings) — logged, not asserted.
    log("GetCurrentSyncRoots after Register: ${WindowsSyncRootManager.currentSyncRootIds()}")
    log("CfGetSyncRootInfoByPath -> ${WindowsCloudFiles.syncRootFileId(root)}")

    val callbacks = ConcurrentLinkedQueue<Callback>()
    val cancels = ConcurrentLinkedQueue<String>()
    val answerer = Executors.newSingleThreadExecutor { Thread(it, "spike-answerer").apply { isDaemon = true } }
    val handler = object : WindowsCloudFiles.Handler {
        override fun fetchData(request: WindowsCloudFiles.FetchData) {
            val identity = request.fileIdentity.decodeToString()
            val t = Thread.currentThread()
            callbacks += Callback(
                identity, t.name, request.facts.threadId, t.isDaemon, request.process?.pid, request.process?.imagePath,
                request.requiredOffset, request.requiredLength, request.fileSize, request.facts.callbackInfoStructSize,
                request.facts.parameterSize, request.normalizedPath,
            )
            when {
                identity == "fail" -> request.fail()
                // Blocks the CfAPI thread on purpose: are concurrent requests serialised behind it?
                identity.startsWith("block-") -> { Thread.sleep(1000); serve(request) }
                // Answered LATER from a JVM thread: the shape X1 needs (bytes come off the network).
                else -> answerer.execute { serve(request) }
            }
        }

        override fun cancelFetchData(cancel: WindowsCloudFiles.CancelFetchData) {
            cancels += cancel.fileIdentity.decodeToString()
        }
    }
    val connection = when (val c = WindowsCloudFiles.connect(root, handler)!!) {
        is ConnectResult.Connected -> c.connection
        is ConnectResult.Failed -> { check(false, "CfConnectSyncRoot -> ${CloudFilesHResult.describe(c.hresult)}"); return }
    }
    cleanup.connection = connection
    check(true, "CfConnectSyncRoot with FETCH_DATA/CANCEL_FETCH_DATA upcalls (key ${connection.key})")
    val second = WindowsCloudFiles.connect(root, handler)
    log("a second CfConnectSyncRoot on the same root -> ${(second as? ConnectResult.Failed)?.hresult?.let(CloudFilesHResult::describe) ?: second}")
    (second as? ConnectResult.Connected)?.connection?.disconnect()

    fun placeholder(name: String, identity: String): Path {
        val hr = WindowsCloudFiles.createPlaceholder(root, WindowsCloudFiles.Placeholder(name, PAYLOAD.size.toLong(), identity.encodeToByteArray(), System.currentTimeMillis()))!!
        check(CloudFilesHResult.succeeded(hr), "CfCreatePlaceholders $name -> ${CloudFilesHResult.describe(hr)}")
        return root.resolve(name)
    }

    val hello = placeholder("hello.txt", "hello")
    val attrs = WindowsCloudFiles.fileAttributes(hello) ?: 0
    log("hello.txt attributes before open: ${attributeNames(attrs)}; on-disk size ${Files.size(hello)}")
    check(attrs and WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS != 0, "a fresh placeholder is online-only (RECALL_ON_DATA_ACCESS)")

    val expected = sha256(PAYLOAD)
    // Another process opens it — the Notepad case, scripted.
    val (hash, ms) = childHash(hello)
    log("child powershell Get-FileHash hello.txt -> $hash in $ms ms")
    check(hash == expected, "another process reads exactly the payload (hydrated on open)")
    val after = WindowsCloudFiles.fileAttributes(hello) ?: 0
    log("hello.txt attributes after open: ${attributeNames(after)}")
    check(after and WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS == 0, "after hydration the file is no longer online-only")

    // The provider process reads its own placeholder: callback on a CfAPI thread while this one blocks.
    val self = placeholder("self.txt", "self")
    val t0 = System.nanoTime()
    val selfBytes = Files.readAllBytes(self)
    check(selfBytes.contentEquals(PAYLOAD), "the provider's own process hydrates implicitly (${(System.nanoTime() - t0) / 1_000_000} ms)")

    val failed = placeholder("fail.txt", "fail")
    val (failHash, failMs) = childHash(failed)
    log("child read of a request the handler FAILS -> '$failHash' in $failMs ms")
    check(failHash != expected, "a failed transfer reaches the opener as an error")

    // Three openers at once against a handler that blocks its callback thread for 1 s each.
    val blocked = (1..3).map { placeholder("block-$it.txt", "block-$it") }
    val tb = System.nanoTime()
    val readers = blocked.map { f -> Thread { check(childHash(f).first == expected, "concurrent reader of ${f.fileName}") }.apply { start() } }
    readers.forEach { it.join() }
    val blockThreads = callbacks.filter { it.identity.startsWith("block-") }.map { it.threadId }.distinct()
    log("3 concurrent opens, each callback blocking 1 s: ${(System.nanoTime() - tb) / 1_000_000} ms wall, on ${blockThreads.size} distinct callback thread(s) $blockThreads")

    if (hold) {
        placeholder("open-me.txt", "open-me") // still online-only: double-click it in Explorer
        log("HOLD: the sync root is connected. Look in Explorer now (see desktop-windows-port.md section 7.7).")
        log("      $root")
        log("      Press Enter to continue (disconnect, the not-running case, then unregister).")
        readlnOrNull()
    }

    val main = Thread.currentThread()
    log("main thread: '${main.name}' id ${main.threadId()}")
    callbacks.forEach {
        log("FETCH_DATA ${it.identity}: thread '${it.thread}' id ${it.threadId} daemon=${it.daemon}; opener pid ${it.pid} ${it.image}; " +
            "range ${it.offset}+${it.length} of ${it.fileSize}; CF_CALLBACK_INFO.StructSize=${it.infoSize} ParamSize=${it.paramSize}; path ${it.path}")
    }
    if (cancels.isNotEmpty()) log("CANCEL_FETCH_DATA for: ${cancels.toList()}")
    check(callbacks.isNotEmpty() && callbacks.all { it.infoSize == 152 }, "the OS's CF_CALLBACK_INFO.StructSize equals our layout (152)")
    check(callbacks.isNotEmpty() && callbacks.all { it.paramSize >= 64 }, "the OS's CF_CALLBACK_PARAMETERS size covers our FetchData arm (64)")
    check(callbacks.none { it.threadId == main.threadId() }, "callbacks never arrive on the thread that made the CfAPI call")

    // The owner goes away: disconnect, then open a placeholder that was never hydrated.
    val dhr = connection.disconnect()!!
    check(CloudFilesHResult.succeeded(dhr), "CfDisconnectSyncRoot -> ${CloudFilesHResult.describe(dhr)}")
    val offline = placeholder("offline.txt", "offline")
    val (offHash, offMs) = childHash(offline)
    log("child read with NO provider connected -> '$offHash' in $offMs ms")
    check(offHash != expected, "with nothing connected, opening a placeholder fails (does not hang)")
    val (helloAgain, _) = childHash(hello)
    check(helloAgain == expected, "an already-hydrated file stays readable with nothing connected")
    answerer.shutdownNow()

    experimentProviderKilled(root, expected)
}

/** C: another process is the provider and is killed while an opener waits on it — a Control crash. */
private fun experimentProviderKilled(root: Path, expected: String) {
    log("--- C: the provider process dies mid-request")
    fun create(name: String) = WindowsCloudFiles.createPlaceholder(root, WindowsCloudFiles.Placeholder(name, PAYLOAD.size.toLong(), "hang".encodeToByteArray(), System.currentTimeMillis()))!!
    val hang = root.resolve("hang.txt")
    // Created while NOTHING is connected, by this (non-provider) process.
    check(CloudFilesHResult.succeeded(create("hang.txt")), "CfCreatePlaceholders hang.txt while disconnected")
    val java = ProcessHandle.current().info().command().orElse("java")
    val child = ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
        "app.tenshu.windows.spike.CfapiSpikeKt", "--child-provider", root.toString()).redirectErrorStream(true).start()
    try {
        val out = child.inputStream.bufferedReader()
        val ready = out.readLine()
        log("child provider pid ${child.pid()} says: $ready")
        check(ready?.startsWith("READY") == true, "a second process can connect once the first has disconnected")
        if (ready?.startsWith("READY") != true) return
        log("CfCreatePlaceholders from a process that is NOT the connected provider -> ${CloudFilesHResult.describe(create("intruder.txt"))}")
        var result: Pair<String, Long>? = null
        val reader = Thread { result = childHash(hang) }.apply { start() }
        var got: String? = null
        Thread { got = out.readLine() }.apply { start() }.join(10_000)
        log("child provider says: $got")
        check(got?.startsWith("GOT hang") == true, "the child provider received FETCH_DATA for hang.txt and is sitting on it")
        Thread.sleep(1000) // the opener is now waiting on a request the child never answers
        val killedAt = System.nanoTime()
        child.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        reader.join(90_000)
        log("opener waiting when the provider was killed -> '${result?.first}', ${(System.nanoTime() - killedAt) / 1_000_000} ms after the kill")
        val r = result?.first.orEmpty()
        check(r.startsWith("ERROR: The file") && "cannot be read" in r, "a provider crash fails the waiting opener promptly (no 60 s hang)")
        val (after, _) = childHash(root.resolve("offline.txt"))
        log("open after the provider died -> '$after'")
        check(WindowsCloudFiles.syncRootFileId(root)?.isSuccess == true, "the sync root stays registered after its provider dies")
    } finally {
        child.destroyForcibly()
    }
}

/** The child half of experiment C: connect, serve nothing for `hang`, say READY, wait to be killed. */
private fun childProvider(root: Path) {
    val c = WindowsCloudFiles.connect(root, object : WindowsCloudFiles.Handler {
        override fun fetchData(request: WindowsCloudFiles.FetchData) {
            if (request.fileIdentity.decodeToString() != "hang") serve(request)
            else { println("GOT hang from pid ${request.process?.pid}"); System.out.flush() }
        }
    })
    println(if (c is ConnectResult.Connected) "READY key ${c.connection.key}" else "NOT CONNECTED $c")
    System.out.flush()
    Thread.sleep(120_000) // killed by the parent long before this
}

/** Serve the required range in 4 KiB-aligned chunks (all but the last must be aligned). */
private fun serve(request: WindowsCloudFiles.FetchData) {
    val size = PAYLOAD.size.toLong()
    val align = WindowsCloudFiles.TRANSFER_ALIGNMENT
    var offset = request.requiredOffset / align * align
    val end = minOf(size, (request.requiredOffset + request.requiredLength + align - 1) / align * align)
    while (offset < end) {
        val n = minOf(align, end - offset)
        val hr = request.transfer(offset, PAYLOAD.copyOfRange(offset.toInt(), (offset + n).toInt()))
        if (hr == null || !CloudFilesHResult.succeeded(hr)) {
            log("TRANSFER_DATA at $offset failed: ${hr?.let(CloudFilesHResult::describe)}")
            request.fail()
            return
        }
        offset += n
    }
}

/** Disconnect, unregister both ways, verify, delete — idempotent, runs from finally and the shutdown hook. */
private class Cleanup(val id: String, val root: Path, val run: Path) {
    @Volatile var connection: WindowsCloudFiles.Connection? = null
    @Volatile var registered = false
    @Volatile private var done = false

    @Synchronized fun run(from: String) {
        if (done) return
        done = true
        log("--- cleanup ($from)")
        connection?.disconnect()?.let { log("disconnect -> ${CloudFilesHResult.describe(it)}") }
        if (registered) log("StorageProviderSyncRootManager.Unregister -> ${WindowsSyncRootManager.unregister(id)}")
        val cf = WindowsCloudFiles.unregisterSyncRoot(root)
        log("CfUnregisterSyncRoot -> ${cf?.let(CloudFilesHResult::describe)} (expected to fail once Unregister has run)")
        val stillListed = WindowsSyncRootManager.currentSyncRootIds()?.getOrNull()?.contains(id) == true
        val stillRoot = WindowsCloudFiles.syncRootFileId(root)?.isSuccess == true
        log("after cleanup: listed=$stillListed, folder still a sync root=$stillRoot, registry key=${regKeys(SYNC_ROOT_REGISTRY).any { id in it }}")
        deleteTree(run)
    }
}

/** Unregister every `TenshuSpike!` root and every sync root under [SPIKE_PARENT], then delete the folder. */
private fun removeLeftovers() {
    // ⚠ The registry, not GetCurrentSyncRoots: that call does not list roots an unpackaged process registered.
    val ids = regKeys(SYNC_ROOT_REGISTRY).map { it.substringAfterLast('\\') } +
        WindowsSyncRootManager.currentSyncRootIds()?.getOrNull().orEmpty()
    ids.filter { it.startsWith("$PROVIDER!") }.distinct().forEach {
        log("leftover sync root $it -> Unregister: ${WindowsSyncRootManager.unregister(it)}")
    }
    if (Files.isDirectory(SPIKE_PARENT)) {
        Files.walk(SPIKE_PARENT, 3).use { s -> s.filter { Files.isDirectory(it) }.toList() }.forEach { dir ->
            if (WindowsCloudFiles.syncRootFileId(dir)?.isSuccess == true) {
                log("leftover cf root $dir -> CfUnregisterSyncRoot: ${WindowsCloudFiles.unregisterSyncRoot(dir)?.let(CloudFilesHResult::describe)}")
            }
        }
        deleteTree(SPIKE_PARENT)
    }
}

private fun leftoversRemain(): Boolean {
    val ids = WindowsSyncRootManager.currentSyncRootIds()?.getOrNull().orEmpty().filter { it.startsWith("$PROVIDER!") }
    val keys = regKeys(SYNC_ROOT_REGISTRY).filter { "$PROVIDER!" in it }
    if (ids.isNotEmpty() || keys.isNotEmpty()) log("LEFTOVER: ids=$ids keys=$keys")
    return ids.isNotEmpty() || keys.isNotEmpty()
}

private fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    runCatching {
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).toList() }.forEach { Files.deleteIfExists(it) }
    }.onFailure { log("could not delete $dir: $it") }
}

private fun attributeNames(a: Int): String = listOf(
    WindowsCloudFiles.FILE_ATTRIBUTE_SPARSE_FILE to "SPARSE", WindowsCloudFiles.FILE_ATTRIBUTE_REPARSE_POINT to "REPARSE_POINT",
    WindowsCloudFiles.FILE_ATTRIBUTE_OFFLINE to "OFFLINE", WindowsCloudFiles.FILE_ATTRIBUTE_PINNED to "PINNED",
    WindowsCloudFiles.FILE_ATTRIBUTE_UNPINNED to "UNPINNED", WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_OPEN to "RECALL_ON_OPEN",
    WindowsCloudFiles.FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS to "RECALL_ON_DATA_ACCESS",
).filter { a and it.first != 0 }.joinToString("|") { it.second }.ifEmpty { "-" } + " (0x%X)".format(a)

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02X".format(it) }

/** SHA-256 of [file] as another process reads it, or that process's error; and how long it took. */
private fun childHash(file: Path): Pair<String, Long> {
    val t0 = System.nanoTime()
    val p = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "try { (Get-FileHash -Algorithm SHA256 -LiteralPath '$file' -ErrorAction Stop).Hash } catch { 'ERROR: ' + \$_.Exception.Message }",
    ).redirectErrorStream(true).start()
    val finished = p.waitFor(90, TimeUnit.SECONDS)
    if (!finished) p.destroyForcibly()
    val out = p.inputStream.readAllBytes().decodeToString().trim()
    return (if (finished) out else "TIMEOUT") to (System.nanoTime() - t0) / 1_000_000
}

private fun regKeys(key: String): Set<String> = runCatching {
    val p = ProcessBuilder("reg.exe", "query", key).redirectErrorStream(true).start()
    p.inputStream.readAllBytes().decodeToString().lines().map { it.trim() }.filter { it.startsWith("HKEY_", ignoreCase = true) && !it.equals(expand(key), true) }.toSet()
        .also { p.waitFor() }
}.getOrDefault(emptySet())

private fun regDefault(key: String): String = runCatching {
    val p = ProcessBuilder("reg.exe", "query", key, "/ve").redirectErrorStream(true).start()
    p.inputStream.readAllBytes().decodeToString().lines().firstOrNull { "REG_SZ" in it }?.substringAfter("REG_SZ")?.trim().orEmpty()
}.getOrDefault("")

private fun expand(key: String) = key.replaceFirst("HKLM\\", "HKEY_LOCAL_MACHINE\\").replaceFirst("HKCU\\", "HKEY_CURRENT_USER\\")

/** `GetCurrentPackageFamilyName`; null when the process has no package identity (APPMODEL_ERROR_NO_PACKAGE). */
private fun packageFamilyName(): String? = runCatching {
    java.lang.foreign.Arena.ofConfined().use { arena ->
        val linker = java.lang.foreign.Linker.nativeLinker()
        val i = java.lang.foreign.ValueLayout.JAVA_INT
        val a = java.lang.foreign.ValueLayout.ADDRESS
        val fn = java.lang.foreign.SymbolLookup.libraryLookup("kernel32.dll", arena).find("GetCurrentPackageFamilyName").orElseThrow()
        val h = linker.downcallHandle(fn, java.lang.foreign.FunctionDescriptor.of(i, a, a))
        val length = arena.allocate(i).also { it.set(i, 0L, 256) }
        val buffer = arena.allocate(512L)
        val rc = h.invokeExact(length, buffer) as Int
        if (rc != 0) null else buffer.getString(0L, Charsets.UTF_16LE)
    }
}.getOrNull()

private fun currentUserSid(): String {
    val p = ProcessBuilder("whoami.exe", "/user", "/fo", "csv", "/nh").redirectErrorStream(true).start()
    val out = p.inputStream.readAllBytes().decodeToString().trim()
    return out.substringAfterLast(",").trim('"', ' ', '\r', '\n')
}
