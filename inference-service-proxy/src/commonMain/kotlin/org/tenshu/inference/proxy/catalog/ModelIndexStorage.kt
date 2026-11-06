package org.tenshu.inference.proxy.catalog

import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.FileDatabase

/**
 * Where [DefaultModelStore] keeps its install index and its garbage list: two small strings under
 * two keys.
 *
 * ⚠⚠ A port because the index must **die with the bytes it names**. [DefaultModelStore.pathOf]
 * answers from the index and never checks the file, so an index that outlives its bytes is a model
 * reported installed that loads nothing. On iOS the default home, the Keychain, survives an
 * uninstall and the bytes do not (`ios-on-device-inference.md` §3.1).
 *
 * ⚠ No implementation may promise durability [DefaultModelStore] then relies on: the store still
 * reclaims only against an index a later process read back.
 */
interface ModelIndexStorage {
    /**
     * What is stored under [key], or `null` when **nothing is**.
     *
     * ⚠⚠ `null` is *absent* and nothing else. A value that is there and could not be read is a
     * throw, because the store deletes files and rewrites the index on the strength of this
     * answer: an install record read as empty is every installed model forgotten, and its bytes
     * swept (`ios-on-device-inference.md` §13.7).
     */
    fun getString(key: String): String?
    fun storeString(key: String, value: String)
    fun remove(key: String)
}

/** The default on every platform but the iOS owner: the device-local string bucket. */
class SecureStorageModelIndex(private val delegate: SecureStorageDelegate) : ModelIndexStorage {
    override fun getString(key: String): String? = delegate.getString(key)
    override fun storeString(key: String, value: String) = delegate.storeString(key, value)
    override fun remove(key: String) = delegate.remove(key)
}

/**
 * The index as files **beside the model bytes**, in the same [FileDatabase], so one container
 * holds both halves and losing it loses both.
 *
 * ⚠ A key is a file name here. Both of [DefaultModelStore]'s are dotted identifiers; anything else
 * is refused rather than turned into a path.
 */
class FileModelIndexStorage(
    private val fileDatabase: FileDatabase,
    private val directory: String = DEFAULT_MODELS_DIR,
) : ModelIndexStorage {

    /**
     * ⚠⚠ `null` from the database is not yet *absent*. `IosFileDatabase.readBytes` answers `null`
     * for a file it could not read as for one that is not there (`NSData.dataWithContentsOfFile`
     * is `nil` either way — a file still under data protection before the first unlock is one).
     * So the file's presence is asked separately, as `AiReadiness.readVerdict` does: a file that
     * is there with no bytes to give is unreadable. A read or a stat that throws propagates.
     */
    override fun getString(key: String): String? {
        val path = pathOf(key)
        val bytes = fileDatabase.readBytes(path)?.get()
        if (bytes == null && fileDatabase.fileSize(path) != null) {
            throw ModelIndexUnreadableException("$path is there and could not be read.")
        }
        return bytes?.decodeToString()
    }

    override fun storeString(key: String, value: String) =
        fileDatabase.writeRawByteArray(pathOf(key), value.encodeToByteArray())

    override fun remove(key: String) = fileDatabase.deleteFile(pathOf(key))

    private fun pathOf(key: String): String {
        require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
            "Not a model index key: $key"
        }
        return "$directory/$key$INDEX_SUFFIX"
    }

    private companion object {
        /** ⚠ Never `.model`, `.part` or `.sha`: those are the store's own artifact names. */
        const val INDEX_SUFFIX = ".json"
    }
}

/**
 * The install index, or the garbage list beside it, is there and this process could not take
 * records from it. Thrown by every [DefaultModelStore] method that would otherwise have answered,
 * written or deleted from a record assumed empty.
 *
 * ⚠⚠ Two kinds, because only one of them can pass by itself:
 * - **unreadable** ([undecodable] `false`): the bytes could not be had. Nothing is remembered, so
 *   the next call reads again and a store that recovers carries on as if nothing had happened;
 * - **undecodable** ([undecodable] `true`): the bytes were read, are JSON, and are not this
 *   build's record. They may be another build's, so they are not overwritten, and the answer is
 *   the same for the life of the process.
 */
class ModelIndexUnreadableException(
    message: String,
    val undecodable: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
