package app.tenshu.takeout

/**
 * A Takeout export, walked **once**, whatever it is physically stored as (B2).
 *
 * ⚠⚠ **One pass, and the name decides before the bytes are read.** This shape exists so a *stream*
 * can implement it: Android hands out a `content://` stream that cannot seek, and an export is
 * mostly photos. [walk] therefore asks [wanted] about each entry's name first and only reads what
 * that accepts — which is also what keeps a 40 GB archive from being copied anywhere.
 *
 * ⚠ Fit met this problem first and solved it the same way (`JvmArchiveReader`, `IosArchiveReader`,
 * `isRelevant`). The two readers stayed separate here only because Fit's yields *activity files*
 * and this yields raw entries; if a third consumer appears, they should become one.
 */
fun interface TakeoutArchive {

    /**
     * Calls [consume] once per entry whose name [wanted] accepts, with that entry's uncompressed
     * bytes.
     *
     * ⚠⚠ [consume] **suspends**, and that is what keeps the memory flat: B5 uploads each photo from
     * inside the walk and drops it before the next is read. A non-suspending callback forced the
     * caller to buffer every entry it wanted and then act — which for a photo export is the whole
     * archive in memory, the one thing this design exists to avoid.
     *
     * The bytes — or `null` when it could not be read (truncated, over a size ceiling, an unknown
     * compression method). Entries [wanted] rejects are never read at all.
     */
    suspend fun walk(
        wanted: (String) -> Boolean,
        consume: suspend (name: String, bytes: ByteArray?) -> Unit,
    )
}
