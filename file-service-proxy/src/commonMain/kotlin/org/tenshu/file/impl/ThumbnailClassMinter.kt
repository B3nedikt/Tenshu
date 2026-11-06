package org.tenshu.file.impl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.MediaWorkBudget
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.ThumbnailCacheSidecars
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.file.proxy.ThumbnailClass
import org.tenshu.file.proxy.ThumbnailLadderRenderer
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * **The one mint** behind every stored thumbnail class — render, write, emit — shared by the
 * upload path ([UploadFileImplementation]) and the backfill (`BackfillThumbnailClassesImplementation`,
 * `drive-synced-thumbnails.md` §15.3 D-6).
 *
 * ⚠⚠ Shared rather than copied, and that is the load-bearing property: §15.3 says *"the mint code
 * gains no second implementation"* and *"the upload's emit path, never a bespoke one"*. Every
 * `emitFileContent` describes its content's refs inline once its chunks land, so a class minted
 * through [store] is described by construction and the reclamation sweep can name it (its edge rides
 * the file row's own push). A pass that wrote the blob and emitted its own way would mint
 * exactly the undescribed population `content-plane-refcount.md` §9.7 exists to unstrand.
 *
 * The three verbs are best-effort in the upload's sense — nothing here may fail an upload — but
 * they report *why* nothing was stored, because the backfill needs the distinction: a
 * [Render.Declined] is terminal for that file and class (upload would have stored nothing either),
 * a [Render.Failed] is not (a transient, worth another tick).
 */
internal class ThumbnailClassMinter(
    private val fileDatabase: FileDatabase,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val motionThumbnailRenderer: MotionThumbnailRenderer? = null,
    /**
     * Renders a whole still ladder from one decode (`media-processing-pipeline-sweep.md` §4.1).
     * Null — a platform without one — keeps the per-rung [GetPreview] path in [renderStills].
     */
    private val thumbnailLadderRenderer: ThumbnailLadderRenderer? = null,
    /**
     * Where the renders run: the process's media lane (`media-processing-pipeline-sweep.md` D4).
     * ⚠ `GetPreview` is a sync op and runs inline, so the decode and encode happen on whatever
     * thread awaits it. Without this, that is the caller's thread on bare `Default`, shared with
     * IPC serving. Upload, backfill and mint-on-view all render through here.
     */
    private val lane: CoroutineDispatcher = MediaWorkBudget.lane,
) {

    /** Whether this platform can mint the `MOTION` class at all (`photos-video-support.md` §4.4: Android only). */
    val canRenderMotion: Boolean get() = motionThumbnailRenderer != null

    sealed interface Render {
        /** Encoded bytes within the cap. */
        class Bytes(val bytes: ByteArray) : Render

        /** Nothing to store: a non-image, an un-renderable file, an over-cap result, a renderer that said no. */
        data object Declined : Render

        /** The render threw. */
        class Failed(val cause: Throwable) : Render
    }

    /**
     * Renders one still class by invoking [GetPreview] against the bytes already in
     * [FileDatabase] at [px] (longest edge) in [format], with `GENERATE_FRESH` so a stored class
     * this file may already have is never the source (re-encoding a JPEG of a JPEG walks the
     * ladder downhill). Over [maxBytes] is [Render.Declined]: an over-cap blob is never stored.
     *
     * ⚠⚠ **A JPEG over the cap is re-encoded at [STEP_DOWN_QUALITIES] before it is declined.** A
     * detailed frame — a screenshot, a page of text, dense foliage — is ~500 KB at 1024 px q80, and
     * declining it outright would leave the photograph on the 256 px class on every device without
     * the original, for ever: the backfill records the decline and never asks again
     * (`drive-synced-thumbnails.md` §17). On this per-rung path each step is a fresh render from the
     * original, never a re-encode of the rejected JPEG; [renderStills]' ladder re-encodes the bitmap
     * it already scaled, which is the same pixels without the decode. ⚠ Only an *over-cap* answer
     * steps down: a render that came back empty is the file saying no, and asking it again at a lower
     * quality cannot change that.
     */
    suspend fun renderStill(
        executionContext: ExecutionContext,
        filePath: String,
        px: Int,
        format: String?,
        maxBytes: Int,
    ): Render = try {
        val caller = File.create(executionContext.context, filePath)
        val qualities = qualitiesFor(format)
        // ⚠⚠ No `var result: Render = Render.Declined` here. Kotlin/Native types that suspend-spilled
        // local by its initializer — the `Declined` object — so assigning `Bytes` throws a
        // ClassCastException, the catch-all below turns it into `Failed`, and iOS mints no rung for
        // any upload. The JVM runs it fine; `UploadMintsThumbnailClassesTest` (iosTest) pins it.
        // `firstNotNullOfOrNull` keeps the answer an expression, never a reassigned local.
        qualities.firstNotNullOfOrNull { quality ->
            val params = buildMap {
                put(GetPreview.Params.WIDTH, px.toTInteger(executionContext.context))
                put(GetPreview.Params.HEIGHT, px.toTInteger(executionContext.context))
                if (format != null) {
                    put(GetPreview.Params.FORMAT, format.toTString(executionContext.context))
                }
                if (quality != null) {
                    put(GetPreview.Params.QUALITY, quality.toTInteger(executionContext.context))
                }
                // ⚠⚠ This is the *generation* call, and saying so is load-bearing. Without it the
                // resolver's stored-class short-circuit answers with a class this file may already
                // have — so a re-upload would mint the new classes out of the OLD ones. It cannot be
                // inferred from the caller, which always arrives as a bare `File`.
                put(GetPreview.Params.GENERATE_FRESH, true.toTBoolean(executionContext.context))
            }
            val rendered = withContext(lane) {
                executionContext.executeAsync<TByteArray?>(
                    operationId = GetPreview.ID,
                    caller = caller,
                    params = params,
                )?.get()
            }
            when {
                // An empty render is the file saying no; a lower quality cannot change that.
                rendered == null || rendered.isEmpty() -> Render.Declined
                rendered.size <= maxBytes -> Render.Bytes(rendered)
                else -> null
            }
        } ?: Render.Declined
    } catch (e: CancellationException) {
        // ⚠ Only meaningful because this function suspends: `executeAsync` awaits an
        // `AsyncReference`, and a cancelled upload surfaces here as a CancellationException.
        // The catch-all below would swallow it, and every layer up (LocalTaskExecutor,
        // viewModelScope) reads a swallowed cancellation as "still running" — the op appears
        // to hang forever. See ExecutionContext's AsyncReference.create comment.
        throw e
    } catch (e: Throwable) {
        Render.Failed(e)
    }

    /**
     * Renders every rung in [rungs] for the file at [filePath], in order — **from one decode** where
     * the platform provides a [ThumbnailLadderRenderer] and it handles this file, else rung by rung
     * through [renderStill] (`media-processing-pipeline-sweep.md` §4.1).
     *
     * [data] is the original already in memory (the upload holds it); the ladder renders from it
     * rather than reading the file back. The answers keep [renderStill]'s contract rung for rung: a
     * [Render.Declined] stays a decline, a throw is [Render.Failed], and the cap is re-checked here
     * whoever encoded — the drop rule is the mint's invariant, as in [renderMotion].
     *
     * ⚠⚠ Kotlin/Native: every answer below is an expression. No local is initialised with a sealed
     * `object` and reassigned — the pattern that turns every iOS rung into [Render.Failed] (see
     * [renderStill]).
     */
    suspend fun renderStills(
        executionContext: ExecutionContext,
        filePath: String,
        data: ByteArray,
        rungs: List<ThumbnailClass.Still>,
        format: String,
    ): List<Render> =
        thumbnailLadderRenderer?.let { renderLadder(it, filePath, data, rungs, format) }
            ?: rungs.map { rung -> renderStill(executionContext, filePath, rung.px, format, rung.maxBytes) }

    /** One ladder render, or `null` when the renderer does not handle this file. */
    private suspend fun renderLadder(
        renderer: ThumbnailLadderRenderer,
        filePath: String,
        data: ByteArray,
        rungs: List<ThumbnailClass.Still>,
        format: String,
    ): List<Render>? = try {
        currentCoroutineContext().ensureActive()
        val asked = rungs.map { rung ->
            ThumbnailLadderRenderer.Rung(
                px = rung.px,
                format = format,
                qualities = qualitiesFor(format),
                maxBytes = rung.maxBytes,
            )
        }
        // ⚠ On the media lane, like every other render here (`media-processing-pipeline-sweep.md`
        // D4): the ladder decodes and encodes inline, and off the lane it would run uncapped on the
        // caller's thread.
        withContext(lane) { renderer.render(filePath.substringAfterLast('/'), data, asked) }?.let { outcomes ->
            check(outcomes.size == rungs.size) { "ladder answered ${outcomes.size} rungs for ${rungs.size}" }
            outcomes.mapIndexed { index, outcome ->
                when (outcome) {
                    is ThumbnailLadderRenderer.Outcome.Encoded ->
                        if (outcome.bytes.isNotEmpty() && outcome.bytes.size <= rungs[index].maxBytes) {
                            Render.Bytes(outcome.bytes)
                        } else {
                            Render.Declined
                        }
                    ThumbnailLadderRenderer.Outcome.Declined -> Render.Declined
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // A throw is a failure at every rung, never a decline: the backfill retries a failure and
        // records a decline for good.
        rungs.map { Render.Failed(e) }
    }

    /**
     * Mints the `MOTION` class from [data], or [Render.Declined] for everything that should not
     * carry one: a non-video (⚠ the category gate lives *here*, not in the renderer — a renderer is
     * handed only videos, so it never has to sniff), a platform with no renderer wired, a clip the
     * renderer declined (too short for a loop, undecodable, over the cap after its one retry).
     *
     * ⚠ The cap is re-checked here even though the renderer is handed it: the drop rule is the
     * mint's invariant (an over-cap blob is never stored — §4.5 of the video doc), not a courtesy
     * the platform code is trusted to extend.
     *
     * ⚠ `suspend` for one reason: [ensureActive] before the mint. The renderer itself is a blocking
     * call that cannot be interrupted, so the check is worth exactly what it says — a cancellation
     * that arrived before the mint started is honoured, one that arrives during it waits for the
     * renderer's own time budget.
     */
    suspend fun renderMotion(fileName: String, data: ByteArray, maxBytes: Int): Render {
        val renderer = motionThumbnailRenderer ?: return Render.Declined
        if (fileCategoryForName(fileName) != FileCategory.Video) return Render.Declined
        currentCoroutineContext().ensureActive()
        return try {
            withContext(lane) { renderer.render(videoBytes = data, maxBytes = maxBytes) }
                ?.takeIf { it.isNotEmpty() && it.size <= maxBytes }
                ?.let { Render.Bytes(it) }
                ?: Render.Declined
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Render.Failed(e)
        }
    }

    /**
     * Write one rendered class to the local [FileDatabase] under the file's `contentId` and emit
     * it onto the content plane under [propertyId] (`sync-device-catch-up.md` §11.3). Returns
     * whether the blob was written; a `null` [thumbnail] writes nothing and returns false.
     *
     * ⚠ The write is caught for one specific reason — **ordering**, not general caution. On the
     * upload path this runs *after* `saveData(fileEntity)` and the `File.Data` emit have already
     * committed, so letting a thumbnail write throw would report a failed upload for a file that
     * is on disk and on its way to every peer. A missing thumbnail is strictly better than that.
     *
     * ⚠ No `emitPropertyBlock` fallback, unlike `File.Data`: a thumbnail that cannot ride the
     * content plane must NOT fall back to an ordinary property block, because that block is
     * exactly the eager entity payload the content plane keeps thumbnails out of.
     */
    suspend fun store(
        executionContext: ExecutionContext,
        dataId: DataId,
        contentId: String,
        propertyId: String,
        thumbnailClass: String,
        thumbnail: ByteArray?,
        /**
         * True on the UPLOAD path: the content id was minted moments ago, so the rung cannot already be
         * on the relay and asking would only cost round trips. False (mint-on-view, backfill): another
         * device, or this one before a restart, may have published it — ask first.
         */
        freshContent: Boolean = false,
    ): Boolean {
        if (thumbnail == null) return false
        val path = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)
        runCatching {
            fileDatabase.writeByteArray(
                filePath = path,
                bytes = thumbnail.toTByteArray(executionContext.context),
            )
        }.onFailure { return false }
        // The class is here again, so the keeper's "deliberately gone" sentinel is stale.
        ThumbnailCacheSidecars.clearEvicted(fileDatabase, contentId, thumbnailClass)
        // ⚠⚠ Ask the relay first. Mint-on-view renders a rung that is missing LOCALLY — which
        // says nothing about the relay, where another device (or this one before a restart) may already
        // have published it. A blind emit then re-uploads it under a fresh CEK, and the losing chunk
        // set stays on the relay undescribed for ever. `ensureContentOnRelay` skips when the rung
        // resolves, and re-emits (forced) when it does not.
        val emitter = syncBlockEmitterHolder?.fileContentEmitter
        if (freshContent) {
            emitter?.emitFileContent(targetEntityId = dataId, propertyId = propertyId, filePath = path)
        } else {
            emitter?.ensureContentOnRelay(
                targetEntityId = dataId,
                propertyId = propertyId,
                filePath = path,
                contentId = FileBytesPropertyWriter.derivedThumbnailContentId(contentId, thumbnailClass),
            )
        }
        return true
    }

    internal companion object {
        /** The qualities a render in [format] tries, in order: the step-down for JPEG, the default otherwise. */
        fun qualitiesFor(format: String?): List<Int?> =
            if (format == GetPreview.Params.FORMAT_JPEG) STEP_DOWN_QUALITIES else listOf(null)

        /**
         * The JPEG qualities [renderStill] tries in order, stopping at the first that fits the cap.
         * `null` is the platform's own default (80 on Android and iOS), so a frame that already fits
         * encodes at that default, untouched by the step-down.
         *
         * ⚠ Measured on a dense-text 1200 px frame at 1024 px (JVM ImageIO): q80 494 KB, q65 377 KB,
         * q50 312 KB — against 139 KB for an ordinary photograph (§12.1). Below 50 the saving flattens
         * (q40 276 KB) while the text visibly smears, so 50 is the floor; what still does not fit is a
         * noise-like frame the cap exists to refuse.
         */
        val STEP_DOWN_QUALITIES: List<Int?> = listOf(null, 65, 50)
    }
}
