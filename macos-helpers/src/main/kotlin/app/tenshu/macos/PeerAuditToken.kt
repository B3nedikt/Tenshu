package app.tenshu.macos

import java.io.FileDescriptor
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.reflect.InaccessibleObjectException
import java.nio.channels.SocketChannel

/**
 * Reads the **audit token** of the process on the other end of an accepted Unix-domain
 * socket — the unforgeable handle everything in [MacPeerAttestation] hangs off.
 *
 * ### Why the audit token, and not the pid
 *
 * The obvious route is `LOCAL_PEERPID` → `proc_pidpath` → `SecStaticCodeCreateWithPath`, and
 * it is wrong twice over (`desktop-ipc-client-identity.md` D2):
 *
 *  - **Pid reuse.** A pid is recycled. Between reading it and resolving it, it can name a
 *    different process.
 *  - **TOCTOU.** `SecStaticCode*` inspects the **file on disk**, not the running image. The
 *    binary can be replaced after exec, so a passing check says nothing about the process
 *    actually holding the socket.
 *
 * The audit token identifies the peer *process* and can be handed straight to
 * `SecCodeCopyGuestWithAttributes`, which yields a `SecCodeRef` for the **running code**.
 * Neither defect applies.
 *
 * ### ⚠ Requires `--add-opens java.base/sun.nio.ch=ALL-UNNAMED`
 *
 * The JDK exposes no public way to reach a channel's file descriptor, so this reflects into
 * `sun.nio.ch.SocketChannelImpl.fd`. Without the flag it fails with
 * `InaccessibleObjectException` — which is why failures carry a *reason*: this one is a
 * build-configuration mistake, and it is otherwise indistinguishable from a hostile peer.
 * The flag must be present in **both** the `run` JVM args and the packaged app's, or the
 * verifier works in development and dies in the shipped bundle.
 */
internal object PeerAuditToken {

    /** `audit_token_t` is 8 × `uint32`. */
    const val SIZE: Int = 32

    private const val SOL_LOCAL = 0
    private const val LOCAL_PEERTOKEN = 0x006

    private val linker: Linker? = runCatching { Linker.nativeLinker() }.getOrNull()

    private val getsockopt: MethodHandle? by lazy {
        val l = linker ?: return@lazy null
        val address = l.defaultLookup().find("getsockopt").orElse(null) ?: return@lazy null
        l.downcallHandle(
            address,
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        )
    }

    /** Either the peer's 32-byte audit token, or why it could not be read. */
    sealed interface Result {
        class Token(val bytes: ByteArray) : Result
        class Unavailable(val reason: String) : Result
    }

    /**
     * Read [channel]'s peer audit token. The channel must be an *accepted* Unix-domain
     * socket that is still open — the token describes whoever is on the far end right now.
     */
    fun read(channel: SocketChannel, arena: Arena): Result {
        val call = getsockopt ?: return Result.Unavailable("native linker unavailable")

        val fd = try {
            fileDescriptorOf(channel)
        } catch (e: InaccessibleObjectException) {
            return Result.Unavailable(
                "cannot read the peer fd — this JVM is missing " +
                    "--add-opens java.base/sun.nio.ch=ALL-UNNAMED",
            )
        } catch (e: Throwable) {
            return Result.Unavailable(
                "cannot read the peer fd: ${e::class.simpleName}: ${e.message}",
            )
        }

        val buffer = arena.allocate(SIZE.toLong())
        val length = arena.allocate(ValueLayout.JAVA_INT)
        length.set(ValueLayout.JAVA_INT, 0, SIZE)

        val rc = call.invokeExact(fd, SOL_LOCAL, LOCAL_PEERTOKEN, buffer, length) as Int
        if (rc != 0) return Result.Unavailable("getsockopt(LOCAL_PEERTOKEN) failed (rc=$rc)")

        val returned = length.get(ValueLayout.JAVA_INT, 0)
        if (returned != SIZE) {
            return Result.Unavailable("audit token was $returned bytes, expected $SIZE")
        }
        return Result.Token(buffer.toArray(ValueLayout.JAVA_BYTE))
    }

    /**
     * The integer fd behind [channel]. Walks the class hierarchy looking for the
     * [FileDescriptor] field rather than naming `sun.nio.ch.SocketChannelImpl` outright, so
     * a JDK that relocates it still resolves.
     */
    private fun fileDescriptorOf(channel: SocketChannel): Int {
        var type: Class<*>? = channel.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (field.type == FileDescriptor::class.java) {
                    field.isAccessible = true
                    val descriptor = field.get(channel) as FileDescriptor
                    val raw = FileDescriptor::class.java.getDeclaredField("fd")
                    raw.isAccessible = true
                    return raw.get(descriptor) as Int
                }
            }
            type = type.superclass
        }
        error("no FileDescriptor field on ${channel.javaClass.name}")
    }
}
