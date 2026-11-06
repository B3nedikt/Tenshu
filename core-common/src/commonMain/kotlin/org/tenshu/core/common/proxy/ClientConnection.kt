package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.common.parser.TokenString

interface ClientConnection {

    fun init()

    fun send(data: Data)

    fun destroy()

    /**
     * **Out-of-band byte tokenizer** — opt-in hook the serializer calls for
     * each [TValue] it's about to write into a payload. Returning a
     * [TokenString] substitutes that token in the serialized bytes while
     * the actual payload travels via a transport-specific side channel.
     * Returning `null` means "serialize this value inline" (the default).
     *
     * This is the same hook the stream-push path
     * (`AndroidClientConnection.send` historically) uses to wrap large byte
     * payloads in `ParcelFileDescriptor`/`SharedMemory` and stay under the
     * Android Binder reply parcel's ~1 MB cap. The sync-call reply path in
     * [ProxyGateway.call] consults the same method so byte payloads from
     * `.search.get()` chains don't blow that cap either — see
     * `docs/proposals/core-chain-entity-typing.md` §2.4 Phase 1.
     *
     * **Default = `null` (inline bytes).** Desktop's `UnixSocketClientConnection`
     * keeps the default — Unix domain sockets have no transport-level cap,
     * so inline byte serialization works today for the file sizes Drive
     * currently exercises. A future Phase 2 can override with an `SCM_RIGHTS`
     * FD-passing impl when heap pressure on multi-GB payloads becomes an
     * observed cost rather than a theoretical one.
     *
     * Implementations are expected to be **side-effecting**: the Android
     * impl performs an outbound AIDL transaction
     * (`clientService.receiveSharedMemory(...)`) to deliver the bytes
     * synchronously *before* returning the substitute token, so by the
     * time the client deserialises the reply parcel the byte payload is
     * already in its local store keyed by the token's uniqueId. Same
     * ordering invariant the stream transport already relies on.
     */
    fun tokenize(value: TValue): TokenString? = null

    /**
     * Control registered [streamId] afresh, or unregistered it (`StreamRegistry`). A transport that
     * keeps anything per stream for later — Android holds a stream end that did not go out — must
     * drop it here: stream ids are callstack hashes, so the same query subscribed again reuses the
     * id, and a stale end would kill the new subscription or hold back its snapshot
     * (`core-client-resubscription.md` §4.8). Default: nothing is kept.
     */
    fun forgetStream(streamId: Long) {}
}