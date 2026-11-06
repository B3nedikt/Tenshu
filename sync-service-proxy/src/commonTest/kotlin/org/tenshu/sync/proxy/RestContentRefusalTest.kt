package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.messageOrNull
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.EdgedPush
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.ContentRefusal
import org.tenshu.sync.common.DescribeOutcome
import org.tenshu.sync.common.PushOutcome
import org.tenshu.sync.common.RevivedContent
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B4c §2.1: the relay's typed `409` (`content-plane-cr2-b4-spec.md` §0) comes back typed, from
 * the JSON body — never through `statusWithReason()`, whose 200-character cap would cut a refusal
 * listing several ids down to a different, shorter one — and a revive rides the push.
 *
 * `commonTest`, so the JS compile lane builds it too.
 */
class RestContentRefusalTest {

    private val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) })
    private val context = proxy.tContext
    private val parser = BinaryParser(proxy.executionContext.schemaProvider, context.dataFactory())
    private val seen = mutableListOf<HttpRequestData>()

    /** Ids long enough that the list is well past 200 characters. */
    private val manyIds = (1..12).map { "content-id-${it.toString().padStart(40, '0')}" }

    private fun refusal(error: String, ids: List<String>) =
        """{"error":"$error","contentIds":[${ids.joinToString(",") { "\"$it\"" }}]}"""

    private fun client(answers: List<Pair<HttpStatusCode, String>>): HttpClient {
        var call = 0
        return HttpClient(
            MockEngine { request ->
                seen += request
                val (status, body) = answers[minOf(call++, answers.lastIndex)]
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { install(ContentNegotiation) { json() } }
    }

    private fun relay(vararg answers: Pair<HttpStatusCode, String>) = RestSyncRelay(
        baseUrl = "https://relay.example",
        httpClient = client(answers.toList()),
        context = context,
        authTokenProvider = { "jwt" },
        ownerIdProvider = { "owner" },
    )

    private fun store(vararg answers: Pair<HttpStatusCode, String>) = RestContentBlobStore(
        baseUrl = "https://relay.example",
        httpClient = client(answers.toList()),
        context = context,
        authTokenProvider = { "jwt" },
    )

    private fun block() = SyncBlock.create(
        context = context,
        blockId = "block-1",
        ownerId = "owner",
        hlc = HybridLogicalClock.create(context, 1_000, 0, "device-a"),
        encryptedPayload = byteArrayOf(1),
        targetEntityIdHash = "holder-1",
    )

    @Test
    fun aContentFreedBodyWithManyIds_parsesWhole_notTruncated() = runTest {
        assertTrue(refusal(ContentRefusal.CONTENT_FREED, manyIds).length > 200, "precondition: past the cap")

        val outcome = relay(HttpStatusCode.Conflict to refusal(ContentRefusal.CONTENT_FREED, manyIds))
            .pushEdged(block(), listOf(ContentEdge("c".repeat(ContentPropertyTag.TAG_HEX_LENGTH), manyIds.first(), false)))

        val refused = assertIs<PushOutcome.Refused>(outcome)
        assertEquals(ContentRefusal.ContentFreed(manyIds), refused.refusal)
    }

    @Test
    fun aReviveIncompleteBody_parsesAsItsOwnRefusal() = runTest {
        val outcome = relay(HttpStatusCode.Conflict to refusal(ContentRefusal.REVIVE_INCOMPLETE, listOf("cid-1")))
            .pushEdged(block(), revive = listOf(RevivedContent("cid-1", listOf("blob-a"))))

        assertEquals(ContentRefusal.ReviveIncomplete(listOf("cid-1")), assertIs<PushOutcome.Refused>(outcome).refusal)
    }

    @Test
    fun aConflictWithoutAB4Body_isAnOrdinaryFailure_soTheBlockStaysQueued() = runTest {
        val outcome = relay(HttpStatusCode.Conflict to """{"error":"something_else"}""").pushEdged(block())
        assertIs<PushOutcome.Failed>(outcome)
    }

    @Test
    fun theReviveRidesThePushAsEdgedPushRevive() = runTest {
        val revive = listOf(RevivedContent("cid-1", listOf("blob-a", "blob-b", "blob-manifest")))

        assertEquals(PushOutcome.Accepted, relay(HttpStatusCode.OK to "").pushEdged(block(), revive = revive))

        val pushed = parser.parse<EdgedPush>((seen.single().body as OutgoingContent.ByteArrayContent).bytes())
        assertEquals(
            revive,
            pushed.revive.toList().map { entry -> RevivedContent(entry.contentId.get(), entry.refs.toList().map { it.get() }) },
        )
    }

    @Test
    fun pushBlock_reportsARefusalAsAFailureNamingEveryId() = runTest {
        // The probes (iOS) still call `pushBlock`; its message keeps the whole list.
        val result = relay(HttpStatusCode.Conflict to refusal(ContentRefusal.CONTENT_FREED, manyIds)).pushBlock(block())

        val message = assertIs<TResult.Failure<*>>(result).error.messageOrNull().orEmpty()
        assertTrue(message.contains(CONTENT_REFUSAL_MARKER), message)
        assertTrue(manyIds.all(message::contains), "every id survives: $message")
    }

    @Test
    fun aDescribeNamingFreedContent_isRefused_andTheRestIsResentWithoutIt() = runTest {
        val outcome = store(
            HttpStatusCode.Conflict to refusal(ContentRefusal.CONTENT_FREED, listOf("cid-freed")),
            HttpStatusCode.OK to "",
        ).describe(
            listOf(
                ContentDescription("cid-freed", listOf("blob-a")),
                ContentDescription("cid-live", listOf("blob-b")),
            )
        )

        assertEquals(DescribeOutcome.Refused(listOf("cid-freed")), outcome)
        assertEquals(2, seen.size, "the refused request wrote nothing, so the rest went again")
        val resent = Json.parseToJsonElement((seen[1].body as TextContent).text).jsonObject
            .getValue("contents").jsonArray.map { it.jsonObject.getValue("contentId").jsonPrimitive.content }
        assertEquals(listOf("cid-live"), resent)
    }

    @Test
    fun describeContent_isFalseOnARefusal_andOnAnyOtherFailure() = runTest {
        val freed = store(HttpStatusCode.Conflict to refusal(ContentRefusal.CONTENT_FREED, listOf("cid")))
        assertEquals(false, freed.describeContent(listOf(ContentDescription("cid", listOf("blob-a")))))
        assertEquals(
            DescribeOutcome.Failed,
            store(HttpStatusCode.ServiceUnavailable to "").describe(listOf(ContentDescription("cid", listOf("blob-a")))),
        )
    }
}
