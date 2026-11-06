package app.tenshu.fit.data

/**
 * A picked activity file to import — the raw bytes plus the original name (used to sniff the
 * format). The file-import path ([ActivityFileParser]); the picker UI hands these in.
 */
data class ActivityFile(
    val name: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ActivityFile && name == other.name && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}
