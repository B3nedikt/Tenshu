package org.tenshu.files.util

import android.os.ParcelFileDescriptor
import android.util.Log
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import java.io.IOException

/**
 * Per-process cache of [ParcelFileDescriptor]s arriving across the
 * Control ⇄ Drive Binder boundary. The receiver-side AIDL stub stores
 * the dup'd FD here keyed by an opaque dataId; the consumer (e.g.
 * `ParcelFileDescriptorFileByteArrayDto`) reads it out by the same id
 * when the byte-array value is first materialised.
 *
 * Registered as a [TComponent] on the [TContext] so callers receive it
 * through normal DI — see `OnDemandResolution` / `ExternalPropertyWriter`
 * for the same pattern. Lookup: `context.components[ParcelFileDescriptorCache.ID]`.
 *
 * The map is encapsulated behind [put]/[get]/[remove] so that overwriting
 * a slot or evicting an entry [closes the previous PFD][ParcelFileDescriptor.close]
 * before dropping the reference. Without that, the orphaned PFD lives
 * until the JVM garbage-collects it and Android's `CloseGuard` logs
 * `"A resource failed to call close"` — one warning per orphaned FD
 * (typical pattern: opening a file detail view re-emits the list,
 * re-sends each file's FD, and overwrites the previous cache occupants
 * for the same dataIds).
 */
class ParcelFileDescriptorCache : TComponent {
    private val cache: MutableMap<String, ParcelFileDescriptor> = mutableMapOf()

    fun get(key: String): ParcelFileDescriptor? = cache[key]

    fun put(key: String, value: ParcelFileDescriptor) {
        cache.put(key, value)?.closeSilently()
    }

    fun remove(key: String) {
        cache.remove(key)?.closeSilently()
    }

    fun clear() {
        cache.values.forEach { it.closeSilently() }
        cache.clear()
    }

    // ── Sender-scoped API — the PROXY side ────────────────────────────────────────────────
    //
    // On the proxy this one cache is shared by every Binder client, and the key is a string
    // the *caller* chose (`sendFile(fd, contentUri)`). Keyed by that alone, one client can
    // seed or overwrite the entry another client is about to upload under — content
    // substitution across an IPC boundary that otherwise attests who everyone is. These
    // wrappers fold the caller's attested identity into the key so a slot belongs to exactly
    // one client.
    //
    // ⚠ The bare [put]/[get]/[remove] above stay for the CLIENT side (`BinderTaskTransport`),
    // where the cache lives in the app's own process, holds descriptors the proxy pushed, and
    // has no notion of a sender. Proxy-side callers must use these three.

    fun putFrom(clientId: String, key: String, value: ParcelFileDescriptor) {
        put(senderScopedKey(clientId, key), value)
    }

    fun getFrom(clientId: String, key: String): ParcelFileDescriptor? =
        get(senderScopedKey(clientId, key))

    fun removeFrom(clientId: String, key: String) {
        remove(senderScopedKey(clientId, key))
    }

    private fun ParcelFileDescriptor.closeSilently() {
        try {
            close()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to close evicted PFD", e)
        }
    }

    companion object {
        const val ID = "tenshu/files/parcelFileDescriptorCache"
        private const val TAG = "ParcelFileDescriptorCache"

        /**
         * `<clientId>\u0000<caller key>`.
         *
         * ⚠ The separator choice is the whole of the isolation property, so it is not
         * cosmetic: `clientId` is an Android package name resolved from the calling uid and
         * **cannot contain NUL**, while the caller-supplied `key` can contain anything. Since
         * the true sender is therefore always the prefix up to the *first* NUL, no caller can
         * craft a `key` that lands in another client's slot. A separator the sender could also
         * produce (`:`, `/`, `#`) would not have that property.
         */
        fun senderScopedKey(clientId: String, key: String): String = "$clientId\u0000$key"
    }
}

fun TContext.parcelFileDescriptorCache(): ParcelFileDescriptorCache? =
    components[ParcelFileDescriptorCache.ID] as? ParcelFileDescriptorCache
