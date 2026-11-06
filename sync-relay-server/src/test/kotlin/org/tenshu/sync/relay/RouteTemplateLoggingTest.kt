package org.tenshu.sync.relay

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ **The relay's log names route templates, never request paths** (relay-metadata-minimisation
 * F4; `docs/how-to/server-logging.md`). Share ids, grant addresses, EPC session ids, invite ids and
 * content refs travel in path segments, and each one is a capability: whoever reads it from a log
 * can fetch or revoke what it names.
 *
 * Captures every log event the relay emits while it serves capability-bearing routes, and asserts
 * that no concrete id appears anywhere — call log, error log, a stack trace's message — while the
 * template (`/share/{shareId}`) does. Status codes do not matter here: a 401 or 404 on a matched
 * route is logged with the same template, and is exactly the line a probing attacker leaves.
 */
class RouteTemplateLoggingTest {

    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    // The test engine logs as `io.ktor.test`, production as `io.ktor.server.Application`. Both to
    // DEBUG, so the debug-level malformed-body lines are captured too.
    private val appLoggers = listOf("io.ktor.test", "io.ktor.server.Application")
        .map { LoggerFactory.getLogger(it) as Logger }
    private val savedLevels = appLoggers.map { it.level }

    @BeforeTest
    fun attach() {
        appender.start()
        root.addAppender(appender)
        appLoggers.forEach { it.level = Level.DEBUG }
    }

    @AfterTest
    fun detach() {
        root.detachAppender(appender)
        appender.stop()
        appLoggers.zip(savedLevels).forEach { (logger, level) -> logger.level = level }
    }

    private fun logText(): String = synchronized(appender.list) {
        appender.list.joinToString("\n") { event ->
            buildString {
                append(event.formattedMessage)
                var cause = event.throwableProxy
                while (cause != null) {
                    append(" | ").append(cause.message)
                    cause = cause.cause
                }
            }
        }
    }

    /** CallLogging writes on `ResponseSent`, which can land just after the client has its answer. */
    private suspend fun awaitLine(fragment: String) {
        repeat(100) {
            if (logText().contains(fragment)) return
            delay(20)
        }
        fail("no log line containing `$fragment`; log was:\n${logText()}")
    }

    @Test
    fun capabilityRoutesLogTheirTemplateAndNeverTheirIds() = testApplication {
        application { testModule() }

        val shareId = "SHAREIDcapability0123456789abcdef"
        val contentRef = "blob-" + "c0ffee".repeat(10) + "abcd"
        val inviteId = "INVITEIDcapability0123456789abcd"
        val grantAddress = "GRANTADDRcapability0123456789abc"
        val epcSession = "EPCSESSIONcapability0123456789ab"
        val unmatchedSecret = "UNMATCHEDcapability0123456789abc"

        client.get("/share/$shareId") // 410: an unknown share reads as a revoked one
        client.get("/share/$shareId/chunk/0")
        client.get("/sync/content/$contentRef")
        client.delete("/sync/invite/$inviteId")
        client.get("/sync/grant/$grantAddress")
        client.post("/sync/epc/$epcSession/up") { setBody(byteArrayOf(1, 2, 3)) }
        assertEquals(HttpStatusCode.NotFound, client.get("/no/such/route/$unmatchedSecret").status)

        awaitLine("GET /share/{shareId}/chunk/{chunkIndex}")
        awaitLine("GET /share/{shareId} in ")
        awaitLine("GET /sync/content/{ref}")
        awaitLine("DELETE /sync/invite/{inviteId}")
        awaitLine("GET /sync/grant/{address}")
        awaitLine("POST /sync/epc/{sessionId}/{direction}")
        awaitLine("404 Not Found: GET $UNMATCHED_ROUTE")

        val log = logText()
        for (id in listOf(shareId, contentRef, inviteId, grantAddress, epcSession, unmatchedSecret)) {
            // Not even a prefix: a call-log line has no reason to carry any part of the value.
            assertTrue(!log.contains(id.take(LOG_ID_PREFIX)), "the log names `$id`:\n$log")
        }
    }

    @Test
    fun theUnhandledExceptionLineNamesTheTemplate() = testApplication {
        val secret = "BOOMcapability0123456789abcdef"
        application {
            testModule()
            routing { get("/test-only/boom/{capability}") { error("boom") } }
        }

        assertEquals(HttpStatusCode.InternalServerError, client.get("/test-only/boom/$secret").status)

        awaitLine("Unhandled exception serving /test-only/boom/{capability}")
        awaitLine("500 Internal Server Error: GET /test-only/boom/{capability}")
        assertTrue(!logText().contains(secret.take(LOG_ID_PREFIX)), "the log names the path's capability:\n${logText()}")
    }

    /**
     * Ktor's ENGINE logs a failure that escapes the pipeline itself, as `<status>: GET - <path>`
     * (`DefaultEnginePipeline.logFailure`, `call.request.toLogString()`), and templating cannot reach
     * it. StatusPages rethrows [CancellationException], so every mid-request client disconnect lands
     * there — at DEBUG, which `RELAY_LOG_LEVEL=DEBUG` writes to disk. The production `logback.xml`
     * masks the path in that shape; this drives a real one through it.
     */
    @Test
    fun theEngineFailureLineReachesDiskWithoutItsPath() = testApplication {
        val secret = "ENGINEcapability0123456789abcdef"
        environment { config = MapApplicationConfig("ktor.test.throwOnException" to "false") }
        application {
            testModule()
            routing { get("/test-only/gone/{capability}") { throw CancellationException("client went away") } }
        }

        client.get("/test-only/gone/$secret")

        awaitLine(" - /test-only/gone/")
        val event = synchronized(appender.list) { appender.list.last { " - /test-only/gone/" in it.formattedMessage } }
        // The leak this guards: Ktor itself names the concrete path. If this ever stops holding,
        // Ktor changed its failure line and the logback mask below needs a fresh look.
        assertTrue(secret in event.formattedMessage, event.formattedMessage)

        for (level in listOf("DEBUG", "INFO")) {
            val line = productionLogback(logLevel = level).renderLine(event)
            assertTrue(!line.contains(secret.take(LOG_ID_PREFIX)), "the on-disk line names the path:\n$line")
            assertTrue("GET - <path>" in line, line)
        }
    }

    @Test
    fun theFormatKeepsMethodStatusAndDuration() = testApplication {
        application { testModule() }

        client.get("/health")

        awaitLine("GET /health in ")
        val line = logText().lines().first { it.contains("GET /health in ") }
        assertTrue(Regex("""^\d{3} [A-Za-z ]+: GET /health in \d+ms$""").matches(line), "unexpected call-log shape: $line")
    }
}
