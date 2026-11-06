package org.tenshu.sync.proxy

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.sync.common.ContentRefusal

/**
 * Marker on the message of a refused push reported through `pushBlock`'s `TResult` (a caller that only
 * logs). The drains never match it: they get the refusal typed from `pushEdged`.
 */
internal const val CONTENT_REFUSAL_MARKER = "content-refused-409"

/** B4's 409 body (`content-plane-cr2-b4-spec.md` §0). */
@Serializable
internal data class ContentRefusalDto(
    val error: String = "",
    val contentIds: List<String> = emptyList(),
)

private val refusalJson = Json { ignoreUnknownKeys = true }

/** Parses a B4 refusal body; null when it is not one (unparsable, another `error`, or no ids). */
internal fun parseContentRefusal(body: String?): ContentRefusal? {
    if (body.isNullOrBlank()) return null
    val dto = runCatching { refusalJson.decodeFromString(ContentRefusalDto.serializer(), body) }.getOrNull()
        ?: return null
    if (dto.contentIds.isEmpty()) return null
    return ContentRefusal.of(dto.error, dto.contentIds)
}

/**
 * The B4 refusal this response carries, read from the **whole** body. ⚠⚠ Never via `statusWithReason()`,
 * which keeps 200 characters: a refusal listing a handful of ids would parse as a different, shorter one.
 */
internal suspend fun HttpResponse.contentRefusal(): ContentRefusal? =
    parseContentRefusal(runCatchingCancellable { bodyAsText() }.getOrNull())
